package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalMergerTest {

    @Test
    void shouldMergeDenseAndLexicalCandidatesByChunkId() {
        HybridRetrievalMerger merger = new HybridRetrievalMerger();

        List<KnowledgeSnippet> dense = List.of(
                new KnowledgeSnippet("dense-source", "chunk-1", 0.92, "第一段 dense"),
                new KnowledgeSnippet("dense-source", "chunk-2", 0.80, "第二段 dense")
        );
        List<KnowledgeSnippet> lexical = List.of(
                new KnowledgeSnippet("lexical-source", "chunk-2", 1.00, "第二段 lexical"),
                new KnowledgeSnippet("lexical-source", "chunk-3", 0.75, "第三段 lexical")
        );

        List<RetrievalCandidate> merged = merger.merge(dense, lexical, 4);

        assertThat(merged).hasSize(3);
        assertThat(merged.getFirst().chunkId()).isEqualTo("chunk-2");
        assertThat(merged.getFirst().denseScore()).isEqualTo(0.80);
        assertThat(merged.getFirst().lexicalScore()).isEqualTo(1.00);
        assertThat(merged.getFirst().score()).isGreaterThan(merged.get(1).score());
    }
}
