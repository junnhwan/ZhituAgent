# Agent Handoff

本文件承接根入口文件里不适合长期堆放的项目快照、代码地图和验证记录。它可以随项目推进更新；根入口文件应尽量保持短而稳定。

## Snapshot

更新时间：2026-05-09。

- 仓库：`D:\dev\my_proj\java\zhitu-agent-java`
- 形态：单模块 Spring Boot 应用 + React/Vite 前端。
- 后端规模：`src/main/java` 当前约 164 个 Java 文件，`src/test/java` 当前约 63 个 Java 测试文件。
- 当前验证：
  - `.\mvnw.cmd -o test`：266 tests, 0 failures, 0 errors, 0 skipped。
  - `cd frontend; npm run build`：TypeScript + Vite build 通过；Vite 仍提示主 chunk 超过 500 kB，这是构建警告，不是失败。
- 当前阶段：核心代码 milestone 已完成，近期 commit 主要集中在 README/文档重组、演示体验、上下文可观测性、LLM router、intent、reflection 和包装层。

## Code Map

| 区域 | 主要职责 |
|---|---|
| `src/main/java/com/zhituagent/api` | REST/SSE API，聊天、会话、文件上传、HITL、SRE alert、health/observability |
| `src/main/java/com/zhituagent/chat` | `ChatService` 主链路：session、memory、route、context、LLM、trace archive |
| `src/main/java/com/zhituagent/orchestrator` | `AgentOrchestrator`、`AgentLoop`、工具执行、schema 校验、审批和 loop 检测 |
| `src/main/java/com/zhituagent/agent` | Reflection、多 Agent SRE Supervisor/Specialist 编排 |
| `src/main/java/com/zhituagent/rag` | KnowledgeStore、ES native hybrid、RRF、rerank、Self-RAG、chunk/idempotency |
| `src/main/java/com/zhituagent/file` | MinIO/Tika/HanLP 文件入库，分片上传，Kafka producer/consumer/status |
| `src/main/java/com/zhituagent/context` | 上下文组装、token 预算、overflow 标记、raw/budgeted token 估算 |
| `src/main/java/com/zhituagent/memory` | Redis/in-memory 记忆、摘要压缩、事实抽取、压缩锁 |
| `src/main/java/com/zhituagent/llm` | OpenAI-compatible runtime、流式输出、rate limit、primary/fallback router |
| `src/main/java/com/zhituagent/intent` | 规则优先、cheap LLM 兜底的双层意图识别 |
| `src/main/java/com/zhituagent/mcp` | MCP client abstraction、official SDK adapter、tool registrar |
| `src/main/java/com/zhituagent/eval` | baseline eval、C-MTEB fixture、ranking metrics、对比报告 |
| `src/main/java/com/zhituagent/metrics` | Micrometer 指标：chat、tool、RAG、memory、AI、error |
| `src/main/java/com/zhituagent/trace` | span 收集与 JSONL trace 归档 |
| `frontend/src` | React 控制台：聊天、TracePanel、文件上传、HITL、SRE Demo、SSE |

## Current Architecture Facts

- RAG 主存储是 `ElasticsearchKnowledgeStore`；ES index lazy create，mapping 位于 `src/main/resources/es-mappings/knowledge_base.json`。
- ES hybrid 是单次请求：KNN 粗召回 + `match` + `rescore`，避免应用层拼接两次查询。
- 文件上传入口 `FileUploadController` 在 MinIO 开启时生效；Kafka 开启时返回 HTTP 202 并异步解析，否则走同步入库。
- Kafka producer 使用 `KafkaTemplate.executeInTransaction`；consumer 不包 Kafka 事务，依靠 ES `_id=chunkId` 吸收重投递。
- `InfrastructureConfig` 通过 `@ConditionalOnProperty` 和 `@ConditionalOnMissingBean` 保证 Redis/ES 关闭时可回退到 in-memory。
- `ContextManager` 默认输入预算 1024 tokens，按旧消息、事实、摘要、evidence 的顺序降级，仍超预算时打 `-overflow`。
- `MemoryService` 使用 `MemoryLock` 防止同一 session 并发压缩；Redis 开启时使用 Redis lock，否则 noop lock。
- `RoutingLlmRuntime` 只有在 `zhitu.llm.router.enabled=true` 时启用；streaming fallback 只允许在首 token 之前发生。
- SRE 编排使用 `MultiAgentOrchestrator`，默认 specialist 包括 `AlertTriageAgent`、`LogQueryAgent`、`ReportAgent`。
- MCP 默认关闭；`application-local.yml` 里保留 Tavily、Baidu、Filesystem、Prometheus 的配置模板。

## Documentation Map

- `README.md`：对外总览、截图和演示入口。
- `docs/quick-start.md`：本地和云端启动命令。
- `docs/architecture.md`：系统架构和关键流程图。
- `docs/features.md`：核心功能说明。
- `docs/api-reference.md`：API 路径、请求和响应。
- `docs/demo-guide.md`：演示 prompt 和截图建议。
- `docs/guide/`：RAG、memory/context、tool/MCP、ReAct、SRE multi-agent 等专题学习文档。
- `docs/eval/`：评测日志、context benchmark、trace 样本。
- `docs/archive/`：历史 plan、旧优化进度、部署 cheatsheet 和一次性评测报告。

## Verification Commands

后端单测：

```powershell
.\mvnw.cmd -o test
```

集成测试：

```powershell
.\mvnw.cmd -o verify
```

前端构建：

```powershell
cd frontend
npm run build
```

本地启动：

```powershell
.\mvnw.cmd -o spring-boot:run -Dspring-boot.run.profiles=local
```

启用 Kafka 异步文件管线：

```powershell
$env:ZHITU_KAFKA_ENABLED = "true"
$env:ZHITU_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
.\mvnw.cmd -o spring-boot:run -Dspring-boot.run.profiles=local
```

## Maintenance Notes

- 不要在根入口文档里维护长 commit 表、长命令清单或用户级 memory 路径；这些内容容易漂移。
- `docs/archive/optimize-progress.md` 是历史档案，不是当前实时状态。需要新阶段记录时，优先新建专题文档或更新本 handoff。
- 只在实际运行后更新测试数字；否则写“未验证”或“上次验证”。
- 文档里出现真实 IP、私有 endpoint、模型 key 或密码时，先判断是否应该改成环境变量名。
- 如果发现 README/quick-start 与源码配置不一致，优先查 `src/main/resources/application*.yml` 和 `InfrastructureConfig` 再改。
