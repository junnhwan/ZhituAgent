# HighCPUUsage Runbook

## 适用告警
- alertName: HighCPUUsage
- severity: high

## 排查步骤
1. 查最近 30 分钟 CPU 与 QPS 趋势,确认是否突发流量(QPS 是否大幅高于 baseline)
2. 查最近 10 分钟慢请求日志,定位耗时最高的 handler / SQL
3. 查最近 30 分钟部署记录,排查是否新版本引入了热点代码
4. 查 GC 日志,排除 GC 风暴拖高 CPU 的可能

## 处置建议
- **突发流量**:触发 HPA 自动扩容(`kubectl scale deployment {service} --replicas=+2`),或开启限流降级
- **新版本回归**:回滚最近一次发布(`kubectl rollout undo deployment/{service}`)
- **代码热点**(死循环 / 正则 ReDoS / 序列化炸):重启实例 + 抓 jstack profile,定位栈帧
- **GC 风暴**:重启实例 + dump heap 后续分析

## 监控引用
- CPU usage panel: dashboards/runtime/cpu
- QPS panel: dashboards/traffic/qps

## 责任人
- @oncall-trade
- @sre-runtime
