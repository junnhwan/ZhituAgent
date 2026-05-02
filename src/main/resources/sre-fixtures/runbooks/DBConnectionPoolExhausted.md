# DBConnectionPoolExhausted Runbook

## 适用告警
- alertName: DBConnectionPoolExhausted
- severity: critical

## 排查步骤
1. 查 HikariCP active / idle / pending 指标,确认是池子不够还是连接长时间不释放
2. 查最近 5 分钟慢 SQL Top N,定位是哪条 SQL 或事务卡住
3. 查上游服务调用量,排查是否被上游突发流量打爆
4. 查数据库主从延迟、锁等待、CPU,排除数据库本身瓶颈
5. 查最近 30 分钟部署记录,排查是否新版本引入未关闭的事务 / 连接

## 处置建议
- **慢 SQL 占满连接池**:让 DBA 加索引或 kill 慢查询(`kill <process_id>`),配合限流上游入口
- **上游流量突增**:开启限流 + 触发上游扩容
- **代码忘关连接 / 事务**:回滚最近发布 + 排查未关闭的 try-with-resources / 事务边界
- **数据库本身瓶颈**:数据库扩容(读写分离 / 加只读实例),临时把 HikariCP maximumPoolSize 调高(治标)

## 监控引用
- HikariCP panel: dashboards/runtime/hikari
- DB latency panel: dashboards/db/latency

## 责任人
- @oncall-account
- @dba-oncall
