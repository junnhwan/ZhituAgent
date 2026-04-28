package com.zhituagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

class RerankResultCalibrator {

    private static final double ENUMERATION_BONUS_STEP = 0.0005;
    private static final double MAX_ENUMERATION_BONUS = 0.0020;
    private static final double ACRONYM_COVERAGE_BONUS = 0.0012;
    private static final double EXPLICIT_CONTAINMENT_BONUS = 0.0005;
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+_.-]{1,}");

    List<CalibratedResult> calibrate(String query,
                                     List<RetrievalCandidate> candidates,
                                     List<RerankClient.RerankResult> rerankResults) {
        if (candidates == null || candidates.isEmpty() || rerankResults == null || rerankResults.isEmpty()) {
            return List.of();
        }

        List<CalibratedResult> calibratedResults = new ArrayList<>();
        for (RerankClient.RerankResult rerankResult : rerankResults) {
            if (rerankResult.index() < 0 || rerankResult.index() >= candidates.size()) {
                continue;
            }
            RetrievalCandidate candidate = candidates.get(rerankResult.index());
            double rawScore = rerankResult.score();
            double calibratedScore = rawScore + scoreBonus(query, candidate.content());
            calibratedResults.add(new CalibratedResult(rerankResult.index(), rawScore, calibratedScore));
        }

        return calibratedResults.stream()
                .sorted(Comparator.comparingDouble(CalibratedResult::calibratedScore)
                        .thenComparingDouble(CalibratedResult::rawScore)
                        .reversed())
                .toList();
    }

    private double scoreBonus(String query, String content) {
        String answerText = extractAnswerText(content);
        return enumerationBonus(query, answerText)
                + acronymCoverageBonus(query, answerText)
                + explicitContainmentBonus(query, answerText);
    }

    private double enumerationBonus(String query, String answerText) {
        if (!hasEnumerationIntent(query)) {
            return 0.0;
        }
        int itemCount = splitEnumerationItems(answerText).size();
        if (itemCount < 3) {
            return 0.0;
        }
        return Math.min(MAX_ENUMERATION_BONUS, ENUMERATION_BONUS_STEP * (itemCount - 2));
    }

    private double acronymCoverageBonus(String query, String answerText) {
        List<String> queryTerms = extractAcronymTerms(query);
        if (queryTerms.isEmpty() || answerText == null || answerText.isBlank()) {
            return 0.0;
        }

        String normalizedAnswer = answerText.toLowerCase(Locale.ROOT);
        long hitCount = queryTerms.stream()
                .filter(term -> normalizedAnswer.contains(term.toLowerCase(Locale.ROOT)))
                .count();
        if (hitCount <= 0) {
            return 0.0;
        }
        return ACRONYM_COVERAGE_BONUS * (hitCount / (double) queryTerms.size());
    }

    private double explicitContainmentBonus(String query, String answerText) {
        String normalizedQuery = normalizeText(query);
        String normalizedAnswer = normalizeText(answerText);
        if (normalizedQuery.isBlank() || normalizedAnswer.isBlank()) {
            return 0.0;
        }
        boolean queryNeedsExplicitAnswer = containsAny(normalizedQuery, "明确", "算不算", "是否", "包含", "列出", "写出来");
        boolean answerStatesExplicitly = containsAny(normalizedAnswer, "明确", "包含", "列出", "写出");
        return queryNeedsExplicitAnswer && answerStatesExplicitly ? EXPLICIT_CONTAINMENT_BONUS : 0.0;
    }

    private boolean hasEnumerationIntent(String query) {
        String normalizedQuery = normalizeText(query);
        if (normalizedQuery.isBlank()) {
            return false;
        }
        int matchedSignals = 0;
        for (String signal : List.of("哪些", "哪几", "什么", "能力", "模块", "步骤", "包括", "包含", "范围", "几项")) {
            if (normalizedQuery.contains(signal)) {
                matchedSignals++;
            }
        }
        return matchedSignals >= 2;
    }

    private List<String> splitEnumerationItems(String answerText) {
        if (answerText == null || answerText.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(answerText.split("[、,，;；\\n]+"))
                .map(String::trim)
                .filter(item -> item.length() >= 2)
                .toList();
    }

    private List<String> extractAcronymTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher matcher = ACRONYM_PATTERN.matcher(query);
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return List.copyOf(terms);
    }

    private String extractAnswerText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String marker : List.of("\nA:", "\nA：", "A:", "A：", "\n答：", "答：")) {
            int markerIndex = content.indexOf(marker);
            if (markerIndex >= 0) {
                return content.substring(markerIndex + marker.length()).trim();
            }
        }
        return content.trim();
    }

    private String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    record CalibratedResult(
            int index,
            double rawScore,
            double calibratedScore
    ) {
    }
}
