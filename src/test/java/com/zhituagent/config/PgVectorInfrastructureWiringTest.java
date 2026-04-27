package com.zhituagent.config;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.rag.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ZhituAgentApplication.class,
        properties = {
                "zhitu.infrastructure.redis-enabled=false",
                "zhitu.infrastructure.pgvector-enabled=true",
                "zhitu.embedding.base-url=https://example.com/v1/embeddings",
                "zhitu.embedding.api-key=test-embedding-key",
                "zhitu.embedding.model-name=test-embedding-model",
                "zhitu.pgvector.host=127.0.0.1",
                "zhitu.pgvector.port=5432",
                "zhitu.pgvector.database=test_db",
                "zhitu.pgvector.username=test_user",
                "zhitu.pgvector.password=test_password",
                "zhitu.pgvector.schema=public",
                "zhitu.pgvector.table=test_knowledge"
        }
)
class PgVectorInfrastructureWiringTest {

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Test
    void shouldUsePgVectorKnowledgeStoreWhenPgVectorIsEnabled() {
        assertThat(knowledgeStore.getClass().getSimpleName()).isEqualTo("PgVectorKnowledgeStore");
    }
}
