package com.zhituagent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, List<ChatMessageRecord>> messagesBySession = new ConcurrentHashMap<>();

    @Override
    public void append(String sessionId, ChatMessageRecord message) {
        messagesBySession.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(message);
    }

    @Override
    public List<ChatMessageRecord> list(String sessionId) {
        return List.copyOf(messagesBySession.getOrDefault(sessionId, List.of()));
    }
}
