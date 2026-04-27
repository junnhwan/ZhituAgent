package com.zhituagent.llm;

public final class OpenAiCompatibleBaseUrlNormalizer {

    private OpenAiCompatibleBaseUrlNormalizer() {
    }

    public static String normalize(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        normalized = trimTrailingSlash(normalized);
        normalized = stripEndpointSuffix(normalized, "/chat/completions");
        normalized = stripEndpointSuffix(normalized, "/completions");
        normalized = stripEndpointSuffix(normalized, "/embeddings");
        normalized = stripEndpointSuffix(normalized, "/moderations");
        normalized = stripEndpointSuffix(normalized, "/images/generations");
        return normalized;
    }

    private static String stripEndpointSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
