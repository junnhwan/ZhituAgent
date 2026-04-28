package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPreprocessorTest {

    @Test
    void shouldNormalizeWhitespaceAndTrailingPunctuation() {
        QueryPreprocessor preprocessor = new QueryPreprocessor();

        String normalized = preprocessor.preprocess("  第一阶段先做什么？？ \n\t");

        assertThat(normalized).isEqualTo("第一阶段先做什么");
    }
}
