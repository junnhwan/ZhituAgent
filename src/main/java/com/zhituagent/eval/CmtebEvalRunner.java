package com.zhituagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.EvalProperties;
import com.zhituagent.eval.CmtebFixtureLoader.Fixture;
import com.zhituagent.eval.CmtebFixtureLoader.Query;
import com.zhituagent.rag.KnowledgeChunk;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.rag.KnowledgeStoreIds;
import com.zhituagent.rag.RagRetrievalResult;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.rag.RetrievalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval-only evaluation against C-MTEB BEIR-format fixtures. Skips the
 * chat / LLM stack entirely — we only care whether the dense + sparse + rerank
 * retrieval layer ranks ground-truth-relevant docs into the top-K.
 *
 * <p>Activated by {@code zhitu.eval.cmteb.enabled=true}. Sweep across chunk
 * size / overlap / retrieval mode by re-launching the boot process with
 * different {@code zhitu.eval.cmteb.*} flags + a fresh
 * {@code zhitu.elasticsearch.index-name} per group, so each run starts with a
 * clean ES index.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.eval.cmteb", name = "enabled", havingValue = "true")
public class CmtebEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(CmtebEvalRunner.class);
    private static final String SOURCE_PREFIX = "cmteb-doc-";
    private static final int INGEST_BATCH = 200;

    private final CmtebFixtureLoader fixtureLoader;
    private final KnowledgeIngestService knowledgeIngestService;
    private final RagRetriever ragRetriever;
    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    public CmtebEvalRunner(CmtebFixtureLoader fixtureLoader,
                           KnowledgeIngestService knowledgeIngestService,
                           RagRetriever ragRetriever,
                           EvalProperties evalProperties,
                           ObjectMapper objectMapper) {
        this.fixtureLoader = fixtureLoader;
        this.knowledgeIngestService = knowledgeIngestService;
        this.ragRetriever = ragRetriever;
        this.evalProperties = evalProperties;
        this.objectMapper = objectMapper;
    }

    public Report run(Path reportPath) throws IOException {
        EvalProperties.Cmteb cmteb = evalProperties.getCmteb();
        Fixture fixture = fixtureLoader.load(Path.of(cmteb.getFixtureDir()));
        log.info(
                "cmteb fixture loaded corpus={} queries={} qrels-queries={}",
                fixture.corpusById().size(),
                fixture.queries().size(),
                fixture.qrelsByQueryId().size()
        );

        IngestResult ingestResult = cmteb.isSkipIngest()
                ? new IngestResult(0, 0L)
                : ingestCorpus(fixture, cmteb);
        if (cmteb.isSkipIngest()) {
            log.info("cmteb ingest SKIPPED (skipIngest=true) — reusing existing index data");
        } else {
            log.info(
                    "cmteb ingest done docs={} chunks={} latencyMs={}",
                    fixture.corpusById().size(),
                    ingestResult.chunksIngested(),
                    ingestResult.latencyMs()
            );
        }

        RetrievalMode mode = RetrievalMode.fromValue(cmteb.getRetrievalMode());
        List<QueryResult> perQuery = new ArrayList<>();
        for (Query query : fixture.queries()) {
            perQuery.add(runQuery(query, fixture.qrelsByQueryId(), mode, cmteb));
        }

        Report report = aggregate(fixture, ingestResult, perQuery, cmteb);
        writeReport(report, reportPath);
        log.info(
                "cmteb eval written nDCG@{}={} Recall@{}={} MRR@{}={} reportPath={}",
                cmteb.getTopK(),
                String.format(java.util.Locale.ROOT, "%.4f", report.meanNdcgAtTopK()),
                cmteb.getRecallK(),
                String.format(java.util.Locale.ROOT, "%.4f", report.meanRecallAtRecallK()),
                cmteb.getRecallK(),
                String.format(java.util.Locale.ROOT, "%.4f", report.meanMrrAtRecallK()),
                reportPath.toAbsolutePath()
        );
        return report;
    }

    private IngestResult ingestCorpus(Fixture fixture, EvalProperties.Cmteb cmteb) {
        long start = System.currentTimeMillis();
        List<KnowledgeChunk> buffer = new ArrayList<>(INGEST_BATCH);
        int chunkCount = 0;
        for (Map.Entry<String, String> entry : fixture.corpusById().entrySet()) {
            String docId = entry.getKey();
            String text = entry.getValue();
            String source = SOURCE_PREFIX + docId;
            for (String chunk : slidingWindow(text, cmteb.getChunkSize(), cmteb.getChunkOverlap())) {
                buffer.add(new KnowledgeChunk(
                        source,
                        KnowledgeStoreIds.computeChunkId(source, chunk),
                        chunk,
                        null
                ));
                chunkCount++;
                if (buffer.size() >= INGEST_BATCH) {
                    knowledgeIngestService.ingest(buffer);
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            knowledgeIngestService.ingest(buffer);
        }
        return new IngestResult(chunkCount, System.currentTimeMillis() - start);
    }

    private QueryResult runQuery(Query query,
                                 Map<String, Set<String>> qrels,
                                 RetrievalMode mode,
                                 EvalProperties.Cmteb cmteb) {
        Set<String> relevant = qrels.getOrDefault(query.id(), Set.of());
        if (relevant.isEmpty()) {
            return QueryResult.skipped(query);
        }

        long start = System.currentTimeMillis();
        RagRetrievalResult retrieval;
        try {
            // Pull more than topK because multiple chunks per doc collapse to one doc id;
            // dedupe down to topK doc ids below.
            retrieval = ragRetriever.retrieveDetailed(query.text(), cmteb.getTopK() * 5, mode);
        } catch (RuntimeException exception) {
            log.warn("cmteb retrieve failed queryId={} reason={}", query.id(), exception.getMessage());
            return QueryResult.errored(query, System.currentTimeMillis() - start, exception.getMessage());
        }
        long latencyMs = System.currentTimeMillis() - start;

        List<String> retrievedDocIds = dedupeToDocIds(retrieval.snippets(), cmteb.getTopK());
        double ndcg = com.zhituagent.eval.RankingMetrics.ndcgAtK(retrievedDocIds, relevant, cmteb.getTopK());
        double recall = com.zhituagent.eval.RankingMetrics.recallAtK(retrievedDocIds, relevant, cmteb.getRecallK());
        double mrr = com.zhituagent.eval.RankingMetrics.mrrAtK(retrievedDocIds, relevant, cmteb.getRecallK());
        boolean hit = com.zhituagent.eval.RankingMetrics.hitAtK(retrievedDocIds, relevant, cmteb.getRecallK());

        return new QueryResult(
                query.id(),
                preview(query.text()),
                retrievedDocIds,
                List.copyOf(relevant),
                ndcg,
                recall,
                mrr,
                hit,
                latencyMs,
                retrieval.retrievalMode(),
                retrieval.retrievalCandidateCount(),
                retrieval.rerankModel(),
                false,
                ""
        );
    }

    private static List<String> dedupeToDocIds(List<KnowledgeSnippet> snippets, int topK) {
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeSnippet snippet : snippets) {
            String source = snippet.source();
            if (source == null || !source.startsWith(SOURCE_PREFIX)) {
                continue;
            }
            seen.add(source.substring(SOURCE_PREFIX.length()));
            if (seen.size() >= topK) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Plain sliding-window character split. Deliberately simple to keep
     * benchmark hyperparameters legible — no HanLP fallback / sentence-boundary
     * heuristics, so chunkSize × overlap directly reflect what the metric
     * curves attribute the change to.
     */
    static List<String> slidingWindow(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        int step = Math.max(1, chunkSize - overlap);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + chunkSize);
            chunks.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    private Report aggregate(Fixture fixture,
                             IngestResult ingest,
                             List<QueryResult> perQuery,
                             EvalProperties.Cmteb cmteb) {
        List<QueryResult> scored = perQuery.stream().filter(QueryResult::scored).toList();
        int scoredCount = scored.size();
        long erroredCount = perQuery.stream().filter(QueryResult::errored).count();
        long skippedCount = perQuery.size() - scoredCount - erroredCount;

        double meanNdcg = mean(scored, QueryResult::ndcgAtTopK);
        double meanRecall = mean(scored, QueryResult::recallAtRecallK);
        double meanMrr = mean(scored, QueryResult::mrrAtRecallK);
        double hitRate = scoredCount == 0
                ? 0.0
                : scored.stream().filter(QueryResult::hitAtRecallK).count() / (double) scoredCount;

        List<Long> latencies = scored.stream()
                .map(QueryResult::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();

        return new Report(
                "cmteb-t2-retrieval",
                cmteb.getLabel(),
                OffsetDateTime.now().toString(),
                fixture.corpusById().size(),
                fixture.queries().size(),
                cmteb.getChunkSize(),
                cmteb.getChunkOverlap(),
                cmteb.getTopK(),
                cmteb.getRecallK(),
                cmteb.getRetrievalMode(),
                ingest.chunksIngested(),
                ingest.latencyMs(),
                scoredCount,
                (int) skippedCount,
                (int) erroredCount,
                meanNdcg,
                meanRecall,
                meanMrr,
                hitRate,
                percentile(latencies, 0.50),
                percentile(latencies, 0.90),
                percentile(latencies, 0.99),
                perQuery
        );
    }

    private static double mean(List<QueryResult> rows, java.util.function.ToDoubleFunction<QueryResult> fn) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        return rows.stream().mapToDouble(fn).average().orElse(0.0);
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private void writeReport(Report report, Path reportPath) throws IOException {
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private record IngestResult(int chunksIngested, long latencyMs) {
    }

    public record QueryResult(
            String queryId,
            String queryPreview,
            List<String> retrievedDocIds,
            List<String> relevantDocIds,
            double ndcgAtTopK,
            double recallAtRecallK,
            double mrrAtRecallK,
            boolean hitAtRecallK,
            long latencyMs,
            String retrievalMode,
            int candidateCount,
            String rerankModel,
            boolean errored,
            String errorMessage
    ) {

        boolean scored() {
            return !errored && !relevantDocIds.isEmpty();
        }

        static QueryResult skipped(Query query) {
            return new QueryResult(query.id(), preview(query.text()), List.of(), List.of(),
                    0.0, 0.0, 0.0, false, 0L, "", 0, "", false, "no qrels");
        }

        static QueryResult errored(Query query, long latencyMs, String message) {
            return new QueryResult(query.id(), preview(query.text()), List.of(), List.of(),
                    0.0, 0.0, 0.0, false, latencyMs, "", 0, "", true, message);
        }
    }

    public record Report(
            String task,
            String label,
            String generatedAt,
            int corpusSize,
            int queryCount,
            int chunkSize,
            int chunkOverlap,
            int topK,
            int recallK,
            String retrievalMode,
            int chunksIngested,
            long ingestLatencyMs,
            int scoredQueries,
            int skippedQueries,
            int erroredQueries,
            double meanNdcgAtTopK,
            double meanRecallAtRecallK,
            double meanMrrAtRecallK,
            double hitRateAtRecallK,
            double p50RetrieveLatencyMs,
            double p90RetrieveLatencyMs,
            double p99RetrieveLatencyMs,
            List<QueryResult> perQuery
    ) {
    }
}
