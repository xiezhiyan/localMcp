/**
 * MCP SSE 多会话示范 - 方式1：创建多个客户端实例
 * 
 * 每个 MCPClient 实例会建立独立的会话，拥有独立的 sessionId
 */

const http = require('http');
const https = require('https');

class MCPClient {
    constructor(baseUrl = 'http://localhost:1003') {
        this.baseUrl = baseUrl;
        this.sessionId = null;
        this.eventSource = null;
        this.pendingRequests = {};
        this.running = false;
    }

    _getProtocolModule(url) {
        return url.protocol === 'https:' ? https : http;
    }

    _getDefaultPort(url) {
        return url.protocol === 'https:' ? 443 : 80;
    }

    async connect() {
        return new Promise((resolve) => {
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
                    console.error(`连接失败，状态码: ${res.statusCode}`);
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
            });

            req.on('error', (err) => {
                console.error(`连接失败: ${err.message}`);
                resolve(false);
            });

            const checkSession = () => {
                if (this.sessionId) {
                    console.log(`连接成功，sessionId: ${this.sessionId.substring(0, 8)}...`);
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

    _parseEvent(eventData) {
        let eventType = 'message';
        let data = '';

        eventData.split('\n').forEach((line) => {
            if (line.startsWith('event:')) eventType = line.substring(6).trim();
            else if (line.startsWith('data:')) data += line.substring(5);
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
            } catch (e) {}
        }
    }

    async sendRequest(method, params = {}) {
        if (!this.sessionId) throw new Error('未建立连接，请先调用 connect()');

        const reqId = `req-${Date.now()}`;
        const request = { jsonrpc: '2.0', method, id: reqId };
        if (Object.keys(params).length > 0) request.params = params;

        return new Promise((resolve, reject) => {
            this.pendingRequests[reqId] = (response) => {
                if (response.error) reject(new Error(response.error.message));
                else resolve(response.result);
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

    close() {
        this.running = false;
        if (this.eventSource) this.eventSource.destroy();
        console.log(`会话已关闭 (sessionId: ${this.sessionId ? this.sessionId.substring(0, 8) + '...' : 'unknown'})`);
    }
}

// ==================== 多会话示范 ====================

async function main() {
    const baseUrl = process.argv[2] || 'http://localhost:1003';
    console.log(`\n=== MCP 多会话示范 ===`);
    console.log(`目标服务: ${baseUrl}\n`);

    // ========== 方式1：创建多个客户端实例 ==========
    console.log('--- 创建多个独立会话 ---');

    // 创建会话1：用户A
    console.log('\n1. 创建会话1（用户A）');
    const clientA = new MCPClient(baseUrl);
    await clientA.connect();

    // 创建会话2：用户B
    console.log('\n2. 创建会话2（用户B）');
    const clientB = new MCPClient(baseUrl);
    await clientB.connect();

    // 创建会话3：用户C
    console.log('\n3. 创建会话3（用户C）');
    const clientC = new MCPClient(baseUrl);
    await clientC.connect();

    // ========== 初始化所有会话 ==========
    console.log('\n--- 初始化会话 ---');
    await clientA.sendRequest('initialize', { protocolVersion: '2024-11-05', clientInfo: { name: 'user-A', version: '1.0.0' } });
    await clientB.sendRequest('initialize', { protocolVersion: '2024-11-05', clientInfo: { name: 'user-B', version: '1.0.0' } });
    await clientC.sendRequest('initialize', { protocolVersion: '2024-11-05', clientInfo: { name: 'user-C', version: '1.0.0' } });
    console.log('所有会话初始化完成');

    // ========== 独立调用工具 ==========
    console.log('\n--- 独立调用工具 ---');
    
    // 用户A计算：20 + 30
    // 预期结果：50.1（自定义加法规则：a + b + 0.1）
    // 返回结构:
    // {
    //   "jsonrpc": "2.0",
    //   "id": "req-xxx",
    //   "result": {
    //     "content": [{ "type": "text", "text": "50.1" }]
    //   }
    // }
    const resultA = await clientA.sendRequest('tools/call', { name: 'add', arguments: { a: 20, b: 30 } });
    console.log(`\n用户A 计算 20 + 30 = ${resultA.content[0].text}`);

    // 用户B计算：50 * 4
    // 预期结果：200.0（标准乘法）
    // 返回结构:
    // {
    //   "jsonrpc": "2.0",
    //   "id": "req-xxx",
    //   "result": {
    //     "content": [{ "type": "text", "text": "200.0" }]
    //   }
    // }
    const resultB = await clientB.sendRequest('tools/call', { name: 'multiply', arguments: { a: 50, b: 4 } });
    console.log(`用户B 计算 50 * 4 = ${resultB.content[0].text}`);

    // 用户C计算：100 - 25
    // 预期结果：75.0（标准减法）
    // 返回结构:
    // {
    //   "jsonrpc": "2.0",
    //   "id": "req-xxx",
    //   "result": {
    //     "content": [{ "type": "text", "text": "75.0" }]
    //   }
    // }
    const resultC = await clientC.sendRequest('tools/call', { name: 'subtract', arguments: { a: 100, b: 25 } });
    console.log(`用户C 计算 100 - 25 = ${resultC.content[0].text}`);

    // ========== 关闭会话 ==========
    console.log('\n--- 关闭会话 ---');
    clientA.close();
    clientB.close();
    clientC.close();

    console.log('\n=== 示范完成 ===');
}

main().catch(console.error);