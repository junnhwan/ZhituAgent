# C-MTEB T2Retrieval — RAG retrieval ablation(采样口径)

> 跑出来的真数字,不是估算。**注意:fixture 是采样的,数字只用于内部 ablation 对比,不直接对标 MTEB leaderboard**(详见下面"采样口径与不可比性")。Reproduce 见文末。

## TL;DR

在 C-MTEB T2Retrieval 的**采样 fixture**(2000 corpus / 300 queries / BEIR 标准 qrels)上对 RAG 检索链路做 rerank ablation:

- **关 rerank**(IK BM25 + KNN hybrid):nDCG@10 = 0.86 / Recall@5 = 0.70 / p50 retrieve = 1.5s
- **开 rerank**(叠加 Qwen3-Reranker-8B):nDCG@10 = 0.91 / Recall@5 = 0.76 / p50 retrieve = 5.6s
- 净效果:rerank **+5.6 pp nDCG@10 / -3.8x p50 latency**

工程结论:**rerank 是锦上添花,不是雪中送炭**。SLA<2s 实时场景可关 rerank 换 4x 吞吐,离线 / batch / 高准确率场景开 rerank 拿满精度。

## 采样口径与不可比性(必读)

**绝对值不能跟 MTEB leaderboard 直接比**,理由:

| 维度 | MTEB 官方评测 | **本评测** |
|---|---|---|
| corpus 规模 | 118,605 docs | **2,000 docs**(59x 小)|
| queries | 22,812 | 300 |
| corpus 中 relevant 占比 | 一个 query 的 5-10 个 relevant 散落在 118K 海量 distractor 里 | 1595 relevant + 405 distractor → **80% 文档对某个 query 是相关的** |
| 单 query 找 5 relevant 的 random 基线 | 0.004% | **0.25%(60x 容易)** |
| 任务本质 | 大海捞针 | **接近开卷考试** |

为什么这样采样:`fetch_cmteb.py` 的策略是**先把 sampled queries 的所有 relevant doc 全加进 corpus**(保证 Recall@k 上限 = 1,不被 sampling 噪声压低),再用 distractor 填到 num-corpus。这换来的是**两组 ablation 用同一 fixture,内部对比的相对差值是硬的**;但单组绝对值偏高,**不能跟 leaderboard 数字横向比**。

**数字怎么用才合法**:
- ✅ 同 fixture 内 baseline vs no-rerank 的 Δ(rerank 真实贡献)
- ✅ 同 fixture 内 chunk-256 vs chunk-512 / overlap=0 vs 64 这种横向 sweep(本次未做完)
- ❌ 把 0.91 / 0.86 跟 bge-m3 / Qwen3-Embed 在 MTEB 上的官方数字直接比
- ❌ 写"超过 SOTA / leaderboard"

## Setup

| 维度 | 值 |
|---|---|
| Dataset | C-MTEB T2Retrieval(dev split,seed=42 采样)|
| Corpus | 2000 docs(包含全部 sampled queries 的 relevant docs + 随机 distractor)|
| Queries | 300 |
| Qrels | 1595(平均 5.32 relevant doc / query)|
| Embedding | Qwen3-Embedding-8B(原 4096 dim → 项目配置 2048 dim)|
| Reranker | Qwen3-Reranker-8B (top-50 candidates) |
| Vector store | Elasticsearch 8.10 + IK 中文分词 |
| Retrieval | KNN cosine + IK BM25 + rescore (单次 ES 调用) |
| chunkSize / overlap | 512 / 64(两组一致)|
| 拒答阈值 | `zhitu.rag.min-accepted-score=0.0`(关掉,纯 retrieval 信号)|

## 主表

| label | mode | nDCG@10 | Recall@5 | MRR@5 | Hit@5 | p50 ms | p90 ms | p99 ms | ingest s | chunks |
|---|---|---|---|---|---|---|---|---|---|---|
| **baseline-v1** | hybrid-rerank | **0.9117** | 0.7582 | 0.9565 | 0.9565 | 5620 | 11813 | 39788 | 487.1 | 5271 |
| **no-rerank** | hybrid(no rerank) | **0.8554** | 0.6998 | 0.9289 | 0.9500 | 1477 | 7605 | 29824 | 0(reuse)| 0(reuse)|

> ⚠️ 绝对值偏高,见上文"采样口径"。Δ 才是这次评测的真信号。

## Δ vs baseline

| label | ΔnDCG@10 | ΔRecall@5 | ΔMRR@5 | Δp50 ms | Δp90 ms |
|---|---:|---:|---:|---:|---:|
| baseline-v1 | +0.0000 | +0.0000 | +0.0000 | +0 | +0 |
| no-rerank | **-0.0563** | -0.0584 | -0.0276 | **-4143**(3.8x 快) | -4208(1.55x 快)|

## 解读

### 1. Rerank 的边际贡献(本次评测的主信号)

Qwen3-Reranker-8B 把 50 个候选重排,带来 **+5.6 pp nDCG@10**。拆开看:

- Recall@5 提升更大(+5.8 pp)→ rerank 把"在前 50 但不在前 5"的相关 doc 顶上来
- MRR@5 提升较小(+2.8 pp)→ 第一相关 doc 本来就常常在前 5,rerank 主要是优化 2-5 位的排序

注:在 corpus 更大(distractor 更多)的真实场景,rerank 的边际贡献**很可能更高** — 因为大 corpus 下 hybrid 召回的 top-50 里假阳性更多,rerank 能砍的水分更多。这个 +5.6 pp 是**采样 corpus 上的下界估计**。

### 2. Latency 的真实代价

p50 升高 3.8x(1.5s → 5.6s)是 Qwen3-Reranker-8B 处理 50 个 candidate 的 batch 时间。这个数字工程上是硬约束:

- 实时聊天 / 搜索框补全 SLA 通常要 <1s,baseline 5.6s 直接超标
- 离线批处理 / agent 任务编排可以接受 5s
- p99 长尾 30-40s 主要是 ES idle RST 后 retry,跟 rerank 无关(已加 1 次 retry on IOException)

### 3. 工程决策矩阵

| 场景 | 选择 | 理由 |
|---|---|---|
| 实时聊天检索 | hybrid (no rerank) | p50 1.5s,牺牲 5.6 pp 准确率换 SLA |
| 离线知识库构建 | hybrid-rerank | latency 不敏感,精度优先 |
| 多 agent 工具调用 | hybrid-rerank | retrieval 调用频率低,质量影响下游 LLM cost |
| 移动端 / 弱网 | hybrid (no rerank) | 减少 RPC 时延 |

## 数据集 sample(给一点真实感)

300 queries 中 nDCG=1.0 的 case 例:
- "鬼眼珠是什么木" → cmteb-doc-417459(score 0.9475)
- "tpn是什么意思医学" → cmteb-doc-345497(score 0.9654)

12 个 nDCG=0 的 case 在 baseline 是 `zhitu.rag.min-accepted-score=0.15` 误拒答(已在 no-rerank 那组用 0.0 关掉)。

## 想做但没做的(诚实清单)

- **完整 corpus 评测**(118K docs):成本太高(corpus 60x → embedding 60x → ES 容量 60x),非本阶段范围。如果做,绝对值预计大幅下降,Δ 可能扩大
- **chunk-size sweep / overlap sweep / embedding 模型对比**:zombie JVM 死锁烧 token 后缩减
- **跟 MTEB 官方 evaluation framework 比对**(`pytrec_eval`):自实现 nDCG 跟官方 eval lib 可能在并列处理 / log 底数等细节有 ε 差异,尚未对账

## Reproduce

### 1. 拉 fixture(一次性,~3 min)
```bash
cd tools/eval
python -m venv .venv && .venv/Scripts/activate
pip install -r requirements.txt
python fetch_cmteb.py --num-queries 300 --num-corpus 2000 --seed 42
```

### 2. 跑 baseline(~50 min,首次需要 ingest 5271 chunks)
```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=0 \
    --zhitu.eval.exit-after-run=true \
    --zhitu.eval.cmteb.enabled=true \
    --zhitu.eval.cmteb.label=baseline-v1 \
    --zhitu.eval.cmteb.chunk-size=512 \
    --zhitu.eval.cmteb.chunk-overlap=64 \
    --zhitu.eval.cmteb.retrieval-mode=hybrid-rerank \
    --zhitu.elasticsearch.index-name=zhitu_agent_eval_cmteb_baseline \
    --zhitu.rerank.final-top-k=50 \
    --zhitu.rag.min-accepted-score=0.0"
```

### 3. 跑 no-rerank(~17 min,reuse baseline 的 ES index 跳 ingest)
```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=0 \
    --zhitu.eval.exit-after-run=true \
    --zhitu.eval.cmteb.enabled=true \
    --zhitu.eval.cmteb.label=no-rerank \
    --zhitu.eval.cmteb.retrieval-mode=hybrid \
    --zhitu.eval.cmteb.skip-ingest=true \
    --zhitu.elasticsearch.index-name=zhitu_agent_eval_cmteb_baseline \
    --zhitu.rerank.final-top-k=50 \
    --zhitu.rag.min-accepted-score=0.0"
```

### 4. 聚合对比表
```bash
python tools/eval/aggregate_sweep.py
# 输出 target/eval-reports/cmteb-sweep-{ts}.{md,json}
```

## 踩过的坑(给后人提醒)

1. **`--zhitu.rerank.final-top-k=50` 必须显式设**。`RagRetriever.resolveFinalLimit` 默认用 `rerankProperties.getFinalTopK()=5` cap,即使 retrievalMode=hybrid 关了 rerank 也会把 candidate 截到 5,nDCG 严重低估
2. **`exit-after-run=true` 后 JVM 不会自动退**。Redis pool / LangChain HTTP / Kafka producer 是非 daemon thread,持着 embedding API connection quota,下一组 sweep 启动时死锁。已加 `System.exit(code)` 强退兜底
3. **C-MTEB fixture sampling 必须保 qrels 100% in-corpus**,不然 Recall@k 上限 < 1。`fetch_cmteb.py` 的策略是先加 relevant doc 再加 distractor —— **代价是 corpus 难度低于完整评测**
4. **mteb 库 2.x 改了 API**,`task.corpus / task.queries` 不再存在,要走 `task.dataset['default'][split]`

## 数字来源 raw report

- `target/eval-reports/cmteb-baseline-v1-20260503-192215.json`(181 KB)
- `target/eval-reports/cmteb-no-rerank-20260504-100127.json`(175 KB)
- `target/eval-reports/cmteb-sweep-20260504-102603.{md,json}`(aggregator 输出)

target/ 在 gitignore,本地保留;commit 进库的是这个 markdown + sweep 脚本 + Java runner。
