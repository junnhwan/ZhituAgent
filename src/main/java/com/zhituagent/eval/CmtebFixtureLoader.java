package com.zhituagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads BEIR-format C-MTEB fixtures produced by tools/eval/fetch_cmteb.py:
 * <ul>
 *     <li>{@code corpus.jsonl}  — {@code {"_id", "text"}}</li>
 *     <li>{@code queries.jsonl} — {@code {"_id", "text"}}</li>
 *     <li>{@code qrels.tsv}     — {@code query-id\tcorpus-id\tscore} (header on line 1)</li>
 * </ul>
 * The fixture lives under {@code target/eval-fixtures/} (gitignored) — refresh
 * by re-running the Python tool.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.eval.cmteb", name = "enabled", havingValue = "true")
public class CmtebFixtureLoader {

    private final ObjectMapper objectMapper;

    public CmtebFixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Fixture load(Path baseDir) throws IOException {
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalStateException(
                    "C-MTEB fixture directory missing: " + baseDir.toAbsolutePath()
                            + " — run tools/eval/fetch_cmteb.py first");
        }
        Map<String, String> corpus = loadDocs(baseDir.resolve("corpus.jsonl"));
        List<Query> queries = loadQueries(baseDir.resolve("queries.jsonl"));
        Map<String, Set<String>> qrels = loadQrels(baseDir.resolve("qrels.tsv"));
        return new Fixture(corpus, queries, qrels);
    }

    private Map<String, String> loadDocs(Path path) throws IOException {
        Map<String, String> docs = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                JsonNode node = parse(line);
                docs.put(node.path("_id").asText(), node.path("text").asText());
            });
        }
        return docs;
    }

    private List<Query> loadQueries(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(line -> {
                        JsonNode node = parse(line);
                        return new Query(node.path("_id").asText(), node.path("text").asText());
                    })
                    .toList();
        }
    }

    private Map<String, Set<String>> loadQrels(Path path) throws IOException {
        Map<String, Set<String>> qrels = new HashMap<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.skip(1)
                    .filter(line -> !line.isBlank())
                    .forEach(line -> {
                        String[] cols = line.split("\t");
                        if (cols.length < 3) {
                            return;
                        }
                        double score = Double.parseDouble(cols[2]);
                        if (score <= 0.0) {
                            return;
                        }
                        qrels.computeIfAbsent(cols[0], k -> new HashSet<>()).add(cols[1]);
                    });
        }
        return qrels;
    }

    private JsonNode parse(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (IOException exception) {
            throw new IllegalStateException("invalid jsonl line: " + line, exception);
        }
    }

    public record Fixture(
            Map<String, String> corpusById,
            List<Query> queries,
            Map<String, Set<String>> qrelsByQueryId
    ) {
    }

    public record Query(String id, String text) {
    }
}
