# 快速启动

## 环境要求

- Java 21+
- Maven 3.8+
- Docker（用于中间件）

## 本地开发

### 1. 启动中间件

```bash
cd infra/local
docker compose up -d
```

启动的容器：
- Elasticsearch 8.10.4（带 IK 分词插件）
- Redis
- MinIO
- Kafka 3.7 KRaft

### 2. 配置环境变量

复制 `.env.example` 为 `.env`，填入必要的配置：

```bash
cp .env.example .env
```

主要配置项：
- `OPENAI_API_KEY`：LLM API Key
- `OPENAI_BASE_URL`：LLM API 地址
- `ZHITU_MINIO_ENABLED`：是否启用 MinIO（文件上传功能）

### 3. 启动后端

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动日志会打印：
```
ZhituAgent active stores: KnowledgeStore=ElasticsearchKnowledgeStore (nativeHybrid=true), ...
```

如果看到 `InMemoryKnowledgeStore`，说明 `.env` 没生效或 ES 没开。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 http://localhost:5173

## 云端部署

### 1. 部署中间件

```bash
ssh codex-server
cd /opt/zhitu-agent-java/infra/cloud
cp .env.example .env  # 填入强密码
docker compose up -d
```

### 2. 构建并部署后端

```bash
cd /opt/zhitu-agent-java
mvn clean package -DskipTests
systemctl restart zhitu-agent
```

### 3. 构建前端

```bash
cd /opt/zhitu-agent-java/frontend
npm install
npm run build
```

前端静态资源会自动打包到 `src/main/resources/static/`。

## 常用命令

### 后端

```bash
# 运行单测
mvn test

# 运行单测 + IT（需要 Docker）
mvn verify

# 启动服务
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 启用 Kafka 异步管线
ZHITU_KAFKA_ENABLED=true ZHITU_KAFKA_BOOTSTRAP_SERVERS=106.12.190.62:9092 \
  mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 前端

```bash
cd frontend

# 开发模式
npm run dev

# 构建
npm run build

# 类型检查
npm run build  # tsc -b && vite build
```

### Docker

```bash
cd infra/cloud  # 或 infra/local

# 启动
docker compose up -d

# 停止
docker compose down

# 停止并删除数据
docker compose down -v

# 查看日志
docker compose logs -f [service_name]
```

### 服务管理

```bash
# 启动服务
systemctl start zhitu-agent

# 停止服务
systemctl stop zhitu-agent

# 重启服务
systemctl restart zhitu-agent

# 查看状态
systemctl status zhitu-agent

# 查看日志
journalctl -u zhitu-agent -f
journalctl -u zhitu-agent --since "10 min ago"
```
