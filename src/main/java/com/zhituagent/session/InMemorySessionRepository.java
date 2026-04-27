package com.zhituagent.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, SessionMetadata> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionMetadata> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void save(SessionMetadata metadata) {
        sessions.put(metadata.getSessionId(), metadata);
    }
}
