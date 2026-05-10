package com.zhituagent.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySummaryStore implements SummaryStore {

    private final Map<String, ConversationSummaryState> statesBySession = new ConcurrentHashMap<>();

    @Override
    public Optional<ConversationSummaryState> get(String sessionId) {
        return Optional.ofNullable(statesBySession.get(sessionId));
    }

    @Override
    public void save(String sessionId, ConversationSummaryState state) {
        if (sessionId == null || sessionId.isBlank() || state == null) {
            return;
        }
        statesBySession.put(sessionId, state);
    }
}
