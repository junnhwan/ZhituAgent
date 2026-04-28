# Zhitu Agent Java 后续优化计划

日期：2026-04-28

## 0. 最新进展补充（2026-04-28 晚）

`Task 1` 已完成第一轮真实评估收口，新增结论如下：

- 已把 baseline eval fixture 扩成 `6` 条 case
  - 新增：
    - `rag-rerank-001`
    - `rag-hybrid-001`
- 已产出真实报告：
  - `target/eval-reports/baseline-comparison-20260428-185440.json`
- 由于 `gpt-5.4` 在真实评估时出现 `model_cooldown`
  - 本轮完整报告是使用同网关的 `gpt-5.4-mini` 跑出的

真实报告摘要：

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

当前最重要的解释不是“哪种模式全都更强”，而是：

- `rag-hybrid-001` 已经在真实链路下成功体现：
  - `dense` 更偏 `phase-one-vague-a`
  - `hybrid-rerank` 能翻到 `phase-one-keyword-target`
- 但 `rag-rerank-001` 没有像 mock 里那样翻到 `phase-one-precise`
  - 说明真实 rerank 收益并不稳定
  - 后续优化不能只靠 stub 测试判断

因此，`Task 1` 的“真实评估跑通并得到更有区分度的数据”已经完成第一版；
接下来主线应自然切到 `Task 2`，优先攻克真实 rerank / hybrid 的质量稳定性。

## 0.1 最新进展补充（2026-04-28 深夜）

`Task 2` 已完成第一轮检索质量优化，核心动作不是继续调 recall，而是对 rerank 结果补了一层轻量本地校准：

- 新增：
  - `src/main/java/com/zhituagent/rag/RerankResultCalibrator.java`
- 接入点：
  - `src/main/java/com/zhituagent/rag/RagRetriever.java`

当前校准信号包括：

- 枚举型回答结构
- 英文 / acronym 查询词覆盖
- “明确 / 包含 / 列出”类显式表达

这层校准的目标不是推翻上游 rerank，而是只在分数很接近时，帮助中文 case 更稳定地把“更具体、更显式”的答案排到前面。

真实收益已经通过新报告验证：

- 报告：
  - `target/eval-reports/baseline-comparison-20260428-192645.json`
- 与上一个完整报告 `baseline-comparison-20260428-185440.json` 对比：
  - `dense-rerank`
    - `passedCases`：`4/6 -> 6/6`
    - `topSourceExpectationHitRate`：`0.0 -> 1.0`
  - `hybrid-rerank`
    - `passedCases`：`5/6 -> 6/6`
    - `topSourceExpectationHitRate`：`0.5 -> 1.0`

最关键的两个 case：

- `rag-rerank-001`
  - 旧结果：rerank 仍偏 `phase-one-vague`
  - 新结果：`dense-rerank` / `hybrid-rerank` 都稳定翻到 `phase-one-precise`
- `rag-hybrid-001`
  - 新结果继续稳定保持：
    - `dense` -> `phase-one-vague-a`
    - `dense-rerank` / `hybrid-rerank` -> `phase-one-keyword-target`

需要额外说明：

- `gpt-5.4`
  - 会命中 `model_cooldown`
- `gpt-5.4-mini`
  - 这轮也在运行中途触发 `model_cooldown`
- 当前完整新报告最终是用：
  - `gpt-5.2`
  跑出来的

所以，接下来除了继续扩充 case 和观察副作用，还需要考虑：

- 评估运行时的模型降级策略
- 或者让 eval runner 能在 provider 冷却时更优雅地续跑 / 断点恢复

## 1. 当前判断

当前仓库已经完成两层交付：

- 第一阶段 `Task 1` 到 `Task 4` 已全部完成，并已提交 `f5b66c3`
- 第二阶段 `Task 1` 到 `Task 4` 第一版已全部完成，并已提交
  - `af79d94`
  - `3ccb902`

当前系统已经具备：

- 真实 OpenAI 兼容对话模型接入
- Redis 会话与短期记忆持久化
- pgvector dense retrieval
- `dense-rerank`
- `hybrid retrieval`
- 中文友好的文档切分
- SSE 对话
- 内置 ToolUse
- `/actuator/health` 与 `/actuator/prometheus`
- Redis 记忆压缩的最小并发保护
- baseline eval 运行器第一版

因此，后续重点不再是“把功能补齐到能跑”，而是把系统继续升级成：

- 可量化对比
- 可持续调优
- 可稳定展示
- 可沉淀为简历数据

## 2. 主要差距

虽然主链已经打通，但当前仍有几类明显差距：

- 还没有基于真实模型和真实检索模式跑出正式的 A/B 对比报告
- 记忆机制仍然停留在最小可用版 `summary + recent messages`
- 上下文策略还没有按 token budget、重要性、主题做更细的裁剪
- 可观测性已有 metrics 端点，但还没有 dashboard、错误分类、长期数据沉淀
- 工具生态、MCP、多 Agent、自动热加载等扩展能力仍然属于后续项

## 3. 优化目标

本轮优化建议仍然控制为 4 个主任务，继续保持“少量阶段、少量 commit”的节奏。

### Task 1：真实评估与 A/B 对比报告

目标：

- 用真实 LLM、真实 embedding、真实 rerank、真实 pgvector 跑一轮可复用评估
- 形成 dense / dense-rerank / hybrid-rerank 的正式对比数据

交付内容：

- 扩充 `baseline-chat-cases.jsonl`
- 让评估结果能清晰区分不同 retrieval mode
- 产出可归档的评估报告文件
- 固定核心口径：
  - 路由命中率
  - 检索命中率
  - tool 命中率
  - 平均耗时、P50、P90
  - 输入 / 输出 token 估算

验收标准：

- 能稳定重复运行评估
- 同一批 case 能得到多模式对比结果
- 结果可直接引用到项目复盘或简历材料

当前状态补充：

- 第一版已完成
- 已经拿到真实评估报告
- 但“可稳定重复运行”这一项还需要继续增强：
  - 对话模型 `gpt-5.4` 会出现上游 `model_cooldown`
  - 当前更稳定的跑法是临时切到 `gpt-5.4-mini`
- 另外，真实报告已证明：
  - hybrid 的收益点已经出现
  - rerank 的稳定收益还未站稳

### Task 2：检索质量深化优化

目标：

- 在现有 `dense-rerank` 与 `hybrid retrieval` 基础上，继续把检索质量调稳

交付内容：

- 扩充中文 query preprocessing 规则
- 调整 dense / lexical 召回数量
- 调整 hybrid merge 策略与权重
- 调整 rerank recall topK、final topK、min score
- 沉淀一组默认推荐参数
- 补一轮“真实 rerank 失败样例”诊断：
  - 为什么 `rag-rerank-001` 没翻到 `phase-one-precise`
  - 为什么 `dense-rerank` 下 `rag-hybrid-001` 会被真实 rerank 拉向 `phase-one-keyword-target`

验收标准：

- 对关键中文 case，`hybrid-rerank` 相比 dense-only 有稳定收益
- 参数切换有明确理由，而不是只凭感觉调整

### Task 3：记忆与上下文策略深化

目标：

- 从“最小可用记忆”升级到“更可控、更适合多轮对话”的上下文与记忆结构

交付内容：

- 将记忆拆成更明确的层次：
  - recent messages
  - session summary
  - user facts / long-term memory（如先做最小版）
- 优化 summary 触发时机
- 让上下文构建更贴近 token budget
- 降低无关历史消息对当前回答的干扰

验收标准：

- 多轮对话下的上下文长度更稳定
- 记忆命中行为可以被 trace 或日志观测
- 与当前实现相比，长会话质量不明显退化

### Task 4：可观测性沉淀与展示资产

目标：

- 把现在已有的 metrics 与 trace 能力，升级成可长期复盘和可展示的资产

交付内容：

- 补一版 Prometheus 指标说明文档
- 补一版 dashboard 设计或导入说明
- 增加错误分类与失败原因沉淀
- 把评估结果与指标结果整理成统一报告模板

验收标准：

- 至少能回答三类问题：
  - 哪种 retrieval mode 更好
  - 哪类问题最容易 miss
  - 优化是否带来了真实收益

## 4. 暂不进入主线的事项

以下方向有价值，但不建议现在插队到主线：

- MCP 协议集成
- 多 Agent 协作抽象
- 知识库自动热加载
- 邮件工具等额外 Tool 扩展
- 前端大改版

这些更适合在当前“评估、检索、记忆、可观测性”四件事站稳之后再做。

## 5. 推荐执行顺序

优先顺序建议固定为：

1. `Task 1`：先拿到真实评估数据
2. `Task 2`：再做检索质量优化
3. `Task 3`：随后深化记忆与上下文
4. `Task 4`：最后把指标与结果沉淀成展示资产

原因很简单：

- 没有真实评估，后面的优化缺少统一标尺
- 检索质量是当前最容易做出明显收益的模块
- 记忆与上下文优化更依赖前面沉淀下来的评测口径
- 可观测性展示适合在核心优化路径稳定后收口

## 6. 对前端协作的边界说明

当前仓库仍以后端为主，前端不由本 agent 负责维护。

但从协作角度，前端后续最值得优先跟的不是“做更多视觉”，而是：

- 正确恢复会话与历史消息
- 接好知识写入入口，能真实触发 RAG
- 把 `trace` 字段真实展示出来，而不是使用假状态
- 在 SSE 中正确处理 `start` / `token` / `complete` / `error`

前端是否扩展更多能力，应以后端主链的真实接口能力为准，而不是先做静态占位。

## 7. 计划结论

接下来这轮开发的核心，不是继续横向堆更多模块，而是围绕已有链路做纵向优化：

- 先量化
- 再调优
- 再深化记忆
- 最后沉淀展示

如果只能先做一件事，那就是：先用真实链路跑出正式评估报告。
