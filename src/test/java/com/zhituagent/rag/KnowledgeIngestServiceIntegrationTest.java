package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIngestServiceIntegrationTest {

    @Test
    void shouldMakeWrittenKnowledgeSearchable() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter());

        ingestService.ingest(
                "第一版重点是什么？",
                "第一版重点是主链路清晰、能力边界明确、后续可以量化优化。",
                "project-notes.md"
        );

        List<KnowledgeSnippet> snippets = ingestService.search("第一版重点", 3);

        assertThat(snippets).isNotEmpty();
        assertThat(snippets.getFirst().source()).isEqualTo("project-notes.md");
        assertThat(snippets.getFirst().content()).contains("第一版重点");
    }
}
