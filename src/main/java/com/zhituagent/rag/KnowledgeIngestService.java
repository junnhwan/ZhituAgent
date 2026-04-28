package com.zhituagent.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KnowledgeIngestService {

    private final DocumentSplitter documentSplitter;
    private final KnowledgeStore knowledgeStore;
    private final AtomicInteger chunkCounter = new AtomicInteger(1);

    @Autowired
    public KnowledgeIngestService(DocumentSplitter documentSplitter, KnowledgeStore knowledgeStore) {
        this.documentSplitter = documentSplitter;
        this.knowledgeStore = knowledgeStore;
    }

    public KnowledgeIngestService(DocumentSplitter documentSplitter) {
        this(documentSplitter, new InMemoryKnowledgeStore());
    }

    public void ingest(String question, String answer, String sourceName) {
        String document = "Q: " + question + "\nA: " + answer;
        List<String> chunks = documentSplitter.split(document);
        if (chunks.isEmpty()) {
            return;
        }

        knowledgeStore.addAll(chunks.stream()
                .map(chunk -> new KnowledgeChunk(sourceName, sourceName + "#" + chunkCounter.getAndIncrement(), chunk))
                .toList());
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        return knowledgeStore.search(query, limit);
    }

    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        return knowledgeStore.lexicalSearch(query, limit);
    }
}
