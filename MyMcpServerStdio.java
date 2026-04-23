import java.io.*;

/**
 * MCP Server 实现 - 自定义计算器
 * 支持 add、subtract、multiply、divide 四个工具
 * 使用纯 Java 标准库，无外部依赖
 */
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
                        writer.flush(); // 确保每个响应立即写出
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

    private static String handleRequest(String json) {
        // 解析 JSON-RPC 请求
        String method = extractValue(json, "method");
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

    private static String handleInitialize(Object id, String paramsStr) {
        StringBuilder result = new StringBuilder();
        result.append("{");
        result.append("\"protocolVersion\":\"2024-11-05\",");
        result.append("\"serverInfo\":{\"name\":\"").append(SERVER_NAME).append("\",\"version\":\"").append(SERVER_VERSION).append("\"},");
        result.append("\"capabilities\":{\"tools\":{}}");
        result.append("}");

        return createResponse(id, result.toString());
    }

    private static String handleToolsList(Object id) {
        StringBuilder result = new StringBuilder();
        result.append("{\"tools\":[");

        // add 工具
        result.append("{\"name\":\"add\",\"description\":\"自定义加法运算\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"第一个数\"},\"b\":{\"type\":\"integer\",\"description\":\"第二个数\"}},\"required\":[\"a\",\"b\"]}}");
        result.append(",");

        // subtract 工具
        result.append("{\"name\":\"subtract\",\"description\":\"自定义减法运算\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"被减数\"},\"b\":{\"type\":\"integer\",\"description\":\"减数\"}},\"required\":[\"a\",\"b\"]}}");
        result.append(",");

        // multiply 工具
        result.append("{\"name\":\"multiply\",\"description\":\"自定义乘法运算\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"第一个数\"},\"b\":{\"type\":\"integer\",\"description\":\"第二个数\"}},\"required\":[\"a\",\"b\"]}}");
        result.append(",");

        // divide 工具
        result.append("{\"name\":\"divide\",\"description\":\"自定义除法运算\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"被除数\"},\"b\":{\"type\":\"integer\",\"description\":\"除数\"}},\"required\":[\"a\",\"b\"]}}");

        result.append("]}");

        return createResponse(id, result.toString());
    }

    private static String handleToolsCall(Object id, String paramsStr) {
        if (paramsStr == null || paramsStr.isEmpty()) {
            return createError(id, -32602, "Invalid params: missing params");
        }

        String toolName = extractValue(paramsStr, "name");
        String argsStr = extractObject(paramsStr, "arguments");
        if (argsStr == null) {
            argsStr = "{}";
        }

        try {
            int a = extractInt(argsStr, "a");
            int b = extractInt(argsStr, "b");
            int result;
            String operationName;

            switch (toolName) {
                case "add":
                    result = MyCalculatorTools.add(a, b);
                    operationName = "加法";
                    break;
                case "subtract":
                    result = MyCalculatorTools.subtract(a, b);
                    operationName = "减法";
                    break;
                case "multiply":
                    result = MyCalculatorTools.multiply(a, b);
                    operationName = "乘法";
                    break;
                case "divide":
                    result = MyCalculatorTools.divide(a, b);
                    operationName = "除法";
                    break;
                default:
                    return createError(id, -32602, "Unknown tool: " + toolName);
            }

            String text = "自定义" + operationName + ": " + result;
            return createResponse(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}");

        } catch (NumberFormatException e) {
            return createError(id, -32602, "Invalid params: " + e.getMessage());
        } catch (ArithmeticException e) {
            return createError(id, -32602, "Arithmetic error: " + e.getMessage());
        }
    }

    // 优化的 JSON 解析工具方法
    private static String extractValue(String json, String key) {
        String search = "\"" + key + "\"";
        int start = json.indexOf(search);
        if (start == -1) return null;

        // 找到冒号位置
        int colon = json.indexOf(":", start);
        if (colon == -1) return null;

        // 跳过空格
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        // 处理字符串值
        if (valueStart < json.length() && json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            // 处理转义的引号
            while (valueEnd > 0 && json.charAt(valueEnd - 1) == '\\') {
                valueEnd = json.indexOf('"', valueEnd + 1);
            }
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        }

        // 处理数字值
        if (valueStart < json.length() && (Character.isDigit(json.charAt(valueStart)) || json.charAt(valueStart) == '-')) {
            int valueEnd = valueStart;
            while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '.' || json.charAt(valueEnd) == 'e' || json.charAt(valueEnd) == 'E' || json.charAt(valueEnd) == '-' || json.charAt(valueEnd) == '+')) {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        }

        return null;
    }

    private static int extractInt(String json, String key) {
        String value = extractValue(json, key);
        if (value == null) {
            throw new NumberFormatException("Missing or invalid integer: " + key);
        }
        return Integer.parseInt(value);
    }

    private static Object extractId(String json) {
        String idStr = extractValue(json, "id");
        if (idStr == null) return null;

        // 尝试解析为数字
        try {
            return Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            // 不是数字，返回字符串
            return idStr;
        }
    }

    private static String extractObject(String json, String key) {
        String search = "\"" + key + "\"";
        int start = json.indexOf(search);
        if (start == -1) return null;

        // 找到冒号位置
        int colon = json.indexOf(":", start);
        if (colon == -1) return null;

        // 跳过空格
        int objectStart = colon + 1;
        while (objectStart < json.length() && Character.isWhitespace(json.charAt(objectStart))) {
            objectStart++;
        }

        // 找到对象的开始
        if (objectStart < json.length() && json.charAt(objectStart) == '{') {
            int depth = 1;
            int objectEnd = objectStart + 1;
            while (objectEnd < json.length() && depth > 0) {
                char c = json.charAt(objectEnd);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == '"') {
                    // 跳过字符串内的内容
                    objectEnd++;
                    while (objectEnd < json.length() && json.charAt(objectEnd) != '"') {
                        if (json.charAt(objectEnd) == '\\') {
                            objectEnd++;
                        }
                        objectEnd++;
                    }
                }
                objectEnd++;
            }
            if (depth == 0) {
                return json.substring(objectStart, objectEnd);
            }
        }

        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String createResponse(Object id, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",");
        if (id != null) {
            sb.append("\"id\":");
            if (id instanceof String) {
                sb.append("\"").append(id).append("\"");
            } else {
                sb.append(id);
            }
            sb.append(",");
        }
        sb.append("\"result\":").append(result);
        sb.append("}");
        return sb.toString();
    }

    private static String createError(Object id, int code, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",");
        if (id != null) {
            sb.append("\"id\":");
            if (id instanceof String) {
                sb.append("\"").append(id).append("\"");
            } else {
                sb.append(id);
            }
            sb.append(",");
        }
        sb.append("\"error\":{\"code\":").append(code).append(",\"message\":\"").append(escapeJson(message)).append("\"");
        sb.append("}}");
        return sb.toString();
    }
}