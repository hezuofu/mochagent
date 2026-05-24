# Hermes-Agent 设计文档

> 基于 `E:\ai-work\hermes-agent` 源码完整分析。hermes-agent 是生产级 Python AI Agent 系统，已部署运行。

---

## 一、核心对象

### 1.1 AIAgent（run_agent.py:1028）

~15K 行文件中的 God Class，约 60 个构造参数，按类别分组：

**核心配置：**
```
base_url, api_key, provider, api_mode           ← API 连接
model, max_iterations (90), tool_delay (1.0)     ← 模型和循环控制
max_tokens, reasoning_config, service_tier        ← 模型参数
```

**工具系统：**
```
enabled_toolsets, disabled_toolsets               ← 工具集开关
tool_progress_callback, tool_start_callback       ← 工具回调钩子
tool_complete_callback                             ← 工具完成钩子
```

**会话与身份：**
```
session_id, platform, user_id, user_name
chat_id, chat_name, chat_type, thread_id
parent_session_id, gateway_session_key
```

**记忆与上下文：**
```
skip_memory, load_soul_identity, skip_context_files
session_db                                          ← SQLite 会话存储
_memory_store, _memory_enabled, _user_profile_enabled
_memory_manager                                     ← MemoryManager 实例
context_compressor, compression_enabled
```

**回调系统（流式 + 进度）：**
```
thinking_callback, reasoning_callback        ← 思考/推理流
stream_delta_callback, step_callback          ← 流式增量 + 步骤
clarify_callback                               ← 澄清回调
tool_gen_callback                              ← 工具生成回调
status_callback                                ← 状态回调
```

**流式输出控制：**
```
verbose_logging, quiet_mode, log_prefix_chars, log_prefix
save_trajectories
prefill_messages
request_overrides
```

### 1.2 MemoryManager（agent/memory_manager.py:38）

插件化记忆管理器。单例委托模式：

```
MemoryManager
├── add_provider(provider)           ← 注册外部 MemoryProvider（仅允许一个）
├── build_system_prompt()            ← 构建记忆系统提示词块
├── prefetch_all(user_msg)           ← 预取相关记忆（对话轮次前调用）
├── sync_all(user_msg, assistant)    ← 同步对话到记忆（对话轮次后调用）
├── queue_prefetch_all(user_msg)     ← 异步预取（减少延迟）
└── on_turn_start(turn_count, msg)   ← 轮次开始钩子
```

关键设计：
- 仅允许一个外部插件 provider，防止工具 schema 膨胀
- `<memory-context>` 标签包裹记忆，区分于用户输入
- sanitize_context() 清洗注入

### 1.3 ContextEngine（agent/context_engine.py:32）

抽象基类，可插件化替换：

```
ContextEngine (ABC)
├── name: str                          ← 引擎标识（'compressor', 'lcm', ...）
├── last_prompt_tokens, last_completion_tokens, last_total_tokens
├── threshold_tokens, context_length
├── compression_count, threshold_percent, protect_first_n
├── on_session_start()                 ← 会话开始
├── update_from_response(usage)        ← 每次 API 响应后更新 token 计数
├── should_compress()                  ← 检查是否需要压缩
├── compress(messages)                 ← 执行压缩
├── on_session_end()                   ← 会话结束
└── get_tools()                        ← 可选暴露工具（如 lcm_grep）
```

### 1.4 MemoryProvider（agent/memory_provider.py）

外部记忆后端的统一接口：

```
MemoryProvider (ABC)
├── name: str
├── build_system_prompt() → str
├── prefetch_all(user_input) → str
├── sync_all(user_input, assistant_response)
├── queue_prefetch_all(user_input)
└── on_turn_start(turn_count, user_input)
```

### 1.5 TrajectoryRecorder（agent/trajectory.py）

对话轨迹记录器：

```
- 步骤类型: TaskStep, ActionStep, PlanningStep, SystemPromptStep
- ToolCall 数据结构: tool_name, arguments, result, timing
- 重放: replay() 逐步骤回放
- 持久化: save() 到 JSONL 文件
```

## 二、核心执行流程

### 2.1 run_conversation() 完整流程（line 10978）

```
1. 初始化
   ├── _install_safe_stdio()           ← 管道安全守护
   ├── _ensure_db_session()            ← SQLite 会话确保
   ├── set_session_context(session_id)  ← 日志会话标记
   ├── _restore_primary_runtime()      ← 恢复主运行时（从 fallback 返回）
   └── 重置 6 种重试计数器

2. 循环前准备
   ├── _build_system_prompt()          ← 7 层组装（缓存，仅构建一次）
   ├── memory_manager.prefetch_all()    ← 外部记忆预取（缓存复用）
   ├── context_compressor 预检          ← token 超 75% 阈值 → 主动压缩
   ├── plugin hook: pre_llm_call       ← 插件注入上下文
   └── user message 注入

3. 主循环（while api_call_count < max_iterations AND budget > 0）
   ├── 检查中断信号 (_interrupt_requested)
   ├── step_callback 触发（网关钩子）
   ├── 构建 API 消息（system prompt + history + user msg）
   ├── 调用 LLM API (streaming)
   │   ├── text delta → stream_delta_callback
   │   ├── thinking delta → thinking_callback
   │   └── tool_use block → 收集
   ├── 检查响应有效性
   │   ├── 空内容 → _empty_content_retries++
   │   ├── 无效 JSON → _invalid_json_retries++
   │   ├── 无效工具 → _invalid_tool_retries++
   │   └── 不完整 → _codex_incomplete_retries++
   ├── 执行工具调用
   │   ├── tool_guardrails 验证
   │   ├── 并发安全? → _execute_tool_calls_concurrent()
   │   │               → 全部并行执行
   │   └── 不并发安全 → _execute_tool_calls_sequential()
   │                   → 逐个执行，结果逐条注入
   ├── 工具结果注入 messages 列表
   └── 检查 stop_reason → "stop" → break

4. 循环后
   ├── memory_manager.sync_all()       ← 同步记忆
   ├── _flush_to_session_db()          ← 持久化到 SQLite
   ├── trajectory.save()               ← 保存轨迹
   └── 返回 final_response
```

### 2.2 工具执行决策树

```
_execute_tool_calls()
├── tool_guardrails.reset_for_turn()
├── for each tool_call in assistant_message:
│   ├── tool_guardrails.check(tool_name) → DENY? → skip
│   ├── permission check → DENY? → skip  
│   └── 分类: concurrent_safe?
│       ├── YES → concurrent_batch.add(tc)
│       └── NO  → flush concurrent_batch, execute sequential
├── flush remaining concurrent_batch
└── 结果逐条注入 messages (role: "tool", tool_call_id, content)
```

### 2.3 6 种重试策略

```
_invalid_tool_retries        ← 工具名无效/不存在 → 重试 3 次
_invalid_json_retries        ← JSON 参数解析失败 → 重试 3 次
_empty_content_retries       ← 模型返回空 → 重试 3 次
_incomplete_scratchpad_retries ← 草稿不完整 → 重试
_codex_incomplete_retries    ← Codex 响应截断 → 重试 3 次
_thinking_prefill_retries    ← 思考预填充失败 → 重试
```

每次 run_conversation() 入口全部重置为 0。Context 压缩后也重置 `_empty_content_retries` 和 `_thinking_prefill_retries`。

## 三、系统提示词体系

### 3.1 7 层组装（_build_system_prompt:5275）

```
Layer 1: Agent 身份
  ├── SOUL.md 文件内容（如果存在）→ 完整的 agent persona
  └── DEFAULT_AGENT_IDENTITY（硬编码回退）

Layer 2: HERMES_AGENT_HELP_GUIDANCE
  └── 指向 hermes-agent 技能 + 文档的引用提示

Layer 3: 工具感知引导（条件注入，仅当工具加载时）
  ├── MEMORY_GUIDANCE           ← memory 工具存在时
  ├── SESSION_SEARCH_GUIDANCE   ← session_search 工具存在时
  ├── SKILLS_GUIDANCE           ← skill_manage 工具存在时
  └── KANBAN_GUIDANCE           ← kanban_show 工具存在时

Layer 4: Computer-Use 引导（macOS）
  └── COMPUTER_USE_GUIDANCE     ← computer_use 工具存在时

Layer 5: 工具使用强制（TOOL_USE_ENFORCEMENT_GUIDANCE）
  ├── 控制方式: config.yaml agent.tool_use_enforcement
  │   ├── "auto" → 匹配 TOOL_USE_ENFORCEMENT_MODELS 列表
  │   ├── true   → 所有模型强制注入
  │   └── false  → 不注入
  ├── Google 模型 → GOOGLE_MODEL_OPERATIONAL_GUIDANCE
  └── OpenAI 模型 → OPENAI_MODEL_EXECUTION_GUIDANCE

Layer 6: 自定义 system_message（可选，API 调用时注入）

Layer 7: 持久记忆
  ├── _memory_store.format_for_system_prompt("memory")  ← USER.md
  ├── _memory_store.format_for_system_prompt("user")     ← memory 块
  └── _memory_manager.build_system_prompt()              ← 外部 provider
```

### 3.2 缓存策略

- 系统提示词**每个会话构建一次**，存储在 `_cached_system_prompt`
- 跨所有轮次复用，最大化 API prefix cache 命中
- 仅在 context 压缩事件后重建
- `ephemeral_system_prompt` 不进入缓存，每次 API 调用单独注入

## 四、核心模块划分

```
hermes-agent/
├── run_agent.py           ← AIAgent 核心类（15K 行，主循环 + 工具执行 + 提示词）
├── hermes_cli/             ← CLI 层（main, gateway, cron, config, auth...）
│   ├── main.py             ← 入口
│   ├── gateway.py          ← WebSocket 网关
│   ├── config.py           ← 配置管理
│   └── plugins.py          ← 插件系统钩子
├── agent/                  ← Agent 内部模块（从 run_agent.py 提取）
│   ├── memory_manager.py   ← 记忆委托管理
│   ├── memory_provider.py  ← 记忆后端接口
│   ├── context_engine.py   ← 上下文引擎 ABC
│   ├── context_compressor.py ← 内置上下文压缩器
│   ├── prompt_builder.py   ← 系统提示词构建工具函数
│   ├── trajectory.py       ← 对话轨迹记录
│   ├── error_classifier.py ← 错误分类器
│   └── retry_utils.py      ← 重试工具
├── tools/                  ← 工具实现（40+ 文件）
│   ├── registry.py         ← 工具注册表
│   ├── approval.py         ← 权限审批
│   ├── browser_*.py        ← 浏览器工具族
│   ├── code_execution_tool.py
│   └── skill_*.py          ← 技能管理工具
├── skills/                 ← 可安装技能（Markdown 格式）
├── plugins/                ← 插件目录（context_engine/, memory_provider/）
├── providers/              ← LLM 提供者适配
├── docs/                   ← 文档
├── tests/                  ← 测试
└── web/                    ← Web UI
```

## 五、LLM 响应分类机制

### 5.1 内容块类型（Anthropic Messages API）

hermes-agent 使用 Anthropic 原生 API，LLM 响应包含类型化内容块：

```
ContentBlock:
├── type: "text"       → 模型文本输出 → 累积到 assistant_message
├── type: "tool_use"   → 工具调用请求 → 收集到 tool_calls 列表
├── type: "thinking"   → 思考过程 → thinking_callback 流式推送
└── type: "tool_result"→ 工具执行结果 → 注入下一轮 messages
```

### 5.2 工具调用检测

```python
# run_agent.py:7550
if block and getattr(block, "type", None) == "tool_use":
    has_tool_use = True
    # 收集 tool_use block
```

框架不强制 LLM 输出格式——LLM 自行决定何时调用工具。框架仅**分类响应块**并据此决策。

### 5.3 多后端适配

```
api_mode 决定使用哪个适配器:
├── "anthropic_messages" → 原生 Anthropic API
├── "responses"          → OpenAI Codex Responses API (codex_responses_adapter.py)
├── "gemini"             → Gemini schema (gemini_schema.py)
└── 其他 OpenAI 兼容     → OpenAI chat/completions
```

每个适配器负责：
1. 将内部消息格式转换为 API 格式
2. 解析 API 响应为统一的内容块
3. 处理特定后端的错误码
