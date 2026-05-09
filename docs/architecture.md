# 架构设计

## 系统架构

```mermaid
graph TB
    subgraph Frontend["前端 (React + TypeScript)"]
        ChatPanel[聊天面板]
        SrePanel[SRE Demo]
        TracePanel[Trace 面板]
        FileUpload[文件上传]
    end

    subgraph Backend["后端 (Spring Boot + LangChain4j)"]
        ChatController[ChatController]
        AlertController[AlertController]
        FileController[FileUploadController]
        
        subgraph Orchestration["编排层"]
            AgentOrchestrator[AgentOrchestrator]
            MultiAgent[MultiAgentOrchestrator]
            AgentLoop[AgentLoop]
        end
        
        subgraph Context["上下文管理"]
            ContextManager[ContextManager]
            FactExtractor[FactExtractor]
            SummaryCompressor[SummaryCompressor]
        end
        
        subgraph RAG["RAG 检索"]
            RagRetriever[RagRetriever]
            SelfRAG[SelfRagOrchestrator]
            Reranker[Reranker]
        end
        
        subgraph Tools["工具层"]
            ToolRegistry[ToolRegistry]
            ToolExecutor[ToolCallExecutor]
            BuiltinTools[内置工具]
            McpAdapter[MCP 适配器]
        end
        
        subgraph Ingest["入库管线"]
            IngestService[IngestService]
            TikaParser[Tika 解析器]
            ChunkSplitter[分片器]
            Embedder[向量化]
        end
    end

    subgraph Storage["存储层"]
        ES[(Elasticsearch)]
        Redis[(Redis)]
        MinIO[(MinIO)]
        Kafka[(Kafka)]
    end

    subgraph External["外部服务"]
        LLM[LLM API]
        MCP[MCP Server]
    end

    ChatPanel --> ChatController
    SrePanel --> AlertController
    FileUpload --> FileController
    
    ChatController --> AgentOrchestrator
    AlertController --> MultiAgent
    FileController --> IngestService
    
    AgentOrchestrator --> Context
    AgentOrchestrator --> RAG
    AgentOrchestrator --> AgentLoop
    AgentLoop --> Tools
    
    Context --> Redis
    RAG --> ES
    Tools --> LLM
    McpAdapter --> MCP
    
    IngestService --> MinIO
    IngestService --> TikaParser
    TikaParser --> ChunkSplitter
    ChunkSplitter --> Embedder
    Embedder --> ES
    
    ChatController --> Kafka
    FileController --> Kafka
```

## 核心流程

### 1. RAG 检索流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant Chat as ChatController
    participant Orch as AgentOrchestrator
    participant RAG as RagRetriever
    participant ES as Elasticsearch
    participant Rerank as Reranker
    participant LLM as LLM

    User->>Chat: 发送消息
    Chat->>Orch: decide(message)
    Orch->>RAG: retrieve(message)
    RAG->>ES: KNN + BM25 hybrid search
    ES-->>RAG: 候选文档
    RAG->>Rerank: rerank(candidates)
    Rerank-->>RAG: 重排结果
    RAG-->>Orch: snippets
    Orch->>LLM: generate(message, context)
    LLM-->>Chat: answer
    Chat-->>User: 响应
```

### 2. 工具调用流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant Chat as ChatController
    participant Orch as AgentOrchestrator
    participant Loop as AgentLoop
    participant Exec as ToolCallExecutor
    participant Tool as 工具
    participant LLM as LLM

    User->>Chat: 发送消息
    Chat->>Orch: decide(message)
    Orch->>Loop: run(message)
    Loop->>LLM: generate(message, tools)
    LLM-->>Loop: tool_call
    Loop->>Exec: execute(tool_call)
    
    alt 需要审批
        Exec->>Chat: pending_approval
        Chat-->>User: 审批弹窗
        User->>Chat: 批准
        Chat->>Exec: approve(id)
    end
    
    Exec->>Tool: execute(args)
    Tool-->>Exec: result
    Exec-->>Loop: observation
    Loop->>LLM: generate(message, observation)
    LLM-->>Loop: answer
    Loop-->>Chat: answer
    Chat-->>User: 响应
```

### 3. 文件上传流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端
    participant API as FileUploadController
    participant MinIO as MinIO
    participant Kafka as Kafka
    participant Consumer as Consumer
    participant Tika as Tika
    participant ES as Elasticsearch

    User->>FE: 选择文件
    FE->>API: POST /api/files/upload
    API->>MinIO: 存储文件
    API->>Kafka: 发送事件
    API-->>FE: 202 + uploadId
    
    FE->>FE: 轮询状态
    
    Kafka->>Consumer: 消费事件
    Consumer->>MinIO: 读取文件
    Consumer->>Tika: 解析文件
    Tika-->>Consumer: 文本内容
    Consumer->>Consumer: 分片
    Consumer->>ES: 批量写入
    Consumer->>Consumer: 更新状态
    
    FE->>API: GET /api/files/status/{id}
    API-->>FE: { status: "INDEXED" }
```

### 4. Multi-Agent SRE 编排

```mermaid
sequenceDiagram
    participant User as 用户
    participant Alert as AlertController
    participant Super as Supervisor
    participant Triage as AlertTriageAgent
    participant Log as LogQueryAgent
    participant Report as ReportAgent

    User->>Alert: POST /api/alert
    Alert->>Super: 分析告警
    
    Super->>Triage: 路由到 Triage
    Triage->>Triage: 检索 runbook
    Triage-->>Super: 分析结果
    
    Super->>Log: 路由到 LogQuery
    Log->>Log: 查询日志/指标
    Log-->>Super: 查询结果
    
    Super->>Report: 路由到 Report
    Report->>Report: 生成报告
    Report-->>Alert: Markdown 报告
    
    Alert-->>User: 分析报告
```

## 数据流

### 上下文组装

```mermaid
graph LR
    subgraph Input["输入"]
        SystemPrompt[系统提示词]
        History[历史消息]
        Facts[用户事实]
        Evidence[RAG 证据]
        Current[当前问题]
    end
    
    subgraph Process["处理"]
        Summary[摘要压缩]
        Budget[Token 预算]
        Trim[动态裁剪]
    end
    
    subgraph Output["输出"]
        Context[最终上下文]
    end
    
    SystemPrompt --> Context
    History --> Summary
    Summary --> Budget
    Facts --> Budget
    Evidence --> Budget
    Current --> Context
    Budget --> Trim
    Trim --> Context
```

### Token 预算裁剪策略

| 优先级 | 策略 | 说明 |
|--------|------|------|
| 1 | 裁旧消息 | 保留最近 N 条 |
| 2 | 裁旧事实 | 保留最近 1 条 |
| 3 | 移除摘要 | 完全移除 |
| 4 | 压缩 evidence | 减半 |

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 19, TypeScript, Vite, SSE |
| 后端框架 | Java 21, Spring Boot 3.5, Maven |
| LLM 接入 | LangChain4j 1.1, OpenAI-compatible |
| 知识库 | Elasticsearch 8.10, IK 分词, dense_vector |
| 文件存储 | MinIO |
| 文件解析 | Apache Tika, HanLP |
| 异步队列 | Kafka 3.7 KRaft |
| 缓存/记忆 | Redis |
| 监控 | Micrometer, Prometheus |
| Agent 编排 | ReAct, Supervisor + Specialist |
| 工具扩展 | MCP (Model Context Protocol) |
