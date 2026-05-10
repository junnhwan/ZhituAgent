package com.zhituagent.memory;

import java.util.Optional;

public interface SummaryStore {

    Optional<ConversationSummaryState> get(String sessionId);

    void save(String sessionId, ConversationSummaryState state);
}
