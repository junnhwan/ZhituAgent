# 部署速查 — 改了代码怎么最快上线

zhitu-agent-java 部署在 106.12.190.62（systemd + docker compose 中间件 + Spring Boot fat jar 内嵌 React 前端）。

首次部署因为下 Maven 依赖、安装 Java/Maven/Node、修权限花了几小时，**那些都是一次性成本**。后续部署单次 1-2 分钟，但**不同改动类型有不同最小操作**——硬跑全量 deploy 浪费时间，跳过该跑的步骤又会"看起来没生效"（最经典的：只 `systemctl restart` 不重 mvn package，前端 / yml 改动不会进 jar）。

照下面速查表 1 分钟决定怎么部署。

---

## 改动类型 × 最小操作

| 改的是 | 最小命令 | 实际耗时 | 备注 |
|---|---|---|---|
| **`.env`**（密码、host、API key） | `systemctl restart zhitu-agent` | ~10s | systemd `EnvironmentFile` 启动时读外部 .env，**不进 jar**。改完 restart 即生效。 |
| **`application*.yml`**（端口、actuator 暴露面） | `scripts/deploy.sh SKIP_FRONTEND=true` | ~70s | yml 在 `src/main/resources/`，**进 jar**。必须重 mvn package。 |
| **Java 后端** (`src/main/java/**`) | `scripts/deploy.sh SKIP_FRONTEND=true` | ~70s | 重 mvn package + restart。前端没动跳过省 30s。 |
| **前端** (`frontend/src/**`) | `scripts/deploy.sh` | ~120s | vite 写到 `src/main/resources/static/` → mvn 必须重 package 才会进 jar，**不能只 npm build 或只 restart**。 |
| **同时改 Java + 前端** | `scripts/deploy.sh` | ~120s | 默认完整流程。 |
| **本地 hotfix，没 push** | `scripts/deploy.sh SKIP_GIT=true` | 跟改动类型同 | 跳过 git pull，避开 dirty check 拦截。 |
| **`infra/cloud/docker-compose.yml`**（中间件配置） | `cd infra/cloud && docker compose up -d <service>` | ~5-30s | 应用层不重启。`--force-recreate` 重建容器，数据卷保留。 |
| **`scripts/install.sh` / `infra/systemd/`** | `sudo SKIP_NGINX=true scripts/install.sh` | ~5s | 重新渲染 systemd unit + nginx 站点。改完跑一次刷新 `/etc/systemd/system/`。 |

99% 的"改了代码"是**前 4 行**。日常就是 `scripts/deploy.sh [SKIP_FRONTEND=true]`。

---

## 时间分解（warm cache，首次后所有部署）

```
git pull              ~2s   (SSH 配好后)
npm ci + vite build   ~30-90s
mvn -o package        ~60-120s   ← 瓶颈,spring-boot-maven-plugin repackage
systemctl restart     ~7s
轮询 /actuator/health ~10-15s
─────────────────────────────
总计                  ~120s 完整 / ~70s 跳前端 / ~10s 仅 .env
```

**为什么 mvn 是瓶颈**：fat jar 含 LangChain4j + ES client + Tika + HanLP + Kafka，140MB 重新打包要一分钟左右。除非进开发模式（`mvn spring-boot:run` 不打 jar）否则没法绕。

---

## 几个易踩的坑

### 坑 1：改了 yml 想"快速生效"只 restart
**结果**：旧 jar 里的旧 yml 还在，改动没生效。
**对策**：yml 在 `src/main/resources/` → 进 jar → 必须重 mvn。**只有 `.env` 是外部文件，可以单独 restart**。

### 坑 2：改了前端只 `npm run build` 不重 mvn
**结果**：bundle 写进了 `src/main/resources/static/`（源码树），但 `target/*.jar` 没更新。restart 加载的是旧 jar 里的旧 bundle。
**对策**：前端改动一定走完整 `scripts/deploy.sh`。

### 坑 3：服务器有未提交修改时跑 deploy.sh
**结果**：脚本拒绝（`refusing to pull. either commit, stash, or set SKIP_GIT=true`）。这是设计的安全检查。
**对策**：要么 `git stash` / `git reset --hard HEAD`，要么 `SKIP_GIT=true scripts/deploy.sh`（前提：你确认不需要拉最新代码）。

### 坑 4：`scripts/deploy.sh: Permission denied`
**原因**：git stash/reset/checkout 偶尔会丢 +x 位（commit `a4b3f95` 之后 `100755` 永久写入 git index，新 clone 不会再遇到）。
**对策**：`chmod +x scripts/*.sh` 一次性补回。

### 坑 5：第一次跑 mvn 慢得像 5 年前
**原因**：Maven 默认走 repo.maven.apache.org，国内 60-200 KB/s。
**对策**：服务器 `~/.m2/settings.xml` 配阿里云 mirror。

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

### 坑 6：服务器重启后应用没自启
- `systemctl is-enabled zhitu-agent` 不是 `enabled` → `systemctl enable zhitu-agent`（install.sh 已做）。
- 之前手动 `docker compose stop` 过 → docker `restart: unless-stopped` 不会拉它们起 → `cd infra/cloud && docker compose start`。

### 坑 7：Spring Boot 启动 `Permission denied (.env)`
**原因**：`.env` 设成 `chmod 600 root:root`，但 `application.yml` 的 `spring.config.import: optional:file:.env` 让 Spring Boot 主进程（zhitu 用户跑）也要读它。
**对策**：`chown root:zhitu .env && chmod 640 .env`。`.env` 由 systemd 主进程（root）和 Spring Boot 子进程（zhitu）两条路径读取，权限要兼顾两边。

### 坑 8：Docker Hub 拉不动 (`registry-1.docker.io: context deadline exceeded`)
**对策**：`/etc/docker/daemon.json` 配国内 mirror。
```json
{"registry-mirrors": ["https://docker.1ms.run", "https://docker.m.daocloud.io"]}
```
重启 docker 会顺手重启所有容器，要权衡时机。

---

## 完整命令速查

```bash
# === 应用层 ===
systemctl start zhitu-agent              # 启
systemctl stop zhitu-agent               # 停
systemctl restart zhitu-agent            # 重启(改 .env 用这个就够)
systemctl status zhitu-agent             # 状态
journalctl -u zhitu-agent -f             # 实时日志
journalctl -u zhitu-agent --since today  # 今日日志

# === 中间件层 ===
cd /opt/zhitu-agent-java/infra/cloud
docker compose ps                        # 7 容器状态
docker compose stop                      # 全停(保留数据卷)
docker compose start                     # 全启
docker compose restart elasticsearch     # 重启单个

# === 部署流程 ===
cd /opt/zhitu-agent-java
scripts/deploy.sh                        # 完整: pull + npm + mvn + restart
scripts/deploy.sh SKIP_FRONTEND=true     # 只改后端: pull + mvn + restart
scripts/deploy.sh SKIP_GIT=true          # 本地 hotfix
scripts/deploy.sh SKIP_GIT=true SKIP_FRONTEND=true   # 网络挂了重试 mvn

# === 危险但偶尔需要 ===
docker compose down                      # ⚠️ 删容器,数据卷在,改 compose 后用
docker compose down -v                   # ❌ 永远别跑,删数据卷
git reset --hard origin/main             # 抹掉本地修改对齐远端
```

---

## 关键文件路径（出问题查这里）

- `scripts/deploy.sh` — 部署主脚本，所有 SKIP flag 在顶部
- `scripts/install.sh` — 一次性 root 安装，渲染 systemd / nginx 模板
- `infra/systemd/zhitu-agent.service` — systemd unit 模板，`__APP_DIR__` / `__APP_USER__` 是占位符
- `infra/cloud/docker-compose.yml` — 7 个中间件定义，prometheus 走 9091（避开 mihomo / portainer 占用 9090）
- `frontend/vite.config.ts` L11 — `outDir: ../src/main/resources/static` 是"前端进 jar"的关键
- `src/main/resources/application.yml` L5 — `spring.config.import: optional:file:.env` 让 Spring Boot 也读外部 .env（systemd 之外的另一条路径，这是坑 7 的根源）
- `/opt/zhitu-agent-java/.env` — 服务器上应用层 secrets，权限 `640 root:zhitu`
- `/opt/zhitu-agent-java/infra/cloud/.env` — 中间件密码，docker compose 启动时读

---

## 验证部署成功（每次 deploy 后跑一遍）

```bash
# 1. 服务在
systemctl status zhitu-agent --no-pager | head -5

# 2. 健康
curl -s http://127.0.0.1:8080/actuator/health     # {"status":"UP"}

# 3. 前端
curl -sI http://127.0.0.1:8080/ | head -3         # 200 OK + text/html

# 4. 关键启动信号
journalctl -u zhitu-agent --since "2 min ago" | grep "active stores"
# 期望: KnowledgeStore=ElasticsearchKnowledgeStore (nativeHybrid=true), Redis*, ...

# 5. 浏览器: http://106.12.190.62:8080/
```

5 条全过 = 部署成功。
