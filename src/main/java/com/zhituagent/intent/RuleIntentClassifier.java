package com.zhituagent.intent;

import com.zhituagent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tier-1 classifier: regex-based rules. Cheapest path — runs synchronously on
 * the request thread in microseconds. When no rule matches, returns
 * {@link IntentResult#fallthrough(long)} so the orchestrator can promote to the
 * cheap LLM tier.
 *
 * <p>The default rule set covers the highest-frequency obvious cases
 * (greetings, time/date queries). Operators can add more rules via
 * {@code zhitu.llm.intent.rules[*]} — each rule has a name, a regex, an intent
 * label, and a confidence (defaults to 1.0 for rule hits since regex matches
 * are deterministic).
 *
 * <p>Rule order matters — rules are evaluated top-to-bottom and the first
 * match wins. Custom rules from config are appended <i>after</i> the built-in
 * defaults.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.llm.intent.dual-layer", name = "enabled", havingValue = "true")
public class RuleIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(RuleIntentClassifier.class);

    private final List<CompiledRule> rules;

    public RuleIntentClassifier(LlmProperties llmProperties) {
        this.rules = buildRules(llmProperties.getIntent().getRules());
        log.info("rule intent classifier ready ruleCount={}", rules.size());
    }

    /** Test-friendly constructor — bypasses config so unit tests can inject a fixed rule set. */
    RuleIntentClassifier(List<LlmProperties.Intent.Rule> ruleConfigs) {
        this.rules = buildRules(ruleConfigs);
    }

    @Override
    public IntentResult classify(String userMessage, Map<String, Object> sessionMetadata) {
        long startNanos = System.nanoTime();
        if (userMessage == null || userMessage.isBlank()) {
            return IntentResult.fallthrough(elapsedMs(startNanos));
        }
        String trimmed = userMessage.trim();
        for (CompiledRule rule : rules) {
            if (rule.pattern.matcher(trimmed).find()) {
                return IntentResult.rule(rule.label, rule.confidence, elapsedMs(startNanos));
            }
        }
        return IntentResult.fallthrough(elapsedMs(startNanos));
    }

    /**
     * Built-in defaults plus operator-supplied rules. The defaults are
     * intentionally narrow (high precision over recall): a noisy rule that
     * mis-routes a substantive question to GREETING is far worse than missing
     * a few greetings and paying the cheap-LLM cost.
     */
    private static List<CompiledRule> buildRules(List<LlmProperties.Intent.Rule> configRules) {
        List<CompiledRule> compiled = new ArrayList<>();

        // Defaults — anchored to keep precision high.
        compiled.add(new CompiledRule(
                "default.greeting",
                Pattern.compile("^\\s*(你好|您好|hi|hello|hey|嗨|早上好|晚上好|下午好)[\\s!?。，,.!]*$",
                        Pattern.CASE_INSENSITIVE),
                IntentLabel.GREETING,
                0.98
        ));
        compiled.add(new CompiledRule(
                "default.time_query",
                Pattern.compile("(现在|此刻|今天|今日)?(几点|几号|几月|星期几|周几|时间|日期|时刻)|what\\s+time|today'?s\\s+date|day\\s+of\\s+week",
                        Pattern.CASE_INSENSITIVE),
                IntentLabel.TIME_QUERY,
                0.95
        ));

        if (configRules != null) {
            for (LlmProperties.Intent.Rule rc : configRules) {
                if (rc.getPattern() == null || rc.getPattern().isBlank() || rc.getLabel() == null) {
                    continue;
                }
                try {
                    compiled.add(new CompiledRule(
                            rc.getName() == null ? "custom" : rc.getName(),
                            Pattern.compile(rc.getPattern(), Pattern.CASE_INSENSITIVE),
                            IntentLabel.valueOf(rc.getLabel()),
                            rc.getConfidence() <= 0 ? 0.9 : rc.getConfidence()
                    ));
                } catch (RuntimeException exception) {
                    log.warn(
                            "skipping invalid intent rule name={} pattern={} label={} error={}",
                            rc.getName(), rc.getPattern(), rc.getLabel(), exception.getMessage()
                    );
                }
            }
        }

        return List.copyOf(compiled);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record CompiledRule(String name, Pattern pattern, IntentLabel label, double confidence) {
    }
}
