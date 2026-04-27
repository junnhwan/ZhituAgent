package com.zhituagent.rag;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagRetriever {

    private final KnowledgeIngestService knowledgeIngestService;

    public RagRetriever(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        return knowledgeIngestService.search(query, limit);
    }
}
