#!/usr/bin/env python3
"""
MCP SSE 测试脚本
用于测试 MyMcpServerSSE 的功能

协议说明：
- GET  /sse      → 建立 SSE 长连接，接收所有事件（包括响应）
- POST /messages → 发送 JSON-RPC 消息，响应通过 SSE 长连接推送
"""

import httpx
import json
import time
import sys
import threading
from typing import Optional, Dict, Any, Callable


class MCPClient:
    """MCP SSE 客户端"""

    def __init__(self, base_url: str = "http://localhost:1003"):
        self.base_url = base_url
        self.session_id: Optional[str] = None
        self.event_source: Optional[httpx.Response] = None
        self.client: Optional[httpx.Client] = None
        self.pending_requests: Dict[str, Callable] = {}
        self.running = False
        self._lock = threading.Lock()

    def connect(self) -> bool:
        """建立 SSE 连接，获取 sessionId"""
        self.client = httpx.Client(timeout=60.0)
        
        try:
            print(f"[连接] 正在连接到 {self.base_url}/sse ...")

            # 使用流式请求获取 SSE
            self.event_source = self.client.send(
                httpx.Request("GET", f"{self.base_url}/sse"),
                stream=True
            )

            if self.event_source.status_code != 200:
                print(f"[错误] 连接失败，状态码: {self.event_source.status_code}")
                return False

            self.running = True

            # 启动事件监听线程
            listener_thread = threading.Thread(target=self._listen_events, daemon=True)
            listener_thread.start()

            return True

        except Exception as e:
            print(f"[错误] 连接失败: {e}")
            import traceback
            traceback.print_exc()
            return False

    def _listen_events(self):
        """监听 SSE 事件（在单独线程中运行）"""
        try:
            event_type = None
            event_data = []

            for line in self.event_source.iter_lines():
                if not self.running:
                    break

                if line.startswith("event:"):
                    event_type = line[6:].strip()
                elif line.startswith("data:"):
                    event_data.append(line[5:].strip())
                elif line == "":
                    # 事件结束
                    if event_type and event_data:
                        data = "\n".join(event_data)
                        self._handle_event(event_type, data)
                        event_type = None
                        event_data = []

        except Exception as e:
            if self.running:
                print(f"[错误] 事件监听异常: {e}")

    def _handle_event(self, event_type: str, data: str):
        """处理 SSE 事件"""
        print(f"[SSE] event={event_type}, data={data[:100]}...")

        if event_type == "endpoint":
            # 解析 endpoint 获取 sessionId
            if "?sessionId=" in data:
                self.session_id = data.split("?sessionId=")[1]
                print(f"[连接] 已建立连接，sessionId: {self.session_id}")

        elif event_type == "message":
            # JSON-RPC 响应
            try:
                response = json.loads(data)
                req_id = response.get("id")
                if req_id and req_id in self.pending_requests:
                    callback = self.pending_requests.pop(req_id)
                    callback(response)
            except json.JSONDecodeError:
                pass

        elif event_type == "ping":
            print("[SSE] 收到心跳 ping")

    def send_request(self, method: str, params: Dict[str, Any] = None,
                     req_id: Any = None) -> Optional[Dict[str, Any]]:
        """发送 JSON-RPC 请求并等待响应"""
        if not self.session_id or not self.client:
            print("[错误] 未建立连接，请先调用 connect()")
            return None

        # 构建 JSON-RPC 请求
        request = {
            "jsonrpc": "2.0",
            "method": method,
        }
        if params is not None:
            request["params"] = params
        if req_id is not None:
            request["id"] = req_id

        print(f"[请求] {json.dumps(request, ensure_ascii=False)}")

        # 用于接收响应的容器
        response_container = [None]
        response_event = threading.Event()

        def on_response(response):
            response_container[0] = response
            response_event.set()

        # 注册回调
        if req_id:
            self.pending_requests[str(req_id)] = on_response

        try:
            # 发送 POST 请求
            response = self.client.post(
                f"{self.base_url}/messages?sessionId={self.session_id}",
                json=request
            )

            if response.status_code == 202:
                print(f"[响应] 请求已接受 (202)，等待 SSE 推送...")
            else:
                print(f"[错误] 请求失败: {response.status_code}")
                if req_id and str(req_id) in self.pending_requests:
                    del self.pending_requests[str(req_id)]
                return None

            # 等待响应（带超时）
            response_event.wait(timeout=30)
            
            if response_container[0]:
                result = response_container[0]
                print(f"[响应] {json.dumps(result, ensure_ascii=False)}")
                return result
            else:
                print("[错误] 等待响应超时")
                return None

        except Exception as e:
            print(f"[错误] 请求失败: {e}")
            if req_id and str(req_id) in self.pending_requests:
                del self.pending_requests[str(req_id)]
            return None

    def close(self):
        """关闭连接"""
        self.running = False
        if self.event_source:
            self.event_source.close()
        if self.client:
            self.client.close()
        print("[关闭] 连接已关闭")


def test_initialize(client: MCPClient):
    """测试 initialize 方法"""
    print("\n" + "=" * 50)
    print("测试 1: initialize")
    print("=" * 50)
    return client.send_request("initialize", {
        "protocolVersion": "2024-11-05",
        "clientInfo": {
            "name": "test-client",
            "version": "1.0.0"
        }
    }, req_id="init-1")


def test_tools_list(client: MCPClient):
    """测试 tools/list 方法"""
    print("\n" + "=" * 50)
    print("测试 2: tools/list")
    print("=" * 50)
    return client.send_request("tools/list", {}, req_id="list-1")


def test_tool_call(client: MCPClient, tool_name: str, arguments: Dict[str, Any]):
    """测试 tools/call 方法"""
    print("\n" + "=" * 50)
    print(f"测试: tools/call ({tool_name})")
    print(f"参数: a={arguments.get('a')}, b={arguments.get('b')}")
    print("=" * 50)
    return client.send_request("tools/call", {
        "name": tool_name,
        "arguments": arguments
    }, req_id=f"call-{tool_name}")


def run_all_tests(base_url: str = "http://localhost:1003"):
    """运行所有测试"""
    print("=" * 50)
    print("MCP SSE 测试脚本")
    print(f"目标服务: {base_url}")
    print("=" * 50)

    # 创建客户端并连接
    client = MCPClient(base_url)

    if not client.connect():
        print("[失败] 无法连接到服务器")
        return

    try:
        # 等待连接建立
        time.sleep(1)

        # 测试 1: initialize
        result = test_initialize(client)
        if result and "result" in result:
            print("[成功] initialize 调用成功")
        else:
            print("[失败] initialize 调用失败")

        time.sleep(0.5)

        # 测试 2: tools/list
        result = test_tools_list(client)
        if result and "result" in result:
            tools = result.get("result", {}).get("tools", [])
            print(f"[成功] tools/list 调用成功，发现 {len(tools)} 个工具")
            for tool in tools:
                desc = tool.get('description', '')[:60]
                print(f"  - {tool.get('name')}: {desc}...")
        else:
            print("[失败] tools/list 调用失败")

        time.sleep(0.5)

        # 测试 3: 工具调用
        test_cases = [
            ("add", {"a": 1, "b": 2}, "1 + 2 + 3 = 6"),
            ("add", {"a": 5, "b": 3}, "5 + 3 + 3 = 11"),
            ("subtract", {"a": 10, "b": 3}, "10 - 3 = 7"),
            ("multiply", {"a": 4, "b": 5}, "4 * 5 = 20"),
            ("divide", {"a": 10, "b": 3}, "10 / 3 = 3 (整除)"),
            ("divide", {"a": 100, "b": 7}, "100 / 7 = 14 (整除)"),
        ]

        for tool_name, args, expected in test_cases:
            result = test_tool_call(client, tool_name, args)
            if result and "result" in result:
                print(f"[成功] {expected}")
            else:
                print(f"[失败] {expected}")
            time.sleep(0.5)

        # 测试 4: 错误处理 - 无效工具
        print("\n" + "=" * 50)
        print("测试: tools/call (无效工具)")
        print("=" * 50)
        result = test_tool_call(client, "invalid_tool", {"a": 1, "b": 2})
        if result and "error" in result:
            print(f"[预期] 收到错误响应: {result.get('error', {}).get('message', '')}")

        # 测试 5: 错误处理 - 除零
        print("\n" + "=" * 50)
        print("测试: tools/call (除零)")
        print("=" * 50)
        result = test_tool_call(client, "divide", {"a": 10, "b": 0})
        if result and "error" in result:
            print(f"[预期] 收到错误响应: {result.get('error', {}).get('message', '')}")

    finally:
        client.close()


if __name__ == "__main__":
    # 从命令行参数获取服务地址，默认 http://localhost:1003
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:1003"
    run_all_tests(base_url)
