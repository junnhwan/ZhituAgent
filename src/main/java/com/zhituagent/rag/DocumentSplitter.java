package com.zhituagent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentSplitter {

    private static final int CHUNK_SIZE = 240;

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String normalized = text.trim();
        for (int start = 0; start < normalized.length(); start += CHUNK_SIZE) {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            chunks.add(normalized.substring(start, end));
        }
        return chunks;
    }
}
