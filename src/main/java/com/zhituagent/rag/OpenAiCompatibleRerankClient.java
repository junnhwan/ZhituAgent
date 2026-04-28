package com.zhituagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.RerankProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleRerankClient implements RerankClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RerankProperties rerankProperties;

    public OpenAiCompatibleRerankClient(HttpClient httpClient,
                                        ObjectMapper objectMapper,
                                        RerankProperties rerankProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.rerankProperties = rerankProperties;
    }

    @Override
    public RerankResponse rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        if (!rerankProperties.isReady()) {
            throw new IllegalStateException("rerank properties are not ready");
        }

        int safeTopN = Math.max(1, Math.min(topN, candidates.size()));
        Map<String, Object> requestBody = Map.of(
                "model", rerankProperties.getModelName(),
                "query", query,
                "documents", candidates.stream().map(RetrievalCandidate::content).toList(),
                "top_n", safeTopN
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalizeUrl(rerankProperties.getUrl())))
                    .timeout(Duration.ofMillis(Math.max(1, rerankProperties.getTimeoutMillis())))
                    .header("Authorization", "Bearer " + rerankProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("rerank request failed with status " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            List<RerankResult> results = responseJson.path("results").findValues("index").isEmpty()
                    ? List.of()
                    : parseResults(responseJson.path("results"));

            return new RerankResponse(rerankProperties.getModelName(), results);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to parse rerank response", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rerank request interrupted", exception);
        }
    }

    private List<RerankResult> parseResults(JsonNode resultsNode) {
        if (resultsNode == null || !resultsNode.isArray()) {
            return List.of();
        }

        return resultsNode.findParents("index").stream()
                .map(node -> new RerankResult(
                        node.path("index").asInt(),
                        node.path("relevance_score").asDouble()
                ))
                .toList();
    }

    private String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        if (rawUrl.endsWith("/v1/rerank")) {
            return rawUrl;
        }
        if (rawUrl.endsWith("/rerank")) {
            return rawUrl.substring(0, rawUrl.length() - "/rerank".length()) + "/v1/rerank";
        }
        if (rawUrl.endsWith("/v1")) {
            return rawUrl + "/rerank";
        }
        return rawUrl + "/v1/rerank";
    }
}
