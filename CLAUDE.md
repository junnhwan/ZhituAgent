# CLAUDE.md

@AGENTS.md

## Claude Code Notes

读一次本文件后应能知道去哪里查事实、怎么验证、哪些边界不能碰。共享规则由上面的 `@AGENTS.md` 导入；当前项目快照继续看 `docs/agent-handoff.md`。

## Read Order

1. `README.md` — 当前对外展示口径和功能总览。
2. `docs/agent-handoff.md` — 当前项目快照、代码地图、验证记录和交接细节。
3. `docs/quick-start.md` / `docs/architecture.md` / `docs/features.md` / `docs/api-reference.md` / `docs/demo-guide.md` — 开发、架构、功能、API 和演示。
4. `docs/guide/` — 专题学习材料。
5. `docs/archive/optimize-progress.md` — 历史工程流水，只读参考；不要再当实时进度文件维护。

## Claude-Specific Workflow

- 做非平凡修改前先用 1-2 句话说明改什么、为什么这样选，再动手。
- 关键决策点要讲 trade-off，尤其是指标定义、协议选择、错误恢复策略和简历叙事边界。
- 用户明确说是面试准备或不涉及代码修改时，保持解释/演练/笔记模式，不主动改代码。
- 修改后至少检查路径和链接；涉及代码时再跑对应后端/前端测试。
