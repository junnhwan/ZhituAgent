package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSplitterTest {

    @Test
    void shouldSplitChineseDocumentBySentenceBoundariesWithOverlap() {
        DocumentSplitter splitter = new DocumentSplitter();

        String sentence = "第一阶段重点是把主链路跑通，并且为第二阶段优化留下清晰的观测与评估基线。";
        String document = sentence.repeat(24);

        List<String> chunks = splitter.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst()).contains("第一阶段重点是把主链路跑通");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(880));
        assertThat(chunks.get(1)).contains(chunks.getFirst().substring(chunks.getFirst().length() - 40));
    }
}
