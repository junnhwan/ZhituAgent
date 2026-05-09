# Multi-Agent SRE 实施手册(execution handbook)

> **关系**:`docs/2026-05-01-multi-agent-plan.md` 是设计意图(为什么 / 架构 / 风险),task list 是进度跟踪,**本文是操作清单**(立即敲什么命令 / 改哪个文件 / 验收什么)。新会话开局必读。

---

## 0. 新会话开局必读(强制 5 步)

每次开新 session 接手本工作时,严格按以下顺序:

1. **读 `CLAUDE.md`** — 30 秒理解项目状态 + 协作模式
2. **读 user memory** — `~/.claude/projects/D--dev-my-proj-java-zhitu-agent-java/memory/MEMORY.md` 索引,然后 `project_multi_agent_plan.md`(知道当前进度)
3. **读本文 §1 进度快照** — 已完成清单 + git status 期望
4. **跑 `mvn -o test`** — 验证 132/132 仍绿(防上次 session 留下未提交的破损)
5. **`TaskList`** — 看下一个 pending task,对应本文 §3-§11 章节执行

> ⚠️ 不要重新读 `docs/2026-05-01-multi-agent-plan.md` 全文 — 1100+ 行太重,只在具体步骤需要 `plan §X` 引用时去查对应章节。本手册已抽取关键设计点。

---

## 1. 进度快照(2026-05-01 19:37 时点)

### 1.1 已完成

✅ **步骤 1(task #10)** — SRE fixture 准备,2h
- `src/main/resources/sre-fixtures/alerts/alert-{001..004}.json`(4 条)
- `src/main/resources/sre-fixtures/runbooks/{HighCPUUsage,HighMemoryUsage,DBConnectionPoolExhausted,SlowResponse}.md`(4 份)
- `src/main/resources/sre-fixtures/logs/{order,payment,user}-service.json`(3 份)
- `src/main/resources/sre-fixtures/metrics/{order,payment,user}-service.json`(3 份)
- `src/main/java/com/zhituagent/tool/sre/SreFixtureLoader.java`(共享 classpath 资源加载 helper)
- `src/main/java/com/zhituagent/tool/sre/SreRunbookLoader.java`(`@Order(HIGHEST_PRECEDENCE)` ApplicationRunner,启动时灌 runbook 进 KB,**tied to `zhitu.app.multi-agent-enabled`** flag,默认 false)

✅ **步骤 2(task #1)** — SRE 工具,1.5h
- `src/main/java/com/zhituagent/tool/sre/QueryLogsTool.java`(name=`query_logs`,params: service[required] / level[optional INFO|WARN|ERROR])
- `src/main/java/com/zhituagent/tool/sre/QueryMetricsTool.java`(name=`query_metrics`,params: service[required] / metric[optional])
- `src/test/java/com/zhituagent/tool/sre/QueryLogsToolTest.java`(5 case)
- `src/test/java/com/zhituagent/tool/sre/QueryMetricsToolTest.java`(5 case)

### 1.2 验收数字

- `mvn -o test` → **132/132 绿**(原 122 + 新 10)
- 编译 124 source files OK

### 1.3 git status 期望

```
M optimize-progress.md (历史保留)
?? docs/2026-05-01-multi-agent-plan.md  (上次 session 写的 plan,未 commit)
?? docs/2026-05-01-multi-agent-execution-handbook.md  (本手册,未 commit)
?? src/main/java/com/zhituagent/tool/sre/  (5 个新 .java 文件)
?? src/main/resources/sre-fixtures/  (14 个 fixture 文件)
?? src/test/java/com/zhituagent/tool/sre/  (2 个测试)
```

### 1.4 待做(按本文 §3-§11 顺序)

| § | task id | 标题 | 工时 | 里程碑 |
|---|---|---|---|---|
| 3 | #4 | ToolRegistry / AgentLoop allowedToolNames 重载 | 1h | |
| 4 | #3 | Agent record + AgentRegistry + 3 SRE specialist | 1.5h | |
| 5 | #2 | supervisor-sre.txt + SupervisorTurnResult JSON parse | 1h | |
| 6 | #8 | MultiAgentOrchestrator 主循环 + span 埋点 | 3h | |
| 7 | #6 | AppProperties flag + ChatService/AlertController 接入 | 2h | **M1:flag=false=v2** |
| 8 | #7 | SSE phase 扩展 + 前端文案 | 1.5h | **M2:flag=true 前端切换** |
| 9 | #11 | Fixture 加 4 条 SRE alert eval case | 0.5h | |
| 10 | #9 | v2 / v3 真 LLM baseline 跑 + compare 报告 | 1.5h | **M3:v2/v3 数字** |
| 11 | #5 | Commit + 更新 progress + memory | 0.5h | |

总计待做 ~12.5h。每个里程碑(M1 / M2 / M3)用 `AskUserQuestion` 显式停下让用户确认。

> **顺序调整说明**:原 plan §10 把 task #3 排在 #4 之前,但实际上 task #4(ToolRegistry/AgentLoop 重载)是 task #3(AgentDefaults 用工具子集)的依赖前置,所以本手册把 #4 调到 #3 前面。

---

## 2. 关键设计要点速查(plan 抽取)

### 2.1 SRE specialist 三角色(plan §3.2)

| name | description | allowedToolNames | systemPrompt 要点 |
|---|---|---|---|
| `AlertTriageAgent` | 读告警,RAG 检索 runbook,决定是否需要查日志 | `Set.of()` | "你是 SRE oncall。给定告警 JSON,先用知识库检索匹配 runbook,再判断是否需要查日志。" |
| `LogQueryAgent` | 查日志 + metric 取证 | `Set.of("query_logs","query_metrics","time")` | "调用 query_logs / query_metrics。简洁返回 3-5 条最相关证据。" |
| `ReportAgent` | 生成 Markdown 报告 | `Set.of()` | "Markdown 输出:## 根因 / ## 影响范围 / ## 处置建议(引用 runbook 步骤)/ ## 监控数据。只用前序 agent 输出。" |

**关键决策:AlertTriageAgent allowedTools 为空** — 它内部直接调 `RagRetriever`(走 KB 里的 runbook),不通过 ToolRegistry。这跟当前 v2 的 retrieval 路径一致。

### 2.2 Supervisor 契约(plan §3.3)

- 一次 `LlmRuntime.generate` 调用,**不带工具**(纯文本生成 → JSON parse)
- 输出 strict JSON: `{"next": "AlertTriageAgent" | "LogQueryAgent" | "ReportAgent" | "FINISH", "reason": "..."}`
- maxRounds=5
- JSON 解析失败兜底:**fallback 到 ReportAgent + log warn**(不重试,multi-agent 场景下 fallback 比 retry 稳)
- prompt 模板放 `src/main/resources/system-prompt/supervisor-sre.txt`

### 2.3 SSE phase 扩展(plan §6.1)

新增 3 个 phase 字符串,**不改 SseEventType 枚举**,只扩 phase 字符串:
- `supervisor-routing`(detail: `{round}`)
- `agent`(detail: `{agentName, round}`)
- `final-writing`(detail: `{}`)

前端文案(`ChatMessage.tsx` PHASE_LABEL):
```
supervisor-routing       → "调度中..."
agent + AlertTriageAgent → "告警分析中..."
agent + LogQueryAgent    → "日志查询中..."
agent + ReportAgent      → "报告生成中..."
final-writing            → "正在生成最终报告..."
```

### 2.4 Span 嵌套结构(plan §6.2)

```
chat.turn (root)
└── multi-agent.run (kind=agent)
    ├── agent.supervisor (kind=llm) [round=1, attrs: next/reason]
    ├── agent.specialist.AlertTriageAgent (kind=agent)
    │   └── agent.iter (复用 AgentLoop 的 span)
    ├── agent.supervisor [round=2]
    ├── agent.specialist.LogQueryAgent
    │   └── agent.iter
    │       ├── agent.tool_calls (kind=tool, name=query_logs)
    │       └── agent.tool_calls (kind=tool, name=query_metrics)
    ├── agent.supervisor [round=3]
    ├── agent.specialist.ReportAgent
    │   └── agent.iter
    └── agent.supervisor [round=4, next=FINISH]
```

完全沿用 `SpanCollector.startSpan(name, kind, attrs)`,**不改它**。

---

## 3. 步骤 3(task #4):ToolRegistry / AgentLoop allowedToolNames 重载

### 3.1 上下文

为 specialist 提供工具子集隔离 — supervisor 路由到 LogQueryAgent 时,该 agent 只能看到 `query_logs / query_metrics / time`,看不到其他工具。
**先做这个**,因为下一步(#3 AgentDefaults)的 specialist 配置需要 `allowedToolNames` 真实生效。

### 3.2 修改文件

#### A. `src/main/java/com/zhituagent/tool/ToolRegistry.java`

加 `specifications(Set<String> allowedNames)` 重载,放在现有 `specifications()` 后面:

```java
/**
 * Returns specifications filtered to the given tool name subset.
 * Pass {@code null} (or call the no-arg overload) to get all tools.
 * Used by multi-agent specialist isolation: supervisor routes to a specialist
 * with a restricted tool set so the LLM does not see tools it cannot use.
 */
public synchronized List<ToolSpecification> specifications(Set<String> allowedNames) {
    if (allowedNames == null) {
        return specifications();
    }
    return toolsByName.values().stream()
            .filter(t -> allowedNames.contains(t.name()))
            .map(ToolDefinition::toolSpecification)
            .toList();
}
```

#### B. `src/main/java/com/zhituagent/orchestrator/AgentLoop.java`

加 `run(..., Set<String> allowedToolNames)` 重载。先 Read 现有 run 签名定位,然后:

1. 把现有 `run(...)` 主体抽成内部方法 `runInternal(..., Set<String> allowedToolNames)`
2. 老 `run(...)` 委托新方法传 `null`
3. 新 `run(..., Set<String> allowedToolNames)` 直接传过去
4. 内部把 `toolRegistry.specifications()` 改为 `toolRegistry.specifications(allowedToolNames)`

注意:可能需要在 ToolCallExecutor 的 `find(name)` 那里也做工具子集校验(防止 LLM 硬编码工具名调用未授权工具)。但 Phase 1 信任 LLM 只调它看到的工具,先不加这层校验,等出问题再补。

### 3.3 新增/扩展测试

#### `src/test/java/com/zhituagent/tool/ToolRegistryTest.java`

加 3 个 case:

```java
@Test
void shouldFilterSpecificationsByAllowedNames() {
    ToolRegistry registry = new ToolRegistry(List.of(/* 3 builtin tools */));
    List<ToolSpecification> specs = registry.specifications(Set.of("time"));
    assertThat(specs).hasSize(1);
    assertThat(specs.get(0).name()).isEqualTo("time");
}

@Test
void shouldReturnAllSpecificationsWhenAllowedNamesIsNull() {
    ToolRegistry registry = new ToolRegistry(List.of(/* 3 builtin tools */));
    assertThat(registry.specifications(null)).hasSize(3);
}

@Test
void shouldReturnEmptyWhenAllowedNamesIsEmpty() {
    ToolRegistry registry = new ToolRegistry(List.of(/* 3 builtin tools */));
    assertThat(registry.specifications(Set.of())).isEmpty();
}
```

#### `src/test/java/com/zhituagent/orchestrator/AgentLoopTest.java`(已存在,加 case)

先 Read 找到现有测试风格(它怎么 mock LLM)。加:

```java
@Test
void shouldOnlyExposeAllowedToolsToLlm() {
    // 跑一个 run(..., Set.of("time")) 且让 mock LLM 验证它只看到 time spec
}
```

### 3.4 验收

```bash
mvn -o test
```

- 全绿:原 132 + 新 ~3-4 = ~135-136
- 老 ToolRegistryTest 既有 2 case 不动(向后兼容)
- 老 AgentLoop 调用方(ChatService 等)零改动

### 3.5 Mark done

`TaskUpdate #4 → completed`,`TaskList` 看下一个。

---

## 4. 步骤 4(task #3):Agent record + AgentRegistry + 3 SRE specialist

### 4.1 上下文

定义 `Agent` 数据结构 + 注册 3 个 SRE specialist。**没有继承,没有 abstract class**,符合 AGENTS.md「不提前抽象」原则。

### 4.2 新增文件

#### A. `src/main/java/com/zhituagent/agent/Agent.java`

```java
package com.zhituagent.agent;

import java.util.Set;

public record Agent(
        String name,
        String description,
        String systemPrompt,
        Set<String> allowedToolNames,
        String modelHint
) {
    public Agent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name must not be blank");
        }
        allowedToolNames = allowedToolNames == null ? Set.of() : Set.copyOf(allowedToolNames);
    }
}
```

#### B. `src/main/java/com/zhituagent/agent/AgentRegistry.java`

```java
package com.zhituagent.agent;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentRegistry {
    private final Map<String, Agent> byName;

    public AgentRegistry(List<Agent> agents) {
        Map<String, Agent> map = new LinkedHashMap<>();
        for (Agent a : agents) {
            if (map.put(a.name(), a) != null) {
                throw new IllegalStateException("duplicate agent name: " + a.name());
            }
        }
        this.byName = Collections.unmodifiableMap(map);
    }

    public Optional<Agent> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Agent> all() { return List.copyOf(byName.values()); }

    public String descriptionsForSupervisor() {
        StringBuilder sb = new StringBuilder();
        for (Agent a : byName.values()) {
            sb.append("- ").append(a.name()).append(": ").append(a.description()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
```

#### C. `src/main/java/com/zhituagent/agent/AgentDefaults.java`

```java
package com.zhituagent.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Set;

@Configuration
public class AgentDefaults {

    @Bean
    Agent alertTriageAgent() {
        return new Agent(
            "AlertTriageAgent",
            "Reads the alert payload, retrieves the most relevant runbook from the knowledge base, decides whether log inspection is needed before reporting.",
            """
            You are the SRE oncall triage agent. Given an alert JSON in the conversation,
            retrieve the most relevant runbook from the knowledge base, then decide:
            - If the runbook plus alert annotations clearly explain the root cause and required action, your output is the triage summary.
            - If you need log/metric evidence before recommending action, say so explicitly so the supervisor routes to LogQueryAgent next.
            Keep your output under 150 words. Quote concrete runbook step numbers when possible.
            """,
            Set.of(),
            null
        );
    }

    @Bean
    Agent logQueryAgent() {
        return new Agent(
            "LogQueryAgent",
            "Fetches recent logs and metrics for the affected service to surface root-cause evidence.",
            """
            You are the SRE evidence-gathering agent. Use query_logs and query_metrics tools
            to fetch the most relevant log entries and metric snapshots for the service named in the alert.
            Pick the strongest 3-5 pieces of evidence (errors / threshold breaches / abnormal trends).
            Do NOT dump everything. Output a concise evidence summary the report agent can quote.
            """,
            Set.of("query_logs", "query_metrics", "time"),
            null
        );
    }

    @Bean
    Agent reportAgent() {
        return new Agent(
            "ReportAgent",
            "Composes the final SRE alert analysis report in Markdown using prior agents' findings. Does not call tools.",
            """
            You are the SRE report writer. Synthesize the prior agents' triage and evidence
            into a Markdown report with the following sections:
            ## 根因
            ## 影响范围
            ## 处置建议  (must reference runbook step numbers if AlertTriageAgent quoted any)
            ## 监控数据  (cite metric values if LogQueryAgent provided them)
            Use only facts already established by prior agents. Do not invent.
            """,
            Set.of(),
            null
        );
    }
}
```

### 4.3 新增测试

#### `src/test/java/com/zhituagent/agent/AgentRegistryTest.java`

```java
package com.zhituagent.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class AgentRegistryTest {
    @Test
    void shouldExposeRegisteredAgentsByName() {
        AgentRegistry registry = new AgentRegistry(List.of(
            new Agent("A", "desc-a", "prompt-a", Set.of(), null),
            new Agent("B", "desc-b", "prompt-b", Set.of("tool1"), null)
        ));
        assertThat(registry.get("A")).isPresent();
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void shouldRejectDuplicateNames() {
        assertThatThrownBy(() -> new AgentRegistry(List.of(
            new Agent("A", "x", "y", Set.of(), null),
            new Agent("A", "x", "y", Set.of(), null)
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFormatDescriptionsForSupervisor() {
        AgentRegistry registry = new AgentRegistry(List.of(
            new Agent("A", "desc-a", "p", Set.of(), null),
            new Agent("B", "desc-b", "p", Set.of(), null)
        ));
        String formatted = registry.descriptionsForSupervisor();
        assertThat(formatted).contains("- A: desc-a", "- B: desc-b");
    }
}
```

### 4.4 验收

- `mvn -o test` 全绿,~135 + 3 = ~138
- 启动应用日志(只在 `--zhitu.app.multi-agent-enabled=true` 时验证)看到 3 个 Agent bean 被注册

### 4.5 Mark done

`TaskUpdate #3 → completed`。

---

## 5. 步骤 5(task #2):supervisor-sre.txt + SupervisorTurnResult JSON parse

### 5.1 新增文件

#### A. `src/main/resources/system-prompt/supervisor-sre.txt`

```
You are the routing supervisor for an SRE alert-analysis multi-agent system.

Available specialists:
{specialists}

You must respond with strict JSON only, no markdown fences, no commentary:
{"next": "<agent_name>" | "FINISH", "reason": "<short reason>"}

Routing rules:
1. For a fresh alert, ALWAYS start with AlertTriageAgent.
2. If AlertTriageAgent says logs/metrics are needed, route to LogQueryAgent.
3. After enough evidence, route to ReportAgent for the final markdown report.
4. Choose FINISH only after ReportAgent has produced the report.
5. Maximum 5 routing turns total — be decisive.
```

`{specialists}` 占位由 MultiAgentOrchestrator 启动时填入(`agentRegistry.descriptionsForSupervisor()`)。

#### B. `src/main/java/com/zhituagent/agent/SupervisorTurnResult.java`

```java
package com.zhituagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SupervisorTurnResult(String next, String reason) {

    private static final Pattern JSON_FENCE = Pattern.compile("\\{[^}]*\"next\"[^}]*\\}", Pattern.DOTALL);

    public static SupervisorTurnResult parseOrFallback(String raw, String fallbackNext, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new SupervisorTurnResult(fallbackNext, "empty supervisor response, fallback");
        }
        // try to extract JSON object even if wrapped in markdown fences or extra text
        Matcher m = JSON_FENCE.matcher(raw);
        String candidate = m.find() ? m.group() : raw;
        try {
            JsonNode node = mapper.readTree(candidate);
            String next = node.path("next").asText("");
            String reason = node.path("reason").asText("");
            if (next.isBlank()) {
                return new SupervisorTurnResult(fallbackNext, "missing 'next' field, fallback");
            }
            return new SupervisorTurnResult(next, reason);
        } catch (Exception e) {
            return new SupervisorTurnResult(fallbackNext, "parse error: " + e.getMessage());
        }
    }
}
```

### 5.2 新增测试

`src/test/java/com/zhituagent/agent/SupervisorTurnResultTest.java`,6+ case:

```java
@Test void shouldParseStrictJson() { ... assertNext == "AlertTriageAgent" ... }
@Test void shouldParseJsonWrappedInMarkdownFence() { /* ```json\n{...}\n``` */ }
@Test void shouldFallbackOnInvalidJson() { /* "not json" → next == fallback */ }
@Test void shouldFallbackOnMissingNextField() { /* {"reason": "..."} 无 next */ }
@Test void shouldFallbackOnEmptyResponse() { /* "" or null */ }
@Test void shouldHandleExtraFieldsGracefully() { /* {"next":"X","reason":"Y","extra":"Z"} */ }
```

### 5.3 验收

`mvn -o test` 全绿 ~138 + 6 = ~144。

---

## 6. 步骤 6(task #8):MultiAgentOrchestrator 主循环 + span 埋点

### 6.1 上下文

核心编排类。前置依赖:Agent / AgentRegistry(§4)+ SupervisorTurnResult(§5)+ ToolRegistry/AgentLoop 重载(§3)。

### 6.2 设计要点(plan §3.5)

- 主循环:`while round < maxRounds`,supervisor 决策 → specialist 执行 → 回 supervisor
- AlertTriageAgent **特殊处理**:它 allowedTools 为空,但内部要走 RAG retrieval。两种实现:
  - 选项 A(简单):AlertTriageAgent 复用 AgentLoop,user message 里塞 retrieval 结果(像 v2 retrieval-then-answer 那样)
  - 选项 B(干净):MultiAgentOrchestrator 在调 AlertTriageAgent 前先做 RAG retrieval,作为 baseContext 传入
  - **推荐选项 B** — supervisor 路由更纯(specialist 内部不再做检索),trace 也更清晰
- maxRounds 到顶时:**强制路由 ReportAgent 收尾**(不直接 FINISH,保证有报告输出)
- JSON parse 失败:**fallback ReportAgent**(不重试)

### 6.3 新增文件

- `src/main/java/com/zhituagent/agent/MultiAgentOrchestrator.java`(~250 行)
- `src/main/java/com/zhituagent/agent/MultiAgentResult.java`(record:`finalAnswer / agentTrail / supervisorDecisions / executions`)
- `src/main/java/com/zhituagent/agent/SupervisorDecision.java`(record:`round / next / reason / latencyMs`)
- `src/test/java/com/zhituagent/agent/MultiAgentOrchestratorTest.java`(5+ case)

### 6.4 测试 case 必须覆盖

| case | mock supervisor 输出 | 预期 agentTrail |
|---|---|---|
| 直接 FINISH | `{"next":"FINISH"}` | `[]`,fallback 答案 |
| 2 步早停 | turn1=AlertTriage,turn2=Report,turn3=FINISH | `[AlertTriage, Report]` |
| 3 步完整 | turn1=AlertTriage,turn2=LogQuery,turn3=Report,turn4=FINISH | `[AlertTriage, LogQuery, Report]` |
| JSON parse 失败 fallback | turn1 返回 `"not json"` | trail 应至少含 ReportAgent |
| maxRounds 到顶 | 一直返回 LogQueryAgent 不停 | 强制 ReportAgent 收尾,trail 末尾是 ReportAgent |

### 6.5 验收

- `mvn -o test` ~144 + 5 = ~149
- span 嵌套手测(可后续手测):打开 trace 应看到 `multi-agent.run` → `agent.supervisor` × N → `agent.specialist.<name>` × M

---

## 7. 步骤 7(task #6):AppProperties flag + ChatService/AlertController 接入【里程碑 1】

### 7.1 修改文件

#### A. `src/main/java/com/zhituagent/config/AppProperties.java`(具体行号 Read 后定)

```java
private boolean multiAgentEnabled = false;
private int multiAgentMaxRounds = 5;

public boolean isMultiAgentEnabled() { return multiAgentEnabled; }
public void setMultiAgentEnabled(boolean v) { this.multiAgentEnabled = v; }
public int getMultiAgentMaxRounds() { return multiAgentMaxRounds; }
public void setMultiAgentMaxRounds(int v) { this.multiAgentMaxRounds = v; }
```

#### B. `src/main/java/com/zhituagent/chat/ChatService.java`

在现有 react-enabled 分支前加 multi-agent 分支(默认 off,行为 = v2):

```java
if (appProperties.isMultiAgentEnabled() && /* 触发条件:context 含 alert JSON 标记 */) {
    return multiAgentOrchestrator.run(...);
}
// 原有逻辑不变
```

⚠️ 触发条件设计:可以用 metadata flag(`metadata.put("trigger", "alert")`),由 AlertController 设定。chat 接口不带这个 flag 走 v2 路径。

#### C. 新建 `src/main/java/com/zhituagent/api/AlertController.java`

```java
@RestController
@RequestMapping("/api/alert")
public class AlertController {
    private final MultiAgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> analyzeAlert(@RequestBody JsonNode alert) {
        // 1. 把 alert JSON 序列化成 user message
        String userMessage = "Analyze this alert:\n" + alert.toPrettyString();
        // 2. 调 multiAgentOrchestrator.run(userMessage, ...)
        // 3. 返回 { reportMarkdown, agentTrail, supervisorDecisions, latencyMs }
    }
}
```

### 7.2 验收(M1 硬要求)

- `mvn -o test` ~149 + 0 = ~149(本步骤主要是配置和接入,可能不增测试或新增 1-2 个 AlertController 集成测试)
- **flag=false 默认行为完全 = v2** — 跑现有所有测试不变,curl `/api/chat` 返回 v2 格式
- **flag=true** 启动后 curl `/api/alert` 应返回 multi-agent 报告 JSON

### 7.3 里程碑 1 检查

```
1. mvn -o test 全绿
2. 默认启动(无 flag)、curl /api/chat 跟之前完全一致
3. 启动加 --zhitu.app.multi-agent-enabled=true,日志看到 SreRunbookLoader 灌库,3 个 Agent bean 注册
4. curl POST /api/alert with alert-001.json → 返回 200 + Markdown 报告
```

**用 `AskUserQuestion` 显式停下让用户确认 4 项都对**。

---

## 8. 步骤 8(task #7):SSE phase 扩展 + 前端文案【里程碑 2】

### 8.1 后端

#### `src/main/java/com/zhituagent/api/ChatController.java`

在 `runAsync` / SSE 分支里,multi-agent 模式下根据 supervisor / specialist 切换 emit `STAGE` 事件:
- `supervisor-routing` 在 supervisor LLM 调用前
- `agent` + detail.agentName 在 specialist 开始前
- `final-writing` 在 ReportAgent 即将出 token 时

具体 emit 点应该在 MultiAgentOrchestrator 注入 `Consumer<StageEvent>` 回调,由 ChatController/AlertController 提供回调 emit SSE。

### 8.2 前端

#### `frontend/src/types/events.ts`

```ts
export type StreamingPhase = 
  | "retrieving" | "calling-tool" | "generating"
  | "supervisor-routing" | "agent" | "final-writing";

export type PhaseDetail = {
  toolName?: string;
  agentName?: string;  // 新增
  round?: number;      // 新增
};
```

#### `frontend/src/components/chat/ChatMessage.tsx`

```ts
const PHASE_LABEL: Record<StreamingPhase, string> = {
  retrieving: "检索中...",
  "calling-tool": "工具调用中...",
  generating: "生成中...",
  "supervisor-routing": "调度中...",
  agent: "处理中...",  // 默认,会被 phaseText 函数覆盖
  "final-writing": "正在生成最终报告...",
};

function phaseText(phase: StreamingPhase, detail?: PhaseDetail): string {
  if (phase === "agent" && detail?.agentName) {
    const map: Record<string, string> = {
      AlertTriageAgent: "告警分析中...",
      LogQueryAgent: "日志查询中...",
      ReportAgent: "报告生成中...",
    };
    return map[detail.agentName] || `${detail.agentName} 处理中...`;
  }
  return PHASE_LABEL[phase];
}
```

#### 可选:`frontend/src/components/AlertDemo.tsx`

简单组件:4 个 alert 选项按钮 + 点击后 fetch /api/alert + 渲染返回的 Markdown。Phase 1 可以做最简单的版本(50-100 行)。

### 8.3 验收(M2 硬要求)

```
1. cd frontend && npm run build → tsc 干净
2. 启动后端 flag=true + 前端 npm run dev
3. 浏览器打开 / 选 alert-001 → 看到 SSE phase 切换:
   "调度中..." → "告警分析中..." → "调度中..." → "日志查询中..." → "调度中..." → "报告生成中..." → "正在生成最终报告..."
```

**`AskUserQuestion` 停下让用户视觉确认**。

---

## 9. 步骤 9(task #11):Fixture 加 4 条 SRE alert eval case

### 9.1 修改文件

#### A. `src/main/resources/eval/baseline-chat-cases.jsonl`

末尾追加 4 条(详见 plan §7.1,本手册简化版):

```jsonl
{"caseId":"sre-001","type":"sre-alert","alertFixture":"alert-001","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["CPU","HPA","扩容","回滚"],"splitMode":"train"}
{"caseId":"sre-002","type":"sre-alert","alertFixture":"alert-002","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["内存","heap","泄漏","重启"],"splitMode":"train"}
{"caseId":"sre-003","type":"sre-alert","alertFixture":"alert-003","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["数据库","连接池","HikariCP","上游"],"splitMode":"eval"}
{"caseId":"sre-004","type":"sre-alert","alertFixture":"alert-004","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["响应时间","p99","降级","fallback"],"splitMode":"eval"}
```

> 注意:**4 条全是 3 步路径**(plan 里写的 sre-004 故意 2 步,本 phase 简化为统一 3 步)。

#### B. `src/main/java/com/zhituagent/eval/BaselineEvalCase.java`

加可选字段:

```java
// 现有字段后面加
private List<String> expectedAgentSequence; // nullable
private String alertFixture;                // nullable
// + getter/setter
```

`BaselineEvalRunner` 在解析 case 时:
- 如果 `case.alertFixture != null` → 从 `sre-fixtures/alerts/{alertFixture}.json` 加载,作为 user message
- 如果 `case.expectedAgentSequence != null` → 跑完后断言 actualAgentTrail prefix-match

### 9.2 验收

`mvn -o test` 仍绿(老 case 不破,新字段是可选)。

---

## 10. 步骤 10(task #9):v2 / v3 真 LLM baseline 跑 + compare 报告【里程碑 3】

### 10.1 命令

跑 v2(关 multi-agent):

```bash
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval ZHITU_LLM_MOCK_MODE=false \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true --zhitu.eval.label=v2 --zhitu.eval.modes=hybrid-rerank --zhitu.llm.rate-limit.enabled=true --zhitu.app.multi-agent-enabled=false --zhitu.app.react-enabled=true --zhitu.rag.contextual-enabled=true --zhitu.rag.fusion-strategy=rrf --zhitu.rag.self-rag-enabled=true"
```

跑 v3(开 multi-agent):

```bash
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval ZHITU_LLM_MOCK_MODE=false \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true --zhitu.eval.label=v3 --zhitu.eval.modes=hybrid-rerank --zhitu.llm.rate-limit.enabled=true --zhitu.app.multi-agent-enabled=true --zhitu.app.react-enabled=true --zhitu.rag.contextual-enabled=true --zhitu.rag.fusion-strategy=rrf --zhitu.rag.self-rag-enabled=true"
```

对比报告:

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true --zhitu.eval.compare-labels=v2,v3"
```

### 10.2 预期数字(plan §9.2)

- SRE case sre-001..004 v3 准确率 > v2(v2 单 ReAct 不知道怎么解 alert JSON)
- 原 16 case v3 latency 略高(supervisor 多 1 轮),准确率不应下降
- 总 token v3 ≈ v2 × 1.5

### 10.3 验收(M3)

- `target/eval-reports/baseline-compare-v2-vs-v3-*.md` 生成
- **数字翻车**(v3 没赢甚至输):看 trace 找根因,通常是 supervisor prompt 不准,调 `supervisor-sre.txt` 再跑

`AskUserQuestion` 停下让用户 review 数字。

---

## 11. 步骤 11(task #5):Commit + 文档收尾

### 11.1 单 commit

```bash
git add docs/2026-05-01-multi-agent-plan.md docs/2026-05-01-multi-agent-execution-handbook.md \
        src/main/java/com/zhituagent/agent/ \
        src/main/java/com/zhituagent/tool/sre/ \
        src/main/java/com/zhituagent/api/AlertController.java \
        src/main/java/com/zhituagent/config/AppProperties.java \
        src/main/java/com/zhituagent/chat/ChatService.java \
        src/main/java/com/zhituagent/api/ChatController.java \
        src/main/java/com/zhituagent/orchestrator/AgentLoop.java \
        src/main/java/com/zhituagent/tool/ToolRegistry.java \
        src/main/java/com/zhituagent/eval/BaselineEvalCase.java \
        src/main/resources/sre-fixtures/ \
        src/main/resources/system-prompt/supervisor-sre.txt \
        src/main/resources/eval/baseline-chat-cases.jsonl \
        src/test/java/com/zhituagent/agent/ \
        src/test/java/com/zhituagent/tool/sre/ \
        frontend/src/types/events.ts \
        frontend/src/components/chat/ChatMessage.tsx
        # 可选 frontend/src/components/AlertDemo.tsx

git commit -m "$(cat <<'EOF'
feat(agent): v3 SRE multi-agent + Phase 1 specialists + fixture

- SRE 智能告警分析场景 multi-agent 编排,对标 LangGraph create_supervisor /
  oncall-agent / Robusta / PagerDuty AIOps Investigation
- 新增 Supervisor + 3 SRE Specialist(AlertTriage / LogQuery / Report),
  supervisor 输出 strict JSON 路由,specialist 工具子集隔离
- 新增 sre-fixtures(4 alert / 4 runbook / 3 service log+metric)+ 启动时灌
  runbook 进 KB,tied to multi-agent-enabled flag 避免 v2 baseline 污染
- 新增 query_logs / query_metrics 工具(mock,Phase 2 可切真 Loki/Prometheus)
- AgentLoop / ToolRegistry 加 allowedToolNames 重载,specialist 内部仍复用
  AgentLoop 多轮 plan-tool-observe
- SSE phase 扩 supervisor-routing / agent:<name> / final-writing,前端文案
- 新增 4 条 SRE alert eval case + BaselineEvalCase.expectedAgentSequence,
  v2/v3 baseline-compare 量化 agent-routing-accuracy
- 142 + 单测全绿(原 122 + Phase 1 ~20),v3 vs oncall-agent 工程深度对比详见
  docs/2026-05-01-multi-agent-plan.md §1.4
EOF
)"
```

### 11.2 更新 progress + memory

- `optimize-progress.md` 加 v3 段落(简历叙事框架表追加 Phase v3 行)
- `~/.claude/projects/.../memory/project_upgrade_roadmap.md` 加 commit + 三幕剧扩展
- `~/.claude/projects/.../memory/project_multi_agent_plan.md` 标记 Phase 1 完成 + 数字

---

## 附录:常见问题

### Q1:每步做完都要跑 `mvn -o test` 吗?

**是**。每步必须保持 132+(增量 + N)全绿,这是 v2 不破坏的硬要求。

### Q2:中途某步崩了怎么办?

不要 git stash / git reset。先 mark 对应 task in_progress,**修当前问题不开新 task**。修不动告诉用户,让用户决定 rollback / 改设计。

### Q3:supervisor JSON 解析在真 LLM 下失败率高怎么办?

参考 plan §11(风险表)。Phase 1 fallback 到 ReportAgent 即可。Phase 2 可以加 GLM-5.1 JSON mode 调用。

### Q4:`mvn -o spring-boot:run` 跑不起来(.env 缺少)?

参考 CLAUDE.md「常用命令」段。需要 `.env` + pgvector 实例。如果只是开发期不跑真 LLM,可以用 `ZHITU_LLM_MOCK_MODE=true` 走 mock。

### Q5:本手册哪些内容会随项目演进过期?

- §1.1 / §1.2 进度快照(每步完成时更新)
- §1.4 待做表(每步完成时勾掉)
- 其余 §3-§11 是设计 + 操作清单,不应漂移
