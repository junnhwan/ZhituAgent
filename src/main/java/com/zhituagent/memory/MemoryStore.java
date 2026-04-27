package com.zhituagent.memory;

import java.util.List;

public interface MemoryStore {

    void append(String sessionId, ChatMessageRecord message);

    List<ChatMessageRecord> list(String sessionId);
}
