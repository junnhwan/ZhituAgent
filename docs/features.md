# 功能详解

## 核心功能

### 1. RAG 知识库

构建了完整的 **文档上传 → 解析切分 → 向量化入库 → 混合检索** 知识库链路：

- **文件解析**：MinIO 存储 → Apache Tika 解析 → HanLP 中文句边界处理
- **分片策略**：parent-child chunk，chunkId 基于 `sha256(source + content)` 生成
- **向量化**：支持 OpenAI-compatible embedding 模型
- **检索层**：Elasticsearch 8.10 + IK 中文分词 + dense_vector
  - KNN 语义召回 + BM25 关键词召回
  - 单次 ES 调用完成 native hybrid（KNN + match + rescore）
- **重排序**：支持 Reranker 重排（Qwen3-Reranker-8B）
  - C-MTEB T2Retrieval 采样集：nDCG@10 从 0.86 提升到 0.91（+5.6pp）

### 2. 会话记忆与上下文管理

基于 Redis 持久化会话消息，`ContextManager` 统一打包上下文：

- **上下文结构**：系统提示词 + 历史摘要 + 用户事实 + 最近消息 + RAG 证据 + 当前问题
- **摘要压缩**：`MessageSummaryCompressor` 对长历史做摘要折叠
- **事实抽取**：`FactExtractor` 通过规则抽取用户稳定事实
- **Token 预算**：
  - 默认 1024 tokens
  - 超预算时按优先级裁剪：旧消息 → 旧事实 → 摘要 → evidence
  - 极端情况标记 `-overflow`
- **性能**：50 轮对话 token 减少 75.8%，100 轮减少 86.4%

### 3. 工具治理与 MCP 扩展

统一 `ToolDefinition` 抽象：

- **工具定义**：name / description / JSON Schema 参数
- **并行执行**：`ToolCallExecutor` 支持多工具并行
- **安全保障**：
  - JSON Schema 参数校验
  - 未知工具兜底
  - 重复调用 loop 检测
- **HITL 审批**：副作用工具需人工批准
- **MCP 适配**：`McpToolAdapter` 封装外部 MCP 工具

### 4. Agent 多轮执行循环

基于 ReAct 思路的 `AgentLoop`：

- **循环逻辑**：规划 → 工具调用 → 观察回填 → 再规划
- **轮次限制**：默认最多 4 轮
- **环检测**：`LoopDetector` 检测重复调用
- **可观测性**：嵌套 span tree 记录决策过程

### 5. 告警分析 Agent 编排

SRE 告警分析流程：

- **入口**：`POST /api/alert` 或 `POST /api/alert/stream`
- **编排器**：`MultiAgentOrchestrator` + Supervisor 路由
- **Specialist**：
  - `AlertTriageAgent`：检索 runbook
  - `LogQueryAgent`：查询日志/指标
  - `ReportAgent`：生成 Markdown 报告
- **安全阀**：超时自动触发报告生成

### 6. 异步文件入库

Kafka KRaft 异步管线：

- **Producer**：`acks=all` + `idempotence=true` + `transactional.id`
- **Consumer**：at-least-once + DLT
- **幂等写入**：ES `_id=chunkId` 吸收重投递
- **状态流转**：QUEUED → PARSING → INDEXED

## 其他能力

### Self-RAG / Reflection

- `SelfRagOrchestrator`：检索质量判断、问题改写
- `ReflectionLoop`：回答自检

### 评测体系

- `BaselineEvalRunner`：基准测试
- `RankingMetrics`：Hit / Recall / MRR / nDCG
- C-MTEB fixture loader

### 可观测性

- Micrometer + Prometheus 指标
- 结构化日志
- TraceArchive（JSONL 归档）
- 嵌套 span tree
- SSE stage 事件

### 前端控制台

- React 19 + TypeScript
- SSE 流式输出
- Trace 折叠面板
- HITL 审批弹窗
- 知识库管理
- 文件上传
- SRE Demo 面板
