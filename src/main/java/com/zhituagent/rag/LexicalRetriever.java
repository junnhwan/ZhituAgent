package com.zhituagent.rag;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LexicalRetriever {

    private final KnowledgeIngestService knowledgeIngestService;

    public LexicalRetriever(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        return knowledgeIngestService.lexicalSearch(query, limit);
    }
}
