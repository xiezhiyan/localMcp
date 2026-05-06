/**
 * MCP SSE 客户端调用示范用例
 * 
 * 本示例展示如何使用 JavaScript 调用 MCP Server 的接口
 * 
 * 使用步骤：
 * 1. 启动 MCP SSE 服务：java MyMcpServerSSE
 * 2. 运行本示例：node mcp_client_demo.js
 */

const http = require('http');
const https = require('https');

class MCPClient {
    /**
     * 构造函数
     * @param {string} baseUrl - MCP 服务地址，默认 http://localhost:1003
     */
    constructor(baseUrl = 'http://localhost:1003') {
        this.baseUrl = baseUrl;
        this.sessionId = null;
        this.eventSource = null;
        this.pendingRequests = {};
        this.running = false;
    }

    /**
     * 根据 URL 协议获取对应的 HTTP 模块
     * @param {URL} url - URL 对象
     * @returns {Object} - http 或 https 模块
     */
    _getProtocolModule(url) {
        return url.protocol === 'https:' ? https : http;
    }

    /**
     * 获取默认端口
     * @param {URL} url - URL 对象
     * @returns {number} - 默认端口
     */
    _getDefaultPort(url) {
        return url.protocol === 'https:' ? 443 : 80;
    }

    /**
     * 连接到 MCP 服务
     * @returns {Promise<boolean>} - 连接是否成功
     */
    async connect() {
        return new Promise((resolve) => {
            console.log(`[MCP] 正在连接到 ${this.baseUrl}...`);

            const url = new URL(`${this.baseUrl}/sse`);
            const protocol = this._getProtocolModule(url);
            const defaultPort = this._getDefaultPort(url);
            
            const options = {
                hostname: url.hostname,
                port: url.port ? parseInt(url.port) : defaultPort,
                path: url.pathname,
                method: 'GET',
                headers: {
                    'Accept': 'text/event-stream',
                    'Cache-Control': 'no-cache',
                    'Connection': 'keep-alive'
                }
            };

            const req = protocol.request(options, (res) => {
                if (res.statusCode !== 200) {
                    console.error(`[MCP] 连接失败，状态码: ${res.statusCode}`);
                    resolve(false);
                    return;
                }

                this.running = true;
                let buffer = '';

                res.on('data', (chunk) => {
                    buffer += chunk.toString();
                    while (buffer.includes('\n\n')) {
                        const idx = buffer.indexOf('\n\n');
                        const eventData = buffer.substring(0, idx);
                        buffer = buffer.substring(idx + 2);
                        this._parseEvent(eventData);
                    }
                });

                res.on('end', () => {
                    if (this.running) {
                        console.error('[MCP] SSE 连接意外关闭');
                    }
                });
            });

            req.on('error', (err) => {
                console.error(`[MCP] 连接失败: ${err.message}`);
                resolve(false);
            });

            const checkSession = () => {
                if (this.sessionId) {
                    console.log(`[MCP] 连接成功，sessionId: ${this.sessionId}`);
                    resolve(true);
                } else {
                    setTimeout(checkSession, 50);
                }
            };
            checkSession();

            req.end();
            this.eventSource = req;
        });
    }

    /**
     * 解析 SSE 事件
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

        if (eventType === 'endpoint') {
            const match = data.match(/sessionId=([^&]+)/);
            if (match) this.sessionId = match[1];
        } else if (eventType === 'message' && data) {
            try {
                const response = JSON.parse(data);
                const callback = this.pendingRequests[response.id];
                if (callback) {
                    delete this.pendingRequests[response.id];
                    callback(response);
                }
            } catch (e) {
                // 忽略解析错误
            }
        }
    }

    /**
     * 发送请求
     * @param {string} method - 方法名
     * @param {object} params - 参数
     * @returns {Promise<Object>} - 响应结果
     */
    async sendRequest(method, params = {}) {
        if (!this.sessionId) {
            throw new Error('未建立连接，请先调用 connect()');
        }

        const reqId = `req-${Date.now()}`;
        const request = { jsonrpc: '2.0', method, id: reqId };
        if (Object.keys(params).length > 0) request.params = params;

        return new Promise((resolve, reject) => {
            this.pendingRequests[reqId] = (response) => {
                if (response.error) {
                    reject(new Error(response.error.message));
                } else {
                    resolve(response.result);
                }
            };

            const url = new URL(`${this.baseUrl}/messages`);
            url.searchParams.set('sessionId', this.sessionId);

            const protocol = this._getProtocolModule(url);
            const defaultPort = this._getDefaultPort(url);

            const postData = JSON.stringify(request);
            const options = {
                hostname: url.hostname,
                port: url.port ? parseInt(url.port) : defaultPort,
                path: url.pathname + url.search,
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(postData)
                }
            };

            const req = protocol.request(options, (res) => {
                if (res.statusCode !== 202) {
                    delete this.pendingRequests[reqId];
                    reject(new Error(`请求失败，状态码: ${res.statusCode}`));
                }
            });

            req.on('error', (err) => {
                delete this.pendingRequests[reqId];
                reject(err);
            });

            req.write(postData);
            req.end();
        });
    }

    /**
     * 关闭连接
     */
    close() {
        this.running = false;
        if (this.eventSource) this.eventSource.destroy();
        console.log('[MCP] 连接已关闭');
    }
}

// ==================== 示范用例 ====================

async function main() {
    // 获取命令行参数中的服务地址，默认为 http://localhost:1003
    const baseUrl = process.argv[2] || 'http://localhost:1003';
    console.log(`[配置] 目标服务地址: ${baseUrl}`);

    // 1. 创建客户端实例
    const client = new MCPClient(baseUrl);

    try {
        // 2. 连接到 MCP 服务
        const connected = await client.connect();
        if (!connected) {
            console.error('无法连接到 MCP 服务');
            return;
        }

        // 3. 初始化连接（必须调用）
        console.log('\n--- 调用 initialize ---');
        const initResult = await client.sendRequest('initialize', {
            protocolVersion: '2024-11-05',
            clientInfo: { name: 'demo-client', version: '1.0.0' }
        });
        console.log('初始化结果:', JSON.stringify(initResult, null, 2));

        // 4. 获取工具列表
        console.log('\n--- 调用 tools/list ---');
        const toolsResult = await client.sendRequest('tools/list');
        console.log('工具列表:', JSON.stringify(toolsResult.tools, null, 2));

        // 5. 调用加法工具
        console.log('\n--- 调用 tools/call (add) ---');
        const addResult = await client.sendRequest('tools/call', {
            name: 'add',
            arguments: { a: 10, b: 20 }
        });
        console.log('加法结果:', addResult.content[0].text);

        // 6. 调用乘法工具
        console.log('\n--- 调用 tools/call (multiply) ---');
        const multiplyResult = await client.sendRequest('tools/call', {
            name: 'multiply',
            arguments: { a: 5, b: 6 }
        });
        console.log('乘法结果:', multiplyResult.content[0].text);

    } catch (error) {
        console.error('调用失败:', error.message);
    } finally {
        // 7. 关闭连接
        client.close();
    }
}

// 运行示范用例
main();