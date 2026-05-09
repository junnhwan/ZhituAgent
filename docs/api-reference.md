# API 参考

## 基础信息

- 基础路径：`/api`
- 认证：无（演示环境）
- 响应格式：JSON

## 聊天 API

### 普通聊天

```
POST /api/chat
```

**请求体：**
```json
{
  "sessionId": "string",
  "userId": "string",
  "message": "string",
  "metadata": {}
}
```

**响应：**
```json
{
  "sessionId": "string",
  "answer": "string",
  "trace": {
    "path": "direct-answer | retrieve-then-answer | tool-then-answer",
    "retrievalHit": true,
    "toolUsed": false,
    "retrievalMode": "dense | hybrid-rerank | none",
    "contextStrategy": "recent-summary-facts",
    "latencyMs": 1234,
    "snippetCount": 3,
    "topSource": "document.pdf",
    "topScore": 0.95,
    "retrievedSnippets": [
      {
        "source": "document.pdf",
        "content": "...",
        "score": 0.95,
        "denseScore": 0.92,
        "rerankScore": 0.98
      }
    ],
    "factCount": 2,
    "inputTokenEstimate": 512,
    "outputTokenEstimate": 128
  }
}
```

### 流式聊天

```
POST /api/streamChat
```

**请求体：** 同普通聊天

**响应：** SSE 流，事件类型：

| 事件 | 说明 | 数据 |
|------|------|------|
| `start` | 开始 | `{ "sessionId": "..." }` |
| `token` | Token | `{ "content": "..." }` |
| `stage` | 阶段 | `{ "phase": "retrieving | calling-tool | generating", "detail": {} }` |
| `tool_start` | 工具开始 | `{ "toolCallId": "...", "name": "...", "args": {} }` |
| `tool_end` | 工具结束 | `{ "toolCallId": "...", "status": "success | error", "resultPreview": "..." }` |
| `tool_call_pending` | 待审批 | `{ "pendingId": "...", "toolName": "...", "arguments": {} }` |
| `complete` | 完成 | 完整 TraceInfo |
| `error` | 错误 | `{ "code": "...", "message": "..." }` |

## 告警分析 API

### 同步分析

```
POST /api/alert
```

**请求体：**
```json
{
  "alertName": "HighCPUUsage",
  "service": "payment-service",
  "severity": "high",
  "context": "..."
}
```

**响应：**
```json
{
  "traceId": "...",
  "sessionId": "...",
  "reportMarkdown": "## 根因\n...",
  "agentTrail": ["AlertTriageAgent", "LogQueryAgent", "ReportAgent"],
  "supervisorDecisions": [...],
  "executions": [...],
  "rounds": 3,
  "latencyMs": 55713
}
```

### 流式分析

```
POST /api/alert/stream
```

**响应：** SSE 流，事件类型同聊天 API

## 文件上传 API

### 上传文件

```
POST /api/files/upload
```

**请求：** `multipart/form-data`，字段 `file`

**响应：**
```json
{
  "uploadId": "d511f179a3aa440f",
  "sourceName": "document.pdf",
  "objectKey": "uploads/d511f179a3aa440f/document.pdf",
  "chunksIngested": -1  // -1 表示异步模式
}
```

### 查询状态

```
GET /api/files/status/{uploadId}
```

**响应：**
```json
{
  "uploadId": "d511f179a3aa440f",
  "uploaded": [],
  "missing": [],
  "complete": true,
  "parseStatus": "QUEUED | PARSING | INDEXED | FAILED"
}
```

### 分片上传

```
POST /api/files/chunk
```

**请求：** `multipart/form-data`，字段 `file`、`uploadId`、`chunkIndex`

### 合并分片

```
POST /api/files/merge
```

**请求体：**
```json
{
  "uploadId": "...",
  "totalChunks": 10,
  "sourceName": "document.pdf",
  "contentType": "application/pdf"
}
```

## 知识库 API

### 写入知识

```
POST /api/knowledge
```

**请求体：**
```json
{
  "question": "什么是 RAG？",
  "answer": "检索增强生成...",
  "sourceName": "ai-notes"
}
```

## 会话 API

### 创建会话

```
POST /api/sessions
```

**请求体：**
```json
{
  "userId": "demo-user",
  "title": "会话标题"
}
```

### 获取会话详情

```
GET /api/sessions/{sessionId}
```

**响应：**
```json
{
  "session": {
    "sessionId": "...",
    "userId": "...",
    "title": "...",
    "createdAt": "...",
    "updatedAt": "..."
  },
  "summary": "...",
  "messages": [...],
  "facts": ["fact1", "fact2"]
}
```

## 工具审批 API

### 查看待审批工具

```
GET /api/tool-calls/pending
```

### 批准工具调用

```
POST /api/tool-calls/{id}/approve
```

### 拒绝工具调用

```
POST /api/tool-calls/{id}/deny
```

## 健康检查

```
GET /actuator/health
```

**响应：**
```json
{
  "status": "UP"
}
```
