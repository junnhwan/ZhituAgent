package com.zhituagent.session;

import java.util.Optional;

public interface SessionRepository {

    Optional<SessionMetadata> findById(String sessionId);

    void save(SessionMetadata metadata);
}
