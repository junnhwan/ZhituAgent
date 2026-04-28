package com.zhituagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryKnowledgeStore implements KnowledgeStore {

    private final CopyOnWriteArrayList<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();

    @Override
    public void addAll(List<KnowledgeChunk> chunks) {
        this.chunks.addAll(chunks);
    }

    @Override
    public List<KnowledgeSnippet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String normalizedQuery = LexicalScoringUtils.normalize(query);
        List<KnowledgeSnippet> ranked = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            double score = score(normalizedQuery, LexicalScoringUtils.normalize(chunk.content()));
            if (score > 0) {
                ranked.add(new KnowledgeSnippet(chunk.source(), chunk.chunkId(), score, chunk.content()));
            }
        }

        return ranked.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<KnowledgeSnippet> ranked = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            double score = LexicalScoringUtils.scoreText(query, chunk.content());
            if (score > 0) {
                ranked.add(new KnowledgeSnippet(chunk.source(), chunk.chunkId(), score, chunk.content()));
            }
        }

        return ranked.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private double score(String query, String content) {
        if (content.contains(query)) {
            return 1.0 + (double) query.length() / Math.max(1, content.length());
        }

        int overlap = 0;
        for (int i = 0; i < query.length(); i++) {
            String ch = query.substring(i, i + 1);
            if (!ch.isBlank() && content.contains(ch)) {
                overlap++;
            }
        }

        if (overlap < Math.min(2, query.length())) {
            return 0;
        }
        return (double) overlap / query.length();
    }

}
