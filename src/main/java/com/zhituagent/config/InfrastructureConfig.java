package com.zhituagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.memory.InMemoryMemoryStore;
import com.zhituagent.memory.MemoryStore;
import com.zhituagent.memory.MemoryLock;
import com.zhituagent.memory.NoopMemoryLock;
import com.zhituagent.memory.RedisMemoryStore;
import com.zhituagent.memory.RedisMemoryLock;
import com.zhituagent.rag.InMemoryKnowledgeStore;
import com.zhituagent.rag.KnowledgeStore;
import com.zhituagent.rag.OpenAiCompatibleRerankClient;
import com.zhituagent.rag.PgVectorKnowledgeStore;
import com.zhituagent.rag.RerankClient;
import com.zhituagent.session.InMemorySessionRepository;
import com.zhituagent.session.RedisSessionRepository;
import com.zhituagent.session.SessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpClient;

@Configuration
public class InfrastructureConfig {

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    SessionRepository redisSessionRepository(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisSessionRepository(stringRedisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    SessionRepository inMemorySessionRepository() {
        return new InMemorySessionRepository();
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
    MemoryLock redisMemoryLock(StringRedisTemplate stringRedisTemplate) {
        return new RedisMemoryLock(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryLock.class)
    MemoryLock noopMemoryLock() {
        return new NoopMemoryLock();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "pgvector-enabled", havingValue = "true")
    KnowledgeStore pgVectorKnowledgeStore(PgVectorProperties pgVectorProperties,
                                          EmbeddingProperties embeddingProperties) {
        return new PgVectorKnowledgeStore(pgVectorProperties, embeddingProperties);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeStore.class)
    KnowledgeStore inMemoryKnowledgeStore() {
        return new InMemoryKnowledgeStore();
    }

    @Bean
    RerankClient rerankClient(RerankProperties rerankProperties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleRerankClient(HttpClient.newHttpClient(), objectMapper, rerankProperties);
    }
}
