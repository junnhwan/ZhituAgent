package com.zhituagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zhituagent.config.RerankProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleRerankClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCallV1RerankEndpointAndParseScores() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();

        server.createContext("/v1/rerank", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));

            byte[] responseBytes = """
                    {
                      "results": [
                        {"index": 1, "relevance_score": 0.91},
                        {"index": 0, "relevance_score": 0.22}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    rerankProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank")
            );

            RerankClient.RerankResponse response = client.rerank(
                    "第一阶段先做什么",
                    List.of(
                            new RetrievalCandidate("source-a", "chunk-1", 0.8, "第一段"),
                            new RetrievalCandidate("source-b", "chunk-2", 0.7, "第二段")
                    ),
                    2
            );

            assertThat(authorizationHeader.get()).isEqualTo("Bearer demo-key");
            assertThat(requestBody.get().path("model").asText()).isEqualTo("Qwen/Qwen3-Reranker-8B");
            assertThat(requestBody.get().path("query").asText()).isEqualTo("第一阶段先做什么");
            assertThat(requestBody.get().path("documents")).hasSize(2);
            assertThat(requestBody.get().path("documents").get(0).asText()).isEqualTo("第一段");
            assertThat(requestBody.get().path("top_n").asInt()).isEqualTo(2);

            assertThat(response.model()).isEqualTo("Qwen/Qwen3-Reranker-8B");
            assertThat(response.results()).hasSize(2);
            assertThat(response.results().getFirst().index()).isEqualTo(1);
            assertThat(response.results().getFirst().score()).isEqualTo(0.91);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNormalizeLegacyRerankSuffixToV1Rerank() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/v1/rerank", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());

            byte[] responseBytes = """
                    {
                      "results": [
                        {"index": 0, "relevance_score": 0.88}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    rerankProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank")
            );

            RerankClient.RerankResponse response = client.rerank(
                    "第一阶段先做什么",
                    List.of(new RetrievalCandidate("source-a", "chunk-1", 0.8, "第一段")),
                    1
            );

            assertThat(requestPath.get()).isEqualTo("/v1/rerank");
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().getFirst().score()).isEqualTo(0.88);
        } finally {
            server.stop(0);
        }
    }

    private RerankProperties rerankProperties(String url) {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setUrl(url);
        properties.setApiKey("demo-key");
        properties.setModelName("Qwen/Qwen3-Reranker-8B");
        properties.setRecallTopK(20);
        properties.setFinalTopK(5);
        return properties;
    }
}
