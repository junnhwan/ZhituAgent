# HighMemoryUsage Runbook

## 适用告警
- alertName: HighMemoryUsage
- severity: high

## 排查步骤
1. 查最近 30 分钟 heap usage 趋势:稳步爬升通常是内存泄漏,锯齿状是 GC 正常
2. 查 GC 日志频率:minor GC 频率突增 + old-gen 持续增长 = 强烈泄漏信号
3. 查最近 10 分钟错误日志,确认是否大对象分配(集合无界增长 / 缓存无淘汰)
4. 查最近 30 分钟部署记录,排查是否新代码引入泄漏点

## 处置建议
- **明确内存泄漏**:重启实例临时止血,**重启前抓 heap dump**(`jmap -dump:live,format=b,file=heap.hprof <pid>`),交后端团队分析
- **GC 调优窗口**:如果泄漏点不明,临时调大 -Xmx 或缩短 -XX:MaxGCPauseMillis 拖延时间(治标不治本)
- **新版本回归**:回滚最近一次发布
- **流量层降级**:对关联接口做熔断,减少请求触发率

## 监控引用
- Memory / heap panel: dashboards/runtime/heap
- GC pause panel: dashboards/runtime/gc

## 责任人
- @oncall-trade
- @sre-runtime
