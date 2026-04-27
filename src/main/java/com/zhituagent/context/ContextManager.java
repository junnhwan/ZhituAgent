package com.zhituagent.context;

import com.zhituagent.memory.MemorySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContextManager {

    public ContextBundle build(String systemPrompt,
                               MemorySnapshot memorySnapshot,
                               String currentMessage,
                               String ragEvidence) {
        List<String> modelMessages = new ArrayList<>();
        modelMessages.add("SYSTEM: " + systemPrompt);

        if (memorySnapshot.summary() != null && !memorySnapshot.summary().isBlank()) {
            modelMessages.add("SUMMARY: " + memorySnapshot.summary());
        }

        memorySnapshot.recentMessages().forEach(message ->
                modelMessages.add(message.role().toUpperCase() + ": " + message.content())
        );

        if (ragEvidence != null && !ragEvidence.isBlank()) {
            modelMessages.add("EVIDENCE: " + ragEvidence);
        }

        modelMessages.add("USER: " + currentMessage);

        return new ContextBundle(
                systemPrompt,
                memorySnapshot.summary(),
                memorySnapshot.recentMessages(),
                currentMessage,
                List.copyOf(modelMessages)
        );
    }
}
