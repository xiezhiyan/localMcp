import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * MCP SSE Server - 地理空间规避工具
 * 纯 Java 标准库，无外部依赖
 *
 * 协议:
 * - GET  /sse      → 建立 SSE 流，发送 endpoint 事件
 * - POST /messages → 接收 JSON-RPC 消息，fork 子进程处理，响应通过 SSE 推送
 *
 * 端口: 1004
 */
public class MyGeoMcpServerSSE {

    private static final int PORT = 1004;
    private static final String MCP_SERVER_CLASS = "MyGeoMcpServerStdio";
    private static final String MCP_SERVER_CWD = System.getProperty("mcp.cwd", System.getProperty("user.dir"));
    private static final String JAVA_BIN = System.getProperty("mcp.java", "java");
    private static final String BIND_HOST = System.getProperty("mcp.host", "0.0.0.0");

    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        System.out.println("========================================");
        System.out.println("  MyGeoMcpServerSSE starting...");
        System.out.println("  PORT:            " + PORT);
        System.out.println("  BIND_HOST:       " + BIND_HOST);
        System.out.println("  MCP_SERVER_CWD:  " + MCP_SERVER_CWD);
        System.out.println("  JAVA_BIN:        " + JAVA_BIN);
        System.out.println("  MCP_SERVER_CLASS:" + MCP_SERVER_CLASS);
        System.out.println("========================================");

        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, PORT), 0);

        // ── GET /sse ───────────────────────────────────────────────────────
        server.createContext("/sse", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.close();
                return;
            }

            String sessionId = UUID.randomUUID().toString();

            ProcessBuilder pb = new ProcessBuilder(JAVA_BIN, MCP_SERVER_CLASS);
            pb.directory(new File(MCP_SERVER_CWD));
            pb.redirectErrorStream(false);
            Process childProcess;
            try {
                childProcess = pb.start();
            } catch (IOException e) {
                exchange.close();
                return;
            }

            BufferedReader childOut = new BufferedReader(
                    new InputStreamReader(childProcess.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader childErr = new BufferedReader(
                    new InputStreamReader(childProcess.getErrorStream(), StandardCharsets.UTF_8));
            PrintWriter childIn = new PrintWriter(
                    new OutputStreamWriter(childProcess.getOutputStream(), StandardCharsets.UTF_8), true);

            Session session = new Session(sessionId, exchange, childProcess, childIn);
            sessions.put(sessionId, session);

            executor.submit(() -> {
                try {
                    String line;
                    while ((line = childErr.readLine()) != null) {
                        System.err.println("[child stderr] " + line);
                    }
                } catch (Exception ignored) {}
            });

            executor.submit(() -> {
                try {
                    String line;
                    while ((line = childOut.readLine()) != null) {
                        sendSSE(session, "message", line);
                    }
                } catch (Exception e) {
                } finally {
                    try {
                        sendSSE(session, "message", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Server session ended\"}}");
                    } catch (Exception ignored) {}
                }
            });

            // 设置 SSE 响应头 - 包含CORS支持
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            try {
                exchange.sendResponseHeaders(200, 0);
            } catch (IOException e) {
                cleanup(sessionId);
                return;
            }

            sendSSE(session, "endpoint", "/messages?sessionId=" + sessionId);

            // 心跳保活
            final AtomicBoolean alive = session.alive;
            Thread heartbeat = new Thread(() -> {
                while (alive.get()) {
                    try {
                        Thread.sleep(25000);
                        if (alive.get()) {
                            sendSSE(session, "ping", "");
                        }
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            });
            heartbeat.setDaemon(true);
            heartbeat.start();
        });

        // ── POST /messages ─────────────────────────────────────────────────
        server.createContext("/messages", exchange -> {
            // 添加 CORS 支持
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            
            // 处理 OPTIONS 请求（预检请求）
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String sessionId = parseQueryParam(query, "sessionId");

            if (sessionId == null || !sessions.containsKey(sessionId)) {
                String body = "{\"error\":\"Invalid or missing sessionId\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(400, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {}
                exchange.close();
                return;
            }

            Session session = sessions.get(sessionId);

            String body;
            try {
                body = readBody(exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String ackBody = "{\"accepted\":true}";
            byte[] ackBytes = ackBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(202, ackBytes.length);
                exchange.getResponseBody().write(ackBytes);
            } catch (IOException ignored) {}
            exchange.close();

            synchronized (session) {
                if (session.childIn != null) {
                    session.childIn.println(body);
                    session.childIn.flush();
                }
            }
        });

        // ── 清理过期 session ────────────────────────────────────────────────
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            sessions.forEach((sid, s) -> {
                if (now - s.lastActivity > 30 * 1000) {
                    System.out.println("[cleanup] Removing inactive session: " + sid);
                    cleanup(sid);
                }
            });
        }, 30, 30, TimeUnit.SECONDS);

        server.setExecutor(executor);
        server.start();
        
        String ipAddress = getLocalIpAddress();
        System.out.println("MyGeoMcpServerSSE started on http://" + ipAddress + ":" + PORT);
        System.out.println("  GET  /sse       → SSE stream (connects a session)");
        System.out.println("  POST /messages  → JSON-RPC endpoint (requires sessionId)");
    }

    private static String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException e) {
        }
        return "localhost";
    }

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
        }
    }

    private static void cleanup(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s != null) {
            s.alive.set(false);
            try { s.exchange.close(); } catch (Exception ignored) {}
            try { s.childProcess.destroy(); } catch (Exception ignored) {}
        }
    }

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