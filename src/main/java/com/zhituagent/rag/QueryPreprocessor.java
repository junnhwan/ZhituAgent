package com.zhituagent.rag;

import org.springframework.stereotype.Component;

@Component
public class QueryPreprocessor {

    public String preprocess(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = query
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）【】《》「」『』]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized;
    }
}
