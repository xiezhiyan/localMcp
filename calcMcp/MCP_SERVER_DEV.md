# MCP Server 开发文档

## 1. 概述

MCP (Model Context Protocol) 是一种用于工具调用的协议标准，允许 AI 模型通过标准化的接口与外部工具进行交互。本文档基于 MCP 规范，详细介绍如何使用 Java 开发 MCP Server，包括 stdio 模式和 SSE 模式的实现。

## 2. 环境准备

### 2.1 开发环境
- JDK 8 或更高版本
- 纯 Java 标准库，无外部依赖
- 代码编辑器（如 VS Code、IntelliJ IDEA 等）

### 2.2 项目结构
```
localMcp/
├── MCP_SERVER_DEV.md           # 本文档
├── MyMcpServerStdio.java       # stdio 模式服务器
├── MyMcpServerSSE.java         # SSE 模式服务器
├── MyCalculatorTools.java      # 计算工具实现
├── *.class                     # 编译产物
└── .gitignore                  # Git 忽略文件
```

## 3. stdio 模式开发

### 3.1 核心原理

stdio 模式是 MCP 服务器的基础模式，通过标准输入/输出流进行通信：

1. **启动方式**：
   - 直接运行时：进程持续运行，循环读 stdin
   - 通过 SSE 服务器调用时：每次 SSE 连接创建一个新子进程，进程随 SSE 会话结束而销毁
2. **通信方式**：通过标准输入（stdin）接收 JSON-RPC 请求，通过标准输出（stdout）发送 JSON-RPC 响应
3. **进程控制**：标准错误流（stderr）输出调试信息，不参与 JSON-RPC 通信

### 3.2 实现代码

#### 3.2.1 主类结构

```java
public class MyMcpServerStdio {
    private static final String SERVER_NAME = "my-calc-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    public static void main(String[] args) {
        System.err.println("[DEBUG] MyMcpServerStdio started, PID=" + ProcessHandle.current().pid());
        System.err.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(System.out), true);

        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                try {
                    String response = handleRequest(line);
                    if (response != null) {
                        writer.println(response);
                        writer.flush();
                    }
                } catch (Exception e) {
                    String err = createError(null, -32700, "Parse error: " + e.getMessage());
                    writer.println(err);
                    writer.flush();
                }
            }
        } catch (IOException e) {
            System.exit(1);
        }
    }
}
```

#### 3.2.2 请求处理

```java
private static String handleRequest(String json) {
    // 解析 JSON-RPC 请求
    String method = extractValue(json, "method");   // 注意：方法名是 extractValue，不是 extractString
    Object id = extractId(json);
    String paramsStr = extractObject(json, "params");

    if (method == null) {
        return createError(id, -32600, "Invalid Request: missing method");
    }

    switch (method) {
        case "initialize":
            return handleInitialize(id, paramsStr);
        case "notifications/initialized":
            // 通知，不需要响应
            return null;
        case "tools/list":
            return handleToolsList(id);
        case "tools/call":
            return handleToolsCall(id, paramsStr);
        default:
            return createError(id, -32601, "Method not found: " + method);
    }
}
```

#### 3.2.3 核心方法实现

1. **初始化方法**：返回服务器信息和能力

```java
private static String handleInitialize(Object id, String paramsStr) {
    StringBuilder result = new StringBuilder();
    result.append("{");
    result.append("\"protocolVersion\":\"2024-11-05\",");
    result.append("\"serverInfo\":{\"name\":\"").append(SERVER_NAME).append("\",\"version\":\"").append(SERVER_VERSION).append("\"},");
    result.append("\"capabilities\":{\"tools\":{}}");
    result.append("}");

    return createResponse(id, result.toString());
}
```

2. **工具列表方法**：返回可用工具信息

```java
private static String handleToolsList(Object id) {
    StringBuilder result = new StringBuilder();
    result.append("{\"tools\":[");

    // add 工具
    result.append("{\"name\":\"add\",\"description\":\"自定义加法运算\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"第一个数\"},\"b\":{\"type\":\"integer\",\"description\":\"第二个数\"}},\"required\":[\"a\",\"b\"]}}");
    // 其他工具...

    result.append("]}");
    return createResponse(id, result.toString());
}
```

3. **工具调用方法**：执行具体的计算逻辑

```java
private static String handleToolsCall(Object id, String paramsStr) {
    // 解析参数
    String toolName = extractValue(paramsStr, "name");
    String argsStr = extractObject(paramsStr, "arguments");
    if (argsStr == null) {
        argsStr = "{}";
    }

    // 提取计算参数
    int a = extractInt(argsStr, "a");
    int b = extractInt(argsStr, "b");
    int result;
    String operationName;

    // 执行相应的计算
    switch (toolName) {
        case "add":
            result = MyCalculatorTools.add(a, b);
            operationName = "加法";
            break;
        // 其他操作...
    }

    // 返回结果
    String text = "自定义" + operationName + ": " + result;
    return createResponse(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}");
}
```

### 3.3 编译与运行

**编译**：
```bash
javac MyMcpServerStdio.java MyCalculatorTools.java
```

**测试**：
```bash
echo '{"jsonrpc":"2.0","id":"test","method":"initialize","params":{}}' | java MyMcpServerStdio
```

## 4. SSE 模式开发

### 4.1 核心原理

SSE (Server-Sent Events) 模式是 MCP 服务器的高级模式，通过 HTTP 服务器进行通信：

1. **启动方式**：作为独立的 HTTP 服务器运行，持续监听请求
2. **通信方式**：
   - GET /sse：建立 SSE 连接，获取会话 ID 和消息端点
   - POST /messages：发送 JSON-RPC 请求
   - 响应通过 SSE 连接推送
3. **生命周期**：服务器持续运行，管理多个会话，每个会话对应一个 stdio 子进程
4. **Session 清理**：服务器每 30 秒检查所有会话，超过 30 秒无活动的会话会被自动销毁

### 4.2 实现代码

#### 4.2.1 主类结构

```java
public class MyMcpServerSSE {
    private static final int PORT = 8080;
    private static final String MCP_SERVER_CLASS = "MyMcpServerStdio";
    private static final String MCP_SERVER_CWD = "/Users/xiezhiyan/code/study/localMcp";
    private static final String JAVA_BIN = "/usr/bin/java";

    // 会话存储
    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // 线程池
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        // 创建 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // 注册 SSE 端点
        server.createContext("/sse", exchange -> {
            // 处理 SSE 连接
        });

        // 注册消息端点
        server.createContext("/messages", exchange -> {
            // 处理 POST 请求
        });

        // 启动服务器
        server.setExecutor(executor);
        server.start();
    }
}
```

#### 4.2.2 SSE 连接处理

> ⚠️ 注意：设置 SSE 响应头和发送 200 状态码必须在发送任何响应体内容之前完成。

```java
server.createContext("/sse", exchange -> {
    if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.close();
        return;
    }

    // 生成会话 ID
    String sessionId = UUID.randomUUID().toString();

    // 1. 创建子进程
    ProcessBuilder pb = new ProcessBuilder(JAVA_BIN, MCP_SERVER_CLASS);
    pb.directory(new File(MCP_SERVER_CWD));
    pb.redirectErrorStream(false);
    Process childProcess = pb.start();

    // 2. 建立进程间通信
    BufferedReader childOut = new BufferedReader(
            new InputStreamReader(childProcess.getInputStream(), StandardCharsets.UTF_8));
    BufferedReader childErr = new BufferedReader(
            new InputStreamReader(childProcess.getErrorStream(), StandardCharsets.UTF_8));
    PrintWriter childIn = new PrintWriter(
            new OutputStreamWriter(childProcess.getOutputStream(), StandardCharsets.UTF_8), true);

    // 3. 创建会话
    Session session = new Session(sessionId, exchange, childProcess, childIn);
    sessions.put(sessionId, session);

    // 4. 处理子进程错误流
    executor.submit(() -> {
        try {
            String line;
            while ((line = childErr.readLine()) != null) {
                System.err.println("[child stderr] " + line);
            }
        } catch (Exception ignored) {}
    });

    // 5. 处理子进程输出流（通过 SSE 推送）
    executor.submit(() -> {
        try {
            String line;
            while ((line = childOut.readLine()) != null) {
                sendSSE(session, "message", line);
            }
        } catch (Exception e) {}
    });

    // 6. 【重要】必须先设置响应头并发送 200，再发送响应体
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
    exchange.sendResponseHeaders(200, 0);  // 必须先于任何响应体写入

    // 7. 发送 endpoint 事件（在 sendResponseHeaders 之后）
    sendSSE(session, "endpoint", "/messages?sessionId=" + sessionId);

    // 8. 心跳保活
    Thread heartbeat = new Thread(() -> {
        while (session.alive.get()) {
            try {
                Thread.sleep(25000);
                sendSSE(session, "ping", "");
            } catch (InterruptedException ignored) {}
        }
    });
    heartbeat.setDaemon(true);
    heartbeat.start();
});
```

#### 4.2.3 消息处理

```java
server.createContext("/messages", exchange -> {
    if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
        return;
    }

    // 解析 sessionId
    String query = exchange.getRequestURI().getQuery();
    String sessionId = parseQueryParam(query, "sessionId");

    // 验证 sessionId
    if (sessionId == null || !sessions.containsKey(sessionId)) {
        String body = "{\"error\":\"Invalid or missing sessionId\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return;
    }

    Session session = sessions.get(sessionId);

    // 读取请求体
    String body = readBody(exchange);

    // 立即返回 202 Accepted（MCP 响应将通过 SSE 推送）
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    String ackBody = "{\"accepted\":true}";
    byte[] ackBytes = ackBody.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(202, ackBytes.length);
    exchange.getResponseBody().write(ackBytes);
    exchange.close();

    // 将消息发送给子进程
    // 注意：PrintWriter 创建时已设置 autoFlush=true，flush() 调用是冗余的但无害
    synchronized (session) {
        if (session.childIn != null) {
            session.childIn.println(body);
            session.childIn.flush();
        }
    }
});
```

### 4.3 编译与运行

**编译**：
```bash
javac MyMcpServerSSE.java MyMcpServerStdio.java MyCalculatorTools.java
```

**运行**：
```bash
java MyMcpServerSSE
```

## 5. 两种模式对比

### 5.1 核心区别

| 特性 | stdio 模式 | SSE 模式 |
|------|------------|----------|
| 启动方式 | 持续运行的进程（直接使用）<br>或每次 SSE 连接新建子进程（通过 SSE 调用） | 持续运行的 HTTP 服务器 |
| 通信方式 | 标准输入/输出流 | HTTP + SSE |
| 会话管理 | 直接使用时无状态；通过 SSE 调用时有状态 | 有状态，管理多个会话 |
| 资源消耗 | 直接使用时稳定；通过 SSE 调用时进程随会话创建/销毁 | 资源持续占用，但更高效 |
| 响应速度 | 直接使用时无启动开销；通过 SSE 调用时每次有新进程开销 | 连接建立后响应更快（进程已就绪） |
| 适用场景 | 简单工具，调用频率低 | 复杂工具，调用频率高 |

### 5.2 开发关系

1. **依赖关系**：SSE 模式依赖 stdio 模式
   - SSE 服务器作为前端，处理 HTTP 请求和 SSE 连接
   - stdio 服务器作为后端，处理具体的 JSON-RPC 请求

2. **代码复用**：
   - stdio 服务器实现核心业务逻辑
   - SSE 服务器处理通信和会话管理

3. **部署选择**：
   - 简单场景：直接使用 stdio 模式
   - 生产场景：使用 SSE 模式提供更好的性能和可靠性

## 6. 在 QClaw（龙虾）上的部署与应用

### 6.1 配置方式

QClaw 的 MCP 配置直接写在 `openclaw.json` 的 `mcp.servers` 节点下，不是独立的配置文件，也不需要 `--mcp-config` 参数。

编辑配置的方法：
```bash
# 通过命令行补丁更新
openclaw config.patch mcp.servers.my-calc '{"command":"/usr/bin/java","args":["MyMcpServerStdio"],"cwd":"/path/to/localMcp"}'
```

或者直接编辑 `~/.qclaw/openclaw.json`，在根级别添加 `mcp` 节点。

### 6.2 stdio 模式部署

**配置文件结构**：
```json
{
  "mcp": {
    "servers": {
      "my-calc": {
        "command": "/usr/bin/java",
        "args": ["MyMcpServerStdio"],
        "cwd": "/Users/xiezhiyan/code/study/localMcp"
      }
    }
  }
}
```

**字段说明**：
| 字段 | 说明 |
|------|------|
| `command` | 可执行文件路径，必须是完整绝对路径 |
| `args` | 命令行参数数组，`MyMcpServerStdio` 是主类名（不是 JAR 文件） |
| `cwd` | 工作目录，**必须设置**，否则找不到 `.class` 文件 |

### 6.3 SSE 模式部署

**配置文件结构**：
```json
{
  "mcp": {
    "servers": {
      "my-calc-sse": {
        "url": "http://localhost:8080/sse"
      }
    }
  }
}
```

**字段说明**：
| 字段 | 说明 |
|------|------|
| `url` | SSE 服务器的 HTTP 地址 |

**部署步骤**：
1. **先启动 SSE 服务器**（必须先启动，QClaw 连接时服务器必须在线）：
   ```bash
   cd /Users/xiezhiyan/code/study/localMcp
   java MyMcpServerSSE
   ```
2. **再配置 QClaw**（同上，通过 `config.patch` 或直接编辑配置文件添加 `url` 字段）
3. **重启 QClaw Gateway** 使配置生效：
   ```bash
   openclaw gateway restart
   ```

### 6.4 应用示例

#### 6.4.1 工具调用示例

**用户输入**：
```
计算 123 + 456
```

**AI 工具调用**：
```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "tools/call",
  "params": {
    "name": "add",
    "arguments": {
      "a": 123,
      "b": 456
    }
  }
}
```

**工具响应**：
```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "自定义加法: 580"
      }
    ]
  }
}
```

**AI 回复**：
```
123 + 456 的计算结果是 580。
```

> ⚠️ **注意**：本示例中 `add` 方法的实现为 `a + b + 1`（见 `MyCalculatorTools.java`），
> 因此 123 + 456 = 580 是正确的预期结果，而非标准加法的 579。

## 7. 监控与维护

### 7.1 日志查看
- **stdio 模式**：查看 QClaw Gateway 的日志输出，子进程的 stderr 信息以 `[DEBUG]` 前缀输出
- **SSE 模式**：查看 SSE 服务器控制台输出，包含请求日志和 session 清理日志

### 7.2 性能监控
- SSE 模式：监控 HTTP 服务器的连接数和响应时间
- 使用 `curl` 测试 SSE 服务器状态：
  ```bash
  curl -v http://localhost:8080/sse
  ```

### 7.3 故障排查

1. **SSE 连接失败**：检查端口 8080 是否被占用
   ```bash
   lsof -i :8080
   ```

2. **stdio 进程找不到 class**：
   - 确认 `cwd` 配置指向正确的目录
   - 确认 `.class` 文件存在（检查是否需要重新编译）

3. **Session 意外断开**：SSE 服务器每 30 秒会自动清理超过 30 秒无活动的 session，
   如果调用间隔较长，建议在业务逻辑中定期发送心跳或使用 stdio 模式

4. **Java 环境问题**：确认 `/usr/bin/java` 路径正确
   ```bash
   which java
   ```

## 8. 常见问题

**Q：SSE 模式下，每次工具调用都会创建新进程吗？**
A：不是。每个 SSE 会话（session）对应一个 stdio 子进程，同一会话内的多次工具调用复用同一个进程。会话超时（30 秒无活动）后进程被销毁。

**Q：stdio 模式支持多会话吗？**
A：直接使用 stdio 模式时，进程是单会话的，没有内置的会话管理。如果需要多会话，需要自行实现类似 SSE 的进程管理。

**Q：Java 类名和 JAR 包怎么选择？**
A：直接运行 `.class` 更简单，无需打包。打包成 JAR 只有在需要分发给其他用户或部署到非开发环境时才推荐。

**Q：协议版本用哪个？**
A：本实现使用 `2024-11-05`，这是当前主流的 MCP 协议版本。

## 9. 总结

MCP 服务器的两种模式各有优缺点：

- **stdio 模式**：简单易实现，适合轻量级工具和低频调用场景，QClaw 配置只需一行
- **SSE 模式**：性能更好，适合复杂工具和高频调用场景，支持多会话管理

在实际应用中，应根据具体需求选择合适的模式。对于生产环境，推荐使用 SSE 模式以获得更好的性能和可靠性。

通过本文档的指导，您可以快速开发和部署符合 MCP 规范的服务器，为 AI 模型提供强大的工具调用能力。
