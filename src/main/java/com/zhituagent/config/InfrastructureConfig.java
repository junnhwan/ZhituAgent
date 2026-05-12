package com.zhituagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.memory.InMemoryMemoryStore;
import com.zhituagent.memory.InMemorySummaryStore;
import com.zhituagent.memory.SummaryStore;
import com.zhituagent.memory.MemoryStore;
import com.zhituagent.memory.MemoryLock;
import com.zhituagent.memory.NoopMemoryLock;
import com.zhituagent.memory.RedisMemoryStore;
import com.zhituagent.memory.RedisMemoryLock;
import com.zhituagent.memory.RedisSummaryStore;
import com.zhituagent.rag.ElasticsearchKnowledgeStore;
import com.zhituagent.rag.InMemoryKnowledgeStore;
import com.zhituagent.rag.KnowledgeStore;
import com.zhituagent.rag.OpenAiCompatibleRerankClient;
import com.zhituagent.rag.TenantAwareKnowledgeStore;
import com.zhituagent.rag.RerankClient;
import com.zhituagent.session.InMemorySessionRepository;
import com.zhituagent.session.RedisSessionRepository;
import com.zhituagent.session.SessionRepository;
import com.zhituagent.session.TenantAwareSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpClient;

@Configuration
public class InfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

    @Bean
    SessionRepository sessionRepository(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${zhitu.infrastructure.redis-enabled:false}") boolean redisEnabled) {
        SessionRepository delegate = (redisEnabled && redisTemplateProvider.getIfAvailable() != null)
                ? new RedisSessionRepository(redisTemplateProvider.getObject(), objectMapper)
                : new InMemorySessionRepository();
        return new TenantAwareSessionRepository(delegate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    MemoryStore redisMemoryStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisMemoryStore(stringRedisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    MemoryStore inMemoryMemoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    SummaryStore redisSummaryStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisSummaryStore(stringRedisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SummaryStore.class)
    SummaryStore inMemorySummaryStore() {
        return new InMemorySummaryStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    MemoryLock redisMemoryLock(StringRedisTemplate stringRedisTemplate) {
        return new RedisMemoryLock(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryLock.class)
    MemoryLock noopMemoryLock() {
        return new NoopMemoryLock();
    }

    @Bean
    KnowledgeStore knowledgeStore(
            ObjectProvider<ElasticsearchClient> esClientProvider,
            EsProperties esProperties,
            EmbeddingProperties embeddingProperties,
            @org.springframework.beans.factory.annotation.Value("${zhitu.infrastructure.elasticsearch-enabled:false}") boolean esEnabled) {
        KnowledgeStore delegate = (esEnabled && esClientProvider.getIfAvailable() != null)
                ? new ElasticsearchKnowledgeStore(esClientProvider.getObject(), esProperties, embeddingProperties)
                : new InMemoryKnowledgeStore();
        return new TenantAwareKnowledgeStore(delegate);
    }

    @Bean
    RerankClient rerankClient(RerankProperties rerankProperties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleRerankClient(HttpClient.newHttpClient(), objectMapper, rerankProperties);
    }

    /**
     * Print active store implementations on startup so eval/baseline runs can grep
     * "ZhituAgent active stores" out of the log to prove which KnowledgeStore was wired
     * (silent fallback to InMemoryKnowledgeStore is otherwise invisible — see audit).
     *
     * Uses ApplicationStartedEvent (fires before ApplicationRunner.run) instead of
     * ApplicationReadyEvent, because EvalApplicationRunner calls SpringApplication.exit
     * when exit-after-run=true, which suppresses ReadyEvent.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void logActiveStores(ApplicationStartedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        KnowledgeStore ks = ctx.getBean(KnowledgeStore.class);
        SessionRepository sr = ctx.getBean(SessionRepository.class);
        MemoryStore ms = ctx.getBean(MemoryStore.class);
        SummaryStore ss = ctx.getBean(SummaryStore.class);
        String sessionRepoDelegate = (sr instanceof TenantAwareSessionRepository tasr)
                ? tasr.delegate().getClass().getSimpleName()
                : sr.getClass().getSimpleName();
        String knowledgeStoreDelegate = (ks instanceof TenantAwareKnowledgeStore taks)
                ? taks.delegate().getClass().getSimpleName()
                : ks.getClass().getSimpleName();
        log.info(
                "ZhituAgent active stores: KnowledgeStore={} (delegate={}, nativeHybrid={}), SessionRepository={} (delegate={}), MemoryStore={}, SummaryStore={}",
                ks.getClass().getSimpleName(),
                knowledgeStoreDelegate,
                ks.supportsNativeHybrid(),
                sr.getClass().getSimpleName(),
                sessionRepoDelegate,
                ms.getClass().getSimpleName(),
                ss.getClass().getSimpleName());
    }
}
