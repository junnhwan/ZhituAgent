# ZhituAgent Java MVP Planning

> **定位**：面向简历与实习投递的 Java 版 Agent 项目规划。目标不是全量复刻 Go 版 `ZhituAgent`，而是围绕“上下文压缩、记忆、RAG、Agent 编排、评估与可观测性”做一个边界清晰、能真实落地、面试时讲得稳的 Java Lite 版本。

**Goal:** 构建一个基于 Java 的对话式 Agent 服务，支持多轮记忆、RAG 检索增强、工具调用、上下文压缩、基础 Agent 编排，以及可观测和可评估的工程闭环。

**Architecture:** 采用单体服务 + 单主链路设计。请求经 API 进入后，统一经过 Session Memory、Context Builder、Orchestrator、RAG/Tool、Answer Generator，再回写记忆和指标。第一版不保留双链路，不引入“大而全”的多 Agent swarm，而是用一个协调器串起少量职责明确的子 Agent。

**Tech Stack:** Java 21, Spring Boot 3.5+, Spring WebFlux, Redis Stack, OpenAI-compatible LLM API, Jackson, Micrometer + Prometheus, JUnit 5, Testcontainers

---

## 1. 项目边界

### 1.1 为什么做 Java 版

Go 版 `ZhituAgent` 已经覆盖了对话、RAG、记忆、MCP、评测等较宽的能力面，但对于当前目标来说，直接 1:1 复刻会让项目战线过长，影响完成度和可讲述性。Java 版应当强调：

- 你具备 Java 后端系统设计与实现能力
- 你理解 Agent 系统的主链路，而不是只会接模型接口
- 你能控制上下文、记忆、检索、调度、评测这些关键工程问题

### 1.2 第一版必须做到的能力

- 对话 API：普通对话 + SSE 流式输出
- Session Memory：Redis 持久化多轮消息
- Context Compression：消息超阈值后压缩旧历史与长工具结果
- RAG：知识入库、向量检索、检索结果注入对话
- Agent Orchestration：由协调器决定 direct answer / retrieve-then-answer / tool-then-answer
- Observability：结构化日志、Request ID、核心指标
- Eval：离线 query set 回放，至少支持 1 到 2 组 A/B 对比

### 1.3 第一版明确不做

- 不做 `legacy + graph` 双链路
- 不做复杂的多 Agent swarm
- 不做 MCP Server
- 不做多通道 hybrid retrieval
- 不做 3 套以上 memory 压缩策略
- 不做过重的 guardrail / intent tree / 多模型路由

第一版的原则是：**单主链路、单套真能力、每个模块都能解释为什么存在。**

## 2. 方案比较

### 方案 A：全量复刻 Go 版

特点：

- 双链路、双向 MCP、multi-agent、多策略 memory、多 pipeline RAG

优点：

- 功能最全

缺点：

- 周期长
- 维护复杂
- 很容易做成“每个功能都浅尝辄止”

结论：

- 不推荐作为当前阶段的 Java 版方案

### 方案 B：Java Lite 单主链路版本

特点：

- 只保留一条 Agent 主链
- 保留最能体现 Agent 工程能力的 5 个核心点

优点：

- 容易做完
- 简历表达清晰
- 面试时可追到实现细节

缺点：

- 功能广度不如 Go 版

结论：

- **推荐方案**

### 方案 C：框架重度托管版本

特点：

- 高度依赖 Spring AI / LangChain4j 的高层 Agent 编排

优点：

- 开发快

缺点：

- 工程辨识度低
- “Agent 开发能力”信号弱

结论：

- 不推荐作为简历主项目

## 3. 推荐架构

### 3.1 总体数据流

1. 客户端调用 `/api/chat` 或 `/api/stream-chat`
2. API 层生成 `requestId`，记录 session/user 基础信息
3. 读取 Redis 中的会话消息
4. `ContextManager` 判断是否需要压缩旧历史或工具输出
5. `AgentOrchestrator` 判断当前请求路径：
   - 直接回答
   - 先检索再回答
   - 先调用工具再回答
6. `RagService` 或 `ToolExecutor` 返回证据
7. `PromptBuilder` 组装最终上下文
8. `ChatRuntime` 发起模型调用并返回结果
9. 回写 assistant 消息到 memory
10. 上报 metrics / tracing logs / eval 样本

### 3.2 推荐模块划分

- `zhitu-agent-api`
  - HTTP 接口、SSE、异常处理、请求上下文
- `zhitu-agent-core`
  - Orchestrator、PromptBuilder、ContextManager、ToolExecutor、ChatRuntime
- `zhitu-agent-memory`
  - SessionMemoryStore、Compressor、MemoryPolicy
- `zhitu-agent-rag`
  - 文档切分、Embedding、Indexer、Retriever、RagService
- `zhitu-agent-observability`
  - Metrics、日志、RequestContext
- `zhitu-agent-eval`
  - QuerySet Loader、EvalRunner、ReportWriter

如果前期想降低复杂度，也可以先做单模块 Maven 项目，按 package 分层，等第一版稳定后再拆模块。

## 4. 五个核心能力的设计建议

### 4.1 上下文压缩

目标：

- 控制长对话和长工具结果带来的 token 膨胀

第一版建议：

- 对会话历史做“保留最近 N 轮 + 历史摘要”压缩
- 对工具结果做 micro-compact，只保留摘要和 preview
- 将压缩触发条件设计为：
  - 消息数超阈值
  - 估算 token 超阈值

不要夸大的点：

- 不写“智能上下文引擎”
- 不写“最优上下文检索”

更真实的实现目标是：

- **在多轮对话中控制上下文长度，并尽量保留高价值历史和工具证据**

### 4.2 记忆

目标：

- 支持多轮会话持续性，而不是每轮裸聊

第一版建议：

- Redis 持久化 session messages
- 单独抽出 `SessionMemoryService`
- 压缩失败时降级为“只保留最近几轮”
- 为并发写入保留简单锁或原子更新方案

第一版不需要：

- 长期人格记忆
- 跨会话知识画像
- Dream / reflection 这类更重的后处理

### 4.3 RAG

目标：

- 让 Agent 回答能基于知识，而不是纯模型幻觉

第一版建议：

- 支持文档加载、切分、embedding、入库、top-k 检索
- 检索结果携带来源信息
- 只保留一条稳定检索链：vector retrieve -> optional rerank -> inject context

第一版不要做：

- vector / BM25 / phrase 多通道融合
- 太复杂的 query rewrite pipeline

你真正需要证明的是：

- **会把检索结果作为可控证据注入 Agent 主链**

### 4.4 Agent 编排

目标：

- 体现这不是普通聊天接口，而是有决策逻辑的 Agent 服务

第一版建议：

- 只做一个 `AgentOrchestrator`
- 内部编排 3 个轻量职责单元：
  - `PlannerAgent`：判断是否需要检索或工具
  - `KnowledgeAgent`：执行 RAG 并整理证据
  - `ResponderAgent`：基于上下文生成最终回答

这里的“Agent 编排”不要求很多 agent 数量，而要求：

- 有明确角色分工
- 有清晰的输入输出边界
- 有失败降级路径

### 4.5 评估与可观测性

目标：

- 避免系统只停留在“感觉还行”

第一版建议：

- 结构化日志字段：
  - `requestId`
  - `sessionId`
  - `workflowPath`
  - `retrievalHitCount`
  - `toolCallCount`
  - `compressed`
  - `latencyMs`
- Prometheus 指标：
  - chat request count / latency
  - LLM token usage
  - retrieval latency / hit rate
  - memory compression trigger count
  - tool call success / failure
- Eval：
  - direct vs rag
  - compression on vs off
  - 不同 prompt template 的简单对比

## 5. 推荐的 MVP 目录结构

```text
zhitu-agent-java/
├── docs/
│   └── 2026-04-27-zhitu-agent-java-plan.md
├── pom.xml
├── src/main/java/com/zhituagent/
│   ├── api/
│   ├── chat/
│   ├── orchestrator/
│   ├── memory/
│   ├── rag/
│   ├── tool/
│   ├── llm/
│   ├── observability/
│   └── common/
└── src/test/java/com/zhituagent/
```

## 6. 分阶段路线

### Phase 0：项目骨架

- Spring Boot 启动
- 基础配置
- OpenAI-compatible LLM 客户端
- `/healthz`

验收：

- 本地可启动
- 基础单测通过

### Phase 1：对话主链

- `/api/chat`
- `/api/stream-chat`
- PromptBuilder
- 单轮 tool-loop

验收：

- 普通对话和流式输出可用

### Phase 2：记忆与上下文压缩

- Redis session memory
- 历史压缩策略
- 工具输出 micro-compact

验收：

- 多轮对话能续上上下文
- 超阈值时能稳定压缩

### Phase 3：RAG

- 文档切分、入库、检索
- 检索结果注入 prompt
- 知识写入接口

验收：

- 给定知识文档后，回答明显依赖检索内容

### Phase 4：Agent 编排

- PlannerAgent
- KnowledgeAgent
- ResponderAgent
- direct / retrieve / tool 三种路径

验收：

- 对不同类型请求能走不同路径

### Phase 5：可观测与评估

- Micrometer + Prometheus
- 结构化日志
- eval query set 与 report

验收：

- 至少能输出一份可对比的评测报告

## 7. 风险与取舍

### 7.1 最大风险

- 战线过宽，导致每个能力都只做半截

### 7.2 关键取舍

- 用单主链换可完成度
- 用轻量多角色编排换可讲述性
- 用一条稳定 RAG 换掉多通道复杂度

### 7.3 面试表达原则

这个项目最值得讲的，不是“功能特别多”，而是：

- 我理解 Agent 主链怎么组织
- 我知道上下文和记忆为什么会失控
- 我知道 RAG 和 Tool 不是简单拼进去，而要在 orchestrator 里被调度
- 我知道没有 eval 和 observability，Agent 项目就很难持续迭代

## 8. 最终建议

这版 Java 项目应当被定义为：

**一个以对话 Agent 为外壳、以内存、RAG、上下文治理和编排能力为核心的 Java 后端系统。**

它不追求“功能最多”，而追求：

- 每个模块都真实存在
- 每条链路都能跑通
- 每个设计都能回答“为什么这样做”

如果后续时间充足，再考虑把以下能力作为 Phase 2 增量：

- MCP Client
- 更强的 memory summary
- hybrid retrieval
- 更复杂的 multi-agent collaboration
