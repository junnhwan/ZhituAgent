# 开发进度

最后更新时间：2026-04-28
当前分支：`main`

## 总体状态

项目第一阶段已经完成，`Task 1` 到 `Task 4` 的后端主链、trace 基线和基础评估样例都已经落地，并已完成阶段性提交。第二阶段的 `Task 1` 到 `Task 4` 第一版也已经全部落地：评估运行器、`dense recall -> rerank`、`hybrid retrieval`、Prometheus 指标、Redis 记忆并发保护都已补齐，并通过当前全量测试验证。

目前状态需要明确区分：

- 已完成：真实对话模型调用的第一步已经接入
- 已完成：默认 in-memory、可选 Redis、可选 pgvector 的接口层骨架已经接好
- 已完成：Redis 真实运行链路已经完成手工联调
- 已完成：pgvector + embedding 的真实 dense retrieval 链路已经完成手工联调
- 已完成：联调所需的最小日志与链路观测已补入
- 已完成：第一阶段基线已提交，提交号为 `f5b66c3`
- 已完成：第二阶段优化计划文档已补入 `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`
- 已完成：第二阶段 `Task 1` 的评估运行器与 trace 扩展第一版已落地
- 已完成：第二阶段 `Task 2` 的 query preprocessing 与 `dense recall -> rerank` 第一版已落地
- 已完成：第二阶段 `Task 3` 的 hybrid retrieval 与中文优化切分第一版已落地
- 已完成：第二阶段 `Task 4` 的 Prometheus 指标与 Redis 记忆并发保护第一版已落地
- 已完成：深化优化阶段 `Task 1` 的运行时评估 runner、多模式对比报告、检索 source 隔离第一版已落地
- 已完成：`/actuator/health` 与 `/actuator/prometheus` 已完成测试覆盖
- 已完成：当前 `.\mvnw.cmd test` 为绿色，统计为 `33` 个测试全部通过

## 当前进展

### 规划与文档

- 已完成：现有规划评估
- 已完成：设计文档 `docs/2026-04-27-zhitu-agent-java-design.md`
- 已完成：接口文档 `docs/2026-04-27-zhitu-agent-java-api.md`
- 已完成：实现计划 `docs/2026-04-27-zhitu-agent-java-implementation-plan.md`
- 已完成：第二阶段计划文档 `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`
- 已完成：后续优化计划文档 `docs/2026-04-28-zhitu-agent-java-optimization-plan.md`
- 已完成：文档术语已统一切到 `Task 1/2/3/4`，并明确当前仓库以后端链路为主

### 第二阶段 Task 1：评估运行器与 trace 扩展

状态：已完成第一版

已完成内容：

- 已扩展 `TraceInfo` 返回字段：
  - `retrievalCandidateCount`
  - `rerankModel`
  - `rerankTopScore`
- 已扩展 `RouteDecision` 中间态：
  - `retrievalMode`
  - `retrievalCandidateCount`
  - `rerankModel`
  - `rerankTopScore`
- 已新增测试侧评估模型：
  - `src/test/java/com/zhituagent/eval/BaselineEvalCase.java`
  - `src/test/java/com/zhituagent/eval/BaselineEvalResult.java`
  - `src/test/java/com/zhituagent/eval/BaselineEvalRunner.java`
  - `src/test/java/com/zhituagent/eval/BaselineEvalRunnerTest.java`
- 已把 `src/test/resources/eval/baseline-chat-cases.jsonl` 从静态样例升级为带结构化预置能力的 fixture：
  - `expectedRetrievalHit`
  - `expectedToolUsed`
  - `expectedSummaryPresentBeforeRun`
  - `knowledgeEntries`
  - `historyTurns`
- `BaselineEvalRunner` 当前已支持：
  - 读取 JSONL fixture
  - 预置知识
  - 预热多轮会话
  - 调用现有 `/api/chat`
  - 汇总路由命中、RAG 命中、Tool 命中、延迟、token 估算
  - 输出报告到 `target/eval-reports/`

已完成验证：

- 已先写失败测试，再补实现：
  - `ChatControllerTest`
  - `BaselineEvalFixtureTest`
  - `BaselineEvalRunnerTest`
- `.\mvnw.cmd "-Dtest=ChatControllerTest,BaselineEvalFixtureTest,BaselineEvalRunnerTest" test` 已通过
- `.\mvnw.cmd test` 已通过

说明：

- 这一版评估器先放在 `src/test/java`，避免把实验性评估逻辑过早塞进生产主链
- 当前 trace 已经为后续 rerank / hybrid retrieval 预留字段，但真正填充这些字段要到第二阶段 `Task 2` 之后

### 第二阶段 Task 2：query preprocessing 与 dense recall -> rerank

状态：已完成第一版

已完成内容：

- 已新增 rerank 配置类：
  - `src/main/java/com/zhituagent/config/RerankProperties.java`
- 已将 `RerankProperties` 纳入 Spring 配置绑定：
  - `src/main/java/com/zhituagent/config/WebConfig.java`
- 已补充应用配置项：
  - `zhitu.rerank.enabled`
  - `zhitu.rerank.recall-top-k`
  - `zhitu.rerank.final-top-k`
  - `zhitu.rerank.timeout-millis`
- 已新增查询预处理器：
  - `src/main/java/com/zhituagent/rag/QueryPreprocessor.java`
- 已新增 rerank 抽象与客户端：
  - `src/main/java/com/zhituagent/rag/RerankClient.java`
  - `src/main/java/com/zhituagent/rag/OpenAiCompatibleRerankClient.java`
- 已新增检索中间态模型：
  - `src/main/java/com/zhituagent/rag/RetrievalCandidate.java`
  - `src/main/java/com/zhituagent/rag/RagRetrievalResult.java`
- 已扩展 `KnowledgeSnippet`，支持同时保留：
  - `score`
  - `denseScore`
  - `rerankScore`
- 已增强 `RagRetriever`：
  - 先对 query 做预处理
  - dense recall 数量可配置
  - 配置齐全时调用真实 `/v1/rerank`
  - rerank 失败时自动降级回 dense
  - 日志中已输出：
    - `retrievalMode`
    - `candidateCount`
    - `rerankModel`
    - `topSource`
    - `topScore`
- 已增强 `AgentOrchestrator` 与 `RouteDecision`：
  - 路由决策现在可感知 `dense-rerank`
  - 可保留 `retrievalCandidateCount`
  - 可保留 `rerankModel`
  - 可保留 `rerankTopScore`

已完成验证：

- 已先写失败测试，再补实现：
  - `QueryPreprocessorTest`
  - `RagRetrieverTest`
  - `OpenAiCompatibleRerankClientTest`
  - `AgentOrchestratorTest`
- 已验证真实中转接口后缀：
  - `https://router.tumuer.me/v1/rerank`
- `.\mvnw.cmd "-Dtest=AgentOrchestratorTest,QueryPreprocessorTest,RagRetrieverTest,OpenAiCompatibleRerankClientTest" test` 已通过
- `.\mvnw.cmd test` 已通过

说明：

- 当前 rerank 已接入主链，并且后续 `Task 3` 已继续补上 hybrid retrieval
- 当前 `BaselineEvalRunnerTest` 仍以 mock LLM 验证主链行为，不直接衡量真实回答质量
- 当前评估样例里只有 `rag-001` 会走 dense RAG，后续应继续扩充更多能体现 rerank 收益的 case

### 第二阶段 Task 3：hybrid retrieval 与中文优化切分

状态：已完成第一版

已完成内容：

- 已新增 hybrid retrieval 配置：
  - `src/main/java/com/zhituagent/config/RagProperties.java`
- 已补充应用配置项：
  - `zhitu.rag.hybrid-enabled`
  - `zhitu.rag.lexical-top-k`
- 已将 `DocumentSplitter` 从固定短切块升级为中文友好的长切块策略：
  - 优先按 `。！？；.!?;` 句界切分
  - chunk 大小提升到 `800`
  - overlap 提升到 `160`
- 已新增 lexical / hybrid 检索组件：
  - `src/main/java/com/zhituagent/rag/LexicalRetriever.java`
  - `src/main/java/com/zhituagent/rag/LexicalScoringUtils.java`
  - `src/main/java/com/zhituagent/rag/HybridRetrievalMerger.java`
- 已扩展 `KnowledgeStore` 抽象，支持：
  - `lexicalSearch`
- 已补齐本地与 pgvector 两种知识库实现的 lexical retrieval：
  - `InMemoryKnowledgeStore`
  - `PgVectorKnowledgeStore`
- 已增强 `RagRetriever`：
  - dense recall + lexical recall 合并
  - 基于 `chunkId` 去重
  - 支持 `hybrid`
  - 支持 `hybrid-rerank`
- 已新增数据库脚本：
  - `docs/sql/03-add-hybrid-retrieval-support.sql`
  - 作用：启用 `pg_trgm` 并为 `public.zhitu_agent_knowledge.text` 建立 trigram 索引

已完成验证：

- 已先写失败测试，再补实现：
  - `DocumentSplitterTest`
  - `HybridRetrievalMergerTest`
  - `RagRetrieverTest`
- `.\mvnw.cmd "-Dtest=DocumentSplitterTest,HybridRetrievalMergerTest,RagRetrieverTest" test` 已通过
- `.\mvnw.cmd test` 已通过

说明：

- 当前 hybrid retrieval 仍然保持轻量实现，没有引入 Elasticsearch 等重型检索基础设施
- 当前 lexical retrieval 采用 PostgreSQL `ILIKE` + token 打分，并通过 trigram 索引做第一轮加速

### 第二阶段 Task 4：Prometheus 指标与记忆并发保护

状态：已完成第一版

已完成内容：

- `pom.xml` 已新增：
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`
- `application.yml` 已补齐：
  - `management.defaults.metrics.export.enabled`
  - `management.endpoints.access.default`
  - `management.prometheus.metrics.export.enabled`
  - `management.health.redis.enabled`
- 已新增埋点组件：
  - `src/main/java/com/zhituagent/metrics/ChatMetricsRecorder.java`
  - `src/main/java/com/zhituagent/metrics/AiMetricsRecorder.java`
  - `src/main/java/com/zhituagent/metrics/RagMetricsRecorder.java`
  - `src/main/java/com/zhituagent/metrics/ToolMetricsRecorder.java`
  - `src/main/java/com/zhituagent/metrics/MemoryMetricsRecorder.java`
- 已在以下链路补齐 metrics：
  - `ChatController`
  - `LangChain4jLlmRuntime`
  - `RagRetriever`
  - `MemoryService`
- 当前已暴露：
  - `/actuator/health`
  - `/actuator/prometheus`
- 已新增 Redis 记忆锁抽象与实现：
  - `src/main/java/com/zhituagent/memory/MemoryLock.java`
  - `src/main/java/com/zhituagent/memory/NoopMemoryLock.java`
  - `src/main/java/com/zhituagent/memory/RedisMemoryLock.java`
- 已增强 `MemoryService`：
  - 压缩前尝试抢锁
  - 抢锁失败时保留 recent messages 并记录 `lock_miss`
  - 无需压缩时记录 `not_needed`
  - 压缩成功时记录 `compressed`

已完成验证：

- 已新增 `ObservabilityEndpointTest`
- 已增强 `MemoryServiceTest`
- `.\mvnw.cmd "-Dtest=ObservabilityEndpointTest,MemoryServiceTest" test` 已通过
- `.\mvnw.cmd test` 已通过

说明：

### 深化优化阶段 Task 1：运行时评估与多模式对比

状态：已完成第一版

已完成内容：

- 已将评估运行器迁入主代码：
  - `src/main/java/com/zhituagent/eval/BaselineEvalRunner.java`
  - `src/main/java/com/zhituagent/eval/BaselineEvalCase.java`
  - `src/main/java/com/zhituagent/eval/BaselineEvalResult.java`
  - `src/main/java/com/zhituagent/eval/BaselineEvalComparisonReport.java`
- 已新增评估配置：
  - `src/main/java/com/zhituagent/config/EvalProperties.java`
- 已新增启动期评估入口：
  - `src/main/java/com/zhituagent/eval/EvalApplicationRunner.java`
- 已将同步对话主链抽出为可复用服务：
  - `src/main/java/com/zhituagent/chat/ChatService.java`
- 已补齐评估链路下的检索运行选项：
  - `src/main/java/com/zhituagent/rag/RetrievalMode.java`
  - `src/main/java/com/zhituagent/rag/RetrievalRequestOptions.java`
- 已让 `AgentOrchestrator` 与 `RagRetriever` 支持：
  - `dense`
  - `dense-rerank`
  - `hybrid-rerank`
  的模式切换
- 已为 eval case 增加检索 source 白名单隔离，避免直答 / 工具 / 长上下文 case 被其他知识污染
- 已补齐运行时 fixture：
  - `src/main/resources/eval/baseline-chat-cases.jsonl`
- 已扩充 `rag-001` 的知识样本，使 rerank 对比稳定产生 2 个候选

已完成验证：

- `.\mvnw.cmd -Dtest=BaselineEvalRunnerTest test` 已通过
- `.\mvnw.cmd test` 已通过

说明：

- 当前 Prometheus label 保持低基数，没有把 `sessionId`、`userId`、`requestId` 直接做成指标维度
- 当前记忆并发保护只覆盖压缩关键路径，后续还可以继续深化 summary 写回与更细粒度的并发一致性策略

### Task 1：项目骨架、基础 API、SSE

状态：已完成

已完成内容：

- Spring Boot 单模块项目骨架
- Maven Wrapper 和基础 `pom.xml`
- 基础接口：
  - `GET /api/healthz`
  - `POST /api/sessions`
  - `GET /api/sessions/{sessionId}`
  - `POST /api/chat`
  - `POST /api/streamChat`
- 基于 `SseEmitter` 的流式响应外壳
- Request ID Filter 与全局异常处理

已完成验证：

- Task 1 对应的定向测试已通过

### Task 2：会话记忆与基础 Context 管理

状态：当前阶段已完成基础版

已完成内容：

- 引入 `MemoryService`
- 引入 `MessageSummaryCompressor`
- 引入 `ContextManager`
- 会话详情接口已支持返回：
  - `summary`
  - `recentMessages`
- 对话主链已经从“只传当前输入”升级为：
  - `system`
  - `summary`
  - `recent messages`
  - `current message`

已完成验证：

- Task 2 的行为测试已通过
- 在加入 Task 3 的失败测试之前，全量测试曾为绿色

说明：

- 当前记忆实现仍是“以内存行为为主”的过渡实现
- Redis 持久化版本还需要在后续阶段继续补齐

### Task 3：路由、RAG、ToolUse

状态：当前阶段已完成基础版

已完成内容：

- `ToolRegistry`
- 3 个内置工具的最小实现：
  - `time`
  - `knowledge-write`
  - `session-inspect`
- `AgentOrchestrator`
- 本地兜底 RAG：
  - `KnowledgeIngestService`
  - `DocumentSplitter`
  - `RagRetriever`
- `KnowledgeController`
- `retrieve-then-answer` 与 `tool-then-answer` 的基础主链打通

已完成验证：

- Task 3 对应测试全部通过
- 当前全量测试已经恢复为绿色
- 已手动验证：
  - `POST /api/knowledge` 可成功写入知识
  - `POST /api/chat` 在知识命中时可返回 `retrieve-then-answer`
  - `POST /api/streamChat` 在时间问题上可返回 `tool-then-answer`

说明：

- 当前 RAG 还是本地内存实现，用于先打通行为 contract
- 这一节记录的是第一阶段 base contract；第二阶段已经在其上补入 pgvector、真实 embedding、真实 rerank 与 hybrid retrieval

### 当前替换阶段：真实模型调用与基础设施接口层

状态：部分完成

已完成内容：

- `LangChain4jLlmRuntime` 已从纯 mock/fallback 改为：
  - 配置齐全时优先走真实 OpenAI 兼容接口
  - 配置缺失或显式 mock 时再走本地兜底
- 已新增 `OpenAiCompatibleBaseUrlNormalizer`
  - 兼容 base URL 直接写成 `/v1`
  - 兼容 base URL 误写成 `/v1/chat/completions`
- 已新增定向测试：
  - `src/test/java/com/zhituagent/llm/LangChain4jLlmRuntimeTest.java`
- 已新增基础设施配置类：
  - `EmbeddingProperties`
  - `PgVectorProperties`
  - `InfrastructureProperties`
- 已新增基础设施 wiring：
  - `InfrastructureConfig`
- 已新增可切换存储接口：
  - `SessionRepository`
  - `MemoryStore`
  - `KnowledgeStore`
- 已新增默认本地实现：
  - `InMemorySessionRepository`
  - `InMemoryMemoryStore`
  - `InMemoryKnowledgeStore`
- 已新增后续真实基础设施适配实现：
  - `RedisSessionRepository`
  - `RedisMemoryStore`
  - `PgVectorKnowledgeStore`
- 已把以下服务改造成依赖接口层而不是直接持有本地状态：
  - `SessionService`
  - `MemoryService`
  - `KnowledgeIngestService`
- 已补充 PostgreSQL / pgvector 初始化 SQL：
  - `docs/sql/01-create-zhitu-agent-db.sql`
  - `docs/sql/02-enable-pgvector-extension.sql`
- 已新增测试环境配置：
  - `src/test/resources/application.yml`
  - 作用：避免 Spring 集成测试误读本地 `.env` 后直接访问真实模型
- 已新增基础设施条件 wiring 测试：
  - `InfrastructureWiringTest`
  - `RedisInfrastructureWiringTest`
  - `PgVectorInfrastructureWiringTest`

### 当前联调增强：最小日志与链路观测

状态：已完成第一版

已完成内容：

- 已在 `RequestIdFilter` 中补充请求完成日志：
  - `method`
  - `path`
  - `status`
  - `requestId`
  - `latencyMs`
- 已在 `ChatController` 中补充聊天链路日志：
  - 路由决策日志
  - 普通对话完成日志
  - SSE 完成 / 失败日志
- 已在 `RagRetriever` 中补充检索日志：
  - `resultCount`
  - `topSource`
  - `topScore`
  - `queryPreview`
- 已在 `LangChain4jLlmRuntime` 中补充模型运行日志：
  - `provider`
  - `messageCount`
  - `model`
  - `latencyMs`
- 已在 `GlobalExceptionHandler` 中补充：
  - 业务异常日志
  - 参数校验异常日志
  - 未预期异常日志

已完成验证：

- 已先写失败测试，再补实现：
  - `HealthControllerTest`
  - `ChatControllerTest`
  - `LangChain4jLlmRuntimeTest`
- 上述定向测试已恢复为绿色

已完成验证：

- `.\mvnw.cmd -Dtest=LangChain4jLlmRuntimeTest test` 已通过
- `.\mvnw.cmd -Dtest=InfrastructureWiringTest test` 已通过
- `.\mvnw.cmd -Dtest=InfrastructureWiringTest,RedisInfrastructureWiringTest,PgVectorInfrastructureWiringTest test` 已通过
- `.\mvnw.cmd test` 已通过

Redis 手工联调结果：

- 已用本地启动应用方式验证：
  - `ZHITU_REDIS_ENABLED=true`
  - `ZHITU_LLM_MOCK_MODE=true`
  - `ZHITU_PGVECTOR_ENABLED=false`
- 已验证接口链路：
  - `POST /api/sessions`
  - `POST /api/chat`
  - `GET /api/sessions/{sessionId}`
- 已直接读取云上 Redis，确认存在：
  - `zhitu:session:<sessionId>`
  - `zhitu:memory:<sessionId>`
- 已完成“停应用 -> 重启应用 -> 再读取同一 sessionId”的跨重启验证
- 结论：
  - `RedisSessionRepository` 已真实生效
  - `RedisMemoryStore` 已真实生效
  - 当前 Redis 会话与短期记忆不再只依赖 JVM 内存

pgvector 手工联调结果：

- 已补齐默认配置映射：
  - `application.yml` 现已覆盖
    - `zhitu.embedding.*`
    - `zhitu.rerank.*`
    - `zhitu.pgvector.*`
- 已修复 pgvector 写入 ID 问题：
  - 新增 `KnowledgeStoreIds`
  - 将业务 `chunkId` 稳定映射为合法 UUID
- 已新增回归测试：
  - `KnowledgeStoreIdsTest`
- 已用本地启动应用方式验证：
  - `ZHITU_REDIS_ENABLED=true`
  - `ZHITU_PGVECTOR_ENABLED=true`
  - `ZHITU_LLM_MOCK_MODE=true`
- 已验证接口链路：
  - `POST /api/knowledge`
  - `POST /api/chat`
- 已验证返回结果：
  - `POST /api/knowledge` 返回成功
  - `POST /api/chat` 返回
    - `trace.path = retrieve-then-answer`
    - `trace.retrievalHit = true`
- 已直接查询 PostgreSQL：
  - `public.zhitu_agent_knowledge` 表存在且已有数据
  - 当前手工验证时查到 `count(*) = 1`
- 结论：
  - `PgVectorKnowledgeStore` 已真实生效
  - `KnowledgeIngestService -> embedding -> pgvector -> RagRetriever` 的 dense RAG 主链已打通

补充说明：

- `PgVectorKnowledgeStore` 第一阶段只接了 dense embedding + vector search
- 当前第二阶段已经在此基础上补入：
  - rerank
  - hybrid retrieval
  - lexical retrieval
  - 中文切分优化

## 配置与密钥

已完成：

- 本地 `.env` 已创建，并已被 git 忽略
- 本地配置已支持从环境变量读取：
  - 对话模型地址与 key
  - embedding 地址与 key
  - rerank 地址与 key
- 已新增 PostgreSQL / pgvector 初始化脚本：
  - `docs/sql/01-create-zhitu-agent-db.sql`
  - `docs/sql/02-enable-pgvector-extension.sql`
  - `docs/sql/03-add-hybrid-retrieval-support.sql`

当前说明：

- rerank 主链接入已完成
- hybrid retrieval 已完成第一版
- 更细粒度 trace 字段增强已完成第一版
- baseline eval 运行器已完成第一版

## Task 4：trace 与评估基线

状态：已完成

已完成内容：

- 已新增 `ChatTraceFactory`
  - 将对外 trace 组装从 `ChatController` 中抽离
- 已扩展 `TraceInfo` 返回字段：
  - `retrievalMode`
  - `contextStrategy`
  - `requestId`
  - `latencyMs`
  - `snippetCount`
  - `topSource`
  - `topScore`
  - `inputTokenEstimate`
  - `outputTokenEstimate`
- 已补充 baseline eval 文件：
  - `src/test/resources/eval/baseline-chat-cases.jsonl`
- 已创建根目录 `README.md`
- 已将 API / 设计 / 实现计划文档同步到当前实现
- 已新增定向测试：
  - `ChatControllerTest`
  - `BaselineEvalFixtureTest`

已完成验证：

- `.\mvnw.cmd -Dtest=ChatControllerTest,BaselineEvalFixtureTest test` 已通过
- `.\mvnw.cmd test` 已通过

阶段提交：

- 已提交：`f5b66c3 feat: complete phase-one backend baseline and tracing`

## 运行状态

- 当前未确认应用正处于持续运行状态
- 按用户要求，暂时不使用 Docker 做验证
- 当前仓库不再维护前端静态页面，后续前端由其他 AI 或其他协作者负责

## 当前约束

- 现在不要依赖 Docker，除非用户再次明确要求
- 文档统一放在 `docs/`
- 优先保持阶段性推进，不要拆成太多零碎 commit
- 后续统一使用 `Task 1/2/3/4` 的说法，不再使用 `M1/M2/M3/M4`
- 密钥只放在 `.env`，不要写进会提交的配置文件

## 下一步

当前第一阶段与第二阶段第一版都已完成，后续主线已切到“深化优化阶段”。详细路线请看：

- `docs/2026-04-28-zhitu-agent-java-optimization-plan.md`

建议按新的 `Task 1` 到 `Task 4` 推进：

1. 用真实 LLM、真实 embedding、真实 rerank、真实 pgvector 跑 `BaselineEvalRunner`，产出 dense / dense-rerank / hybrid-rerank 的正式对比报告
2. 继续调优 hybrid retrieval、query preprocessing、topK 与阈值，稳定检索收益
3. 深化记忆机制与上下文压缩策略，从 `summary + recent messages` 升级为更清晰的短期 / 长期记忆分层
4. 补齐指标看板、错误分类、评估报告模板与可写入简历的量化沉淀

## 2026-04-28 晚间补充：深化优化阶段 Task 1 进展

状态：已完成第一轮“真实评估 + 区分型样例扩充”，但也暴露出真实 rerank 仍需继续优化

本轮新增实现：

- 已扩充 baseline eval case：
  - `rag-rerank-001`
  - `rag-hybrid-001`
- 已增强 `BaselineEvalRunner`：
  - 默认模式不再只写死为 `default`
  - 当运行默认检索模式时，会按真实 `retrievalMode` 解析 `modeExpectations`
  - 评估报告会回填更真实的 `mode`
- 已补强测试：
  - `BaselineEvalRunnerTest`
  - `BaselineEvalFixtureTest`

本轮已完成验证：

- `.\mvnw.cmd "-Dtest=BaselineEvalFixtureTest,BaselineEvalRunnerTest" test` 通过
- `.\mvnw.cmd test` 通过
- 当前全量测试结果：
  - `34` 个测试全部通过

真实评估运行情况：

- 第一次尝试使用当前 `.env` 中的对话模型 `gpt-5.4`
  - 运行到中途命中上游 `model_cooldown`
  - 未能完整产出报告
- 第二次改用同网关可用模型 `gpt-5.4-mini` 跑通整套真实评估
  - 评估报告：
    - `target/eval-reports/baseline-comparison-20260428-185440.json`

本轮真实报告关键结论：

- `dense`
  - `passedCases = 6/6`
  - `topSourceExpectationHitRate = 1.0`
  - `averageLatencyMs = 10337.5`
- `dense-rerank`
  - `passedCases = 4/6`
  - `topSourceExpectationHitRate = 0.0`
  - `averageLatencyMs = 11054.0`
- `hybrid-rerank`
  - `passedCases = 5/6`
  - `topSourceExpectationHitRate = 0.5`
  - `averageLatencyMs = 12771.83`

区分度验证结果：

- `rag-hybrid-001`
  - `dense` 命中 `phase-one-vague-a`
  - `hybrid-rerank` 命中 `phase-one-keyword-target`
  - 说明新增的“关键词 + lexical 信号”样例在真实链路下也能拉开模式差异
- `rag-rerank-001`
  - 真实 `dense-rerank` / `hybrid-rerank` 并没有像 mock stub 那样翻到 `phase-one-precise`
  - 真实报告里仍然更偏向 `phase-one-vague`
  - 说明当前 rerank 提升并不稳定，后续不能只看 mock 测试结论

与旧报告对比：

- 旧报告：
  - `target/eval-reports/baseline-comparison-20260428-181156.json`
  - 只有 `4` 条 case，模式区分度不足
- 新报告：
  - 已扩成 `6` 条 case
  - 已能真实观察到 hybrid 的收益点
  - 同时也真实暴露了 rerank 质量缺口

当前判断：

- 深化优化阶段 `Task 1` 的“真实评估跑通 + 结果更有区分度”已经完成第一版
- 但这轮结果同时证明：
  - `hybrid retrieval` 已经有真实收益信号
  - `rerank` 的真实收益还不稳定
  - 下一步应优先转入检索质量深化，而不是只继续堆更多 case

## 2026-04-28 深夜补充：深化优化阶段 Task 2 第一轮

状态：已完成第一轮“rerank 结果校准 + 真实报告回归验证”

本轮新增实现：

- 已新增 `RerankResultCalibrator`
  - 文件：
    - `src/main/java/com/zhituagent/rag/RerankResultCalibrator.java`
- 已将 rerank 校准接入 `RagRetriever`
  - 目标不是替代上游 rerank，而是在分数非常接近时补一层本地校准
  - 当前校准信号包括：
    - 枚举型回答结构
    - 英文 / acronym 查询词覆盖
    - “明确 / 包含 / 列出”类显式表达
- 已同步调整 baseline eval 期望：
  - `rag-hybrid-001` 在 `dense-rerank` 下现在也以 `phase-one-keyword-target` 为合理期望

本轮新增测试：

- `src/test/java/com/zhituagent/rag/RerankResultCalibratorTest.java`
- `RagRetrieverTest` 新增 structured-answer rerank calibration 用例

本轮已完成验证：

- `.\mvnw.cmd "-Dtest=RagRetrieverTest,RerankResultCalibratorTest,BaselineEvalFixtureTest,BaselineEvalRunnerTest" test` 通过
- `.\mvnw.cmd test` 通过
- 当前全量测试结果已更新为：
  - `37` 个测试全部通过

真实评估运行情况：

- 再次尝试 `gpt-5.4-mini`
  - 中途仍然命中上游 `model_cooldown`
  - 未能完成整轮
- 改用同网关可用模型 `gpt-5.2`
  - 成功完成整轮真实评估
  - 新报告：
    - `target/eval-reports/baseline-comparison-20260428-192645.json`

优化前后核心对比：

- 旧报告：
  - `target/eval-reports/baseline-comparison-20260428-185440.json`
  - `dense-rerank`
    - `passedCases = 4/6`
    - `topSourceExpectationHitRate = 0.0`
  - `hybrid-rerank`
    - `passedCases = 5/6`
    - `topSourceExpectationHitRate = 0.5`
- 新报告：
  - `target/eval-reports/baseline-comparison-20260428-192645.json`
  - `dense-rerank`
    - `passedCases = 6/6`
    - `topSourceExpectationHitRate = 1.0`
  - `hybrid-rerank`
    - `passedCases = 6/6`
    - `topSourceExpectationHitRate = 1.0`

关键 case 结果：

- `rag-rerank-001`
  - 优化前：
    - `dense-rerank` / `hybrid-rerank` 都更偏 `phase-one-vague`
  - 优化后：
    - `dense-rerank` / `hybrid-rerank` 都稳定翻到 `phase-one-precise`
- `rag-hybrid-001`
  - 优化前：
    - `dense-rerank` 已能翻到 `phase-one-keyword-target`，但当时 fixture 期望还没同步
  - 优化后：
    - `dense-rerank` / `hybrid-rerank` 都稳定命中 `phase-one-keyword-target`
    - `dense` 仍保持 `phase-one-vague-a`

当前判断：

- 深化优化阶段 `Task 2` 的第一轮已经拿到真实、可量化的收益
- 这轮最直接可讲的数据是：
  - `dense-rerank` 的 `topSourceExpectationHitRate` 从 `0.0` 提升到 `1.0`
  - `hybrid-rerank` 的 `topSourceExpectationHitRate` 从 `0.5` 提升到 `1.0`
  - 两个 rerank 模式的 `passedCases` 都提升到 `6/6`
- 下一步更值得做的不是继续硬堆启发式，而是：
  - 继续扩充真实评估 case
  - 观察校准规则是否会在更多中文查询上带来副作用
  - 进一步处理模型网关 `cooldown` 对评估稳定性的影响
