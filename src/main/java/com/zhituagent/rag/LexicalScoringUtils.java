package com.zhituagent.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LexicalScoringUtils {

    private LexicalScoringUtils() {
    }

    static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）【】《》「」『』]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static List<String> tokens(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }

        String compact = normalized.replace(" ", "");
        if (compact.length() >= 2) {
            tokens.add(compact);
            for (int index = 0; index < compact.length() - 1; index++) {
                tokens.add(compact.substring(index, index + 2));
            }
        }

        return new ArrayList<>(tokens).stream()
                .filter(token -> !token.isBlank())
                .limit(8)
                .toList();
    }

    static double scoreText(String query, String content) {
        List<String> tokens = tokens(query);
        String normalizedContent = normalize(content);
        if (tokens.isEmpty() || normalizedContent.isBlank()) {
            return 0.0;
        }

        double score = 0.0;
        for (String token : tokens) {
            if (normalizedContent.contains(token)) {
                score += token.length() >= 4 ? 1.5 : 1.0;
            }
        }

        if (score == 0.0) {
            return 0.0;
        }
        return score / tokens.size();
    }
}
