package com.zhituagent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.memory.ChatMessageRecord;
import com.zhituagent.memory.FactExtractor;
import com.zhituagent.memory.MemorySnapshot;
import com.zhituagent.memory.MessageSummaryCompressor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token-budget micro benchmark for {@link ContextManager}.
 *
 * <p>Tagged {@code benchmark} so it does not run during {@code mvn test} (see
 * {@code surefire-plugin.excludedGroups} in pom.xml). Run explicitly via
 * {@code mvn -o test -Dgroups=benchmark}. Artefacts land in
 * {@code target/context-bench/results-{timestamp}.json} and {@code results-latest.{json,md}}.
 *
 * <p>Methodology: for each N in {@link #TURN_SCENARIOS} we generate 2N messages
 * (alternating user/assistant), feed them through the real
 * {@link MessageSummaryCompressor} and {@link FactExtractor}, then compare two
 * upstream LLM input shapes:
 * <ul>
 *   <li><b>raw concat</b>: {@code systemPrompt + 全部 history + RAG evidence + current}
 *       (naive baseline, no ContextManager)</li>
 *   <li><b>budgeted</b>: {@link ContextManager#build} output, after the four-tier
 *       trimming loop (drop oldest recent → drop oldest fact → clear summary →
 *       halve evidence)</li>
 * </ul>
 *
 * <p>Token estimates use the project's {@link TokenEstimator} (CJK 1 char ≈ 1
 * token, ASCII 4 char ≈ 1 token); see results doc for tokenizer caveats.
 */
@Tag("benchmark")
class ContextManagerBenchmark {

    private static final int[] TURN_SCENARIOS = {10, 30, 50, 100};
    private static final String CURRENT_MESSAGE = "线上 k8s 集群 nginx-ingress pod 频繁 CrashLoopBackOff,排查思路是?";

    private final TokenEstimator tokenEstimator = new TokenEstimator();

    @Test
    void runScenarios() throws IOException {
        String systemPrompt = readSystemPrompt();
        String evidence = mockEvidence();
        ContextManager contextManager = new ContextManager();

        List<ScenarioResult> results = new ArrayList<>();
        for (int turns : TURN_SCENARIOS) {
            results.add(runScenario(contextManager, systemPrompt, evidence, turns));
        }

        writeArtefacts(results);

        assertThat(results)
                .as("benchmark sanity: every scenario should produce positive token reduction")
                .allSatisfy(r -> assertThat(r.reductionPct()).isPositive());
    }

    private ScenarioResult runScenario(ContextManager contextManager,
                                       String systemPrompt,
                                       String evidence,
                                       int turns) {
        List<ChatMessageRecord> messages = generateConversation(turns);
        MemorySnapshot compressed = new MessageSummaryCompressor(4, 6).compress(messages);
        List<String> facts = new FactExtractor().extract(messages);
        MemorySnapshot snapshotWithFacts = new MemorySnapshot(
                compressed.summary(),
                compressed.recentMessages(),
                facts
        );

        long rawTokens = estimateRawConcat(systemPrompt, messages, evidence, CURRENT_MESSAGE);
        ContextBundle bundle = contextManager.build(systemPrompt, snapshotWithFacts, CURRENT_MESSAGE, evidence);
        long budgetedTokens = tokenEstimator.estimateMessages(bundle.modelMessages());

        return new ScenarioResult(
                turns,
                messages.size(),
                rawTokens,
                budgetedTokens,
                bundle.contextStrategy(),
                facts.size()
        );
    }

    private long estimateRawConcat(String systemPrompt,
                                   List<ChatMessageRecord> messages,
                                   String evidence,
                                   String currentMessage) {
        long total = tokenEstimator.estimateText(systemPrompt);
        for (ChatMessageRecord message : messages) {
            total += tokenEstimator.estimateText(message.content());
        }
        total += tokenEstimator.estimateText(evidence);
        total += tokenEstimator.estimateText(currentMessage);
        return total;
    }

    private List<ChatMessageRecord> generateConversation(int turns) {
        List<ChatMessageRecord> msgs = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now();
        for (int i = 0; i < turns; i++) {
            int idx = i % USER_TEMPLATES.length;
            msgs.add(new ChatMessageRecord("user", USER_TEMPLATES[idx], base.plusSeconds(i * 60L)));
            msgs.add(new ChatMessageRecord("assistant", ASSISTANT_TEMPLATES[idx], base.plusSeconds(i * 60L + 30L)));
        }
        return msgs;
    }

    private String mockEvidence() {
        return String.join("\n---\n", List.of(
                "[runbook/k8s-crashloop.md] 当 pod 出现 CrashLoopBackOff 时,首先通过 kubectl describe pod 查看 Events 段判断是 OOMKilled 还是 LivenessProbe 失败,然后用 kubectl logs --previous 看上一次启动的最后输出,通常包含真正的失败原因。",
                "[runbook/nginx-ingress-5xx.md] nginx-ingress controller 出现 5xx 突增时,优先排查 upstream service 的健康检查通过率、connection pool 是否打满、以及 controller pod 自身的 memory/CPU 是否耗尽。可以通过 nginx access log 的 upstream_response_time 字段定位。",
                "[runbook/jvm-oom.md] JVM OOM 告警分两种:堆内 OOM(java.lang.OutOfMemoryError: Java heap space)和堆外 OOM(Direct buffer memory)。前者用 jmap -histo:live 查看大对象;后者用 Native Memory Tracking + jcmd VM.native_memory 排查。",
                "[design/etcd-tuning.md] etcd 延迟突增的根因常见有三类:1)磁盘 IO 瓶颈(WAL fsync 延迟),应使用 SSD 或专用磁盘;2)网络分区导致 raft heartbeat 失败;3)snapshot 压缩慢,应配置 auto-compaction-retention 防止历史无限增长。",
                "[postmortem/2024-q3-mysql-replica-lag.md] 主从延迟从历史故障看主要原因是大事务、单线程复制瓶颈、IO 子系统瓶颈三类。可以通过 sql_thread 状态、SHOW ENGINE INNODB STATUS 的 history list length、以及主库 binlog 写入速率综合判断。"
        ));
    }

    private String readSystemPrompt() throws IOException {
        return Files.readString(Path.of("src/main/resources/system-prompt/chat-agent.txt"), StandardCharsets.UTF_8);
    }

    private void writeArtefacts(List<ScenarioResult> results) throws IOException {
        Path outDir = Path.of("target/context-bench");
        Files.createDirectories(outDir);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        long ts = Instant.now().getEpochSecond();
        Files.writeString(outDir.resolve("results-" + ts + ".json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results-latest.json"), json, StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# ContextManager Benchmark Results\n\n");
        md.append("| N(轮) | total messages | raw tokens | budgeted tokens | reduction % | facts | strategy |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (ScenarioResult r : results) {
            md.append(String.format("| %d | %d | %d | %d | %.1f%% | %d | %s |%n",
                    r.turns(), r.totalMessages(), r.rawTokens(), r.budgetedTokens(),
                    r.reductionPct() * 100, r.factCount(), r.strategy()));
        }
        Files.writeString(outDir.resolve("results-latest.md"), md.toString(), StandardCharsets.UTF_8);

        System.out.println("[context-bench] wrote " + outDir.resolve("results-" + ts + ".json"));
        System.out.println(md);
    }

    public record ScenarioResult(
            int turns,
            int totalMessages,
            long rawTokens,
            long budgetedTokens,
            String strategy,
            int factCount
    ) {
        public double reductionPct() {
            return rawTokens == 0 ? 0.0 : (rawTokens - budgetedTokens) / (double) rawTokens;
        }
    }

    private static final String[] USER_TEMPLATES = {
            "我是 SRE,负责生产环境的稳定性,这个集群的 nginx-ingress 突然出现 5xx 飙升怎么排查",
            "我在杭州做 Java 微服务的 SRE 工作,这个 OOM 告警最近一周出现了好几次,有什么思路",
            "我负责公司的 Kubernetes 平台运维,最近 etcd 延迟突增,影响了控制面的 API server 响应",
            "我目前在排查一个 MySQL 主从延迟告警,从库的 seconds_behind_master 持续上涨",
            "我做 DevOps 多年,这次的 Redis 集群 CPU 告警之前没遇到过,想请教一下排查思路",
            "之前提到的 k8s pod 频繁重启,最近又出现了类似情况,这次还伴随了 OOMKilled 信号",
            "Prometheus 抓取间隔从 15s 调到 30s 后,部分指标的告警变得不灵敏了,该怎么补偿",
            "Spark 作业在 yarn 上跑,最近几天 stage 重试率明显上升,但 driver 日志没有直接报错",
            "ES 集群最近写入 latency p99 从 200ms 涨到 800ms,GC 看起来没异常,要怎么定位",
            "Kafka consumer lag 在凌晨 2 点的批量任务后会爆涨到几百万,白天能消化掉但响应延迟很大"
    };

    private static final String[] ASSISTANT_TEMPLATES = {
            "建议先看 kubectl describe pod 的 Events 段,看是 OOMKilled 还是 LivenessProbe 失败,接着看 pod 资源 limit 设置",
            "可以先看 jstat -gc 看 GC 频率和 heap 使用,再看 jmap -histo 看大对象,如果是堆外内存就要看 Native Memory Tracking",
            "etcd 延迟突增看 raft commit/apply duration 指标,排查 disk IO 是否瓶颈,以及网络分区情况和 snapshot 进度",
            "MySQL 主从延迟先看 SHOW SLAVE STATUS 的 Relay_Log_Pos 进度,再看 Seconds_Behind_Master 是否真实",
            "Redis CPU 告警先看 slowlog,排查 KEYS / SMEMBERS 等大 O(N) 命令,再看 client list 找慢客户端",
            "类似情况通常先看是否是同一个根因复发,kubectl logs --previous 看上一次启动的最后输出最关键",
            "建议把 evaluation_interval 也调整一下,for 字段的告警持续时间相应增加,避免抓取间隔变化导致的抖动",
            "Spark stage 重试看 SparkUI 的 task 维度,通常是 shuffle fetch failed 或 executor lost 导致的连锁重试",
            "ES p99 写入延迟先看 hot threads,看是否是 merge / refresh 阻塞,再看 disk IO 和 segment count",
            "Kafka 凌晨批量任务的 lag 爆涨,可以先扩 partition + 增加 consumer 并发,或者拆离线批和实时流分集群"
    };
}
