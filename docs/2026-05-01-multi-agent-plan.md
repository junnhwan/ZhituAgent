# SRE 智能告警分析 Multi-Agent — Phase 1 + Fixture

**日期**:2026-05-01
**目标**:把 v2 通用 ReAct agent 升级为 **Supervisor + 3 SRE Specialist** 多智能体编排,场景定为「SRE oncall 告警智能分析」,对标 LangGraph `create_supervisor` / oncall-agent / Robusta / PagerDuty AIOps,作为简历 **v3 升级**(v2 ReAct 之上)。
**节奏**:Phase 1(MVP)+ SRE 数据源准备 + 4 条 alert fixture = **~2 工作日**。
**协作模式**:沿用 CLAUDE.md `feedback_collab_mode.md` — 主导写代码 + 关键决策点显式 trade-off + 里程碑停下让用户确认。

---

## 1. 背景:为什么做这个

### 1.1 v2 现状(已完成,不动)

阶段 2 工程深度已经到作品集水平(19 commit / 122 单测全绿 / v2 p90 latency -25%):

- 单 ReAct AgentLoop 4 轮 + 5 工具 + RRF + Self-RAG + Contextual Retrieval
- 嵌套 span trace + Recall/MRR/nDCG 评测 + v1↔v2 baseline-compare 报告

但有个**简历致命缺陷**:**没有业务场景**。挂在简历上"通用对话 agent",招聘官第一反应"这是干嘛用的"。

### 1.2 v3 一次解决两件事

**A. 加垂直场景 — SRE 告警分析**:输入告警 JSON,自动查日志 + 检索 runbook + 生成 Markdown 分析报告(根因 / 影响 / 处置建议)。这是 AIOps 题材里业务流程最标准、数据结构化最好仿真的入门场景。

**B. 单 agent → multi-agent 编排**:v2 单 ReAct AgentLoop 把全工具喂给一个 prompt。v3 引入 Supervisor + 3 Specialist,supervisor 输出 JSON 路由决策,每个 specialist 拿独立 system prompt + 工具子集。对标 LangGraph `create_supervisor` / OpenAI Swarm。

**A + B 揉一起做**(Phase 1 一次到位)的理由:
- 抽象 specialist(KnowledgeAgent / ToolAgent / WriterAgent)没场景灵魂,做完还得改一遍
- SRE 场景天然 3 个角色(Triage / LogQuery / Report),supervisor 路由更自然
- 简历叙事一次成型:「SRE 告警 multi-agent + 评测 + 路由准确率」

### 1.3 业界对标

| 维度 | 业界做法 | v3 对标 |
|---|---|---|
| Multi-agent 编排 | LangGraph `create_supervisor` / OpenAI Swarm / CrewAI hierarchical | Supervisor LLM + JSON routing + Agent record + agent-level span |
| SRE 智能分析 | Robusta / PagerDuty AIOps Investigation / Datadog Bits AI | AlertTriage + LogQuery + Runbook RAG + 报告生成 |
| 评测 | BEIR / MTEB / Ragas | 复用 v2 评测体系 + agent-routing-accuracy 维度 |

### 1.4 与 oncall-agent 的差异化(简历直接讲)

oncall-agent 是同题材的对照样本(D:\dev\learn_proj\xiaolin_java\oncall-agent,Spring AI Alibaba + DashScope + 飞书),它做了什么、缺什么:

| 维度 | oncall-agent | zhitu v3 |
|---|---|---|
| 单测 | **0 个** | 122 + 新增 ~20 个 |
| 评测 | 无 | Recall/MRR/nDCG + train/eval split + v2↔v3 baseline-compare |
| Multi-agent 实现 | **prompt 驱动** — 100+ 行 Markdown 提示词当编排 | **graph 驱动** — Supervisor LLM JSON routing + Agent record + 工具子集隔离 |
| Trace | 扁平日志 | 嵌套 span(supervisor + per-specialist + per-iter) |
| RAG 工程 | Milvus + 简单检索 | pgvector + RRF + Contextual Retrieval + Self-RAG 早停 |
| 限速 / 错误恢复 | 无 | rate limiter + 单 case 容错 + JSON parse fallback |
| HITL | 飞书卡片(确认/取消)| approval token + 前端 modal + auto resume(已有) |
| Mock 数据 | 写死在 tool 类里 | 独立 fixture 目录 + JSONL 数据源 |

**简历一句话**:「实现了与 oncall-agent 同题材的 SRE 智能告警分析 multi-agent,但工程深度差距明显:单测从 0 → 142,multi-agent 从 prompt 驱动 → graph 驱动,新增 v2/v3 baseline-compare 量化路由准确率。」

### 1.5 v3 量化目标(评测出数字)

- **Alert 路由准确率**:supervisor 路径与 expected agent sequence 的 prefix-match 率
- **Runbook 检索 Recall@5 / MRR**:复用现有 RAG 评测,换数据集为 SRE runbook
- **Per-agent latency / token**:supervisor 1 次 + specialists N 次 breakdown
- **报告 keyword pass**:报告里是否包含期望根因关键词 + runbook 步骤编号

---

## 2. 架构总览

### 2.1 关系图(文字版)

```
v2 链路(保留,fallback):
  User → ChatController → AgentOrchestrator.decide()
                              ↓
            [retrieval | tool | direct] → ChatService → AgentLoop.run() → answer

v3 链路(新增,zhitu.app.multi-agent-enabled=true 才走):
  User/Webhook → ChatController/AlertController → MultiAgentOrchestrator.run()
                                                       ↓
                                                   Supervisor(LLM) ──────────┐
                                                       ↓ next=AlertTriage     │
                                                   AlertTriageAgent           │
                                                   (RAG 找 runbook)           │
                                                       ↓ result               │ loop until
                                                   Supervisor ←───────────────┘ FINISH 或 maxRounds
                                                       ↓ next=LogQuery
                                                   LogQueryAgent
                                                   (mock query_logs)
                                                       ↓ result
                                                   Supervisor
                                                       ↓ next=Report
                                                   ReportAgent
                                                   (Markdown 报告,无工具)
                                                       ↓ final
                                                   User/前端
```

**关键决策**:v3 与 v2 共存,通过 `zhitu.app.multi-agent-enabled` flag 切换;**默认 false**,避免破坏 v2 baseline。这点跟 `react-enabled` / `self-rag-enabled` 一致(渐进开关 + v1↔v2 对比叙事)。

### 2.2 与现有抽象的关系

| 已有 | v3 处理 |
|---|---|
| `AgentOrchestrator.decide()` | **不动**,作为 v2 fallback。v3 走 `MultiAgentOrchestrator` 完全独立 |
| `AgentLoop.run()` | **不动**,Specialist 内部直接复用(specialist = AgentLoop + 工具子集) |
| `ToolRegistry` | **不动**,新增 `specifications(Set<String> allowedNames)` 重载 |
| `SpanCollector` | **不动**,直接复用嵌套 span;`agent.supervisor` / `agent.specialist.<name>` 作为新 span name |
| `SseEventType.STAGE` | **不动**,phase 字符串扩 `supervisor-routing` / `agent:<name>` / `final-writing` |
| `BaselineEvalRunner` | 扩展 `case.expectedAgentSequence`(可选),不破坏现有 case |
| `RagRetriever` | **不动**,AlertTriageAgent 内部直接调用 |
| `PendingToolCallStore` | **不动**,specialist 内部 AgentLoop 自动复用 HITL 流程(query_logs 不需要 HITL,纯只读) |

**没有继承、没有 abstract class**:`Agent` 是 record + 显式数据,Supervisor/Specialist 通过组合 `LlmRuntime + ToolRegistry + AgentLoop` 实现。符合 AGENTS.md「不提前抽象,先有具体类」原则。

---

## 3. 核心抽象设计

### 3.1 `Agent` record

```java
package com.zhituagent.agent;  // 新建包

public record Agent(
    String name,                  // "AlertTriageAgent" 等,supervisor 路由 + span 命名用
    String description,           // 一句话能力,supervisor 看这个决定路由
    String systemPrompt,          // 该 agent 自己的 system prompt(覆盖全局)
    Set<String> allowedToolNames, // 工具子集;空 set = 不允许工具
    String modelHint              // Phase 2 用;Phase 1 全用 zhitu.llm.chat.* 默认
) {}
```

### 3.2 3 个 SRE specialist(系统启动时 hardcode 注册)

| name | description | allowedTools | systemPrompt 要点 |
|---|---|---|---|
| `AlertTriageAgent` | "Reads the alert payload, retrieves the most relevant runbook from the knowledge base, decides whether log inspection is needed before reporting." | (无显式工具,内部走 `RagRetriever` 检索 runbook) | 「你是 SRE oncall 助手。给定告警 JSON,先用知识库检索匹配的 runbook,再判断是否需要查日志(取决于告警类型 + runbook 步骤)。」 |
| `LogQueryAgent` | "Fetches recent logs and metrics for the affected service to surface root-cause evidence." | `query_logs`, `query_metrics`, `time` | 「调用 query_logs 拿日志,调用 query_metrics 拿监控数据。简洁返回 3-5 条最相关的证据,不要全部 dump。」 |
| `ReportAgent` | "Composes the final SRE alert analysis report in Markdown using prior agents' findings. Does not call tools." | `Set.of()` | 「按 Markdown 输出:## 根因 / ## 影响范围 / ## 处置建议(引用 runbook 步骤编号)/ ## 监控数据。只用前序 agent 输出的事实,不要编造。」 |

**为什么 3 个不是 4 个**(没拆 `RunbookAgent` 出来):AlertTriage 和 Runbook 检索紧密耦合(都基于告警内容找方案),硬拆 supervisor 路由开销翻倍且没新信息量。3 specialist 是 LangGraph 教程里也常见的最小 multi-agent。

**为什么 LogQueryAgent 持有 `time` 工具**:报告里要时间戳(检测时间 vs 当前时间),让 LogQueryAgent 顺手取了带回。ReportAgent 不直接调工具,纯组合答案。

### 3.3 Supervisor 的 LLM 契约

Supervisor 是一次 `LlmRuntime.generate` 调用(**不带工具**,纯文本生成 → JSON parse),system prompt:

```
You are the routing supervisor for an SRE alert-analysis multi-agent system.
Available specialists:
- AlertTriageAgent: <description>
- LogQueryAgent: <description>
- ReportAgent: <description>

Respond with strict JSON: {"next": "<agent_name>" | "FINISH", "reason": "<brief>"}
Rules:
1. For a fresh alert, ALWAYS start with AlertTriageAgent.
2. If AlertTriageAgent says logs are needed, route to LogQueryAgent.
3. After enough evidence is gathered, route to ReportAgent for the final report.
4. Choose FINISH only after ReportAgent has produced the Markdown report.
5. Maximum 5 routing turns total — be decisive.
```

**JSON 解析失败兜底**:直接路由到 `ReportAgent` + log warn(让它至少能基于 supervisor 已有上下文出一个降级版报告)。不重试,不 raise — multi-agent 场景下 fallback 比 retry 更稳。

### 3.4 SRE 工具设计(2 个新工具)

#### `query_logs`(mock,Phase 1)

```java
package com.zhituagent.tool.sre;

@Component
public class QueryLogsTool implements Tool {
    public String name() { return "query_logs"; }
    public ToolSpecification spec() { /* service: string, time_range: string */ }
    public ToolResult execute(JsonNode args) {
        // Phase 1: 读 src/main/resources/sre-fixtures/logs/{service}.json
        //   返回预设日志片段(3-5 条),按 time_range 过滤
        // Phase 2 候选:接 Loki HTTP API 或 MCP CLS server
    }
}
```

#### `query_metrics`(mock,Phase 1)

```java
package com.zhituagent.tool.sre;

@Component
public class QueryMetricsTool implements Tool {
    public String name() { return "query_metrics"; }
    public ToolSpecification spec() { /* service: string, metric: string */ }
    public ToolResult execute(JsonNode args) {
        // Phase 1: 读 src/main/resources/sre-fixtures/metrics/{service}.json
        //   返回 {metric, value, threshold, status} 结构
        // Phase 2 候选:接 Prometheus HTTP API
    }
}
```

**为什么 mock 不接真**:Phase 1 demo 级,真接 Prometheus / Loki 需要外部基础设施且评测不可重现。Mock 的好处是 fixture 写死、跑 baseline 完全确定性。Phase 2 想升级再切真。

### 3.5 `MultiAgentOrchestrator`

```java
package com.zhituagent.agent;

@Component
public class MultiAgentOrchestrator {
    private final AgentRegistry agentRegistry;
    private final LlmRuntime llmRuntime;
    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final SpanCollector spanCollector;
    private final ObjectMapper objectMapper;
    private final String supervisorSystemPrompt;

    public MultiAgentResult run(String userMessage,
                                ContextBundle baseContext,
                                Map<String,Object> metadata,
                                int maxRounds /* 默认 5 */) {
        // 1. supervisor span
        // 2. while round < maxRounds:
        //      supervisorTurn = supervisor.decide(conversation)  // JSON parse
        //      if next == "FINISH": break
        //      specialist = agentRegistry.get(supervisorTurn.next)
        //      specialistResult = runSpecialist(specialist, conversation, metadata)
        //      append to conversation
        //      round++
        // 3. 返回 MultiAgentResult { finalAnswer, agentTrail, supervisorDecisions, executions }
    }

    private SpecialistResult runSpecialist(Agent agent, ...) {
        // span: agent.specialist.<name>
        // agentLoop.run(agent.systemPrompt, ..., toolRegistry.specifications(agent.allowedToolNames))
    }
}

public record MultiAgentResult(
    String finalAnswer,
    List<String> agentTrail,                       // ["AlertTriageAgent", "LogQueryAgent", "ReportAgent"]
    List<SupervisorDecision> supervisorDecisions,  // 每次路由决策(trace + 评测用)
    List<ToolCallExecutor.ToolExecution> executions
) {}

public record SupervisorDecision(int round, String next, String reason, long latencyMs) {}
```

**Specialist 复用 AgentLoop 的理由**:Specialist 在自己工具子集内可能仍然要多轮(LogQueryAgent 调 query_logs → 看结果不够 → 再调 query_metrics),AgentLoop 把这套「plan-tool-observe」做对了,无理由重写。

**改 AgentLoop 的小手术**:
```java
// 当前签名
public LoopResult run(String systemPrompt, String userMessage, ContextBundle ..., int maxIters)
// 末尾加重载,可选 tool subset
public LoopResult run(..., int maxIters, Set<String> allowedToolNames)
```
内部 `toolRegistry.specifications()` 改成 `toolRegistry.specifications(allowedToolNames)`。**老签名保留**,新参数可选。

### 3.6 `AgentRegistry`

```java
@Component
public class AgentRegistry {
    private final Map<String, Agent> byName;
    public AgentRegistry(List<Agent> agents) { ... }   // Spring 注入,@Bean 一次性建好 3 个
    public Agent get(String name) { ... }
    public List<Agent> all() { ... }
    public String descriptionsForSupervisor() { ... }  // 拼 supervisor system prompt
}
```

---

## 4. SRE 数据源准备(新章节,关键)

### 4.1 alert fixture(JSON,4 条)

新建目录 `src/main/resources/sre-fixtures/alerts/`,4 条 case:

```jsonl
// alert-001.json — HighCPUUsage
{
  "alertId": "alert-001",
  "severity": "high",
  "alertName": "HighCPUUsage",
  "service": "order-service",
  "instance": "order-service-prod-3",
  "firedAt": "2026-05-01T14:32:00+08:00",
  "summary": "CPU usage > 90% for 10 minutes",
  "labels": {"env": "prod", "team": "trade"},
  "annotations": {"description": "Order service CPU sustained at 92-95%"}
}
// alert-002.json — HighMemoryUsage
// alert-003.json — DBConnectionPoolExhausted
// alert-004.json — SlowResponse
```

每条 alert 对应 ~3-5 条预设日志(`src/main/resources/sre-fixtures/logs/{service}.json`)+ 1-2 条 metric snapshot(`src/main/resources/sre-fixtures/metrics/{service}.json`)。

### 4.2 runbook docs(用 RAG 索引)

新建 `src/main/resources/sre-fixtures/runbooks/`,4 份 Markdown(对应 4 类告警),每份结构:

```markdown
# HighCPUUsage Runbook

## 适用告警
- alertName: HighCPUUsage
- severity: high

## 排查步骤
1. 查最近 30min CPU/memory metric 趋势,确认是否突发
2. 查最近 10min 慢日志,锁定耗时最高的请求
3. 查最近 30min 部署记录,排查是否新版本引入
4. 查上游流量是否突增(QPS / RPS)

## 处置建议
- 突发流量 → 触发 HPA 扩容
- 代码 hotspot → 回滚最近发布
- 死循环 / 内存泄漏 → 重启实例 + 抓 heap dump

## 责任人
- @oncall-trade
```

启动时把 runbook 灌进 KB(`KnowledgeIndexer` 把 markdown 切片入库),`AlertTriageAgent` 走现有 RAG retrieval 检索。**复用现有索引管线,零改造**。

### 4.3 mock log / metric 内容设计要点

- **必须包含期望根因关键词**:让 LogQueryAgent 拿到证据后,ReportAgent 能命中「内存泄漏」「数据库连接池满」这类报告 keyword
- **包含噪声**:不能全是相关日志,要混 1-2 条无关 INFO/WARN,否则 specialist 太轻松,体现不出 LLM 推理价值
- **时间戳与 alert.firedAt 对齐**:保证「最近 10min」过滤逻辑能跑通

---

## 5. 落地点清单

### 5.1 新增文件

| 文件 | 内容 | 行数估算 |
|---|---|---|
| `src/main/java/com/zhituagent/agent/Agent.java` | record | ~30 |
| `src/main/java/com/zhituagent/agent/AgentRegistry.java` | Spring component | ~50 |
| `src/main/java/com/zhituagent/agent/AgentDefaults.java` | `@Configuration` 注册 3 个 SRE specialist | ~100 |
| `src/main/java/com/zhituagent/agent/MultiAgentOrchestrator.java` | 核心编排 | ~250 |
| `src/main/java/com/zhituagent/agent/MultiAgentResult.java` | record | ~25 |
| `src/main/java/com/zhituagent/agent/SupervisorDecision.java` | record | ~10 |
| `src/main/java/com/zhituagent/agent/SupervisorTurnResult.java` | record + JSON parse helper | ~50 |
| `src/main/java/com/zhituagent/tool/sre/QueryLogsTool.java` | mock log 查询 | ~80 |
| `src/main/java/com/zhituagent/tool/sre/QueryMetricsTool.java` | mock metric 查询 | ~60 |
| `src/main/java/com/zhituagent/tool/sre/SreFixtureLoader.java` | 加载 fixtures/ 下 JSON 文件 | ~60 |
| `src/main/resources/system-prompt/supervisor-sre.txt` | supervisor 提示词 | - |
| `src/main/resources/sre-fixtures/alerts/alert-{001..004}.json` | 4 条 alert | - |
| `src/main/resources/sre-fixtures/logs/{order,payment,user}-service.json` | mock log | - |
| `src/main/resources/sre-fixtures/metrics/{order,payment,user}-service.json` | mock metric | - |
| `src/main/resources/sre-fixtures/runbooks/{HighCPU,HighMemory,DBPool,SlowResponse}.md` | 4 份 runbook | - |
| `src/test/java/com/zhituagent/agent/MultiAgentOrchestratorTest.java` | mock LLM 单测,5+ case | ~250 |
| `src/test/java/com/zhituagent/agent/AgentRegistryTest.java` | 注入 / description 拼装 | ~50 |
| `src/test/java/com/zhituagent/tool/sre/QueryLogsToolTest.java` | fixture load + filter | ~60 |
| `src/test/java/com/zhituagent/tool/sre/QueryMetricsToolTest.java` | fixture load | ~40 |

### 5.2 修改文件

| 文件 | 改动 |
|---|---|
| `src/main/java/com/zhituagent/config/AppProperties.java:9` | 加 `multiAgentEnabled = false` + `multiAgentMaxRounds = 5` |
| `src/main/java/com/zhituagent/tool/ToolRegistry.java:31` | 加 `specifications(Set<String> allowedNames)` 重载 |
| `src/main/java/com/zhituagent/orchestrator/AgentLoop.java:71` | 加 `run(..., Set<String> allowedToolNames)` 重载;原签名委托新签名传 `null` |
| `src/main/java/com/zhituagent/chat/ChatService.java:152` | `if (multiAgentEnabled)` 分支调 `multiAgentOrchestrator.run` |
| `src/main/java/com/zhituagent/api/ChatController.java:107` | SSE stage phase 扩 `supervisor-routing` / `agent:<name>` / `final-writing` |
| `src/main/java/com/zhituagent/api/ChatTraceFactory.java` | 加可选字段 `agentTrail: List<String>` |
| `src/main/java/com/zhituagent/api/AlertController.java`(**新文件**) | `POST /api/alert` 接 alert JSON,触发 multi-agent 流程,返回 Markdown 报告 |
| `frontend/src/types/events.ts` | `StreamingPhase` union 加新 phase;`detail` 加可选 `agentName` |
| `frontend/src/components/chat/ChatMessage.tsx:9-13` | `PHASE_LABEL` 加新文案 |
| `frontend/src/components/AlertDemo.tsx`(**新文件**,可选) | 简单 alert 输入框 + 选 4 条 fixture,展示报告 |
| `src/main/resources/eval/baseline-chat-cases.jsonl` | 末尾追加 4 条 SRE alert case |
| `src/main/java/com/zhituagent/eval/BaselineEvalCase.java` | 加可选 `expectedAgentSequence: List<String>` |
| `src/main/java/com/zhituagent/rag/KnowledgeIndexer.java` 或启动时 hook | 启动时把 `sre-fixtures/runbooks/*.md` 灌进 KB |

### 5.3 不动的文件(明确边界)

- `AgentOrchestrator.java` — v2 路径完全保留
- `RagRetriever.java` / `SelfRagOrchestrator.java` — AlertTriageAgent 内部用
- `MemoryService.java` / `ContextManager.java` — 上下文构建沿用
- `PendingToolCallStore.java` / `HitlController.java` — query_logs 是只读不需要 HITL,但机制保留可用
- `BaselineEvalRunner.java` 主流程 — 只加可选 assert,不改 v1↔v2 对比口径

---

## 6. SSE 协议扩展

### 6.1 STAGE phase 字符串(向前兼容)

| phase 字符串 | detail 字段 | 触发 |
|---|---|---|
| `retrieving`(已有) | `{}` | v2 检索 |
| `calling-tool`(已有) | `{toolName}` | v2 工具 |
| `generating`(已有) | `{}` | v2 出 token |
| `supervisor-routing`(**新**) | `{round}` | v3 supervisor 决策 |
| `agent`(**新**) | `{agentName, round}` | v3 specialist 执行 |
| `final-writing`(**新**) | `{}` | v3 ReportAgent 出 token |

**前端文案**(`ChatMessage.tsx`):

```
supervisor-routing      → "调度中..."
agent + AlertTriageAgent → "告警分析中..."
agent + LogQueryAgent   → "日志查询中..."
agent + ReportAgent     → "报告生成中..."
final-writing           → "正在生成最终报告..."
```

### 6.2 Span 结构

```
chat.turn (root, kind=root)
├── multi-agent.run (kind=agent)
│   ├── agent.supervisor (kind=llm)              [round=1]
│   │   └── attrs: {next, reason, latencyMs, tokensIn, tokensOut}
│   ├── agent.specialist.AlertTriageAgent (kind=agent)
│   │   ├── agent.iter (kind=agent, iteration=1)
│   │   │   ├── agent.llm_call (kind=llm)
│   │   │   └── rag.retrieve (kind=tool)
│   │   └── attrs: {agentName, finalLength}
│   ├── agent.supervisor (kind=llm)              [round=2]
│   ├── agent.specialist.LogQueryAgent
│   │   └── agent.iter
│   │       ├── agent.tool_calls (kind=tool, name=query_logs)
│   │       └── agent.tool_calls (kind=tool, name=query_metrics)
│   ├── agent.supervisor (kind=llm)              [round=3]
│   ├── agent.specialist.ReportAgent
│   │   └── agent.iter
│   └── agent.supervisor (kind=llm)              [round=4, next=FINISH]
└── attrs: {agentTrail, supervisorRounds}
```

完全沿用 `SpanCollector.startSpan(name, kind, attrs)`。

---

## 7. Fixture 扩充(SRE 主题)

### 7.1 新增 case(JSONL,4 条)

追加到 `src/main/resources/eval/baseline-chat-cases.jsonl`:

```jsonl
{"caseId":"sre-001","type":"sre-alert","message":"分析这个告警: alert-001","alertFixture":"alert-001","expectedPath":"multi-agent","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["CPU","HPA","扩容","回滚"],"splitMode":"train","notes":"HighCPUUsage 经典 case,完整三阶段路径。"}
{"caseId":"sre-002","type":"sre-alert","message":"分析这个告警: alert-002","alertFixture":"alert-002","expectedPath":"multi-agent","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["内存","heap","泄漏","重启"],"splitMode":"train","notes":"HighMemoryUsage,期望提到 heap dump。"}
{"caseId":"sre-003","type":"sre-alert","message":"分析这个告警: alert-003","alertFixture":"alert-003","expectedPath":"multi-agent","expectedAgentSequence":["AlertTriageAgent","LogQueryAgent","ReportAgent"],"expectedAnswerKeywords":["数据库","连接池","HikariCP","上游"],"splitMode":"eval","notes":"DBConnectionPoolExhausted,跨域证据(慢 SQL + metric)。"}
{"caseId":"sre-004","type":"sre-alert","message":"分析这个告警: alert-004","alertFixture":"alert-004","expectedPath":"multi-agent","expectedAgentSequence":["AlertTriageAgent","ReportAgent"],"expectedAnswerKeywords":["响应时间","p99","降级"],"splitMode":"eval","notes":"SlowResponse 但 runbook 已经说清楚不必查日志(测 supervisor 的早 FINISH 能力)— 故意设计成 2 步路径。"}
```

**关键设计**:`sre-004` 是 2 步路径(跳过 LogQueryAgent),用来测**supervisor 是否会盲目走全流程**。如果 supervisor 总是走 3 步,会被这条 case 扣分,推动 supervisor prompt 调优。

### 7.2 评测维度(新)

`BaselineEvalCase` 加可选 `expectedAgentSequence: List<String>`,`BaselineEvalRunner` 在 v3 模式:

- **agent-routing-accuracy**:`actualAgentTrail` 与 `expectedAgentSequence` 的 prefix-match 率
- **per-agent latency**:从 trace 拆每个 specialist span
- **supervisor overhead**:`agent.supervisor` span 总时长 / 总 latency 比例
- **报告 keyword pass**:沿用现有 `expectedAnswerKeywords`

### 7.3 v2 vs v3 对比报告

复用 `--zhitu.eval.compare-labels=v2,v3`。报告自动出 split 命中率 + p50/p90/p99 latency。

**注意**:v2 跑 SRE case 时会走老路径(单 ReAct),由于没有 alert JSON 解析能力,大概率分数低于 v3 — 这正是简历叙事要的对比数字。

---

## 8. 配置开关

`AppProperties.java`:

```java
private boolean multiAgentEnabled = false;
private int multiAgentMaxRounds = 5;
private String multiAgentSupervisorPromptLocation = "classpath:system-prompt/supervisor-sre.txt";
```

`application.yml`:

```yaml
zhitu:
  app:
    multi-agent-enabled: false
    multi-agent-max-rounds: 5
```

启动命令(v3 baseline):

```bash
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval ZHITU_LLM_MOCK_MODE=false \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="\
    --zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true \
    --zhitu.eval.label=v3 --zhitu.eval.modes=hybrid-rerank \
    --zhitu.llm.rate-limit.enabled=true \
    --zhitu.app.multi-agent-enabled=true \
    --zhitu.app.react-enabled=true \
    --zhitu.rag.contextual-enabled=true \
    --zhitu.rag.fusion-strategy=rrf \
    --zhitu.rag.self-rag-enabled=true"
```

---

## 9. 测试策略

### 9.1 单元测试(必须)

| 测试类 | 用例 |
|---|---|
| `MultiAgentOrchestratorTest` | (a) supervisor 直接 FINISH;(b) AlertTriage → Report(2 步早停);(c) AlertTriage → LogQuery → Report(3 步完整);(d) supervisor JSON 解析失败 → fallback ReportAgent;(e) 超过 maxRounds → 强制 ReportAgent 收尾 |
| `AgentRegistryTest` | 注入 3 个 specialist + description 拼装 |
| `AgentLoopTest`(扩) | `run(..., allowedToolNames)` 重载:工具子集生效 / 空 set 时无工具 |
| `ToolRegistryTest`(扩) | `specifications(Set<String>)` 过滤行为 |
| `QueryLogsToolTest` | fixture load + time_range 过滤 + 服务名匹配 + fixture 缺失返回空 |
| `QueryMetricsToolTest` | fixture load + 字段完整性 |

### 9.2 真 LLM baseline 验证

跑两次:`label=v2`(关 multi-agent)和 `label=v3`(开),生成 `baseline-compare-v2,v3-*.md`。

**预期**:
- SRE case(sre-001..004)v3 准确率 > v2(v2 单 ReAct 不知道怎么解 alert JSON)
- 原 16 case v3 latency 略高(supervisor 多 1 轮),准确率不应下降
- 总 token v3 ≈ v2 × 1.5(supervisor + report 额外开销)

**如果数字翻车**(v3 没赢甚至输了):看 trace 找根因,通常是 supervisor prompt 不准,调它就行。**这是关键里程碑,跑出来停下让用户 review 数字再继续。**

---

## 10. 实施顺序(自下而上,~2 工作日)

| 步骤 | 工时 | 关键交付 | 验收 |
|---|---|---|---|
| 1. SRE fixture 准备(4 alert + 4 runbook + mock log/metric)| 2h | 文件齐全 + 启动时 runbook 灌库成功 | 启动日志看到 4 个 runbook 入索引 |
| 2. `QueryLogsTool` + `QueryMetricsTool` + 单测 | 1.5h | 工具能跑 + 单测绿 | `mvn -o test -Dtest='Query*ToolTest'` |
| 3. `Agent` record + `AgentRegistry` + 3 个 SRE specialist `AgentDefaults` | 1.5h | 应用启动注入 3 agent | `AgentRegistryTest` |
| 4. `ToolRegistry.specifications(Set<String>)` + `AgentLoop.run` 重载 + 单测 | 1h | 老测全绿 + subset 测试绿 | `mvn -o test` |
| 5. `supervisor-sre.txt` 提示词 + `SupervisorTurnResult` JSON parse | 1h | parse helper 6 case 单测 | 新单测 |
| 6. `MultiAgentOrchestrator` 主循环 + span 埋点 | 3h | 5+ 核心 case 单测全绿 | `MultiAgentOrchestratorTest` |
| 7. `AppProperties` flag + `ChatService` / `ChatController` 接入(默认 off)+ `AlertController` | 2h | flag=false 行为 = v2 | `mvn -o test` 全绿 |
| **A**. **里程碑 1**:`mvn -o test` 全绿;启动应用 flag=false 默认行为不变 | | | **停下让用户确认** |
| 8. SSE phase 扩展 + 前端 `ChatMessage` 文案 + 可选 `AlertDemo.tsx` | 1.5h | tsc 干净 + 浏览器看到 phase 切换 | `npm run build` |
| **B**. **里程碑 2**:flag=true 跑 alert-001,前端看到 supervisor → AlertTriage → LogQuery → Report 切换 | | | **停下让用户确认** |
| 9. fixture 加 4 条 SRE case + `BaselineEvalCase.expectedAgentSequence` | 0.5h | JSONL OK,老 case 不破 | `mvn -o test` |
| 10. v2 / v3 真 LLM baseline 各跑一次 + compare 报告 | 1h(机器跑)+ 0.5h 看数字 | `baseline-compare-v2,v3-*.md` 生成 | **停下让用户 review 数字** |
| 11. commit + 更新 `optimize-progress.md` v3 段 + 更新 user memory roadmap | 0.5h | 单 commit `feat(agent): v3 SRE multi-agent + Phase 1 + fixture` | git log + memory |

**总计**:~16h(约 2 工作日,留 3-4h 出意外)。

### 10.1 里程碑

- **里程碑 1**(步骤 7 完):flag=false 行为完全 = v2 — 不破坏现网的硬要求
- **里程碑 2**(步骤 8 完):前端能看到 v3 阶段切换 — 演示价值的硬要求
- **里程碑 3**(步骤 10 完):v3 数字到底比 v2 强还是弱 — 简历叙事的硬要求

每个里程碑用 `AskUserQuestion` 显式停下。

---

## 11. 风险与回滚

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Supervisor JSON 解析失败率高 | 中 | 高 | (a) GLM-5.1 JSON mode;(b) prompt 里 strict JSON 示例;(c) fallback 到 ReportAgent |
| GLM-5.1 限速 48/min 不够 | 中 | 中 | `--zhitu.llm.rate-limit.enabled=true`;v3 baseline 跑一次 ~20 case × 4 LLM 调用 = 80 calls,~2min |
| SRE fixture 不够真实 | 中 | 中 | 参考 oncall-agent 的 `aiops-docs/` 题材 + Robusta playbook 公开样本设计 |
| `sre-004` 故意 2 步设计反而拖低 v3 数字 | 低 | 低 | 这条单独 split=eval,只看不参与训练观察 |
| 多 agent 数字反而 v3 输 | 低 | 高(简历崩) | trace 找根因;最差只发 Phase 1 不出"v3 比 v2 好"叙事,改写成"工程上对标 LangGraph"叙事 |
| AgentLoop 加 allowedTools 重载破坏现有 ReAct 测试 | 低 | 低 | 老签名委托新签名传 `null`,一行兼容 |

### 11.1 回滚

- **代码层**:flag=false,无 schema/DB 改动
- **fixture 层**:新增 4 条 case 留着也不破老 v1/v2 跑(case 是 sre-alert 类型,旧 runner 当普通 case 跑无害)
- **commit 层**:单 feat commit,实在崩 `git revert`

---

## 12. 简历叙事(v3 升级,SRE 场景版)

### 12.1 三层故事(扩展三幕剧)

**v1**:关键词 if-else 路由 → 工具调用扁平
**v2**:单 ReAct AgentLoop 4 轮 + 嵌套 span trace + Self-RAG 早停
**v3**:**SRE 智能告警分析 multi-agent** — Supervisor + 3 Specialist + 角色专精 + JSON routing + per-agent trace

### 12.2 一句话 + 数字(简历版)

> 「实现 SRE oncall 告警智能分析系统:输入告警 JSON,multi-agent 自动检索 runbook + 查日志 + 生成 Markdown 分析报告。架构上引入 Supervisor + 3 Specialist 多智能体编排(对标 LangGraph create_supervisor / oncall-agent),supervisor 输出严格 JSON 路由,每个 specialist 拿独立 system prompt + 工具子集。新增 4 条 SRE alert fixture,v3 在多步推理 fixture 上 agent-routing-accuracy 达 X%,报告 keyword pass rate Y%,trade-off:token +50%、p90 latency +Z ms。配套 142 单测 + nested span trace + v2/v3 baseline-compare 报告。」

(X / Y / Z 实际跑出来填)

### 12.3 简历叙事框架表新增一行

在 `optimize-progress.md` 现有表后追加:

| 层 | v2 现状 | v3 改造 | 业界对标 | 对应 commit |
|---|---|---|---|---|
| **Agent + 业务场景** | 单 ReAct AgentLoop / 通用对话 | **SRE 智能告警分析** + Supervisor + 3 Specialist multi-agent + JSON routing + agent-level trace + alert fixture | LangGraph create_supervisor / oncall-agent / Robusta / PagerDuty AIOps | MA-1(本计划)|

---

## 13. 验收标准

### 13.1 必达

- [ ] `mvn -o test` 全绿(原 122 + 新增 ~20)
- [ ] `cd frontend && npm run build` tsc 干净
- [ ] flag 默认 false,默认行为完全 = v2
- [ ] flag=true,POST `/api/alert` 传 alert-001,前端看到 SSE phase 切换 `supervisor-routing → agent (AlertTriageAgent) → agent (LogQueryAgent) → agent (ReportAgent) → final-writing`
- [ ] 输出 Markdown 报告包含 ## 根因 / ## 处置建议 / ## 监控数据 段落
- [ ] Supervisor JSON 解析失败有兜底(给 alert-001 喂个故意坏 LLM 也不 500)
- [ ] v2/v3 真 LLM baseline 各跑一次,生成 compare 报告

### 13.2 期望(简历加分)

- [ ] v3 SRE case agent-routing-accuracy ≥ 75%
- [ ] v3 报告 keyword pass rate ≥ 80%
- [ ] v3 单步原 case(非 SRE)准确率不低于 v2
- [ ] Trace 面板看到 supervisor span + 3 个 specialist 嵌套树
- [ ] 单 commit 干净(`feat(agent): v3 SRE multi-agent + Phase 1 specialists + fixture`),body 解释三幕剧 + oncall-agent 差异化

### 13.3 可选(Phase 2,不在本计划工时内)

- [ ] 接真 Prometheus / Loki(替换 mock 工具)
- [ ] HITL:报告生成后让用户确认是否触发 runbook 修复动作(`approval token`)
- [ ] Specialist 之间 handoff(specialist 主动指定下一个 agent)
- [ ] 混搭模型(supervisor=Opus / worker=Haiku,跑成本对比)
- [ ] Webhook 接 Alertmanager 真告警

---

## 14. 实施前需要用户确认的开放问题

新会话开局先用 `AskUserQuestion` 确认这 3 个:

1. **Specialist 是否就 3 个(AlertTriage / LogQuery / Report)?**
   - A. 按 §3.2 现状:3 个
   - B. 拆成 4 个:AlertTriage(分类)/ Runbook(检索)/ LogQuery / Report

2. **Alert 入口是 chat 接口复用还是新 `/api/alert`?**
   - A. 新建 `AlertController`,`POST /api/alert` 专用入口(推荐,语义清晰)
   - B. 复用 `/api/chat`,把 alert JSON 当 user message 字符串塞进去

3. **Runbook 灌库时机是启动一次性还是手动 endpoint?**
   - A. 启动时自动灌(推荐,baseline 跑前必有)
   - B. 提供 `POST /api/admin/load-runbooks` 手动触发

---

## 附录 A:关键文件 quick-link

- `src/main/java/com/zhituagent/orchestrator/AgentLoop.java:64-144` — Specialist 复用的循环
- `src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java:90-123` — v2 路由
- `src/main/java/com/zhituagent/api/ChatController.java:107-232` — SSE 主流程
- `src/main/java/com/zhituagent/chat/ChatService.java:152-180` — 同步路径
- `src/main/java/com/zhituagent/tool/ToolRegistry.java:31` — 加 subset 重载
- `src/main/java/com/zhituagent/trace/SpanCollector.java:45-66` — 复用 startSpan/endSpan
- `src/main/resources/eval/baseline-chat-cases.jsonl` — 末尾追加 4 条
- `src/main/java/com/zhituagent/eval/BaselineEvalCase.java:9-26` — 加 `expectedAgentSequence`
- `src/main/java/com/zhituagent/api/sse/SseEventType.java` — phase 字符串扩
- `frontend/src/components/chat/ChatMessage.tsx:9-19` — `PHASE_LABEL` 加新文案

## 附录 B:命令速查

```bash
# 单测
mvn -o test

# 前端
cd frontend && npm run build

# v3 baseline(参考 §8)
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval ZHITU_LLM_MOCK_MODE=false \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true --zhitu.eval.label=v3 --zhitu.eval.modes=hybrid-rerank --zhitu.llm.rate-limit.enabled=true --zhitu.app.multi-agent-enabled=true --zhitu.app.react-enabled=true --zhitu.rag.contextual-enabled=true --zhitu.rag.fusion-strategy=rrf --zhitu.rag.self-rag-enabled=true"

# v2 / v3 对比
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true --zhitu.eval.compare-labels=v2,v3"

# 手测 alert(flag 打开后)
curl -X POST http://localhost:8080/api/alert -H "Content-Type: application/json" -d @src/main/resources/sre-fixtures/alerts/alert-001.json
```
