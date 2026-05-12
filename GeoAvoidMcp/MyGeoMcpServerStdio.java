import java.io.*;

/**
 * MCP Server 实现 - 地理空间规避工具
 * 支持 avoidZone、avoidBufferConstant、avoidBufferExpression 三个工具
 * 使用纯 Java 标准库，无外部依赖
 */
public class MyGeoMcpServerStdio {
    private static final String SERVER_NAME = "my-geo-avoid-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    public static void main(String[] args) {
        System.err.println("[DEBUG] MyGeoMcpServerStdio started, PID=" + ProcessHandle.current().pid());
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

    private static String handleRequest(String json) {
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

        // avoidZone 工具 - 无缓冲区规避
        result.append("{\"name\":\"avoidZone\",\"description\":\"无缓冲区规避。匹配选址规则：永久基本农田规避；选线规则：冰区、微地形规避。在指定选址范围内检查是否存在需要规避的区域。\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"bounds\":{\"type\":\"object\",\"description\":\"选址范围矩形\",\"properties\":{\"leftBottom\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"左下角经度\"},\"y\":{\"type\":\"number\",\"description\":\"左下角纬度\"}},\"required\":[\"x\",\"y\"]},\"rightTop\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"右上角经度\"},\"y\":{\"type\":\"number\",\"description\":\"右上角纬度\"}},\"required\":[\"x\",\"y\"]}},\"required\":[\"leftBottom\",\"rightTop\"]},\"srcDataset\":{\"type\":\"string\",\"description\":\"原始面数据集ID\"},\"filterExpression\":{\"type\":\"string\",\"description\":\"筛选表达式（可选），如 level==1\"}},\"required\":[\"bounds\",\"srcDataset\"]}}");
        result.append(",");

        // avoidBufferConstant 工具 - 常量缓冲区范围规避
        result.append("{\"name\":\"avoidBufferConstant\",\"description\":\"常量缓冲区范围规避。匹配选址规则：暂无；选线规则：采石场爆炸作业区规避、加油加气站及设施规避。在指定选址范围内按固定距离缓冲区检查规避区域。\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"bounds\":{\"type\":\"object\",\"description\":\"选址范围矩形\",\"properties\":{\"leftBottom\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"左下角经度\"},\"y\":{\"type\":\"number\",\"description\":\"左下角纬度\"}},\"required\":[\"x\",\"y\"]},\"rightTop\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"右上角经度\"},\"y\":{\"type\":\"number\",\"description\":\"右上角纬度\"}},\"required\":[\"x\",\"y\"]}},\"required\":[\"leftBottom\",\"rightTop\"]},\"srcDataset\":{\"type\":\"string\",\"description\":\"原始面数据集ID\"},\"avoidance\":{\"type\":\"number\",\"description\":\"规避距离（米）\"}},\"required\":[\"bounds\",\"srcDataset\",\"avoidance\"]}}");
        result.append(",");

        // avoidBufferExpression 工具 - 字段缓冲区范围规避
        result.append("{\"name\":\"avoidBufferExpression\",\"description\":\"字段缓冲区范围规避。匹配选址规则：历史文化遗迹、矿产等敏感区规避；选线规则：暂无。根据字段表达式动态计算缓冲区距离进行规避检查。\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"bounds\":{\"type\":\"object\",\"description\":\"选址范围矩形\",\"properties\":{\"leftBottom\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"左下角经度\"},\"y\":{\"type\":\"number\",\"description\":\"左下角纬度\"}},\"required\":[\"x\",\"y\"]},\"rightTop\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"description\":\"右上角经度\"},\"y\":{\"type\":\"number\",\"description\":\"右上角纬度\"}},\"required\":[\"x\",\"y\"]}},\"required\":[\"leftBottom\",\"rightTop\"]},\"srcDataset\":{\"type\":\"string\",\"description\":\"原始敏感区数据集ID\"},\"bufferExpression\":{\"type\":\"string\",\"description\":\"规避距离字段名或字段表达式，如 level、(10-level)*8\"}},\"required\":[\"bounds\",\"srcDataset\",\"bufferExpression\"]}}");

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
            // 解析 bounds 参数
            String boundsStr = extractObject(argsStr, "bounds");
            if (boundsStr == null) {
                return createError(id, -32602, "Invalid params: missing bounds");
            }

            // 解析左下角点
            String leftBottomStr = extractObject(boundsStr, "leftBottom");
            if (leftBottomStr == null) {
                return createError(id, -32602, "Invalid params: missing leftBottom");
            }
            double lbX = extractDouble(leftBottomStr, "x");
            double lbY = extractDouble(leftBottomStr, "y");
            Point2D leftBottom = new Point2D(lbX, lbY);

            // 解析右上角点
            String rightTopStr = extractObject(boundsStr, "rightTop");
            if (rightTopStr == null) {
                return createError(id, -32602, "Invalid params: missing rightTop");
            }
            double rtX = extractDouble(rightTopStr, "x");
            double rtY = extractDouble(rightTopStr, "y");
            Point2D rightTop = new Point2D(rtX, rtY);

            Rectangle2D bounds = new Rectangle2D(leftBottom, rightTop);

            // 解析 srcDataset
            String srcDataset = extractValue(argsStr, "srcDataset");
            if (srcDataset == null || srcDataset.isEmpty()) {
                return createError(id, -32602, "Invalid params: missing srcDataset");
            }

            AvoidData result;
            String operationName;

            switch (toolName) {
                case "avoidZone":
                    String filterExpression = extractValue(argsStr, "filterExpression");
                    result = GeoAvoidTools.avoidZone(bounds, srcDataset, filterExpression);
                    operationName = "无缓冲区规避";
                    break;
                case "avoidBufferConstant":
                    double avoidance = extractDouble(argsStr, "avoidance");
                    result = GeoAvoidTools.avoidBufferConstant(bounds, srcDataset, avoidance);
                    operationName = "常量缓冲区规避";
                    break;
                case "avoidBufferExpression":
                    String bufferExpression = extractValue(argsStr, "bufferExpression");
                    if (bufferExpression == null || bufferExpression.isEmpty()) {
                        return createError(id, -32602, "Invalid params: missing bufferExpression");
                    }
                    result = GeoAvoidTools.avoidBufferExpression(bounds, srcDataset, bufferExpression);
                    operationName = "字段缓冲区规避";
                    break;
                default:
                    return createError(id, -32602, "Unknown tool: " + toolName);
            }

            if (result == null) {
                return createResponse(id, "{\"content\":[{\"type\":\"text\",\"text\":\"选址区域内无规避区\"}]}");
            } else {
                String text = String.format("%s成功，原始数据ID: %s，规避数据集ID: %s，规避区名称: %s，类型: %s，描述: %s，坐标:%s", 
                        operationName, result.srcDataID, result.avoidID, result.avoidName, result.avoidType, result.description, formatCoords(result.polygonCoords));
                return createResponse(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}");
            }

        } catch (NumberFormatException e) {
            return createError(id, -32602, "Invalid params: " + e.getMessage());
        }
    }

    private static String extractValue(String json, String key) {
        String search = "\"" + key + "\"";
        int start = json.indexOf(search);
        if (start == -1) return null;

        int colon = json.indexOf(":", start);
        if (colon == -1) return null;

        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart < json.length() && json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            while (valueEnd > 0 && json.charAt(valueEnd - 1) == '\\') {
                valueEnd = json.indexOf('"', valueEnd + 1);
            }
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        }

        if (valueStart < json.length() && (Character.isDigit(json.charAt(valueStart)) || json.charAt(valueStart) == '-')) {
            int valueEnd = valueStart;
            while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '.' || json.charAt(valueEnd) == 'e' || json.charAt(valueEnd) == 'E' || json.charAt(valueEnd) == '-' || json.charAt(valueEnd) == '+')) {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        }

        return null;
    }

    private static double extractDouble(String json, String key) {
        String value = extractValue(json, key);
        if (value == null) {
            throw new NumberFormatException("Missing or invalid number: " + key);
        }
        return Double.parseDouble(value);
    }

    private static Object extractId(String json) {
        String idStr = extractValue(json, "id");
        if (idStr == null) return null;

        try {
            return Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return idStr;
        }
    }

    private static String extractObject(String json, String key) {
        String search = "\"" + key + "\"";
        int start = json.indexOf(search);
        if (start == -1) return null;

        int colon = json.indexOf(":", start);
        if (colon == -1) return null;

        int objectStart = colon + 1;
        while (objectStart < json.length() && Character.isWhitespace(json.charAt(objectStart))) {
            objectStart++;
        }

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

    private static String formatCoords(double[][] coords) {
        if (coords == null || coords.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("[").append(coords[i][0]).append(",").append(coords[i][1]).append("]");
        }
        sb.append("]");
        return sb.toString();
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