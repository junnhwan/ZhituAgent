package com.zhituagent.rag;

import java.util.List;

public interface KnowledgeStore {

    void addAll(List<KnowledgeChunk> chunks);

    List<KnowledgeSnippet> search(String query, int limit);
}
