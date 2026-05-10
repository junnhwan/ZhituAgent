package com.zhituagent.memory;

import java.util.List;

@FunctionalInterface
public interface ConversationSummarizer {

    SummaryResult summarize(String previousSummary, List<ChatMessageRecord> messagesToCompress);
}
