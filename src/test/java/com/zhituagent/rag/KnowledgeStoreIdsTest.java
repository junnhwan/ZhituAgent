package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeStoreIdsTest {

    @Test
    void shouldConvertArbitraryChunkIdToStableUuid() {
        String first = KnowledgeStoreIds.toEmbeddingId("pgvector-live-test.md#1");
        String second = KnowledgeStoreIds.toEmbeddingId("pgvector-live-test.md#1");

        assertThat(first).isEqualTo(second);
        assertThatCode(() -> UUID.fromString(first)).doesNotThrowAnyException();
    }
}
