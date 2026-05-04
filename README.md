# Zhitu Agent Java

后端优先的 Java AI Agent 项目,在公开中文 retrieval benchmark(C-MTEB T2Retrieval)上拍出可解释的 RAG 检索数字 + 完整测试体系 + 真实云端部署。当前版本 = v3。

> 项目背景:作者在找 Agent 开发实习,这是核心作品集项目。设计目标是**让面试官 clone 下来 5 分钟跑通,看到 RAG / Tool / Trace / 异步入库的全链路**,而不是又一个"OpenAI API 包一层 chat"的玩具。

## 这个项目能讲什么(简历级)

- **真 ES 栈现代化**:从 v1 的 `pgvector + ILIKE 子串匹配伪 hybrid` 替换为 v3 的 **Elasticsearch 8.10 + IK 中文分词 + KNN+BM25+rescore 单次调用 native hybrid**
- **MinIO + Tika + HanLP 多格式入库管线**:15 种文件格式 / 分片续传 / 内存阈值熔断 / 解析—嵌入—ES 一站式
- **Kafka KRaft 异步管线**:`acks=all + idempotence=true + transactional.id` producer exactly-once + ES `_id=sha256(content)` 幂等吃掉 consumer 重投递 = exactly-once-effect
- **C-MTEB rerank ablation**:Qwen3-Reranker-8B 在采样 fixture 上贡献 **+5.6 pp nDCG@10 / 3.8x p50 latency**,据此落地 SLA-aware 检索决策(详见 `docs/2026-05-04-cmteb-eval-baseline-vs-norerank.md`)
- **多 agent 编排 + SRE 路由真功臣澄清**:routeAcc +0.20 提升真正来自 multi-agent SRE Phase 1 commit,**不是** ES 替换 — 用评测对比帮自己诚实归因
- **完整测试体系**:217 单测 + 4 Kafka Testcontainers IT,`mvn verify` surefire/failsafe 拆分,无 Docker 环境自动 skip
- **生产事故响应**:云端 ES 公网无认证被 ransomware bot 17 min 删光索引,处理:不付赎金 + IP 白名单 + xpack auth + IK 重装(写进 `feedback_infra_security.md`)

## 真栈(v3,2026-05-04)

| 层 | 技术 |
|---|---|
| 语言 / 框架 | Java 21,Spring Boot 3.5,Maven Wrapper |
| LLM 抽象 | LangChain4j 1.1(OpenAI 兼容协议)|
| 向量库 | Elasticsearch 8.10 + analysis-ik(中文分词)+ dense_vector + KNN |
| 文件存储 | MinIO + 分片续传 |
| 解析 / 切片 | Apache Tika + HanLP(中文句边界感知)|
| 缓存 / 会话 | Redis 7(bitmap chunk dedup)|
| 异步 | Kafka 3.7 KRaft(单节点)+ DLT |
| 评测 | 自实现 RankingMetrics(nDCG/Recall/MRR)+ C-MTEB T2Retrieval BEIR 格式 fixture |
| 前端 | React + TypeScript(`frontend/`,SSE 流式 + Trace 折叠 + iMessage 气泡)|
| Embedding / Rerank | Qwen3-Embedding-8B + Qwen3-Reranker-8B(SiliconFlow endpoint) |

## 5 分钟跑通(面试官视角)

### 0. 前置

- Java 21 + Maven Wrapper
- 一份 `.env`(向作者要,或用云端共享 .env)
- 网络能访问 `106.12.190.62`(云端 ES + Redis + MinIO + Kafka)— 或者改连本地 docker

### 1. clone + 配置

```bash
git clone <repo>
cd zhitu-agent-java
cp .env.example .env    # 填 ES/Redis/MinIO/Kafka 密码 + LLM API key
```

### 2. 跑测试(确认环境 OK)

```bash
./mvnw -o test               # 217 单测 ~30s
./mvnw -o verify             # +4 Kafka Testcontainers IT(需 Docker;无 Docker 自动 skip)
```

### 3. 启动后端

```bash
./mvnw -o spring-boot:run -Dspring-boot.run.profiles=local
# 启动日志看到这一行就 OK:
# ZhituAgent active stores: KnowledgeStore=ElasticsearchKnowledgeStore (nativeHybrid=true), ...
```

### 4. 启动前端(可选)

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

### 5. 跑 4 个核心 demo(每个 < 1 min)

```bash
# (a) 普通对话 + RAG
curl -N -H "Content-Type: application/json" \
  -d '{"sessionId":"demo","message":"什么是 Apache Tika"}' \
  http://localhost:8080/api/streamChat
# 看 trace.path / retrievalHit / topSource / topScore

# (b) 同步文件上传 + 入库
curl -F file=@docs/m2-smoke-sample.txt http://localhost:8080/api/files/upload
# 返回 200 + chunkCount,等 1-3s ES 立即可查

# (c) 异步文件上传(Kafka 异步管线)
ZHITU_KAFKA_ENABLED=true ./mvnw -o spring-boot:run -Dspring-boot.run.profiles=local
curl -i -F file=@docs/m2-smoke-sample.txt http://localhost:8080/api/files/upload
# 立即返回 202 + uploadId,后台 consumer 跑 Tika+embed+ES bulk
curl http://localhost:8080/api/files/status/{uploadId}
# parseStatus: QUEUED → PARSING → INDEXED

# (d) 跑 C-MTEB rerank ablation(reuse baseline ES index,~17 min)
./mvnw -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=0 \
    --zhitu.eval.exit-after-run=true \
    --zhitu.eval.cmteb.enabled=true \
    --zhitu.eval.cmteb.label=no-rerank \
    --zhitu.eval.cmteb.retrieval-mode=hybrid \
    --zhitu.eval.cmteb.skip-ingest=true \
    --zhitu.elasticsearch.index-name=zhitu_agent_eval_cmteb_baseline \
    --zhitu.rerank.final-top-k=50 \
    --zhitu.rag.min-accepted-score=0.0"
# 报告落到 target/eval-reports/cmteb-no-rerank-{ts}.json
```

## 主要 HTTP 接口

| 路径 | 用途 |
|---|---|
| `GET /api/healthz` | 健康检查 |
| `POST /api/sessions` | 创建会话 |
| `POST /api/chat` | 单轮对话 |
| `POST /api/streamChat` | SSE 流式对话(主入口)|
| `POST /api/files/upload` | 文件上传(同步 200 / 异步 202)|
| `GET /api/files/status/{uploadId}` | 异步入库状态查询 |
| `POST /api/knowledge` | 直接 ingest 文本(跳过文件解析)|
| `POST /api/sse/sre/{requestId}` | Multi-agent SRE 流式 demo(前端 SRE 面板)|

## 关键文档(深度细节)

- **`CLAUDE.md`** — 给 AI 协作者看的工作笔记,包含 TL;DR / 协作模式 / 当前状态速查 / 简历叙事框架。**新会话先 read 这个,30 秒进入工作状态**
- **`optimize-progress.md`** — 项目主进度,完整 A-1..A-7 + 阶段 3 M1-M5 + v3-eval 章节
- **`docs/2026-05-04-cmteb-eval-baseline-vs-norerank.md`** — C-MTEB rerank ablation 报告(含 sampling 边界声明)
- **`docs/2026-04-27-zhitu-agent-java-design.md`** — v1 设计文档(历史锚点)
- **`docs/2026-05-01-multi-agent-execution-handbook.md`** — Multi-agent SRE 编排
- **`AGENTS.md`** — 长期协作 / 密钥 / 基础设施约束
- **`infra/cloud/docker-compose.yml`** — 云端中间件部署(ES auth + Kafka + Redis + MinIO)

## 评测体系(retrieval-only)

```bash
# (1) 拉 C-MTEB T2Retrieval fixture(一次性)
cd tools/eval
python -m venv .venv && .venv/Scripts/activate
pip install -r requirements.txt
python fetch_cmteb.py --num-queries 300 --num-corpus 2000 --seed 42

# (2) 跑 baseline(~50 min)/ no-rerank(~17 min)看上面 demo (d)

# (3) 聚合两组结果到对比 markdown
python tools/eval/aggregate_sweep.py
# 输出 target/eval-reports/cmteb-sweep-{ts}.{md,json}
```

数字结论:在采样 fixture(2000 corpus / 300 queries)上,**rerank 贡献 +5.6 pp nDCG@10 但 3.8x p50 latency**。绝对值不对标 MTEB leaderboard(corpus 只有完整 T2Retrieval 的 1/60),Δ 才是这次评测的真信号。

## 当前观测能力

控制台日志:请求完成 / 路由决策 / RAG 检索 / 模型调用 / 异常 + 结构化字段(latencyMs / tokenEstimate)
返回给前端的 `trace`:`path` / `retrievalHit` / `topSource` / `topScore` / `retrievalMode` / `requestId` / 嵌套 span 树

## 已知限制(诚实清单)

1. C-MTEB 评测只跑了采样版(2K corpus),完整 118K corpus 评测因 token 成本未做
2. chunk-size sweep / overlap sweep / embedding 模型对比都未做(zombie JVM 死锁烧 token 后缩减)
3. ElasticsearchKnowledgeStore 缺独立 IT(hybrid DSL 只靠云端 smoke 验证;Kafka 都用 Testcontainers,ES 同样可以加)
4. Frontend 未做 e2e 测试

## License

私有作品集项目,未公开发布。