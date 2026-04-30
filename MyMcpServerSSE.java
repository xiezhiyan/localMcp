import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * MCP SSE Server - 纯 Java 标准库，无外部依赖
 *
 * 协议 (参考 MCP SDK SSEServerTransport):
 * - GET  /sse      → 建立 SSE 流，发送 endpoint 事件 (告诉客户端 POST 端点)
 * - POST /messages → 接收 JSON-RPC 消息，fork 子进程处理，响应通过 SSE 推送
 *
 * 端口: 8080
 */
public class MyMcpServerSSE {

    private static final int PORT = 1003;
    private static final String MCP_SERVER_CLASS = "MyMcpServerStdio";
    // 部署到远端时，修改以下两个路径为远端服务器实际路径
    private static final String MCP_SERVER_CWD = System.getProperty("mcp.cwd", System.getProperty("user.dir"));
    private static final String JAVA_BIN = System.getProperty("mcp.java", "java");
    // 绑定地址：默认 0.0.0.0（支持远端访问），可通过 -Dmcp.host 覆盖
    private static final String BIND_HOST = System.getProperty("mcp.host", "0.0.0.0");

    // 会话存储：使用 ConcurrentHashMap 线程安全地存储 sessionId 到 Session 的映射
    // ConcurrentHashMap 是 Java 并发包提供的线程安全哈希表，适合多线程环境
    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // 线程池：用于处理子进程的输出和错误流，以及 HTTP 请求
    // Executors.newCachedThreadPool() 创建一个可缓存的线程池，线程数可根据需要动态调整
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        // 打印启动配置信息
        System.out.println("========================================");
        System.out.println("  MyMcpServerSSE starting...");
        System.out.println("  PORT:            " + PORT);
        System.out.println("  BIND_HOST:       " + BIND_HOST);
        System.out.println("  MCP_SERVER_CWD:  " + MCP_SERVER_CWD);
        System.out.println("  JAVA_BIN:        " + JAVA_BIN);
        System.out.println("  MCP_SERVER_CLASS:" + MCP_SERVER_CLASS);
        System.out.println("========================================");

        // 创建 HTTP 服务器：使用 com.sun.net.httpserver.HttpServer 类
        // 这是 Java 标准库提供的轻量级 HTTP 服务器实现，无需外部依赖
        // 参数1: 绑定的地址和端口，参数2: 队列长度（0表示无限制）
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, PORT), 0);

        // ── GET /sse ───────────────────────────────────────────────────────
        // 创建 HTTP 上下文：处理 GET /sse 请求
        // createContext 方法用于注册一个路径处理器，当客户端访问该路径时执行指定的处理逻辑
        server.createContext("/sse", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.close();
                return;
            }

            // 生成唯一会话ID：使用 UUID 类生成全局唯一标识符
            String sessionId = UUID.randomUUID().toString();

            // 1. 创建子进程（进程创建的关键代码）
            // -----------------------------------------------------------------
            // ProcessBuilder 用于创建和配置子进程
            // 参数：命令及其参数，这里是运行 Java 命令执行 MyMcpServerStdio 类
            ProcessBuilder pb = new ProcessBuilder(JAVA_BIN, MCP_SERVER_CLASS);
            pb.directory(new File(MCP_SERVER_CWD));  // 设置子进程工作目录
            pb.redirectErrorStream(false);  // 分离标准错误流，便于单独处理
            Process childProcess;
            try {
                childProcess = pb.start();  // 启动子进程，返回 Process 对象用于控制和监控
            } catch (IOException e) {
                exchange.close();
                return;
            }

            // 2. 建立进程间通信通道（进程通信的关键代码）
            // -----------------------------------------------------------------
            // 子进程的标准输出流（子进程 → 父进程）：用于接收子进程的响应
            BufferedReader childOut = new BufferedReader(
                    new InputStreamReader(childProcess.getInputStream(), StandardCharsets.UTF_8));
            // 子进程的标准错误流（子进程 → 父进程，用于日志）：用于接收子进程的错误信息
            BufferedReader childErr = new BufferedReader(
                    new InputStreamReader(childProcess.getErrorStream(), StandardCharsets.UTF_8));
            // 子进程的标准输入流（父进程 → 子进程）：用于向子进程发送请求
            PrintWriter childIn = new PrintWriter(
                    new OutputStreamWriter(childProcess.getOutputStream(), StandardCharsets.UTF_8), true);

            // 3. 创建会话并存储
            // Session 类封装了会话相关的所有资源：HTTP 交换对象、子进程、输入流等
            Session session = new Session(sessionId, exchange, childProcess, childIn);
            sessions.put(sessionId, session);  // 将会话存储到线程安全的 ConcurrentHashMap 中

            // 4. 处理子进程的标准错误流（日志线程）
            // executor.submit() 提交一个任务到线程池执行
            executor.submit(() -> {
                try {
                    String line;
                    while ((line = childErr.readLine()) != null) {
                        System.err.println("[child stderr] " + line);
                    }
                } catch (Exception ignored) {}
            });

            // 5. 处理子进程的标准输出流（响应推送线程）
            // -----------------------------------------------------------------
            // 子进程的输出（JSON-RPC 响应）通过 SSE 推送给客户端
            executor.submit(() -> {
                try {
                    String line;
                    while ((line = childOut.readLine()) != null) {
                        // 读取到子进程的响应，通过 SSE 推送给客户端
                        sendSSE(session, "message", line);
                    }
                } catch (Exception e) {
                    // 子进程结束或 SSE 连接断开
                } finally {
                    try {
                        // 子进程结束时，发送会话结束的错误消息
                        sendSSE(session, "message", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Server session ended\"}}");
                    } catch (Exception ignored) {}
                }
            });

            // 6. 设置 SSE 响应头
            // HttpExchange 代表一次 HTTP 交换，包含请求和响应信息
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");  // SSE 内容类型
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");  // 禁止缓存
            exchange.getResponseHeaders().set("Connection", "keep-alive");  // 保持连接
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");  // 禁止代理缓冲
            try {
                // 发送响应头，参数2为响应体长度，0表示动态长度
                exchange.sendResponseHeaders(200, 0);
            } catch (IOException e) {
                cleanup(sessionId);
                return;
            }

            // 7. 发送 endpoint 事件，告知客户端 POST 端点（含 sessionId）
            sendSSE(session, "endpoint", "/messages?sessionId=" + sessionId);

            // 8. 心跳保活（每 25s 发送一次 ping）
            final AtomicBoolean alive = session.alive;  // AtomicBoolean 用于线程安全的布尔值操作
            Thread heartbeat = new Thread(() -> {
                while (alive.get()) {
                    try {
                        Thread.sleep(25000);  // 每 25 秒发送一次心跳
                        if (alive.get()) {
                            sendSSE(session, "ping", "");
                        }
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            });
            heartbeat.setDaemon(true);  // 设置为守护线程，主线程结束时自动结束
            heartbeat.start();
        });

        // ── POST /messages ─────────────────────────────────────────────────
        // 创建 HTTP 上下文：处理 POST /messages 请求
        server.createContext("/messages", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);  // 405 方法不允许
                exchange.close();
                return;
            }

            // 解析 URL 查询参数，获取 sessionId
            String query = exchange.getRequestURI().getQuery();
            String sessionId = parseQueryParam(query, "sessionId");

            // 验证 sessionId 是否有效
            if (sessionId == null || !sessions.containsKey(sessionId)) {
                String body = "{\"error\":\"Invalid or missing sessionId\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(400, bytes.length);  // 400 错误请求
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {}
                exchange.close();
                return;
            }

            Session session = sessions.get(sessionId);

            // 读取请求体（JSON-RPC 消息）
            String body;
            try {
                body = readBody(exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            // 立即返回 202 Accepted（MCP 响应将通过 SSE 推送）
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String ackBody = "{\"accepted\":true}";
            byte[] ackBytes = ackBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(202, ackBytes.length);  // 202 已接受
                exchange.getResponseBody().write(ackBytes);
            } catch (IOException ignored) {}
            exchange.close();

            // 核心：将 JSON-RPC 消息发送给子进程（进程通信的关键代码）
            // -----------------------------------------------------------------
            synchronized (session) {  // 同步块，确保线程安全
                if (session.childIn != null) {
                    session.childIn.println(body);  // 写入子进程的标准输入
                    session.childIn.flush();  // 确保数据立即发送，不缓存
                }
            }
        });

        // ── 清理过期 session（每 30 秒检查一次，超时 30 秒）─────────────────────
        // ScheduledExecutorService 用于执行定时任务
        // Executors.newSingleThreadScheduledExecutor() 创建一个单线程的定时任务执行器
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        // scheduleAtFixedRate 方法用于定期执行任务
        // 参数1: 要执行的任务，参数2: 初始延迟时间，参数3: 执行间隔，参数4: 时间单位
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // 遍历所有会话，检查是否过期
            sessions.forEach((sid, s) -> {
                if (now - s.lastActivity > 30 * 1000) {
                    System.out.println("[cleanup] Removing inactive session: " + sid);
                    cleanup(sid);  // 调用清理方法
                }
            });
        }, 30, 30, TimeUnit.SECONDS);

        // 设置服务器的线程池：使用之前创建的 executor
        // 这样 HTTP 请求会在线程池中执行，而不是在主线程中
        server.setExecutor(executor);
        // 启动服务器：开始接受 HTTP 请求
        server.start();
        
        // 获取并打印真实IP地址
        String ipAddress = getLocalIpAddress();
        System.out.println("MyMcpServerSSE started on http://" + ipAddress + ":" + PORT);
        System.out.println("  GET  /sse       → SSE stream (connects a session)");
        System.out.println("  POST /messages  → JSON-RPC endpoint (requires sessionId)");
    }

    // 获取本地真实IP地址
    private static String getLocalIpAddress() {
        try {
            // 遍历所有网络接口
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                // 跳过回环接口和禁用的接口
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                
                // 遍历所有IP地址
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    // 只返回IPv4地址，排除IPv6
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException e) {
            // 忽略异常
        }
        // 如果无法获取真实IP，返回localhost
        return "localhost";
    }

    // ─── SSE 发送 ──────────────────────────────────────────────────────────

    private static void sendSSE(Session session, String event, String data) {
        if (!session.alive.get()) return;
        try {
            String sse = "event: " + event + "\ndata: " + data + "\n\n";
            synchronized (session) {
                session.exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                session.exchange.getResponseBody().flush();
            }
            session.lastActivity = System.currentTimeMillis();
        } catch (Exception e) {
            // 连接已断，但不立即清理 - 等待 POST 请求处理
        }
    }

    // 清理会话和子进程（进程销毁的关键代码）
    // -----------------------------------------------------------------
    private static void cleanup(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s != null) {
            s.alive.set(false);  // 标记会话为非活动状态
            try { s.exchange.close(); } catch (Exception ignored) {}
            try { s.childProcess.destroy(); } catch (Exception ignored) {}
            // 注意：destroy() 会强制终止子进程，释放系统资源
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────

    private static String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String parseQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ─── Session ────────────────────────────────────────────────────────────

    private static class Session {
        final String sessionId;
        final HttpExchange exchange;
        final Process childProcess;
        final PrintWriter childIn;
        final AtomicBoolean alive = new AtomicBoolean(true);
        volatile long lastActivity = System.currentTimeMillis();

        Session(String sessionId, HttpExchange exchange, Process childProcess, PrintWriter childIn) {
            this.sessionId = sessionId;
            this.exchange = exchange;
            this.childProcess = childProcess;
            this.childIn = childIn;
        }
    }
}
