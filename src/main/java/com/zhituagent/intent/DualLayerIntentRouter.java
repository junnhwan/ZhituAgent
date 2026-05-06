package com.zhituagent.intent;

import com.zhituagent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Two-tier intent classifier coordinator: rule classifier first, cheap-LLM
 * second, fall through to the existing expensive routing path otherwise.
 *
 * <p><b>Cost model</b>:
 * <ul>
 *   <li>Tier 1 (rule): &lt;1ms, no LLM call.
 *   <li>Tier 2 (cheap LLM): ~200-600ms, ~$0.0001/call (gpt-5.4-mini).
 *   <li>Tier 3 (fallthrough): existing {@code AgentOrchestrator.decide()}
 *       runs unchanged → 1 RAG call + 1 expensive LLM tool-selection call.
 * </ul>
 *
 * <p><b>Caching</b>: a small bounded LRU keyed by SHA-256 of the normalized
 * prompt avoids re-paying the cheap-LLM cost for repeat questions inside a
 * session burst. TTL is enforced lazily on access. Cache miss rate is fine —
 * this is a perf optimization, not a correctness gate.
 *
 * <p><b>Skip-cheap shortcut</b>: when a rule hit returns confidence
 * {@code >= ruleConfidenceForSkipCheap} (default 0.6), the cheap-LLM tier is
 * not invoked at all. This is the primary TTFB protection.
 *
 * <p><b>Disabled mode</b>: when {@code zhitu.llm.intent.dual-layer.enabled=false}
 * (default), no instance of this class is created and
 * {@code AgentOrchestrator} sees a null {@code DualLayerIntentRouter} which it
 * treats as a fallthrough — preserving byte-stable pre-M1 routing.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.llm.intent.dual-layer", name = "enabled", havingValue = "true")
public class DualLayerIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(DualLayerIntentRouter.class);

    private final RuleIntentClassifier ruleClassifier;
    private final CheapLlmIntentClassifier cheapClassifier;  // may be null if no fallbackLlm bean
    private final double ruleConfidenceForSkipCheap;
    private final double cheapLlmConfidenceThreshold;
    private final LruCache<String, IntentResult> cache;

    public DualLayerIntentRouter(RuleIntentClassifier ruleClassifier,
                                 ObjectProvider<CheapLlmIntentClassifier> cheapClassifierProvider,
                                 LlmProperties llmProperties) {
        this.ruleClassifier = ruleClassifier;
        this.cheapClassifier = cheapClassifierProvider.getIfAvailable();
        LlmProperties.Intent cfg = llmProperties.getIntent();
        this.ruleConfidenceForSkipCheap = cfg.getRuleConfidenceForSkipCheap();
        this.cheapLlmConfidenceThreshold = cfg.getCheapLlmConfidenceThreshold();
        this.cache = new LruCache<>(cfg.getCacheMaxEntries(), cfg.getCacheTtlMs());
        log.info(
                "dual-layer intent router enabled hasCheapClassifier={} skipCheapAtConf={} cheapMinConf={} cacheMax={} ttlMs={}",
                cheapClassifier != null,
                ruleConfidenceForSkipCheap,
                cheapLlmConfidenceThreshold,
                cfg.getCacheMaxEntries(),
                cfg.getCacheTtlMs()
        );
    }

    /** Classify the user message with two-tier short-circuit + LRU cache. */
    public IntentResult classify(String userMessage, Map<String, Object> sessionMetadata) {
        if (userMessage == null || userMessage.isBlank()) {
            return IntentResult.fallthrough(0);
        }

        String cacheKey = sha256(userMessage.trim().toLowerCase());
        Optional<IntentResult> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get().withTier(IntentResult.Tier.CACHE);
        }

        // Tier 1: rules.
        IntentResult ruleResult = ruleClassifier.classify(userMessage, sessionMetadata);
        if (ruleResult.label() != IntentLabel.FALLTHROUGH
                && ruleResult.confidence() >= ruleConfidenceForSkipCheap) {
            cache.put(cacheKey, ruleResult);
            return ruleResult;
        }

        // Tier 2: cheap LLM (skipped if classifier bean wasn't created — e.g. no
        // fallbackLlm in router-disabled mode).
        if (cheapClassifier == null) {
            return IntentResult.fallthrough(ruleResult.latencyMs());
        }
        IntentResult cheapResult = cheapClassifier.classify(userMessage, sessionMetadata);
        if (cheapResult.label() != IntentLabel.FALLTHROUGH
                && cheapResult.confidence() >= cheapLlmConfidenceThreshold) {
            cache.put(cacheKey, cheapResult);
            return cheapResult;
        }

        // Both tiers gave up — caller runs the expensive routing path.
        return IntentResult.fallthrough(ruleResult.latencyMs() + cheapResult.latencyMs());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException neverHappens) {
            // SHA-256 is required by JLS; if missing we have bigger problems.
            return input;
        }
    }

    /**
     * Tiny bounded LRU with TTL — chosen over Caffeine to avoid adding a
     * dependency for a non-critical perf cache. Synchronization is via a
     * read-write lock; the read path (get) takes a read lock so concurrent
     * classifications on cache hit are non-blocking.
     */
    static final class LruCache<K, V> {

        private final int maxEntries;
        private final long ttlMs;
        private final LinkedHashMap<K, Entry<V>> map;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        LruCache(int maxEntries, long ttlMs) {
            this.maxEntries = Math.max(1, maxEntries);
            this.ttlMs = ttlMs;
            this.map = new LinkedHashMap<>(this.maxEntries, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                    return size() > LruCache.this.maxEntries;
                }
            };
        }

        Optional<V> get(K key) {
            lock.readLock().lock();
            try {
                Entry<V> entry = map.get(key);
                if (entry == null) {
                    return Optional.empty();
                }
                if (isExpired(entry)) {
                    // upgrade to write lock to remove
                    lock.readLock().unlock();
                    lock.writeLock().lock();
                    try {
                        map.remove(key);
                    } finally {
                        lock.readLock().lock();
                        lock.writeLock().unlock();
                    }
                    return Optional.empty();
                }
                return Optional.of(entry.value);
            } finally {
                lock.readLock().unlock();
            }
        }

        void put(K key, V value) {
            lock.writeLock().lock();
            try {
                map.put(key, new Entry<>(value, System.currentTimeMillis()));
            } finally {
                lock.writeLock().unlock();
            }
        }

        int size() {
            lock.readLock().lock();
            try {
                return map.size();
            } finally {
                lock.readLock().unlock();
            }
        }

        private boolean isExpired(Entry<V> entry) {
            return ttlMs > 0 && (System.currentTimeMillis() - entry.timestampMs) > ttlMs;
        }

        private record Entry<V>(V value, long timestampMs) {
        }
    }
}
