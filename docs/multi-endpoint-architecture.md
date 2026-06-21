# hermes-agent vs Claude Code — 多端交互架构深度对比

> 分析日期: 2026-06-07
> 覆盖: CLI、Web、Desktop、SDK/API、消息平台 (Telegram/Discord/WhatsApp)

---

## 目录

1. [全景对比](#1-全景对比)
2. [hermes-agent: Gateway + Platform Adapter 模式](#2-hermes-agent-gateway--platform-adapter-模式)
3. [Claude Code: Bridge + SDK + REPL 三通道模式](#3-claude-code-bridge--sdk--repl-三通道模式)
4. [hermes-agent 详细架构](#4-hermes-agent-详细架构)
5. [Claude Code 详细架构](#5-claude-code-详细架构)
6. [关键差异分析](#6-关键差异分析)
7. [对 mochagent 的建议](#7-对-mochagent-的建议)

---

## 1. 全景对比

```
                    hermes-agent                          Claude Code
                    ─────────────                          ──────────

CLI 终端     ✅ cli.py (直接 Python 进程)          ✅ CLI (Bun + React/Ink)

Web UI       ✅ web/ (FastAPI + WebSocket)          ✅ claude.ai (Bridge 协议)
                                                      └── WS + POST events

Desktop      ✅ tui_gateway/ (JSON-RPC stdio)       ✅ Desktop App (Bridge/WS)
             └── 独立子进程 + StdioTransport          └── claude.ai 前端 + Bridge backend

SDK / API    ❌ 无独立 SDK                            ✅ Agent SDK (npm package)
                                                      └── query(prompt, options) → AsyncGenerator<SDKMessage>

消息平台     ✅ Gateway + 28+ Platform Adapters      ❌ 无内置
             └── Telegram, Discord, Slack,
                WhatsApp, WeChat, DingTalk,
                Feishu, Signal, Matrix,
                Email, SMS, BlueBubbles...

远程控制     ❌ 无                                    ✅ /remote-control (Bridge)
                                                      └── claude.ai → 本地 CLI 双向控制

IDE 集成     ⚠️ ACP 协议 (copilot)                   ✅ Agent SDK
                                                      └── TypeScript/Python SDK
```

---

## 2. hermes-agent: Gateway + Platform Adapter 模式

### 2.1 架构分层

```
┌─────────────────────────────────────────────────────────────────────┐
│                        hermes-agent 多端架构                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─ ENTRYPOINTS ─────────────────────────────────────────────────┐  │
│  │                                                                │  │
│  │  CLI:    python cli.py chat                                    │  │
│  │         → AIAgent.run_conversation()                          │  │
│  │         → 本地终端 Rich/Textual UI                             │  │
│  │                                                                │  │
│  │  Gateway: python cli.py --gateway                              │  │
│  │         → GatewayRunner.start_gateway()                        │  │
│  │         → 启动所有 platform adapters (并行)                    │  │
│  │                                                                │  │
│  │  Web:    python -m web.server                                  │  │
│  │         → FastAPI + WebSocket                                  │  │
│  │         → AIAgent.run_conversation() 通过 REST/WS              │  │
│  │                                                                │  │
│  │  TUI:    python -m tui_gateway.server                          │  │
│  │         → JSON-RPC over stdin/stdout                           │  │
│  │         → 独立子进程 + StdioTransport                          │  │
│  │                                                                │  │
│  │  Cron:   python cli.py cron                                    │  │
│  │         → 定时任务调度 (cron/scheduler.py)                     │  │
│  │                                                                │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ GATEWAY CORE ────────────────────────────────────────────────┐  │
│  │  gateway/run.py: GatewayRunner                                  │  │
│  │  gateway/config.py: Platform enum + GatewayConfig               │  │
│  │  gateway/session.py: SessionSource + session context            │  │
│  │  gateway/runtime_footer.py: 运行时显示                          │  │
│  │  gateway/delivery.py: 消息投递 (delivery manager)              │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ PLATFORM ADAPTERS (28+) ─────────────────────────────────────┐  │
│  │  gateway/platforms/base.py: BaseAdapter (ABC)                   │  │
│  │  gateway/platforms/telegram.py                                  │  │
│  │  gateway/platforms/discord.py                             ...  │  │
│  │  gateway/platforms/whatsapp.py                                  │  │
│  │  gateway/platforms/signal.py                                    │  │
│  │  gateway/platforms/slack.py                                     │  │
│  │  gateway/platforms/wecom.py                                     │  │
│  │  gateway/platforms/feishu.py                                    │  │
│  │  gateway/platforms/matrix.py                                    │  │
│  │  gateway/platforms/email.py                                     │  │
│  │  gateway/platforms/sms.py                                       │  │
│  │  ... (dingtalk, yuanbao, weixin, webhook, bluebubbles, ...)     │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Platform Adapter 基类

```python
# gateway/platforms/base.py
class BaseAdapter(ABC):
    """所有平台适配器的基类"""

    # —— 核心生命周期 ——
    async def start(self) -> None: ...       # 启动 adapter
    async def stop(self) -> None: ...        # 停止 adapter
    async def handle_event(self, event) -> None: ...  # 处理入站事件

    # —— 消息发送 ——
    async def send_message(self, chat_id, text, ...) -> None: ...
    async def send_media(self, chat_id, media, ...) -> None: ...

    # —— 会话管理 ——
    def build_session_source(self, event) -> SessionSource: ...
    # → platform, chat_id, user_id, chat_type, thread_id...
```

### 2.3 GatewayRunner 启动流程

```python
# gateway/run.py
class GatewayRunner:
    async def start_gateway(self):
        # 1. 加载 gateway.yaml 配置
        config = GatewayConfig.from_yaml()

        # 2. 为每个启用的 platform 创建 adapter 实例
        for platform_name, platform_config in config.platforms.items():
            adapter = load_adapter(platform_name, platform_config)
            self.adapters.append(adapter)

        # 3. 并行启动所有 adapter
        await asyncio.gather(*[
            adapter.start() for adapter in self.adapters
        ])

    async def dispatch_message(self, event, source: SessionSource):
        # 1. 从 agent cache 获取或创建 AIAgent
        agent = await self.get_or_create_agent(source)

        # 2. 运行对话
        result = await agent.run_conversation(
            user_message=event.text,
            session_id=source.session_id,
            platform=source.platform.value,
            user_id=source.user_id,
            chat_id=source.chat_id,
            ...
        )

        # 3. 投递响应回到正确的 platform adapter
        await source.platform.send_message(
            chat_id=source.chat_id,
            text=result.response,
            thread_id=source.thread_id,
        )
```

### 2.4 Agent Cache 策略

```python
# gateway/run.py
_AGENT_CACHE_MAX_SIZE = 128              # LRU 上限
_AGENT_CACHE_IDLE_TTL_SECS = 3600.0      # 1h idle → evict

# 缓存键: (platform, user_id, chat_id, profile_signature)
# Gateway 会为不同的用户/群聊创建独立的 AIAgent 实例

# Agent 重建机制:
# - cache miss → 创建新 AIAgent
# - 从 sessions.system_prompt 恢复 cached system prompt
# - 从 session DB 恢复 conversation history
# - prefix cache 保持命中 (因为 system prompt 不变)
```

### 2.5 SessionSource

```python
@dataclass
class SessionSource:
    platform: Platform              # LOCAL | TELEGRAM | DISCORD | WHATSAPP | ...
    chat_id: str                    # 聊天 ID (私聊 or 群聊)
    chat_name: Optional[str]        # 聊天名称
    chat_type: str = "dm"           # "dm" | "group" | "channel" | "thread"
    user_id: Optional[str]          # 用户 ID
    user_name: Optional[str]        # 用户名
    thread_id: Optional[str]        # 论坛主题 / Discord thread
    chat_topic: Optional[str]       # 频道描述
    user_id_alt: Optional[str]      # 备用稳定 ID (Signal UUID, Feishu union_id)
    chat_id_alt: Optional[str]      # Signal group internal ID
    is_bot: bool = False            # 消息作者是否 bot
    guild_id: Optional[str]         # Discord guild / Slack workspace scope
    parent_chat_id: Optional[str]   # 父频道 (当 chat_id 指向 thread 时)
    message_id: Optional[str]       # 触发消息 ID (用于 pin/reply)
```

---

## 3. Claude Code: Bridge + SDK + REPL 三通道模式

### 3.1 架构分层

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Claude Code 多端架构                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─ CLI ENTRYPOINTS ─────────────────────────────────────────────┐  │
│  │  entrypoints/cli.tsx    → 标准 CLI (Bun + React/Ink)          │  │
│  │  entrypoints/init.ts    → 初始化入口                          │  │
│  │  entrypoints/mcp.ts     → MCP 服务器入口                      │  │
│  │  entrypoints/sdk/       → Agent SDK (npm package)             │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ BRIDGE LAYER (云端 ↔ 本地双向通道) ─────────────────────────┐  │
│  │  bridge/replBridge.ts         → REPL ↔ claude.ai 桥梁         │  │
│  │  bridge/remoteBridgeCore.ts   → 远程会话核心                  │  │
│  │  bridge/bridgeMain.ts         → Bridge 入口                   │  │
│  │  bridge/sessionRunner.ts      → 远程会话 runner               │  │
│  │  bridge/createSession.ts      → POST /v1/sessions             │  │
│  │  bridge/bridgeMessaging.ts    → 消息处理 (ingress/control)    │  │
│  │  bridge/replBridgeTransport.ts→ 传输抽象 (v1/v2)             │  │
│  │  bridge/workSecret.ts         → JWT + 环境注册               │  │
│  │  bridge/trustedDevice.ts      → 设备信任                      │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ TRANSPORT LAYER ─────────────────────────────────────────────┐  │
│  │  cli/transports/HybridTransport.ts  → v1: WS read + POST write│  │
│  │  cli/transports/SSETransport.ts     → v2: SSE read            │  │
│  │  cli/transports/WebSocketTransport.ts → WS only (legacy)      │  │
│  │  cli/transports/ccrClient.ts        → v2: CCR write client    │  │
│  │  cli/transports/SerialBatchEventUploader.ts → 批量事件上传    │  │
│  │  cli/transports/WorkerStateUploader.ts → worker 状态上报      │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ REMOTE SESSION LAYER ────────────────────────────────────────┐  │
│  │  remote/RemoteSessionManager.ts  → CCR 远程会话管理           │  │
│  │  remote/SessionsWebSocket.ts     → 会话列表 WS                │  │
│  │  remote/remotePermissionBridge.ts→ 远程权限桥接               │  │
│  │  remote/sdkMessageAdapter.ts     → SDK 消息适配               │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ SDK LAYER (npm: @anthropic-ai/claude-code) ──────────────────┐  │
│  │  entrypoints/sdk/coreTypes.ts    → 核心序列化类型             │  │
│  │  entrypoints/sdk/runtimeTypes.ts → 运行时类型 (callbacks)     │  │
│  │  entrypoints/sdk/controlTypes.ts → 控制协议类型               │  │
│  │  query(prompt, options)          → AsyncGenerator<SDKMessage>  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 三个主要通道

```
通道 1: CLI (本地)
  user → React/Ink TUI → main.tsx → QueryEngine.submitMessage()
  → query() generator → Anthropic API
  ← StreamEvents ← yield ← normalize → display

通道 2: Bridge (claude.ai 远程控制)
  claude.ai → WS (cloud) → Bridge transport → replBridge.ts
  → handleIngressMessage() → QueryEngine.submitMessage()
  ← yield SDKMessage ← writeMessages() → transport.writeBatch()
  → POST /v1/sessions/events → claude.ai 展示

通道 3: Agent SDK (程序化)
  npm install @anthropic-ai/claude-code
  const q = query("Build a React app", { options })
  for await (const msg of q) { /* process SDKMessage */ }
```

---

## 4. hermes-agent 详细架构

### 4.1 CLI 模式

```
$ hermes chat
  → cli.py → AIAgent.__init__() → run_conversation(user_message)
  → 本地 Rich/Textual 终端 UI
  → session 持久化到 state.db (SQLite)
  → cwd = os.getcwd() (用户当前目录)
```

### 4.2 Gateway 模式 (消息平台)

```python
# gateway/run.py — 核心调度
class GatewayRunner:
    def __init__(self):
        self.agent_cache = OrderedDict()     # LRU cache (128上限, 1h TTL)
        self.adapters = []                   # 所有 platform adapter 实例
        self.session_contexts = {}           # 每用户的 session context

    async def _dispatch_inbound_event(self, event, adapter):
        source = adapter.build_session_source(event)

        # 1. 获取/创建 agent
        cache_key = (source.platform, source.chat_id, source.user_id)
        agent = await self._get_or_create_agent(cache_key, source)

        # 2. 运行对话
        result = await asyncio.to_thread(
            agent.run_conversation,
            user_message=event.text,
            conversation_history=source.session_history,
            platform=source.platform.value,
            user_id=source.user_id,
            chat_id=source.chat_id,
            chat_type=source.chat_type,
            thread_id=source.thread_id,
        )

        # 3. 投递响应
        await adapter.send_message(
            chat_id=source.chat_id,
            text=result['response'],
            reply_to=source.message_id,
            thread_id=source.thread_id,
        )

        # 4. 后台任务
        # - 自动标题生成
        # - 记忆审查 (memory nudge)
        # - 技能审查 (skill nudge)
```

### 4.3 Platform Adapter 实现示例 (Telegram)

```python
# gateway/platforms/telegram.py
class TelegramAdapter(BaseAdapter):
    async def start(self):
        self.bot = TelegramBot(token=self.config.bot_token)
        await self.bot.start_polling(self.handle_update)

    async def handle_update(self, update: Update):
        # 1. 解析 update → SessionSource
        source = SessionSource(
            platform=Platform.TELEGRAM,
            chat_id=str(update.chat_id),
            user_id=str(update.from_user.id),
            chat_type='group' if update.is_group else 'dm',
            message_id=str(update.message_id),
            thread_id=str(update.message_thread_id) if update.message_thread_id else None,
        )

        # 2. 构建 GatewayEvent
        event = GatewayEvent(
            text=update.text,
            source=source,
            attachments=update.attachments,
        )

        # 3. 提交到 GatewayRunner
        await self.gateway.dispatch_message(event, source)

    async def send_message(self, chat_id, text, reply_to=None, thread_id=None):
        await self.bot.send_message(
            chat_id=chat_id,
            text=text,
            reply_to_message_id=reply_to,
            message_thread_id=thread_id,
        )
```

### 4.4 配置文件 (gateway.yaml)

```yaml
gateway:
  platforms:
    telegram:
      enabled: true
      bot_token: "${TELEGRAM_BOT_TOKEN}"
      home_channel: "-1001234567890"     # 管理员频道 (接收通知)
      dm_behavior: "pair"                # pair | allow_all | ignore
      unauthorized_dm_behavior: "pair"
      notice_delivery: "public"

    discord:
      enabled: true
      bot_token: "${DISCORD_BOT_TOKEN}"
      home_channel: "1234567890"
      guild_id: "1234567890"

    whatsapp:
      enabled: true
      phone_number_id: "${WA_PHONE_NUMBER_ID}"
      access_token: "${WA_ACCESS_TOKEN}"
      webhook_verify_token: "${WA_VERIFY_TOKEN}"

    signal:
      enabled: true
      phone_number: "+1234567890"

    # ... 更多平台

  # 全局配置
  agent_cache_max_size: 128
  agent_cache_idle_ttl_seconds: 3600.0
  session_reset_policy: "on_command"     # on_command | on_new_chat | never
  default_toolset: "messaging"
```

### 4.5 TUI Gateway (Desktop-like)

```
┌──────────────────────────────────────────────────────┐
│                  TUI Gateway 架构                      │
├──────────────────────────────────────────────────────┤
│                                                       │
│  tui_gateway/entry.py                                 │
│    → TUI 进程入口                                     │
│                                                       │
│  tui_gateway/server.py                                │
│    → GatewayServer (JSON-RPC over stdio)              │
│    → 独立子进程 (subprocess)                          │
│    → StdioTransport: stdin/stdout JSON-RPC            │
│                                                       │
│  tui_gateway/transport.py                             │
│    → StdioTransport 实现                              │
│    → ContextVar 绑定 (current_transport)              │
│                                                       │
│  tui_gateway/render.py                                │
│    → 渲染逻辑 (Markdown → TUI)                        │
│                                                       │
│  tui_gateway/slash_worker.py                          │
│    → /command 异步 worker                             │
│                                                       │
│  tui_gateway/ws.py                                    │
│    → WebSocket 传输 (替代 stdio)                      │
│                                                       │
│  TUI 前端 (React): tui_gateway/src/                   │
│    → TypeScript + React 前端                          │
│    → 通过 stdio/WS 与后端通信                         │
│                                                       │
└──────────────────────────────────────────────────────┘
```

### 4.6 公共 API (对外服务)

```python
# gateway/run.py
class GatewayRunner:
    # 外部可注册自定义 adapter:
    def register_adapter(self, name: str, adapter: BaseAdapter): ...

    # 获取 session 状态:
    def get_session_status(self, chat_id: str) -> dict: ...

    # 强制重置 session:
    async def reset_session(self, chat_id: str) -> None: ...

    # 广播消息到所有平台:
    async def broadcast_to_all_platforms(self, text: str) -> None: ...
```

---

## 5. Claude Code 详细架构

### 5.1 CLI 模式

```
$ claude
  → entrypoints/cli.tsx → main.tsx (800KB React/Ink UI)
  → AppStateProvider → REPL.tsx → PromptInput
  → user types → processUserInput → QueryEngine.submitMessage()
  → query() AsyncGenerator → yield 所有中间消息
  → React 组件订阅 AppState → 实时渲染
```

### 5.2 Bridge 模式 (claude.ai ↔ 本地)

```typescript
// 1. 注册环境 (本地 CLI 端)
$ claude --bridge
  → initReplBridge()
    ├── registerWorker() → POST /v1/workers → 获取 environmentId
    ├── 创建 HybridTransport(environmentId)
    ├── 打开 WebSocket (接收 claude.ai 的入站消息)
    └── 设置 poll loop (长轮询配置)

// 2. Web 端 (claude.ai)
// 用户 → claude.ai 前端 → 发送消息到 Bridge
→ POST /v1/sessions/{bridgeSessionId}/events

// 3. 本地 CLI 接收
HybridTransport (WS read)
  ↓ onData callback
handleIngressMessage(transport, message)
  ├── isSDKMessage → 验证 type discriminant
  ├── isUserMessage → QueryEngine.submitMessage(prompt)
  ├── isControlRequest → handleServerControlRequest
  └── isControlResponse → handleClientControlResponse

// 4. 本地 CLI 响应
query() 产生 SDKMessage → writeMessages()
  ↓
transport.writeBatch(messages)
  ↓
POST /v1/sessions/{id}/events (批量)
  ↓
claude.ai 前端展示
```

### 5.3 Bridge 状态机

```typescript
// bridge/replBridge.ts
type BridgeState = 'ready' | 'connected' | 'reconnecting' | 'failed'

// 'ready':      环境已注册, 等待 Web 客户端连接
// 'connected':  WS 活跃, claude.ai 用户已连接
// 'reconnecting': WS 断开, 正在重连 (exponential backoff)
// 'failed':     重连耗尽或永久性错误
```

### 5.4 Transport 双版本

```typescript
// bridge/replBridgeTransport.ts

// v1 (Session-Ingress):
//   Read:  HybridTransport (WebSocket to Session-Ingress)
//   Write: POST /v1/sessions/events
//   → 用于 `claude remote-control` 和 `/remote-control`

// v2 (CCR - Claude Code Remote):
//   Read:  SSETransport (Server-Sent Events)
//   Write: CCRClient → SerialBatchEventUploader → POST /worker/events
//   → 用于 CCR worker / multi-session 场景
//   → 支持 reportState() / reportMetadata() / reportDelivery()

type ReplBridgeTransport = {
  write(message: StdoutMessage): Promise<void>
  writeBatch(messages: StdoutMessage[]): Promise<void>
  close(): void
  isConnectedStatus(): boolean
  getStateLabel(): string
  setOnData(callback: (data: string) => void): void
  setOnClose(callback: (closeCode?: number) => void): void
  setOnConnect(callback: () => void): void
  connect(): void
  getLastSequenceNum(): number
  readonly droppedBatchCount: number
  reportState(state: SessionState): void
  reportMetadata(metadata: Record<string, unknown>): void
  reportDelivery(eventId: string, status: 'processing' | 'processed'): void
  flush(): Promise<void>
}
```

### 5.5 Agent SDK 模式

```typescript
// npm install @anthropic-ai/claude-code
import { query } from '@anthropic-ai/claude-code'

const q = query('Build a React component library', {
  model: 'claude-sonnet-4-6',
  cwd: '/path/to/project',
  tools: [BashTool, FileWriteTool, ...],
  permissionMode: 'acceptEdits',
  maxTurns: 50,
  // ... 更多 options
})

for await (const message of q) {
  switch (message.type) {
    case 'assistant':
      console.log(message.message.content)
      break
    case 'result':
      console.log('Done:', message.result)
      break
    case 'system':
      console.log('System:', message.subtype)
      break
  }
}
```

### 5.6 SDKMessage 类型体系

```typescript
// 生成自 Zod schemas (coreSchemas.ts)
type SDKMessage =
  | SDKUserMessage          // 用户输入
  | SDKAssistantMessage     // 模型响应
  | SDKResultMessage        // 最终结果
  | SDKSessionInfo          // 会话信息
  | SDKStatus               // 状态更新
  | SDKPermissionDenial     // 权限拒绝
  | SDKCompactBoundaryMessage // 压缩边界
  | SDKUserMessageReplay    // 消息重放 (resume)
  // ... 更多

type SDKResultMessage = {
  type: 'result'
  subtype: 'success' | 'error' | 'error_during_execution'
  result: string
  usage: NonNullableUsage
  totalCostUSD: number
  // ...
}
```

---

## 6. 关键差异分析

### 6.1 多端哲学

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **核心理念** | One Agent, Many Platforms | One Agent, Three Channels |
| **消息平台** | 28+ 内置适配器, 用户只需配置 token | 无内置, 依赖第三方 |
| **SDK** | 无独立 SDK | 完整 npm SDK (TypeScript) |
| **Web UI** | 自建 FastAPI + WebSocket | 依赖 claude.ai (Bridge) |
| **远程控制** | 无 | ✅ /remote-control (claude.ai ↔ CLI) |
| **远程会话** | ❌ | ✅ CCR (Claude Code Remote) |
| **Desktop** | TUI gateway (独立子进程) | claude.ai Desktop (Bridge) |

### 6.2 状态隔离模型

```
hermes-agent:
  AIAgent per (platform, chat_id, user_id)
  → Gateway 为每个聊天上下文创建独立 agent 实例
  → 通过 agent cache 管理生命周期 (128 上限, 1h TTL)
  → ContextVar (HERMES_SESSION_CWD) 隔离每会话 cwd

Claude Code:
  每个 CLI 进程 = 一个活跃会话
  → QueryEngine per conversation (headless/SDK)
  → Bridge: 一个 replBridge 对应一个 environment
  → SDK: 每个 query() 调用 = 一个会话
```

### 6.3 消息流转

```
hermes-agent Gateway:
  Telegram Update
    → adapter.handle_update()
    → GatewayRunner.dispatch_message()
    → agent.run_conversation()
    → adapter.send_message()
  (同步风格, asyncio 异步)

Claude Code Bridge:
  claude.ai event
    → WS/SSE → transport.onData
    → handleIngressMessage()
    → QueryEngine.submitMessage()
    → writeMessages() → transport.writeBatch()
    → POST /v1/sessions/events
  (AsyncGenerator + 批量写入)
```

### 6.4 配置管理

```
hermes-agent:
  gateway.yaml:
    → platforms.<name>.enabled
    → platforms.<name>.bot_token / access_token
    → platforms.<name>.home_channel
    → platforms.<name>.dm_behavior
    → agent_cache_max_size
    → default_toolset

Claude Code:
  settings.json:
    → permissions.allow / deny / ask
    → model
    → enabledTools
    → hooks
  Bridge config:
    → CLAUDE_CODE_BRIDGE_ENABLED (runtime flag)
    → bridge transport version (v1/v2)
    → worker_type
```

---

## 7. 对 mochagent 的建议

### 7.1 推荐采用 hermes-agent 的 Gateway + Adapter 模式

Java 生态中此模式天然适合:

```
MochaGateway (类 GatewayRunner)
  ├── AgentCache (LRU, TTL)
  ├── PlatformRegistry (ServiceLoader SPI)
  └── PlatformAdapter 接口
      ├── TelegramAdapter
      ├── DiscordAdapter
      ├── SlackAdapter
      └── WebAdapter (内置)
```

### 7.2 推荐采用 Claude Code 的 SDK 模式

```java
// mochagent SDK
MochaClient client = MochaClient.builder()
    .model("claude-sonnet-4-6")
    .tools(List.of(new BashTool(), new FileWriteTool()))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Async iterator 模式
client.query("Build a React app")
    .subscribe(msg -> {
        switch (msg.type()) {
            case ASSISTANT -> System.out.println(msg.content());
            case RESULT -> System.out.println("Done: " + msg.result());
        }
    });
```

### 7.3 三层架构建议

```
┌─────────────────────────────────────────────────┐
│              mochagent 多端架构                   │
├─────────────────────────────────────────────────┤
│                                                  │
│  1. Core Engine Layer (平台无关)                 │
│     AgentEngine (类 QueryEngine)                 │
│     └── submitMessage(prompt) → Stream<Message>  │
│                                                  │
│  2. Transport Layer (多通道)                     │
│     ├── CLI: 直接调用 AgentEngine                │
│     ├── Web: REST/WebSocket + JSON               │
│     ├── SDK: Java API (类 Agent SDK)             │
│     └── Bridge: WebSocket (类 Claude Bridge)     │
│                                                  │
│  3. Platform Layer (消息平台)                     │
│     PlatformAdapter 接口                         │
│     ├── onMessage(text, userId, chatId)          │
│     └── sendMessage(text, chatId)                │
│     实现: TelegramBot, DiscordBot, etc.           │
│                                                  │
└─────────────────────────────────────────────────┘
```

### 7.4 优先级路线

| 优先级 | 任务 | 来源 |
|--------|------|------|
| P0 | AgentEngine 核心抽象 (streaming query) | Claude Code |
| P0 | SDK/API 模式 (Java Client API) | Claude Code |
| P1 | Gateway Runner + PlatformAdapter SPI | hermes-agent |
| P1 | Agent Cache (LRU, TTL) | hermes-agent |
| P2 | Web 端 (REST + WebSocket) | hermes-agent |
| P2 | Bridge 模式 (远程控制) | Claude Code |
| P3 | 消息平台 adapter (Telegram, Discord, ...) | hermes-agent |
