# AGENTS.md

本文件只放当前仓库长期有效的 agent 开发规则。当前项目快照见 `docs/agent-handoff.md`；历史工程流水见 `docs/archive/optimize-progress.md`，不要再把它当作当前进度文件维护。

## Context Files

- `README.md`：对外展示口径、截图和功能总览。
- `docs/agent-handoff.md`：当前项目快照、代码地图、验证记录和交接细节。
- `docs/quick-start.md`、`docs/architecture.md`、`docs/features.md`、`docs/api-reference.md`、`docs/demo-guide.md`：开发、架构、功能、API 和演示文档。
- `docs/guide/`：专题学习材料。
- `docs/archive/`：历史 plan 和一次性记录，只读参考。

## Project Direction

- 构建 `ZhituAgent` 的 Java 版本，面向 SRE/DevOps 知识问答、工具调用和告警分析。
- 保持单模块 Spring Boot，不提前拆多模块或引入额外服务编排层。
- 使用 LangChain4j 作为大模型接入层，但核心 Agent 编排、工具治理、上下文预算和 RAG 决策由项目代码掌控。
- 默认要能本地运行并回退到 in-memory 实现；真实 Redis、Elasticsearch、MinIO、Kafka 通过环境变量显式启用。

## Architecture Rules

- 会话与记忆：Redis 可选；默认必须能回退到 in-memory store。
- RAG：Elasticsearch 8.10 + IK 中文分词是当前主路径；pgvector 已退役，不要新增 pgvector 依赖。
- 文件入库：MinIO + Tika + HanLP；Kafka KRaft 异步管线通过 `ZHITU_KAFKA_ENABLED=true` 显式开启，关闭时保留同步入库路径。
- Kafka 语义：producer 使用事务与幂等；consumer 按 at-least-once 处理；ES `_id=chunkId` 吸收重复投递，形成 exactly-once-effect。
- MCP、LLM router、reflection、多 Agent SRE 编排都应保持可配置开关，避免默认本地单测依赖外部服务。

## Build And Test

```powershell
# 后端单测，默认不依赖 Docker
.\mvnw.cmd -o test

# 单测 + integration tests；需要 Docker/Testcontainers 环境
.\mvnw.cmd -o verify

# 后端本地启动
.\mvnw.cmd -o spring-boot:run -Dspring-boot.run.profiles=local

# 前端构建
cd frontend
npm run build
```

- 当前日常验证不要把 Docker 作为前提，除非用户明确要求跑真实中间件或 IT。
- 前端产物输出到 `src/main/resources/static/`，该目录已被 gitignore。
- 真 LLM / ES / Redis / MinIO / Kafka 相关验证要说明是否依赖外部服务和密钥；没有实际运行就不要写成已验证。

## Change Discipline

- 先查当前源码和文档，再改入口文件；不要用旧记忆覆盖仓库事实。
- 优先采用少量阶段、少量 milestone commit 的推进方式，不拆过多碎小提交。
- 后续阶段描述统一使用 `Task 1` / `Task 2` / `Task 3` / `Task 4`。
- 规划、设计、计划文档放在 `docs/`；已实施或过期的 plan 放到 `docs/archive/`。
- 根目录入口文档应短而稳定；共享细节放 `docs/agent-handoff.md` 或专题文档。
- 汇报进度必须绑定具体文件、行为和验证命令；不要只说“差不多完成了”。

## Security And Config

- 绝对不要把 provider key、数据库密码、ES 密码、Prometheus basic-auth 等敏感信息写进会提交的源码或文档。
- 本地敏感信息只放 `.env` 或未跟踪的部署配置；`.env` 必须保持在 `.gitignore` 中。
- 应用配置应通过环境变量读取地址、模型名和 API key。
- 如果需要说明模型入口，只写变量名和配置位置，不在提交文档里固化真实 key 或私有 endpoint。

## Windows Notes

- 默认工作目录是 `D:\dev\my_proj\java\zhitu-agent-java`。
- 不要把项目脚本、临时产物或交接文档写到 `C:\` 的随机目录；除非用户明确要求，所有项目产物应留在当前仓库内。
- PowerShell 环境下优先使用 `.\mvnw.cmd`、`Get-ChildItem`、`Select-String` 等稳定命令；`rg` 可用时可以优先用，失败时直接回退 PowerShell 原生命令。

## Communication

- 必须清楚区分：已完成、部分完成、未完成、未验证。
- 如果测试失败是因为外部基础设施、Docker、密钥或后续阶段测试先写了，要明确说明原因。
- 当用户问“理解对不对”或“写得是不是过大”时，先查源码和当前文档，再校准说法，不要泛泛给建议。
- 对简历、项目叙事和面试材料，优先保证真实性、可追问性和边界清楚。
