# SlowResponse Runbook

## 适用告警
- alertName: SlowResponse
- severity: medium

## 排查步骤
1. 查最近 5 分钟 p50 / p95 / p99 响应时间分布,确认是整体慢还是长尾慢
2. 查最近 5 分钟下游依赖调用耗时(payment / inventory / risk-control 等),定位是哪个下游变慢
3. 查应用 GC pause 是否突增,排除自身 stop-the-world 拖累
4. 查最近 30 分钟部署记录,排查是否新版本接口调用链路变长

## 处置建议
- **下游依赖变慢**:启用降级 / fallback(`feature.payment.fallback=true`),或临时调大下游超时
- **GC stop-the-world**:重启实例 + 排查内存泄漏
- **新版本回归**:回滚最近发布
- **整体慢**:扩容应用实例 + 检查是否 DB 慢查询连锁影响

## 监控引用
- p99 latency panel: dashboards/api/latency
- 下游依赖耗时: dashboards/api/downstream

## 责任人
- @oncall-trade
