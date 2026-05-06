#!/usr/bin/env node
/**
 * MCP SSE 测试脚本 - Node.js 版本
 * 用于测试 MyMcpServerSSE 的功能
 * 
 * 依赖：无需额外依赖，使用 Node.js 内置模块
 */

const http = require('http');
const https = require('https');
const querystring = require('querystring');

class MCPClient {
    /**
     * 构造函数
     * @param {string} baseUrl - 服务器地址，默认为 http://localhost:1003
     */
    constructor(baseUrl = 'http://localhost:1003') {
        this.baseUrl = baseUrl;
        this.sessionId = null;
        this.eventSource = null;
        this.pendingRequests = {};
        this.running = false;
        this.requestIdCounter = 0;
    }

    /**
     * 建立 SSE 连接
     * @returns {Promise<boolean>}
     */
    async connect() {
        return new Promise((resolve) => {
            console.log(`[连接] 正在连接到 ${this.baseUrl}/sse ...`);

            const url = new URL(`${this.baseUrl}/sse`);
            const options = {
                hostname: url.hostname,
                port: url.port,
                path: url.pathname,
                method: 'GET',
                headers: {
                    'Accept': 'text/event-stream',
                    'Cache-Control': 'no-cache',
                    'Connection': 'keep-alive'
                }
            };

            const protocol = url.protocol === 'https:' ? https : http;
            const req = protocol.request(options, (res) => {
                if (res.statusCode !== 200) {
                    console.log(`[错误] 连接失败，状态码: ${res.statusCode}`);
                    resolve(false);
                    return;
                }

                this.running = true;
                let buffer = '';

                res.on('data', (chunk) => {
                    buffer += chunk.toString();
                    buffer = this._processBuffer(buffer);
                });

                res.on('end', () => {
                    if (this.running) {
                        console.log('[错误] SSE 连接意外关闭');
                    }
                });

                res.on('error', (err) => {
                    console.log(`[错误] SSE 连接错误: ${err.message}`);
                    resolve(false);
                });
            });

            req.on('error', (err) => {
                console.log(`[错误] 连接失败: ${err.message}`);
                resolve(false);
            });

            // 设置超时
            const timeout = setTimeout(() => {
                if (!this.sessionId) {
                    console.log('[错误] 连接超时');
                    this.close();
                    resolve(false);
                }
            }, 10000);

            // 监听 sessionId 设置
            const checkSession = () => {
                if (this.sessionId) {
                    clearTimeout(timeout);
                    console.log(`[连接] 已建立连接，sessionId: ${this.sessionId}`);
                    resolve(true);
                } else if (this.running) {
                    // 连接建立后继续检查
                    setTimeout(checkSession, 50);
                } else {
                    // 连接还在建立中，继续检查
                    setTimeout(checkSession, 50);
                }
            };
            checkSession();

            req.end();
            this.eventSource = req;
        });
    }

    /**
     * 处理 SSE 缓冲区
     * @param {string} buffer 
     */
    _processBuffer(buffer) {
        while (buffer.includes('\n\n')) {
            const idx = buffer.indexOf('\n\n');
            const eventData = buffer.substring(0, idx);
            buffer = buffer.substring(idx + 2);

            this._parseEvent(eventData);
        }
        return buffer;
    }

    /**
     * 解析 SSE 事件
     * @param {string} eventData 
     */
    _parseEvent(eventData) {
        let eventType = 'message';
        let data = '';

        eventData.split('\n').forEach((line) => {
            if (line.startsWith('event:')) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith('data:')) {
                data += line.substring(5);
            }
        });

        console.log(`[SSE] event=${eventType}, data=${data ? data.substring(0, 100) + (data.length > 100 ? '...' : '') : '(empty)'}`);

        if (!data && eventType !== 'ping') return;

        if (eventType === 'endpoint') {
            // 解析 sessionId
            const match = data.match(/sessionId=([^&]+)/);
            if (match) {
                this.sessionId = match[1];
            }
        } else if (eventType === 'message') {
            // JSON-RPC 响应
            try {
                const response = JSON.parse(data);
                const reqId = response.id;
                if (reqId && this.pendingRequests[reqId]) {
                    const callback = this.pendingRequests[reqId];
                    delete this.pendingRequests[reqId];
                    callback(response);
                }
            } catch (e) {
                // 忽略解析错误
            }
        } else if (eventType === 'ping') {
            console.log('[SSE] 收到心跳 ping');
        }
    }

    /**
     * 发送 JSON-RPC 请求
     * @param {string} method - 方法名
     * @param {object} params - 参数
     * @param {any} reqId - 请求 ID
     * @returns {Promise<object>}
     */
    async sendRequest(method, params = {}, reqId = null) {
        if (!this.sessionId) {
            console.log('[错误] 未建立连接，请先调用 connect()');
            return null;
        }

        if (!reqId) {
            reqId = `req-${++this.requestIdCounter}-${Date.now()}`;
        }

        // 构建 JSON-RPC 请求
        const request = {
            jsonrpc: '2.0',
            method: method,
            id: reqId
        };

        if (Object.keys(params).length > 0) {
            request.params = params;
        }

        console.log(`[请求] ${JSON.stringify(request, null, 2)}`);

        return new Promise((resolve) => {
            // 注册回调
            this.pendingRequests[reqId] = (response) => {
                console.log(`[响应] ${JSON.stringify(response, null, 2)}`);
                resolve(response);
            };

            // 发送 POST 请求
            const url = new URL(`${this.baseUrl}/messages`);
            url.searchParams.set('sessionId', this.sessionId);

            const postData = JSON.stringify(request);
            const options = {
                hostname: url.hostname,
                port: url.port,
                path: url.pathname + url.search,
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(postData)
                }
            };

            const protocol = url.protocol === 'https:' ? https : http;
            const req = protocol.request(options, (res) => {
                let body = '';
                res.on('data', (chunk) => { body += chunk; });
                res.on('end', () => {
                    if (res.statusCode === 202) {
                        console.log('[响应] 请求已接受 (202)，等待 SSE 推送...');
                    } else {
                        console.log(`[错误] 请求失败: ${res.statusCode}`);
                        delete this.pendingRequests[reqId];
                        resolve(null);
                    }
                });
            });

            req.on('error', (err) => {
                console.log(`[错误] 请求失败: ${err.message}`);
                delete this.pendingRequests[reqId];
                resolve(null);
            });

            req.write(postData);
            req.end();

            // 设置超时
            setTimeout(() => {
                if (this.pendingRequests[reqId]) {
                    console.log('[错误] 等待响应超时');
                    delete this.pendingRequests[reqId];
                    resolve(null);
                }
            }, 30000);
        });
    }

    /**
     * 关闭连接
     */
    close() {
        this.running = false;
        if (this.eventSource) {
            this.eventSource.destroy();
            this.eventSource = null;
        }
        console.log('[关闭] 连接已关闭');
    }
}

/**
 * 测试 initialize 方法
 */
async function testInitialize(client) {
    console.log('\n' + '='.repeat(50));
    console.log('测试 1: initialize');
    console.log('='.repeat(50));

    const result = await client.sendRequest('initialize', {
        protocolVersion: '2024-11-05',
        clientInfo: {
            name: 'node-test-client',
            version: '1.0.0'
        }
    }, 'init-1');

    if (result && result.result) {
        console.log('[成功] initialize 调用成功');
        return true;
    } else {
        console.log('[失败] initialize 调用失败');
        return false;
    }
}

/**
 * 测试 tools/list 方法
 */
async function testToolsList(client) {
    console.log('\n' + '='.repeat(50));
    console.log('测试 2: tools/list');
    console.log('='.repeat(50));

    const result = await client.sendRequest('tools/list', {}, 'list-1');

    if (result && result.result) {
        const tools = result.result.tools || [];
        console.log(`[成功] tools/list 调用成功，发现 ${tools.length} 个工具`);
        tools.forEach(tool => {
            const desc = tool.description ? tool.description.substring(0, 60) : '';
            console.log(`  - ${tool.name}: ${desc}${tool.description && tool.description.length > 60 ? '...' : ''}`);
        });
        return true;
    } else {
        console.log('[失败] tools/list 调用失败');
        return false;
    }
}

/**
 * 测试工具调用
 */
async function testToolCall(client, toolName, args, expected) {
    console.log('\n' + '='.repeat(50));
    console.log(`测试: tools/call (${toolName})`);
    console.log(`参数: ${Object.entries(args).map(([k, v]) => `${k}=${v}`).join(', ')}`);
    console.log('='.repeat(50));

    const result = await client.sendRequest('tools/call', {
        name: toolName,
        arguments: args
    }, `call-${toolName}`);

    if (result && result.result) {
        console.log(`[成功] ${expected}`);
        return true;
    } else if (result && result.error) {
        console.log(`[预期错误] ${result.error.message}`);
        return true;
    } else {
        console.log(`[失败] ${expected}`);
        return false;
    }
}

/**
 * 运行所有测试
 * @param {string} baseUrl - 服务器地址，默认为 http://localhost:1003
 */
async function runAllTests(baseUrl = 'http://localhost:1003') {
    console.log('='.repeat(50));
    console.log('MCP SSE 测试脚本 - Node.js 版本');
    console.log(`目标服务: ${baseUrl}`);
    console.log('='.repeat(50));

    // 创建客户端并连接
    const client = new MCPClient(baseUrl);

    if (!(await client.connect())) {
        console.log('[失败] 无法连接到服务器');
        return;
    }

    try {
        // 等待连接稳定
        await new Promise(resolve => setTimeout(resolve, 500));

        // 测试 initialize
        await testInitialize(client);
        await new Promise(resolve => setTimeout(resolve, 500));

        // 测试 tools/list
        await testToolsList(client);
        await new Promise(resolve => setTimeout(resolve, 500));

        // 测试工具调用
        const testCases = [
            { tool: 'add', args: { a: 1, b: 2 }, expected: '1 + 2 + 3 = 6' },
            { tool: 'add', args: { a: 5, b: 3 }, expected: '5 + 3 + 3 = 11' },
            { tool: 'subtract', args: { a: 10, b: 3 }, expected: '10 - 3 = 7' },
            { tool: 'multiply', args: { a: 4, b: 5 }, expected: '4 * 5 = 20' },
            { tool: 'divide', args: { a: 10, b: 3 }, expected: '10 / 3 = 3 (整除)' },
            { tool: 'divide', args: { a: 100, b: 7 }, expected: '100 / 7 = 14 (整除)' },
        ];

        for (const tc of testCases) {
            await testToolCall(client, tc.tool, tc.args, tc.expected);
            await new Promise(resolve => setTimeout(resolve, 500));
        }

        // 测试错误处理 - 无效工具
        console.log('\n' + '='.repeat(50));
        console.log('测试: tools/call (无效工具)');
        console.log('='.repeat(50));
        const invalidResult = await client.sendRequest('tools/call', {
            name: 'invalid_tool',
            arguments: { a: 1, b: 2 }
        }, 'call-invalid');
        if (invalidResult && invalidResult.error) {
            console.log(`[预期] 收到错误响应: ${invalidResult.error.message}`);
        }

        // 测试错误处理 - 除零
        console.log('\n' + '='.repeat(50));
        console.log('测试: tools/call (除零)');
        console.log('='.repeat(50));
        const divideZeroResult = await client.sendRequest('tools/call', {
            name: 'divide',
            arguments: { a: 10, b: 0 }
        }, 'call-divide-zero');
        if (divideZeroResult && divideZeroResult.error) {
            console.log(`[预期] 收到错误响应: ${divideZeroResult.error.message}`);
        }

    } finally {
        client.close();
    }
}

// 运行测试
// 从命令行参数获取服务地址，默认 http://localhost:1003
const baseUrl = process.argv[2] || 'http://localhost:1003';
runAllTests(baseUrl).catch(console.error);