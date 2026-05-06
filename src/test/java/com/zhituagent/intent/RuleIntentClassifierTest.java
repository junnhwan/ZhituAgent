package com.zhituagent.intent;

import com.zhituagent.config.LlmProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleIntentClassifierTest {

    private final RuleIntentClassifier classifier = new RuleIntentClassifier(List.<LlmProperties.Intent.Rule>of());

    @Test
    void classifiesPureGreetingAsGreetingWithHighConfidence() {
        IntentResult result = classifier.classify("你好", Map.of());
        assertThat(result.label()).isEqualTo(IntentLabel.GREETING);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.tier()).isEqualTo(IntentResult.Tier.RULE);
    }

    @Test
    void classifiesEnglishGreetingCaseInsensitively() {
        assertThat(classifier.classify("Hello", Map.of()).label()).isEqualTo(IntentLabel.GREETING);
        assertThat(classifier.classify("HEY!", Map.of()).label()).isEqualTo(IntentLabel.GREETING);
    }

    @Test
    void classifiesTimeQueryFromChineseAndEnglish() {
        assertThat(classifier.classify("现在几点了", Map.of()).label()).isEqualTo(IntentLabel.TIME_QUERY);
        assertThat(classifier.classify("今天是星期几", Map.of()).label()).isEqualTo(IntentLabel.TIME_QUERY);
        assertThat(classifier.classify("what time is it now?", Map.of()).label()).isEqualTo(IntentLabel.TIME_QUERY);
    }

    @Test
    void substantiveQuestionsFallthroughToProtectAgainstFalsePositives() {
        // "你好" embedded in a longer question must NOT be misrouted to GREETING.
        IntentResult result = classifier.classify("你好，请详细介绍一下 RAG 的工作原理", Map.of());
        assertThat(result.label()).isIn(IntentLabel.FALLTHROUGH);
    }

    @Test
    void blankInputFallsthrough() {
        assertThat(classifier.classify("", Map.of()).label()).isEqualTo(IntentLabel.FALLTHROUGH);
        assertThat(classifier.classify(null, Map.of()).label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    @Test
    void customRuleAppendsAfterDefaults() {
        LlmProperties.Intent.Rule custom = new LlmProperties.Intent.Rule();
        custom.setName("custom.tool");
        custom.setPattern("(计算|算一下|calculate)");
        custom.setLabel("TOOL_CALL");
        custom.setConfidence(0.85);

        RuleIntentClassifier custom1 = new RuleIntentClassifier(List.of(custom));
        IntentResult result = custom1.classify("帮我算一下 2+2", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.TOOL_CALL);
        assertThat(result.confidence()).isEqualTo(0.85);
    }

    @Test
    void invalidCustomRuleIsSkippedNotFatal() {
        LlmProperties.Intent.Rule bogus = new LlmProperties.Intent.Rule();
        bogus.setName("bogus");
        bogus.setPattern("[invalid(regex");  // unclosed
        bogus.setLabel("TOOL_CALL");

        RuleIntentClassifier classifier = new RuleIntentClassifier(List.of(bogus));
        // Defaults must still work; bogus rule silently skipped.
        assertThat(classifier.classify("你好", Map.of()).label()).isEqualTo(IntentLabel.GREETING);
    }
}
