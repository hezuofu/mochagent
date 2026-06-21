# hermes-agent 新版本 深度架构分析

> 分析日期: 2026-06-07
> 目标版本: hermes-agent `dev` 分支 (Python 3.12+)
> 分析目的: 为 mochagent (Java) 的架构设计提供参考

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [模块提取与代码组织模式](#2-模块提取与代码组织模式)
3. [AIAgent 核心类](#3-aiagent-核心类)
4. [会话生命周期与会话存储](#4-会话生命周期与会话存储)
5. [工作空间 (Workspace) 概念](#5-工作空间-workspace-概念)
6. [Conversation Loop — 主对话循环](#6-conversation-loop--主对话循环)
7. [System Prompt 三层架构](#7-system-prompt-三层架构)
8. [Context Compression — 上下文压缩](#8-context-compression--上下文压缩)
9. [Tool System — 工具系统](#9-tool-system--工具系统)
10. [Memory System — 记忆系统](#10-memory-system--记忆系统)
11. [Error Classification & Recovery — 错误分类与恢复](#11-error-classification--恢复)
12. [Credential & Credit System](#12-credential--credit-system)
13. [Plugin System — 插件系统](#13-plugin-system--插件系统)
14. [Transport Layer — 传输层](#14-transport-layer--传输层)
15. [LSP Integration — 语言服务器集成](#15-lsp-integration--语言服务器集成)
16. [Skill System — 技能系统](#16-skill-system--技能系统)
17. [Gateway & Multi-Session — 网关与多会话](#17-gateway--multi-session)
18. [Batch Runner & RL — 批处理与强化学习](#18-batch-runner--rl)
19. [关键数据结构总览](#19-关键数据结构总览)
20. [对 mochagent 的架构启示](#20-对-mochagent-的架构启示)

---

## 1. 整体架构概览

hermes-agent 采用 **"模块提取" (Module Extraction)** 而非传统的 OOP 继承层次来组织代码。核心思想是：

- **AIAgent 是数据中心** — 持有所有状态字段
- **每个功能域是一个独立模块 (Python module)** — 模块中的顶层函数接受 `agent` 作为第一参数
- **"friend function" 模式** — 模块函数通过 `agent.xxx` 直接访问/修改 agent 状态

```
┌──────────────────────────────────────────────────────────────────┐
│                         run_agent.py                              │
│                     class AIAgent (~1500 行)                       │
│                                                                    │
│  __init__(60+ params) ──────► agent/agent_init.py (1400 行)      │
│  run_conversation() ────────► agent/conversation_loop.py (3900行)│
│  _build_system_prompt() ────► agent/system_prompt.py             │
│  _compress_context() ───────► agent/conversation_compression.py  │
│  _execute_tool_calls() ─────► agent/tool_executor.py (500行)     │
│  _get_session_db_for_recall()► hermes_state.py (4300行)          │
│                                                                    │
│  Composition (has-a):                                             │
│  ├── ContextCompressor         agent/context_compressor.py (1500) │
│  ├── MemoryManager             agent/memory_manager.py (650)      │
│  ├── _session_db (SessionDB)   hermes_state.py (4300)            │
│  ├── _memory_store             MemoryProvider 实现               │
│  ├── _credential_pool          agent/credential_pool.py (1000)   │
│  ├── _tool_guardrails          agent/tool_guardrails.py (230)     │
│  ├── _todo_store               Todo 状态存储                     │
│  ├── _compression_warning      压缩模型可行性警告                  │
│  └── 15+ callbacks             流式/工具/步骤/状态回调             │
└──────────────────────────────────────────────────────────────────┘
```

### 文件规模统计

| 模块 | 文件 | 大约行数 | 职责 |
|------|------|---------|------|
| 主 Agent | `run_agent.py` | ~1500 | AIAgent 类定义 + 薄包装方法 |
| Agent 初始化 | `agent/agent_init.py` | ~1400 | 60+参数的初始化逻辑 |
| 对话循环 | `agent/conversation_loop.py` | ~3900 | 单轮对话主循环 |
| 状态存储 | `hermes_state.py` | ~4300 | SQLite SessionDB + FTS5 |
| 上下文压缩 | `agent/context_compressor.py` | ~1500 | LLM摘要+尾保护 |
| Anthropic适配 | `agent/anthropic_adapter.py` | ~1200 | OpenAI↔Anthropic消息转换 |
| 提示词组装 | `agent/system_prompt.py` + `agent/prompt_builder.py` | ~1500 | 三层系统提示词 |
| 工具执行 | `agent/tool_executor.py` | ~500 | 顺序+并发工具分发 |
| 记忆管理 | `agent/memory_manager.py` | ~650 | 多Provider编排 |
| 错误分类 | `agent/error_classifier.py` | ~1000 | 30+种FailoverReason |
| 信用追踪 | `agent/credits_tracker.py` | ~900 | 余额/套餐解析 |
| 凭证池 | `agent/credential_pool.py` | ~1000 | API Key池+cooldown |
| 技能维护 | `agent/curator.py` | ~1000 | 后台技能维护 |
| 辅助客户端 | `agent/auxiliary_client.py` | ~1200 | 辅助LLM路由器 |
| LSP | `agent/lsp/` | ~1000 | 语言服务器集成 |
| 传输层 | `agent/transports/` | ~2000 | ChatCompletion/Anthropic/Codex |

---

## 2. 模块提取与代码组织模式

### 2.1 "Friend Function" 模式

hermes-agent 不采用传统的继承或多态来组织 agent 行为，而是将所有核心逻辑提取为模块级函数：

```python
# agent/conversation_loop.py
def run_conversation(
    agent,               # AIAgent 实例 (第一参数)
    user_message: str,
    system_message: str = None,
    conversation_history: List[Dict] = None,
    task_id: str = None,
    stream_callback = None,
    persist_user_message: str = None,
) -> Dict[str, Any]:
    """Run a complete conversation with tool calling until completion."""
    agent._ensure_db_session()
    # ... 3900行逻辑直接访问 agent.xxx 属性
```

AIAgent 自身仅保留薄包装：

```python
# run_agent.py
class AIAgent:
    def run_conversation(self, user_message, **kwargs):
        from agent.conversation_loop import run_conversation
        return run_conversation(self, user_message, **kwargs)
```

### 2.2 Lazy Import 模式

所有重量级 SDK 都采用延迟导入：

```python
# agent/auxiliary_client.py
if TYPE_CHECKING:
    from openai import OpenAI  # 类型检查时使用
else:
    # 运行时延迟加载 (~240ms 节省)
    _OPENAI_CLS_CACHE: Optional[type] = None
    def _load_openai_cls() -> type:
        global _OPENAI_CLS_CACHE
        if _OPENAI_CLS_CACHE is None:
            from openai import OpenAI as _cls
            _OPENAI_CLS_CACHE = _cls
        return _OPENAI_CLS_CACHE
```

同样的模式用于 Anthropic SDK (~220ms)、PyYAML 等。

### 2.3 测试兼容性 (_ra 模式)

模块提取后，测试中 `patch("run_agent.xxx")` 可能失败。hermes-agent 通过 `_ra()` 函数保持兼容：

```python
def _ra():
    """Lazy reference to run_agent so patches like
    run_agent.OpenAI / run_agent.cleanup_vm / ... still work."""
    import run_agent
    return run_agent

# 使用:
_r = _ra()
_r.cleanup_vm(task_id)  # 测试 patch("run_agent.cleanup_vm") 依然生效
```

### 2.4 设计模式映射

| 模式 | hermes-agent 中的运用 |
|------|----------------------|
| **Facade** | `AIAgent` 封装所有内部复杂性，外部只需 `agent.run_conversation(msg)` |
| **Strategy** | `ContextEngine` ABC — 可替换压缩引擎 (compressor/LCM/...) |
| **Chain of Responsibility** | Shell hooks → Python plugins → ToolGuardrail → 工具执行 |
| **Observer** | 15+ callbacks: `stream_delta_callback`, `tool_progress_callback`, `status_callback`, ... |
| **Template Method** | `MemoryProvider` ABC — 定义初始化/预取/同步/关闭模板 |
| **Singleton** | `LSPService` 进程级单例，`ToolResultStorage` 实例单例 |
| **Object Pool** | `CredentialPool` — API Key 实例池，含 cooldown/rotation |
| **State Machine** | `StreamingContextScrubber`, `StreamingThinkScrubber` — 流式内容过滤状态机 |
| **Decorator** | `IterationBudget` 包装 max_iterations with remaining/sub_budget |

---

## 3. AIAgent 核心类

### 3.1 构造函数参数 (60+)

```python
class AIAgent:
    def __init__(
        self,
        # ── Provider/Model (核心) ──
        base_url: str, api_key: str, provider: str, api_mode: str,
        model: str = "",
        max_iterations: int = 90,
        tool_delay: float = 1.0,

        # ── Toolsets ──
        enabled_toolsets: List[str], disabled_toolsets: List[str],

        # ── 会话 ──
        session_id: str = None,        # 预生成或自动生成
        session_db=None,                # SessionDB 实例 (外部注入)
        parent_session_id: str = None,  # 父会话 ID (子代理/压缩链)
        pass_session_id: bool = False,  # 跨进程传递

        # ── 平台/用户 ──
        platform: str = None,           # "cli"|"telegram"|"discord"|"cron"|"tui"
        user_id: str = None,            # 平台用户标识
        user_id_alt: str = None,        # 备用稳定标识
        user_name: str = None,
        chat_id: str = None,            # 群聊 ID
        chat_name: str = None,
        chat_type: str = None,          # "private"|"group"|"channel"
        thread_id: str = None,          # 消息线程 ID

        # ── Gateway ──
        gateway_session_key: str = None,# 网关会话缓存键

        # ── 15+ Callbacks ──
        tool_progress_callback, tool_start_callback, tool_complete_callback,
        thinking_callback, reasoning_callback, clarify_callback,
        step_callback, stream_delta_callback, interim_assistant_callback,
        tool_gen_callback, status_callback, notice_callback, notice_clear_callback,

        # ── 上下文/压缩 ──
        skip_context_files: bool = False,
        load_soul_identity: bool = False,
        checkpoints_enabled: bool = False,
        checkpoint_max_snapshots: int = 20,
        checkpoint_max_total_size_mb: int = 500,
        checkpoint_max_file_size_mb: int = 10,

        # ── 模型配置 ──
        max_tokens: int, reasoning_config: Dict,
        service_tier: str, request_overrides: Dict,
        prefill_messages: List[Dict],   # Few-shot priming
        fallback_model: Dict,           # 故障转移模型

        # ── 高级 ──
        skip_memory: bool = False,      # 子代理跳过记忆
        credential_pool=None,           # 多 Key 池
        iteration_budget: IterationBudget = None,
        ...
    ):
```

### 3.2 关键内部字段

```python
# agent/agent_init.py 中设置的字段:

# ── 会话相关 ──
agent.session_id: str                  # "20260607_abc123def"
agent._session_db: SessionDB           # SQLite 会话存储
agent._parent_session_id: str          # 父会话 ID
agent._session_db_created: bool        # DB 行已创建标记
agent._session_init_model_config: dict # 会话创建时的模型配置快照

# ── 系统提示词 ──
agent._cached_system_prompt: str       # 缓存的全系统提示词 (prefix cache 用)

# ── 记忆 ──
agent._memory_manager: MemoryManager   # 多 Provider 编排
agent._memory_store: MemoryStore       # 内置 Memory 工具后端
agent._memory_nudge_interval: int      # 记忆审查间隔 (turn数)
agent._turns_since_memory: int         # 自上次审查以来的 turn 数

# ── 工具 ──
agent.tools: List[Dict]                # 当前工具定义 (OpenAI格式)
agent.valid_tool_names: set            # 有效工具名集合
agent._toolset_distribution: str       # 工具集分布
agent._tool_guardrails: ToolGuardrailController
agent._tool_registry_generation: int   # 注册表版本号

# ── 模型/Provider ──
agent.provider: str
agent.model: str
agent.base_url: str
agent.api_key: str
agent.api_mode: str                    # "chat_completions"|"anthropic_messages"|"responses"
agent._context_engine: ContextEngine   # 可插拔 context engine

# ── 压缩 ──
agent.compression_enabled: bool
agent.context_compressor: ContextCompressor

# ── 计费/信用 ──
agent._credits_state: CreditsState
agent._credential_pool: CredentialPool

# ── 其他 ──
agent._todo_store: TodoStore
agent._checkpoint_manager: CheckpointManager
agent._tool_result_budget: BudgetConfig
```

---

## 4. 会话生命周期与会话存储

### 4.1 会话创建流程

```
┌─────────────────────────────────────────────────────────────────┐
│                     会话创建流程                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. AIAgent.__init__()                                          │
│     ├── session_id = 外部传入 或 自动生成                         │
│     │   格式: "{YYYYMMDD_HHMMSS}_{uuid前8位}"                    │
│     │   示例: "20260607_143025_a1b2c3d4"                        │
│     ├── session_id 写入 os.environ["HERMES_SESSION_ID"]         │
│     └── set_current_session_id(agent.session_id)                │
│                                                                  │
│  2. AIAgent.run_conversation(user_message)                      │
│     ├── _ensure_db_session()  ← 首次调用时才创建 DB 行            │
│     │   ├── CREATE 或 INSERT OR IGNORE INTO sessions (...)       │
│     │   └── _session_db_created = True                          │
│     ├── 创建或恢复 system_prompt                                  │
│     └── 执行对话...                                              │
│                                                                  │
│  3. 每轮结束后                                                    │
│     ├── _flush_messages_to_session_db() → INSERT INTO messages  │
│     ├── update_token_counts() → UPDATE sessions SET ...          │
│     └── sync_all() → MemoryManager 通知所有 Provider             │
│                                                                  │
│  4. 会话结束 (退出/压缩/超时)                                     │
│     └── end_session(reason) → UPDATE sessions SET ended_at, ... │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 SQLite Schema (hermes_state.py — SCHEMA_VERSION=14)

```sql
-- 会话元数据表
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,              -- 'cli'|'telegram'|'discord'|'tui'|'cron'|'webchat'|...
    user_id TEXT,
    model TEXT,
    model_config TEXT,                 -- JSON: 模型配置快照 + 可选的 _branched_from 标记
    system_prompt TEXT,                -- 完整系统提示词文本 (prefix cache恢复)
    parent_session_id TEXT,            -- 父会话ID (压缩链/branch链)
    started_at REAL NOT NULL,          -- Unix timestamp
    ended_at REAL,
    end_reason TEXT,                   -- 'compression'|'branched'|'tui_shutdown'|'orphaned_compression'|...
    message_count INTEGER DEFAULT 0,
    tool_call_count INTEGER DEFAULT 0,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    cache_read_tokens INTEGER DEFAULT 0,   -- Anthropic prompt cache 读取
    cache_write_tokens INTEGER DEFAULT 0,  -- Anthropic prompt cache 写入
    reasoning_tokens INTEGER DEFAULT 0,
    cwd TEXT,                           -- 工作目录 (持久化)
    billing_provider TEXT,              -- 计费 backend
    billing_base_url TEXT,
    billing_mode TEXT,
    estimated_cost_usd REAL,
    actual_cost_usd REAL,
    cost_status TEXT,                   -- 'estimated'|'actual'|'unavailable'
    cost_source TEXT,                   -- 费用来源
    pricing_version TEXT,
    title TEXT,                         -- 会话标题
    api_call_count INTEGER DEFAULT 0,
    handoff_state TEXT,                 -- ✅ Gateway 等待客户端连接时的离线状态
    handoff_platform TEXT,              -- ✅ 客户端平台
    handoff_error TEXT,                 -- ✅ 离线期间的错误
    rewind_count INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,-- 归档标记 (软删除)
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id)
);

-- 消息明细表
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    role TEXT NOT NULL,                 -- 'user'|'assistant'|'system'|'tool'
    content TEXT,                       -- 支持JSON编码的多模态内容 (NUL前缀标记)
    tool_call_id TEXT,                  -- 关联的 tool_call ID
    tool_calls TEXT,                    -- JSON: [{id, type:"function", function:{name,arguments}}]
    tool_name TEXT,                     -- tool role 消息对应的工具名
    timestamp REAL NOT NULL,
    token_count INTEGER,
    finish_reason TEXT,                 -- 'stop'|'length'|'tool_calls'|...
    reasoning TEXT,                     -- 推理/thinking 内容
    reasoning_content TEXT,             -- 备用推理内容
    reasoning_details TEXT,             -- JSON: 推理详情
    codex_reasoning_items TEXT,         -- Codex Responses API 推理项
    codex_message_items TEXT,           -- Codex 消息项
    platform_message_id TEXT,           -- 外部平台消息 ID (Telegram update_id 等)
    observed INTEGER DEFAULT 0,         -- 是否被用户观测到
    active INTEGER NOT NULL DEFAULT 1   -- 软删除 (rewind/undo 用)
);
```

### 4.3 关键索引

```sql
CREATE INDEX idx_sessions_source ON sessions(source);
CREATE INDEX idx_sessions_parent ON sessions(parent_session_id);
CREATE INDEX idx_sessions_started ON sessions(started_at DESC);
CREATE INDEX idx_messages_session ON messages(session_id, timestamp);
CREATE INDEX idx_messages_session_active ON messages(session_id, active, timestamp);
CREATE INDEX idx_messages_platform_msg_id
    ON messages(session_id, platform_message_id)
    WHERE platform_message_id IS NOT NULL;
CREATE UNIQUE INDEX idx_sessions_title_unique
    ON sessions(title) WHERE title IS NOT NULL;
```

### 4.4 FTS5 全文搜索

```sql
-- 双 FTS5 策略: 标准 + Trigram (CJK子串搜索)
CREATE VIRTUAL TABLE messages_fts USING fts5(content);
CREATE VIRTUAL TABLE messages_fts_trigram USING fts5(
    content, tokenize='trigram'
);

-- 触发器自动同步 (INSERT/UPDATE/DELETE)
CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content) VALUES (
        new.id,
        COALESCE(new.content, '') || ' ' ||
        COALESCE(new.tool_name, '') || ' ' ||
        COALESCE(new.tool_calls, '')
    );
END;
```

### 4.5 会话存储的设计亮点

1. **parent_session_id 链**: 压缩和分支创建新会话行，通过 `parent_session_id` 形成 lineage。`get_compression_tip()` 沿链找最新 continuation

2. **soft-delete (active 列)**: `/undo`、`/retry` 不删除消息，而是 `UPDATE messages SET active=0`。`get_messages()` 默认 `WHERE active=1`

3. **system_prompt 持久化**: 系统提示词缓存在 sessions 表，gateway 每消息重建 AIAgent 时直接恢复，不需要 rebuild（关键：保持 prompt cache 命中）

4. **FTS5 + Trigram 双索引**: 标准 FTS5 处理英文，trigram FTS5 处理 CJK/多语言子串搜索

5. **压缩锁 (compression_locks 表)**: 防止并发压缩竞态。5分钟 TTL，崩溃自动恢复。holder 字段包含 `pid:tid:agent_id:nonce` 用于诊断

6. **自动 schema 演进**: `_reconcile_columns()` diff SCHEMA_SQL 与 live table，自动 ADD 缺失列（Beets/sqlite-utils 模式）。版本号由 `SCHEMA_VERSION` 管理

7. **WAL + jitter retry**: WAL 模式提升并发，NFS/SMB 不可用时自动退到 DELETE 模式。写入使用 `BEGIN IMMEDIATE` + 随机 jitter (20-150ms) 重试防 convoy

8. **多模态内容编码**: `content` 字段支持 `\x00json:` 前缀标记的 JSON 编码多模态内容（list of text+image_url parts）

---

## 5. 工作空间 (Workspace) 概念

hermes-agent 有**多层次的工作空间概念**：

### 5.1 CWD — 执行工作目录

```
三层解析优先级 (agent/runtime_cwd.py):
  1. _SESSION_CWD ContextVar  — Gateway 多会话每用户独立 cwd
  2. TERMINAL_CWD 环境变量    — Cron/Gateway 启动时设置
  3. os.getcwd()              — CLI 本地模式默认
```

```python
# agent/runtime_cwd.py — 核心 API
resolve_agent_cwd()    → Path  # 用于工具执行
resolve_context_cwd()  → Path|None  # 用于上下文文件发现
set_session_cwd(cwd)   → Token  # Pin 逻辑 cwd
```

### 5.2 Git Worktree — LSP 工作空间

```python
# agent/lsp/workspace.py
find_git_worktree(start) → str|None    # 从 start 向上找 .git
is_inside_workspace(path, root) → bool  # 路径是否在工作空间内
nearest_root(path, markers) → Path     # 按语言特定标记找项目根
```

- LSP 仅在检测到 git 仓库时启用（门控）
- workspace cache: `{cwd: (worktree_root, is_git)}` — 避免重复 stat

### 5.3 项目目录 — SessionStore 级

```
~/.hermes/projects/<cwdHash>/
    <sessionId>.jsonl         — 对话转录 (JSONL格式)
    <sessionId>.meta.json     — 元数据
```

cwd 的 hash 用作项目标识，同一项目下的多次会话共享同一目录。

### 5.4 MemoryProvider 的 Workspace

```python
MemoryProvider.initialize(session_id, **kwargs):
    kwargs 包含:
      - hermes_home (str)      — Profile 根目录
      - platform (str)         — "cli"|"telegram"|...
      - agent_context (str)    — "primary"|"subagent"|"cron"|"flush"
      - agent_workspace (str)  — 共享工作空间名 (如 "hermes")
      - agent_identity (str)   — Profile 名称 (如 "coder")
```

`agent_workspace` 允许不同 profile 共享同一记忆后端命名空间。

### 5.5 文件操作安全边界

```python
# agent/file_safety.py
build_write_denied_paths(home) → set[str]     # 绝对禁止写入的路径
build_write_denied_prefixes(home) → list[str]  # 禁止写入的目录前缀
get_safe_write_root() → str|None               # HERMES_WRITE_SAFE_ROOT 环境变量
is_write_denied(path) → bool                   # 综合判断
```

敏感路径包括: `~/.ssh/`, `~/.aws/`, `~/.gnupg/`, `~/.kube/`, `~/.docker/`, `/etc/sudoers*`, `~/.env` 等

### 5.6 Context References 的 allowed_root

```python
# agent/context_references.py
# @file:path 引用默认限制在 cwd 内
allowed_root_path = allowed_root or cwd_path
# 超过 allowed_root 的路径访问被拒绝
resolved.relative_to(allowed_root)  # 抛出 ValueError → 拒绝
```

---

## 6. Conversation Loop — 主对话循环

### 6.1 完整流程

```
run_conversation(agent, user_message, ...)
│
├── 1. PRE-TURN SETUP
│   ├── _install_safe_stdio()              # 防 OSError (后台/daemon)
│   ├── _ensure_db_session()               # 创建 SQLite 会话行 (延迟)
│   ├── set_session_context(session_id)     # 日志上下文
│   ├── set_runtime_main(...)              # 通知 auxiliary_client 主模型
│   ├── set_current_write_origin(...)      # 设置 skill 写入来源
│   ├── _restore_primary_runtime()         # 恢复主 runtime (如果 fallback 激活)
│   ├── user_message 清理 (surrogate chars)
│   ├── 重置 per-turn 状态:
│   │   ├── _invalid_tool_retries = 0
│   │   ├── _invalid_json_retries = 0
│   │   ├── _empty_content_retries = 0
│   │   ├── _vision_supported = True
│   │   ├── tool_guardrails.reset_for_turn()
│   │   └── iteration_budget = IterationBudget(max_iterations)
│   ├── _cleanup_dead_connections()        # 清理僵尸 TCP 连接
│   └── 计算可观测性指标
│
├── 2. SYSTEM PROMPT RESOLUTION
│   ├── _restore_or_build_system_prompt()
│   │   ├── 尝试从 sessions.system_prompt 恢复 → prefix cache HIT
│   │   └── 失败/_cached_system_prompt=None → 从零构建 + 写回 DB
│   └── active_system_prompt = agent._cached_system_prompt
│
├── 3. PREFLIGHT COMPRESSION CHECK
│   ├── estimate_request_tokens_rough(messages, system_prompt, tools)
│   ├── 检查 should_defer_preflight_to_real_usage()
│   └── 如果 token > threshold → _compress_context() (最多3轮)
│
├── 4. MAIN AGENT LOOP (while not finished, up to max_iterations)
│   │
│   ├── 4a. BUILD API REQUEST
│   │   ├── 构建 messages (system + user + history + tool results)
│   │   ├── apply_anthropic_cache_control()  # 设置 prompt cache 断点
│   │   ├── 系统提示词注入 (条件化: tools>0 才注入工具指引)
│   │   └── 构建 api_kwargs (tools, max_tokens, temperature, ...)
│   │
│   ├── 4b. INTERRUPTIBLE API CALL
│   │   ├── 流式 path: stream=True → delta callback 实时推送
│   │   │   ├── StreamingContextScrubber.feed(delta)  # 过滤 memory-context 标签
│   │   │   └── StreamingThinkScrubber.feed(delta)    # 过滤 think 标签
│   │   └── 非流式 path: stream=False → 同步等待
│   │
│   ├── 4c. ERROR CLASSIFICATION & RECOVERY
│   │   ├── classify_api_error() → FailoverReason (30+ 种)
│   │   └── 差异化恢复:
│   │       ├── auth → refresh/rotate OAuth token
│   │       ├── billing/rate_limit → credential_pool.next()
│   │       ├── context_overflow → compress + retry
│   │       ├── image_too_large → shrink + retry
│   │       ├── overloaded/server_error → jittered_backoff
│   │       ├── content_policy_blocked → ABORT (同一 prompt 重试也是徒劳)
│   │       ├── model_not_found → fallback model
│   │       └── format_error → strip problematic tools + retry
│   │
│   ├── 4d. PARSE RESPONSE
│   │   ├── 提取 assistant_content (text)
│   │   ├── 提取 tool_calls (OpenAI 格式)
│   │   ├── 提取 reasoning/thinking 内容
│   │   ├── 提取 finish_reason
│   │   └── 提取 usage (prompt/completion/cache tokens)
│   │
│   ├── 4e. HANDLE RESPONSE
│   │   ├── finish_reason=stop (无 tool_calls) → 返回最终结果
│   │   ├── finish_reason=length → CONTINUE (内容截断)
│   │   ├── finish_reason=tool_calls → 执行工具
│   │   │   ├── ToolGuardrailController.check()  # 安全规则
│   │   │   ├── 顺序执行: _execute_tool_calls_sequential()
│   │   │   │   └── 逐个执行 + interrupt 检查 + 错误恢复
│   │   │   ├── 并发执行: _execute_tool_calls_concurrent()
│   │   │   │   └── ThreadPoolExecutor(max_workers=8)
│   │   │   │       根据 Tool.is_concurrency_safe() 分组
│   │   │   ├── maybe_persist_tool_result() → /tmp/hermes-results/
│   │   │   └── enforce_turn_budget() → 聚合超限 spilling
│   │   ├── append assistant message + tool results to messages[]
│   │   └── 更新 token 计数 + usage tracking
│   │
│   └── 4f. POST-ITERATION CHECK
│       ├── 检查 iteration_budget.remaining()
│       ├── 检查 _tool_guardrail_halt_decision (工具护栏触发停止)
│       └── 检查 should_compress() → 可能触发压缩
│
├── 5. POST-TURN PROCESSING
│   ├── _flush_messages_to_session_db()     → INSERT INTO messages
│   ├── update_token_counts()               → UPDATE sessions
│   ├── save_trajectory()                   → trajectory JSONL (batch模式)
│   ├── memory_manager.sync_all()           → 通知所有 Provider
│   ├── memory_manager.queue_prefetch_all() → 后台预取
│   ├── _maybe_nudge_skills()              → 定期 skill 审查检查
│   ├── _maybe_nudge_memory()              → 定期 memory 审查检查
│   └── maybe_auto_title()                 → 首次 exchange 后异步生成标题
│
└── 6. RETURN RESULT
    └── {
          "response": final_assistant_text,
          "messages": full_message_list,
          "input_tokens": ...,
          "output_tokens": ...,
          "tool_calls_count": ...,
          ...
        }
```

### 6.2 工具并发执行

```python
# agent/tool_executor.py
_MAX_TOOL_WORKERS = 8  # 最大并发线程数

def _execute_tool_calls_concurrent(agent, tool_calls, ...):
    """按 concurrency_safe 分组: 连续安全工具 → 同批并行"""
    futures = {}
    with ThreadPoolExecutor(max_workers=_MAX_TOOL_WORKERS) as pool:
        for tc in tool_calls:
            futures[pool.submit(execute_single, tc)] = tc
    # 收集结果，按原始顺序排列
```

### 6.3 流式 Content Scrubbing

两个状态机在流式传输期间并发运行：

**StreamingContextScrubber** (`agent/memory_manager.py:62`)
- 过滤 `<memory-context>...</memory-context>` 标签对
- 过滤 `[System note: The following is recalled memory context...]` 系统注释
- 处理跨 chunk 边界的标签碎片 → 在内部缓冲中 hold back 不完整的标签

**StreamingThinkScrubber** (`agent/think_scrubber.py`)
- 过滤 `<think>...</think>` 标签对（非原生 thinking 模型）
- 同样的跨 chunk 边界处理

---

## 7. System Prompt 三层架构

### 7.1 分层设计

```
┌──────────────────────────────────────────────────────────────┐
│                   SYSTEM PROMPT 结构                          │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─ STABLE TIER (稳定的) ─────────────────────────────────┐  │
│  │  1. 身份定义 (SOUL.md 或 DEFAULT_AGENT_IDENTITY)        │  │
│  │  2. 工具使用强制指引 (仅 tools>0 时注入)                │  │
│  │  3. Hermes 帮助指引 + 文档链接                          │  │
│  │  4. Memory 工具使用指引                                 │  │
│  │  5. Skills 加载指引                                     │  │
│  │  6. Session Search 指引                                 │  │
│  │  7. Kanban 协调指引 (仅 kanban worker)                  │  │
│  │  8. Task Completion 指引                                │  │
│  │  9. Steer Channel 提示                                  │  │
│  │  10. 模型家族操作指引 (OpenAI/Google/Anthropic)         │  │
│  │  11. 环境提示 (OS, Shell, 日期, 平台)                   │  │
│  │  12. Computer Use 指引 (仅 macOS + cua-driver)          │  │
│  │  13. Platform Hints (平台特有约束)                      │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─ CONTEXT TIER (上下文) ────────────────────────────────┐  │
│  │  14. Caller-supplied system_message                     │  │
│  │  15. Context Files: AGENTS.md, .cursorrules, CLAUDE.md, │  │
│  │      .hermes.md, HERMES.md, .github/copilot-instructions│  │
│  │      (从 cwd 向上遍历 git root 发现)                    │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─ VOLATILE TIER (动态的) ───────────────────────────────┐  │
│  │  16. Memory snapshot (builtin memory 提取)              │  │
│  │  17. USER.md profile 内容                               │  │
│  │  18. External memory provider block                     │  │
│  │  19. MemoryManager.build_system_prompt() 组合           │  │
│  │  20. Timestamp / Session / Model / Provider 信息行      │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  所有层用 \n\n 连接。整个字符串缓存在                          │
│  agent._cached_system_prompt 中，整个 session 生命周期不变。   │
│  仅在 context compression 后重建。                             │
└──────────────────────────────────────────────────────────────┘
```

### 7.2 Prompt Cache 策略

hermes-agent 的 system prompt 缓存策略对应 Anthropic prompt cache:

- **静态前缀保持稳定**: stable tier 的内容在 session 内不变
- **全 session 复用**: gateway 每消息创建新 AIAgent 时从 `sessions.system_prompt` 恢复
- **显式缓存断点**: `apply_anthropic_cache_control()` 在消息列表的合适位置设置 `cache_control: {"type": "ephemeral"}` 标记
- **重建触发**: 仅 context compression 后重建（记忆内容变化）

### 7.3 条件化注入 (hermes-agent 关键模式)

```python
# agent/prompt_builder.py
# 工具使用强制指引仅在 tools > 0 时注入
if tools and len(tools) > 0:
    stable_parts.append(TOOL_USE_ENFORCEMENT_GUIDANCE)

# Computer use 指引仅 macOS
if platform.system() == "Darwin":
    stable_parts.append(COMPUTER_USE_GUIDANCE)

# Kanban 指引仅 kanban worker
if os.getenv("HERMES_KANBAN_TASK"):
    stable_parts.append(KANBAN_GUIDANCE)
```

---

## 8. Context Compression — 上下文压缩

### 8.1 架构

```
ContextEngine (ABC)                    ← agent/context_engine.py
    │
    └── ContextCompressor (默认实现)    ← agent/context_compressor.py
         │
         ├── update_from_response(usage)
         ├── should_compress(prompt_tokens) → bool
         ├── should_compress_preflight(messages) → bool
         ├── compress(messages, current_tokens, focus_topic) → List[Dict]
         ├── on_session_start(session_id)
         ├── on_session_end(session_id, messages)
         └── get_status() → Dict
```

### 8.2 触发条件

```python
# 默认参数
threshold_percent = 0.75     # 使用 75% context_length 时触发
protect_first_n = 3          # 始终保护前 N 条非系统消息
protect_last_n = 6           # 始终保护后 N 条消息

# 双触发机制:
# 1. 正常触发: API 响应后 prompt_tokens >= context_length * 0.75
# 2. Preflight: API 调用前 rough estimate >= threshold (防止小模型切换时溢出)
```

### 8.3 压缩流程

```
compress_context(agent, messages, system_message, ...)
│
├── 1. try_acquire_compression_lock(session_id)
│   └── 失败 → 跳过压缩 (另一个实例正在处理)
│
├── 2. ContextCompressor.compress(messages)
│   ├── 计算 token 预算
│   │   ├── context_length: 模型最大 context
│   │   ├── tail budget: 保护后 protect_last_n 条消息
│   │   └── summary budget: max(2000, 被压内容*20%, 12000上限)
│   │
│   ├── 分离 head/tail/middle
│   │   ├── head: 前 protect_first_n 条 (始终保护)
│   │   ├── tail: 后 protect_last_n 条 (始终保护)
│   │   └── middle: 剩余 → 交给辅助 LLM 做摘要
│   │
│   ├── 工具输出预剪枝 (cheap pre-pass)
│   │   └── 大工具结果替换为 _PRUNED_TOOL_PLACEHOLDER
│   │
│   ├── 辅助 LLM 调用 (便宜/快 模型)
│   │   ├── 使用结构化摘要模板:
│   │   │   ├── ## Active Task (当前进行中的任务)
│   │   │   ├── ## Completed (已完成)
│   │   │   ├── ## In Progress (进行中)
│   │   │   ├── ## Pending User Asks (等待用户回复)
│   │   │   ├── ## Resolved Questions (已解决问题)
│   │   │   ├── ## Relevant Files & Paths (相关文件)
│   │   │   ├── ## Key Decisions & Conventions (关键决策)
│   │   │   ├── ## Remaining Work (剩余工作) — 替代 "Next Steps"
│   │   │   └── ## Errors & Issues (错误和问题)
│   │   └── 迭代更新: 保留之前摘要的信息 (跨多次压缩)
│   │
│   └── 返回: SUMMARY_PREFIX + 摘要 + protected tail messages
│
├── 3. 会话旋转 (session rotation)
│   ├── 旧 session → end_session("compression")
│   ├── 新 session → create_session(parent_session_id=old_id)
│   └── agent.session_id = new_session_id
│
├── 4. 重建 system prompt
│   ├── agent._cached_system_prompt = _build_system_prompt()
│   └── update_system_prompt(new_session_id, ...)
│
├── 5. 通知所有 Provider
│   ├── memory_manager.on_session_switch(new_session_id)
│   ├── context_engine.on_session_end(old_id, messages)
│   └── context_engine.on_session_start(new_id)
│
├── 6. release_compression_lock()
│
└── 返回 (compressed_messages, new_system_prompt)
```

### 8.4 压缩锁机制

```sql
CREATE TABLE compression_locks (
    session_id TEXT PRIMARY KEY,
    holder TEXT NOT NULL,         -- "pid=123:tid=456:agent=abc:nonce=def12345"
    acquired_at REAL NOT NULL,
    expires_at REAL NOT NULL      -- TTL = 300s (5分钟)
);
```

- `try_acquire_compression_lock()` — 尝试获取锁，过期锁自动回收
- `release_compression_lock()` — 释放锁
- 解决两个 agent 实例同时压缩同一 session 的竞态问题（主 agent + background review fork）

---

## 9. Tool System — 工具系统

### 9.1 注册机制

```python
# tools/registry.py — 中央注册表
class ToolEntry:
    __slots__ = (
        "name", "toolset", "schema", "handler", "check_fn",
        "requires_env", "is_async", "description", "emoji",
        "max_result_size_chars", "dynamic_schema_overrides",
    )

# 工具模块自注册: 每个 tools/*.py 在模块级调用
registry.register(
    name="web_search",
    toolset="web",
    schema={...},          # OpenAI function calling schema
    handler=web_search_tool,
    check_fn=check_web_search_available,  # 可选: 环境检查
    requires_env=False,
    is_async=True,
    description="Search the web",
    emoji="🔍",
    max_result_size_chars=100_000,
)
```

### 9.2 工具发现 (AST-based)

```python
# 扫描语法树检测 registry.register() 调用
def _module_registers_tools(module_path: Path) -> bool:
    source = module_path.read_text()
    tree = ast.parse(source)
    return any(_is_registry_register_call(stmt) for stmt in tree.body)

# 自动发现: tools/*.py → import → 注册
discover_builtin_tools() → List[str]  # 返回模块名列表
```

### 9.3 工具集 (Toolsets)

```python
# toolsets.py
TOOLSETS = {
    "web": {"tools": ["web_search", "web_extract"], "includes": []},
    "terminal": {"tools": ["terminal", "process"], "includes": []},
    "files": {"tools": ["read_file", "write_file", "patch", "search_files"], "includes": []},
    "browser": {"tools": ["browser_navigate", "browser_snapshot", ...], "includes": []},
    "coding": {"tools": [...], "includes": ["files", "terminal", "web"]},
    "research": {"tools": [...], "includes": ["web", "files"]},
    "full_stack": {"includes": ["coding", "browser", "research", ...]},
    ...
}

# 工具集解析: 递归展开 includes
resolve_toolset("coding") → {"read_file", "write_file", "patch", ..., "terminal", ...}
```

### 9.4 工具集分布 (Toolset Distributions)

```python
# toolset_distributions.py
# 用于 batch runner: 不同任务类型分配不同的工具集组合
DISTRIBUTIONS = {
    "image_gen": {"image_gen": 1.0},
    "coding": {"coding": 0.5, "full_stack": 0.3, "research": 0.2},
    ...
}
```

### 9.5 工具执行安全链

```
ToolGuardrailController.check()
    │
    ├── TOOL BLOCK 规则:
    │   ├── 并发限制: max_concurrent_children 检查 (delegate_task)
    │   ├── 嵌套限制: max_spawn_depth 检查 (delegate_task)
    │   ├── 命令过滤: deny_commands + allow_commands 列表
    │   └── 参数注入防护
    │
    └── 返回 ToolGuardrailDecision:
        ├── ALLOW: 正常执行
        ├── BLOCK: 拒绝 (返回错误结果)
        └── HALT: 停止整个 agent 循环
```

### 9.6 工具结果管理

```python
# tools/tool_result_storage.py — 三层防御

# L1: Per-tool output cap (工具内部截断)
# L2: Per-result persistence (大结果溢出到文件)
maybe_persist_tool_result(content, tool_name, env)
# → 超过 max_result_size_chars 时写入 /tmp/hermes-results/{uuid}.txt
# → 返回: [Result stored: /tmp/... (N chars, preview below)]\n{preview}

# L3: Per-turn aggregate budget (200K chars 总上限)
enforce_turn_budget(results, env)
# → 如果聚合超过 MAX_TURN_BUDGET_CHARS，最大结果优先溢出
```

---

## 10. Memory System — 记忆系统

### 10.1 架构

```
MemoryManager (Facade — agent/memory_manager.py)
    │
    ├── Builtin Provider (always present, always first)
    │   └── MemoryStore (Markdown 文件: MEMORY.md / USER.md)
    │       ├── memory tool → write to MEMORY.md
    │       └── user tool   → write to USER.md
    │
    └── External Provider (最多1个, memory.provider config 决定)
        ├── Honcho, Hindsight, Mem0, ...
        └── 实现 MemoryProvider ABC
```

### 10.2 MemoryProvider ABC

```python
class MemoryProvider(ABC):
    """Abstract base class for memory providers."""

    @property
    def name(self) -> str: ...

    # Core lifecycle
    def is_available(self) -> bool: ...
    def initialize(self, session_id, **kwargs) -> None: ...
    def system_prompt_block(self) -> str: ...     # → 系统提示词
    def prefetch(self, query, *, session_id) -> str: ...  # → 轮前回忆
    def queue_prefetch(self, query, *, session_id) -> None: ...
    def sync_turn(self, user, assistant, *, session_id, messages) -> None: ...
    def get_tool_schemas(self) -> List[Dict]: ...
    def handle_tool_call(self, tool_name, args, **kwargs) -> str: ...
    def shutdown(self) -> None: ...

    # Optional hooks
    def on_turn_start(self, turn_number, message, **kwargs) -> None: ...
    def on_session_end(self, messages) -> None: ...
    def on_session_switch(self, new_session_id, **kwargs) -> None: ...
    def on_pre_compress(self, messages) -> str: ...     # → 压缩前提取
    def on_memory_write(self, action, target, content, metadata) -> None: ...
    def on_delegation(self, task, result, *, child_session_id) -> None: ...
    def get_config_schema(self) -> List[Dict]: ...
    def save_config(self, values, hermes_home) -> None: ...
```

### 10.3 MemoryManager (Facade)

```python
class MemoryManager:
    """Orchestrates the built-in provider plus at most one external provider."""

    def __init__(self):
        self._providers: List[MemoryProvider] = []
        self._tool_to_provider: Dict[str, MemoryProvider] = {}
        self._has_external: bool = False

    # Registration
    def add_provider(self, provider) -> None:
        """Builtin always accepted; external rejected if one already exists."""

    # Core API
    def build_system_prompt(self) -> str      # → 所有 provider 的 system_prompt_block()
    def prefetch_all(self, query, session_id) -> str  # → 所有 provider 的 prefetch()
    def queue_prefetch_all(self, query, session_id)   # → 后台预取
    def sync_all(self, user, assistant, session_id, messages)  # → 同步所有

    # Tool routing
    def get_all_tool_schemas(self) -> List[Dict]
    def handle_tool_call(self, tool_name, args) -> str  # → 路由到正确 provider

    # Lifecycle
    def on_turn_start(self, turn_number, message, **kwargs)
    def on_session_end(self, messages)
    def on_session_switch(self, new_session_id, **kwargs)
    def on_pre_compress(self, messages) -> str
    def on_memory_write(self, action, target, content, metadata)
    def on_delegation(self, task, result, **kwargs)
    def initialize_all(self, session_id, **kwargs)
    def shutdown_all(self)
```

### 10.4 Memory Context Fencing

memories 被注入为 fenced blocks，包含系统注释：

```
<memory-context>
[System note: The following is recalled memory context, NOT new user input.
Treat as authoritative reference data — this is the agent's persistent memory
and should inform all responses.]

{provider_content}
</memory-context>
```

- `sanitize_context()` — 静态移除 fence 标签和系统注释
- `StreamingContextScrubber` — 流式过滤跨 chunk 边界的 memory-context span

---

## 11. Error Classification & Recovery — 错误分类与恢复

### 11.1 FailoverReason 枚举 (30+ 种)

```python
class FailoverReason(enum.Enum):
    # 认证
    auth = "auth"                          # 401/403
    auth_permanent = "auth_permanent"      # 刷新后仍失败

    # 计费/配额
    billing = "billing"                    # 402 / 信用耗尽
    rate_limit = "rate_limit"              # 429

    # 服务端
    overloaded = "overloaded"              # 503/529
    server_error = "server_error"          # 500/502

    # 传输
    timeout = "timeout"

    # 上下文/Payload
    context_overflow = "context_overflow"   # Context too large
    payload_too_large = "payload_too_large" # 413
    image_too_large = "image_too_large"     # Image size limit

    # 模型/Provider 策略
    model_not_found = "model_not_found"
    provider_policy_blocked = "provider_policy_blocked"
    content_policy_blocked = "content_policy_blocked"  # 不应重试

    # 请求格式
    format_error = "format_error"
    invalid_encrypted_content = "invalid_encrypted_content"
    multimodal_tool_content_unsupported = "multimodal_tool_content_unsupported"

    # Provider 特定
    thinking_signature = "thinking_signature"
    long_context_tier = "long_context_tier"
    oauth_long_context_beta_forbidden = "oauth_long_context_beta_forbidden"
    llama_cpp_grammar_pattern = "llama_cpp_grammar_pattern"

    # 兜底
    unknown = "unknown"
```

### 11.2 分类器输出

```python
@dataclass
class ClassifiedError:
    reason: FailoverReason
    status_code: Optional[int]
    provider: Optional[str]
    model: Optional[str]
    message: str
    error_context: Dict[str, Any]

    # Recovery hints:
    should_retry: bool
    should_rotate_credential: bool
    should_fallback_model: bool
    should_compress: bool
    retry_delay_seconds: float
```

### 11.3 差异化重试策略

```python
# agent/retry_utils.py
def jittered_backoff(attempt: int, base_delay: float = 1.0, max_delay: float = 60.0) -> float:
    """Exponential backoff with random jitter: base * 2^attempt + jitter"""

# 按错误类型的重试策略:
# auth          → refresh token, retry 1 time
# billing       → rotate credential from pool, retry
# rate_limit    → read Retry-After header or jittered backoff, rotate
# timeout       → rebuild HTTP client, retry 1 time
# server_error  → jittered backoff, max 3 retries
# context_overflow → compress immediately, no retry count limit
# format_error  → strip problematic parts, retry 1 time
# content_policy_blocked → ABORT (deterministic, same input = same rejection)
```

---

## 12. Credential & Credit System

### 12.1 CredentialPool

```python
# agent/credential_pool.py
class CredentialPool:
    """Multiple API key management with cooldown and rotation."""

    # 来源:
    # - config.yaml credential_pool entries
    # - OPENAI_API_KEY / ANTHROPIC_API_KEY env vars
    # - ~/.hermes/auth.json (OAuth tokens)
    # - Claude Code credentials (~/.claude.json / ~/.claude/.credentials.json)

    # 状态 per entry:
    STATUS_ACTIVE = "active"
    STATUS_RATE_LIMITED = "rate_limited"  # 429 → cooldown
    STATUS_EXHAUSTED = "exhausted"        # 402 → do not reuse
    STATUS_AUTH_FAILED = "auth_failed"    # 401/403 → do not reuse

    def next(self) -> CredentialEntry:
        """Next available credential, skipping exhausted/rate-limited ones."""
```

### 12.2 CreditsTracker

```python
# agent/credits_tracker.py
# 解析 x-nous-credits-* 响应头

@dataclass
class CreditsState:
    version: int
    remaining_micros: int         # 剩余总余额 (微元)
    subscription_micros: int      # 订阅余额 (可为负=debt)
    subscription_limit_micros: int # 订阅上限
    rollover_micros: int          # 滚存余额
    purchased_micros: int         # 购买余额
    paid_access: bool             # 付费访问
    disabled_reason: Optional[str]
    tool_pool_micros: int         # 工具池余额
    tool_pool_gated_off: bool
```

### 12.3 AccountUsageSnapshot

```python
# agent/account_usage.py
@dataclass
class AccountUsageSnapshot:
    provider: str
    source: str
    fetched_at: datetime
    plan: Optional[str]
    windows: tuple[AccountUsageWindow, ...]  # 多个使用窗口 (订阅/购买/rollover)

@dataclass
class AccountUsageWindow:
    label: str                     # "Subscription" / "Purchased" / "Rollover"
    used_percent: Optional[float]
    reset_at: Optional[datetime]   # 重置时间
```

### 12.4 多种 API 来源

```python
# agent/credential_sources.py
# 支持的 API key 来源:
# 1. 直接 API key: sk-ant-api..., sk-or-v1-..., etc.
# 2. OAuth setup-token: sk-ant-oat... (→ Bearer auth)
# 3. Claude Code credentials: ~/.claude.json + macOS keychain
# 4. Codex OAuth: ~/.hermes/.codex_access_token
# 5. Azure Identity: DefaultAzureCredential chain
# 6. Google OAuth: gcloud auth + Gemini Code Assist
# 7. Portal OAuth: Nous Portal (auth.json)
# 8. 自定义 endpoint: OPENAI_API_KEY + base_url
```

---

## 13. Plugin System — 插件系统

### 13.1 四种来源

```
1. Bundled plugins:  <repo>/plugins/<name>/
2. User plugins:     ~/.hermes/plugins/<name>/
3. Project plugins:  ./.hermes/plugins/<name>/
                     (需 HERMES_ENABLE_PROJECT_PLUGINS=1)
4. Pip plugins:      hermes_agent.plugins entry-point group
```

后面来源覆盖前面同名的 (project > user > bundled)。

### 13.2 插件结构

```
plugins/<name>/
    __init__.py      ← register(ctx) 函数 + 可选 hook 处理器
    plugin.yaml      ← 清单文件: name, version, description, author
```

### 13.3 PluginContext API

```python
class PluginContext:
    plugin_name: str
    plugin_dir: Path

    def register_tool(self, name, schema, handler, **kwargs):
        """注册到 tools.registry，与内置工具同等待遇"""

    def register_middleware(self, middleware: Middleware):
        """注册中间件 — 可拦截工具执行"""

    def get_config(self, key: str, default=None):
        """读取 config.yaml 中此插件的配置"""
```

### 13.4 生命周期 Hooks (17+)

```
VALID_HOOKS:
    pre_tool_call           — 工具执行前 (可 block)
    post_tool_call          — 工具执行后
    transform_terminal_output — 终端输出转换
    transform_tool_result   — 工具结果转换
    transform_llm_output    — LLM 输出转换
    pre_llm_call            — LLM 调用前 (可注入 context)
    post_llm_call           — LLM 调用后
    pre_api_request         — API 请求发送前
    post_api_request        — API 响应接收后
    api_request_error       — API 请求错误
    on_session_start        — 会话创建
    on_session_end          — 会话结束
    on_session_finalize     — 会话终结 (数据持久化后)
    on_session_reset        — 会话重置
    subagent_start          — 子代理启动
    subagent_stop           — 子代理停止
    pre_gateway_dispatch    — Gateway 消息分发前
```

### 13.5 Shell Hooks (外部脚本 hooks)

```yaml
# cli-config.yaml 中的 hooks: 配置
hooks:
  - event: pre_tool_call
    matcher: "terminal"       # 可选: 工具名匹配 (支持 regex)
    command: "~/.hermes/hooks/terminal-guard.sh"
    timeout: 30               # 超时秒数

  - event: pre_llm_call
    command: "python3 ~/inject_context.py"
```

```json
// stdin (JSON) → Shell 脚本
{
    "hook_event_name": "pre_tool_call",
    "tool_name": "terminal",
    "tool_input": {"command": "rm -rf /"},
    "session_id": "sess_abc123",
    "cwd": "/home/user/project"
}

// stdout (JSON) ← Shell 脚本
{"decision": "block", "reason": "Forbidden command"}
// 或
{"context": "Today is Friday"}  // pre_llm_call
```

---

## 14. Transport Layer — 传输层

### 14.1 传输层架构

```
agent/transports/
    base.py              ← TransportSession ABC
    chat_completions.py  ← Chat Completions API (OpenAI/兼容)
    anthropic.py         ← Anthropic Messages API (native)
    bedrock.py           ← AWS Bedrock
    codex.py             ← OpenAI Codex/Responses API
    codex_app_server.py  ← Codex App Server 协议
    codex_app_server_session.py  ← Codex App Server Session
    codex_event_projector.py     ← Codex Event → OpenAI 格式投影
    hermes_tools_mcp_server.py   ← Hermes Tools → MCP Server
    types.py             ← 共享类型定义
```

### 14.2 TransportSession ABC

```python
# agent/transports/base.py
class TransportSession(ABC):
    """Abstract base for API transport backends."""

    @abstractmethod
    async def send_request(self, messages, tools, **kwargs) -> AssistantMessage: ...
    @abstractmethod
    async def send_stream(self, messages, tools, **kwargs) -> AsyncIterator[Delta]: ...
    @abstractmethod
    def build_client(self, **kwargs) -> Any: ...
```

### 14.3 Chat Completions (主传输路径)

```python
# agent/transports/chat_completions.py — 最复杂的传输适配器

# 功能:
# 1. OpenAI Chat Completions API 调用
# 2. 自动检测 model family → 应用模型特定参数映射
#    - Anthropic via OpenRouter: cache_control → Anthropic prompt cache
#    - Google Gemini: system_instruction → system message split
#    - xAI Grok: reasoning_effort 映射
#    - DeepSeek: thinking 配置
# 3. 流式响应处理 + delta 聚合
# 4. tool_calls 增量聚合 (跨 delta 的 partial JSON 累积)
# 5. finish_reason 处理
# 6. token usage 提取 (prompt/completion/cache)
# 7. reasoning/thinking content 提取
```

### 14.4 Anthropic Native Adapter

```python
# agent/anthropic_adapter.py
# OpenAI 格式 ↔ Anthropic Messages API 双向转换:

# System prompt → system message (支持 cache_control)
# Messages → 交替 user/assistant 序列
# Tools → Anthropic tool format (含 tool_choice)
# Tool results → tool_result content blocks
# Images → base64 image content blocks (含尺寸预警)
# Thinking → thinking block + budget

# 特性:
# - Prompt cache control point 设置
# - OAuth setup-token 支持 (Bearer auth)
# - Claude Code credential 读取 (~/.claude.json + keychain)
# - 多模态 image 尺寸检查和压缩
# - Thinking budget 映射 (effort level → thinking tokens)
```

---

## 15. LSP Integration — 语言服务器集成

### 15.1 架构

```
agent/lsp/
    __init__.py        ← get_service() → LSPService 单例
    manager.py         ← LSPService: 后台 event loop + 客户端编排
    client.py          ← LSPClient: 单个 (server, workspace) 客户端
    servers.py         ← 服务器配置 + 语言检测
    workspace.py       ← Git 工作空间检测
    protocol.py        ← JSON-RPC 编码/解码
    eventlog.py        ← LSP 事件日志 (调试用)
    cli.py             ← CLI 命令: hermes lsp ...
```

### 15.2 设计模式

```
LSPService (单例, 后台 asyncio event loop)
    │
    ├── ClientPool: Dict[(server_id, workspace_root), LSPClient]
    │   └── 延迟创建: 首次请求时才启动 server
    │
    ├── BrokenSet: 记录失败 (server, workspace) → 不再重试
    │
    ├── DeltaBaseline: Dict[file_path, diagnostics_snapshot]
    │   ├── snapshot_baseline(path) — 写文件前记录
    │   └── get_diagnostics_sync(path) — 只返回新增诊断
    │
    └── IdleTimeout: 10分钟无活动 → reap server
```

### 15.3 Claude Code 模式

```
beforeFileEdited    → snapshot_baseline(file)
  ↓ 执行 write_file / patch
afterFileEdited     → getNewDiagnostics(file)
                      = current_diagnostics - baseline_diagnostics
                      → 只报告由本次写入引入的新 diagnostic
```

### 15.4 Touch-File Dance

```python
# 每次 open_file 都发送 workspace/didChangeWatchedFiles 通知
# 首次: CREATED | 后续: CHANGED
# 原因: clangd/eslint 等某些服务器仅在收到此通知时才重新扫描
```

---

## 16. Skill System — 技能系统

### 16.1 技能发现

```
技能来源 (按优先级):
  1. ~/.hermes/skills/           ← 用户安装的技能
  2. <repo>/skills/              ← 内置技能 (bundled)
  3. HERMES_EXTERNAL_SKILLS_DIRS ← 外部目录 (用于开发)
  4. Skills Hub                  ← 远程安装 (git clone)
```

### 16.2 技能结构

```
skills/<skill-name>/
    SKILL.md              ← 技能主体文档 (YAML frontmatter + Markdown)
    references/           ← 参考文件 (会话特定的细节/知识库)
    templates/            ← 模板文件 (可复制修改)
    scripts/              ← 脚本文件 (可执行)
```

```yaml
# SKILL.md frontmatter 示例:
---
name: my-skill
description: A useful skill for X
version: 1.0.0
triggers:
  - keywords: [react, component]
  - file_patterns: ["*.tsx", "*.jsx"]
platforms: [cli, telegram, discord]
os: [linux, macos]
---
```

### 16.3 技能加载

```python
# agent/skill_preprocessing.py
def load_skills_config() -> Dict:
    """解析 skills.yaml → 决定哪些技能启用"""

def substitute_template_vars(content, context) -> str:
    """变量替换: {{cwd}}, {{date}}, {{platform}}, ..."""

def expand_inline_shell(content) -> str:
    """行内 shell 扩展: $(command) → 命令输出"""

# agent/skill_utils.py
def get_all_skills_dirs() -> List[Path]: ...
def iter_skill_index_files() -> Iterator[Path]: ...
def skill_matches_platform(skill_frontmatter, platform) -> bool: ...
def skill_matches_environment(skill_frontmatter, os_name) -> bool: ...
```

### 16.4 后台技能审查

```python
# agent/background_review.py
def spawn_background_review(agent, conversation_snapshot):
    """Fork AIAgent → 审查对话 → 建议 memory/skill 更新"""

    # 工具白名单: 只有 memory + skill_manage
    # 继承父 runtime (同模型,同auth,同prompt cache)
    # 在 daemon thread 中运行，不阻塞主回复
```

### 16.5 技能维护器 (Curator)

```python
# agent/curator.py
# 后台周期性维护 agent-created skills

# 触发: idle 时, 距上次运行 > 7天
# 操作:
#   - Auto-transition lifecycle states (活跃→陈旧→归档)
#   - Pinned skills 免于自动操作
#   - 永远不删除 — 只归档 (可恢复)
#   - 使用辅助模型 (不消耗主模型额度)
```

---

## 17. Gateway & Multi-Session — 网关与多会话

### 17.1 网关架构

```
Gateway (hermes_cli/gateway.py)
    │
    ├── 支持的平台: Telegram, Discord, Slack, Web, DingTalk, ...
    │
    ├── Session Management:
    │   ├── 每用户/每群聊 独立 AIAgent 实例
    │   ├── 缓存策略: 1h idle → evict
    │   ├── 每消息可能创建新 AIAgent (cache miss)
    │   └── ContextVar (_SESSION_CWD) per-session cwd isolation
    │
    ├── Message Flow:
    │   入站消息 → preprocess → auth/pairing → agent dispatch → 出站回复
    │
    └── Agent Caching Key:
        (platform, user_id, chat_id, profile_signature)
```

### 17.2 Web Server

```python
# hermes_cli/web_server.py
# FastAPI-based web interface
# WebSocket support for streaming responses
# REST endpoints for session management
```

### 17.3 多会话关键设计

1. **AIAgent 每消息可重建**: gateway 缓存 miss 时重建，从 `sessions.system_prompt` 恢复（保持 prompt cache）
2. **ContextVar 隔离**: `_SESSION_CWD` 为每个 asyncio task 提供独立 cwd
3. `gateway_session_key`: 唯一标识缓存中的 agent 实例
4. `handoff_state`: 支持离线消息队列（用户离线时暂存回复）

---

## 18. Batch Runner & RL — 批处理与强化学习

### 18.1 Batch Runner

```python
# batch_runner.py
# 并行批处理: 跨数据集运行 agent

# 特性:
# - Dataset 加载: JSONL / HuggingFace datasets
# - Multiprocessing Pool: 并行 worker 处理
# - Checkpoint: 断点续跑 (fault tolerance)
# - Trajectory saving: ShareGPT 格式 (from/value pairs)
# - Tool usage stats: 跨 batch 汇总
# - Toolset distributions: 不同的工具集概率分布
```

### 18.2 工具集分布

```python
# toolset_distributions.py
sample_toolsets_from_distribution("coding")
# → 按概率分布随机采样工具集组合
# 用于 RL 训练中的工具多样性
```

---

## 19. 关键数据结构总览

### 19.1 会话中的核心数据结构

```
一次完整会话 = {
    # ── SQLite sessions 表行 ──
    "id": "20260607_143025_a1b2c3d4",
    "source": "cli",
    "model": "claude-sonnet-4-6",
    "model_config": "{...}",             # JSON: 完整模型配置快照
    "system_prompt": "{全系统提示词}",    # 用于 prefix cache 恢复
    "parent_session_id": null,           # 父会话 (压缩/branch)
    "started_at": 1717768825.123,
    "ended_at": null,
    "end_reason": null,
    "message_count": 42,
    "tool_call_count": 15,
    "input_tokens": 45000,
    "output_tokens": 12000,
    "cache_read_tokens": 38000,
    "cache_write_tokens": 5000,
    "reasoning_tokens": 3000,
    "cwd": "/home/user/project",
    "title": "Build React Component Library",
    "billing_provider": "anthropic",
    "estimated_cost_usd": 0.45,
    "actual_cost_usd": null,
    "handoff_state": null,
    "archived": 0,

    # ── messages 表行 (列表) ──
    "messages": [
        {
            "id": 1,
            "role": "user",
            "content": "Build a React component library...",
            "tool_call_id": null,
            "tool_calls": null,
            "timestamp": 1717768825.5,
            "active": 1,
        },
        {
            "id": 2,
            "role": "assistant",
            "content": "I'll start by setting up...",
            "tool_calls": [
                {
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                        "name": "write_file",
                        "arguments": "{\"path\": \"package.json\", ...}"
                    }
                }
            ],
            "reasoning": "The user needs a React component library...",
            "finish_reason": "tool_calls",
            "active": 1,
        },
        {
            "id": 3,
            "role": "tool",
            "content": "File written successfully.",
            "tool_call_id": "call_abc123",
            "tool_name": "write_file",
            "active": 1,
        },
        ...
    ],

    # ── AIAgent 运行时状态 ──
    "_cached_system_prompt": str,
    "_memory_manager": MemoryManager,
    "_todo_store": TodoStore,
    "_credits_state": CreditsState,
    "_tool_guardrails": ToolGuardrailController,
    "context_compressor": ContextCompressor,
    "iteration_budget": IterationBudget,
}
```

### 19.2 消息内容编码

```python
# 纯文本 content:
content = "Hello, how can I help?"

# 多模态 content (JSON + NUL前缀标记):
content = "\x00json:[
    {"type": "text", "text": "I see the following:"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
]"

# 存储前: _encode_content() → "\x00json:[...]" 字符串
# 读取后: _decode_content() → 原始 list/dict
```

---

## 20. 对 mochagent 的架构启示

### 20.1 可直接借鉴的模式

| hermes-agent 模式 | mochagent 现状 | 建议实现 |
|-------------------|---------------|---------|
| **SessionDB = SQLite + FTS5** | SessionStore = JSONL per session | 追加 H2/HSQLDB 嵌入式数据库，FTS for 跨会话搜索 |
| **parent_session_id 链** | 无 | 压缩+branch 创建链式 session |
| **system_prompt 持久化** | 每次 rebuild | 缓存到 session 元数据中 |
| **MemoryManager = Facade** | God class 正在重构 | ✅ 继续推进 |
| **MemoryProvider ABC** | MemoryPlugin 接口 | 统一为 Provider 接口 |
| **ContextEngine ABC** | CompactionEngine 固定 | 可抽象化 |
| **三层 System Prompt** | 单层拼接 | stable/context/volatile 分层 |
| **条件化注入** | 无条件注入 | 仅 tools>0 时才注入工具指引 |
| **流式 Content Scrubbing** | 无 | 实现状态机过滤 memory-context 标签 |
| **压缩锁** | 无 | 防止并发压缩竞态 |
| **soft-delete (active=0)** | 直接删除 | rewind/undo 用软删除 |
| **FailoverReason 分类** | 简单异常 | 30+ 种分类 + 差异化恢复 |
| **check_fn TTL Cache** | 无 | 工具可用性检查缓存 (30s) |
| **Tool Result Storage** | ToolResultStorage 存在 | 追加 aggregate budget 层 |
| **Shell Hooks** | 无 | 外部脚本 hook 支持 |
| **LSP** | 无 | eclipse-lsp4j 实现 (Java 原生) |
| **AST-based 工具发现** | 手动注册 | 可扫描 @Tool 注解自动发现 |
| **auxiliary_client 路由链** | 单一模型 | 辅助任务降级链 (便宜模型 → 免费模型) |
| **账户/信用追踪** | 无 | Token 使用 + 费用估算 |
| **后台审查 (background_review)** | 无 | 每轮后异步 fork 审查 memory/skill |
| **技能维护器 (Curator)** | 无 | 定期清理/归档过时技能 |
| **Gateway per-message AIAgent** | 单一实例 | 支持无状态重建 (缓存 system_prompt) |
| **Lazy Import** | 直接 import | 延迟加载重量级 SDK |

### 20.2 架构差异 (需要适配)

| 差异 | hermes-agent | mochagent 建议 |
|------|-------------|---------------|
| 语言 | Python (duck typing, friend functions) | Java (强类型, OOP) |
| 模块提取方式 | 函数 + agent 参数 | Strategy/Service 类 + 注入 |
| 存储 | SQLite (单文件, WAL) | 可考虑 H2/HSQLDB |
| 并发模型 | asyncio + 线程池 | CompletableFuture + ExecutorService |
| Plugin 系统 | Python import + entry_points | SPI/ServiceLoader 或自定义 |
| 工具发现 | AST 扫描 | 注解扫描 (@Tool) |
| Identity | SOUL.md 文件 | 可考虑类似机制 |

### 20.3 优先级建议

1. **高优先级** (立即推进):
   - Memory module OOP closure (Facade + Provider + StepTracker 分离)
   - SessionManager 移到 ReActAgent 级别
   - SQLite/H2 会话存储 + FTS 搜索
   - system_prompt 持久化 + 缓存恢复

2. **中优先级** (下一阶段):
   - ErrorClassifier 细化 (差异化重试)
   - 压缩锁机制
   - soft-delete (active flag)
   - LSP 集成
   - 流式 Content Scrubbing

3. **低优先级** (远期规划):
   - Plugin 系统 (SPI)
   - Shell Hooks
   - Batch Runner
   - Background Review
   - Skill Curator

---

> **注**: 本文档基于 hermes-agent `dev` 分支 (2026-06-07) 分析编写。
> hermes-agent 项目地址: https://github.com/NousResearch/hermes-agent
