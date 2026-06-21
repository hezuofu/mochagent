# hermes-agent vs Claude Code — Agent 核心对比分析

> 分析日期: 2026-06-07
> 聚焦: Agent 循环架构、状态管理、工具调度、错误恢复、流式处理

---

## 目录

1. [一图看懂](#1-一图看懂)
2. [Agent 循环架构对比](#2-agent-循环架构对比)
3. [状态管理对比](#3-状态管理对比)
4. [消息构建对比](#4-消息构建对比)
5. [API 调用对比](#5-api-调用对比)
6. [工具执行对比](#6-工具执行对比)
7. [错误恢复对比](#7-错误恢复对比)
8. [流式处理对比](#8-流式处理对比)
9. [系统提示词对比](#9-系统提示词对比)
10. [生命周期对比](#10-生命周期对比)
11. [Back Pressure 控制对比](#11-back-pressure-控制对比)
12. [对 mochagent 的评估与建议](#12-对-mochagent-的评估与建议)

---

## 1. 一图看懂

```
┌──────────────────────────────────────────────────────────────┐
│                    AGENT LOOP 对比                            │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  hermes-agent:                     Claude Code:               │
│  ┌─────────────────────┐          ┌─────────────────────┐    │
│  │ run_conversation()   │          │ query() generator    │    │
│  │ 同步函数             │          │ AsyncGenerator       │    │
│  │ while loop           │          │ while(true) loop     │    │
│  │ return Dict          │          │ yield 中间消息        │    │
│  └─────────────────────┘          └─────────────────────┘    │
│                                                               │
│  消息流转:                       消息流转:                     │
│  messages: List[Dict]            state.messages: Message[]   │
│  ↓                                ↓                           │
│  复制+追加 → build_api_kwargs    解构 → getMessagesAfterCB   │
│  ↓                                ↓                           │
│  OpenAI client.chat.completions  Anthropic messages.stream   │
│  ↓                                ↓                           │
│  解析 → ModelResponse            yield StreamEvent delta     │
│  ↓                                ↓                           │
│  追加到 messages[]               push 到 state.messages      │
│  ↓                                ↓                           │
│  工具执行 (同步/异步)             工具执行 (AsyncGenerator)    │
│  ↓                                ↓                           │
│  追加 tool results               yield tool result messages  │
│  ↓                                ↓                           │
│  return {response, messages}      return Terminal.stop         │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Agent 循环架构对比

### 2.1 hermes-agent: 命令式函数

```python
# agent/conversation_loop.py
def run_conversation(
    agent,                        # AIAgent 实例
    user_message: str,
    system_message: str = None,
    conversation_history: List[Dict] = None,
    task_id: str = None,
    stream_callback = None,
    persist_user_message: str = None,
) -> Dict[str, Any]:             # 同步返回完整结果

    # ===== PRE-TURN SETUP =====
    agent._ensure_db_session()       # 延迟创建 SQLite 会话行
    set_session_context(session_id)  # 日志标签
    user_msg = {"role": "user", "content": user_message}
    messages.append(user_msg)

    # system prompt restore or build
    _restore_or_build_system_prompt(agent, ...)

    # preflight compression check
    if should_compress_preflight(messages):
        messages, prompt = agent._compress_context(...)

    # ===== MAIN LOOP =====
    while api_call_count < max_iterations:
        # 1. Build API request
        api_kwargs = build_api_kwargs(messages, system_prompt, tools)

        # 2. API call (with retries)
        response = _interruptible_api_call(agent, api_kwargs, stream_callback)

        # 3. Classify error → recover or abort
        if is_error(response):
            classified = classify_api_error(response)
            if classified.should_retry:
                continue  # retry with backoff / credential rotation
            else:
                break

        # 4. Parse response
        content, tool_calls, finish_reason, usage = parse(response)

        # 5. Append assistant message
        messages.append({"role": "assistant", "content": content, "tool_calls": tool_calls})

        # 6. Execute tools or return
        if not tool_calls:
            final_response = content
            break

        results = execute_tools(tool_calls, agent)
        for r in results:
            messages.append({"role": "tool", "content": r, "tool_call_id": ...})

        # 7. Check context overflow → compress
        if should_compress(prompt_tokens):
            messages, prompt = agent._compress_context(...)

    # ===== POST-TURN =====
    _flush_messages_to_session_db(messages)
    update_token_counts()
    memory_manager.sync_all()
    maybe_auto_title()
    return {
        "response": final_response,
        "messages": messages,
        "input_tokens": ...,
        ...
    }

特点:
• 同步函数 → 调用者等待完成
• while True + break 模式
• 每个循环迭代 = 1 次 LLM 调用 + 1 轮工具执行
• 完整结果在 return 中
```

### 2.2 Claude Code: AsyncGenerator

```typescript
// src/query.ts
export async function* query(
  params: QueryParams,
): AsyncGenerator<
  StreamEvent | RequestStartEvent | Message | TombstoneMessage | ToolUseSummaryMessage,
  Terminal           // ← 最终返回类型: Continue | Stop
>

async function* queryLoop(
  params: QueryParams,
  consumedCommandUuids: string[],
): AsyncGenerator<...> {

  // Immutable params (never reassigned)
  const { systemPrompt, userContext, systemContext, canUseTool, ... } = params

  // Mutable cross-iteration state
  let state: State = {
    messages, toolUseContext,
    maxOutputTokensRecoveryCount: 0,
    turnCount: 1,
    ...
  }

  // ===== MAIN LOOP =====
  while (true) {
    // Destructure state at TOP of each iteration
    let { toolUseContext } = state
    const { messages, turnCount, ... } = state

    yield { type: 'stream_request_start' }  // ← 通知调用者

    // 1. Pre-API processing
    let messagesForQuery = [...getMessagesAfterCompactBoundary(messages)]
    // Snip, MicroCompact, ContextCollapse, Autocompact...
    messagesForQuery = await applyCompactPipeline(messagesForQuery, ...)

    // 2. Memory prefetch (non-blocking)
    using pendingMemoryPrefetch = startRelevantMemoryPrefetch(messages, ...)

    // 3. API call (streaming)
    yield* deps.streamModel(messagesForQuery, systemPrompt, ...)
    //     ↑ yields StreamEvent for each delta

    // 4. Parse assistant message
    const assistantMessage = collectAssistantMessage(streamedEvents)

    // 5. Yield assistant message
    yield assistantMessage

    // 6. Execute tools (if tool_use present)
    if (hasToolUse(assistantMessage)) {
      yield* runTools(toolUseBlocks, assistantMessages, canUseTool, toolUseContext)
      //     ↑ yields Message for each tool result

      // Update context from tool results
      state = { ...state, messages: [...messages, ...toolResults] }
      continue  // ← 继续循环
    }

    // 7. Stop hook processing
    if (stopReason === 'end_turn') {
      const terminal = yield* handleStopHooks(state, ...)
      return terminal  // ← Terminal.stop
    }
  }
}

特点:
• AsyncGenerator → 调用者实时获取中间消息
• 通过 yield 推送，而非 return 汇总
• while(true) + continue/return 模式
• 不可变 params + 可变 state (函数式更新)
• 每个 yield 点调用者都能消费
```

### 2.3 架构差异总结

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **循环类型** | `while` + `break` | `while(true)` + `continue`/`return` |
| **返回值** | `Dict[str, Any]` (同步汇总) | `AsyncGenerator` (实时推送) |
| **消息流转** | List 追加 + 复制 | State 函数式更新 + yield |
| **函数风格** | 命令式 (mutable messages) | 声明式 (immutable params + state spread) |
| **迭代=** | 1 LLM + 1 tool batch | 1 LLM + 1 tool batch (相同) |

---

## 3. 状态管理对比

### 3.1 hermes-agent: Agent 实例字段

```python
# 所有状态都在 AIAgent 实例上
class AIAgent:
    # —— 会话 ——
    session_id: str
    _session_db: SessionDB
    _cached_system_prompt: str

    # —— 循环状态 ——
    messages: List[Dict]              # 当前会话消息
    tools: List[Dict]                 # 工具定义
    max_iterations: int               # 最大迭代数

    # —— 重试/恢复 ——
    _invalid_tool_retries: int
    _invalid_json_retries: int
    _empty_content_retries: int
    _incomplete_scratchpad_retries: int
    _vision_supported: bool
    _fallback_activated: bool

    # —— Tool 执行 ——
    _tool_guardrails: ToolGuardrailController
    _tool_guardrail_halt_decision: Decision | None

    # —— 中断 ——
    _interrupt_requested: bool
    _interrupt_thread_signal_pending: bool

    # —— 计费 ——
    _credits_state: CreditsState

    # —— Turn 级临时状态 ——
    _turn_failed_file_mutations: Dict     # 本轮失败的文件修改
    _current_task_id: str
    _current_turn_id: str

特点:
• 所有状态是 AIAgent 的 mutable 字段
• 跨 turn 持久: session_id, tools, compression_enabled...
• Per-turn 重置: _invalid_tool_retries=0, _vision_supported=True...
• 没有清晰的 mutable/immutable 分离
```

### 3.2 Claude Code: 分层状态

```typescript
// 三层状态分离:

// Layer 1: Bootstrap State (全局, 非 UI)
//   src/bootstrap/state.ts
//   - sessionId, cwd, projectRoot
//   - totalCostUSD, totalAPIDuration
//   - modelUsage
//   - 生命周期: 进程级别

// Layer 2: AppState (UI, React 响应式)
//   src/state/AppState.tsx
//   - toolPermissionContext
//   - tasks, messages
//   - verbose, spinnerTip
//   - 生命周期: UI 组件树

// Layer 3: Query Loop State (per-turn, AsyncGenerator 内部)
//   src/query.ts — State type
type State = {
  messages: Message[]
  toolUseContext: ToolUseContext
  autoCompactTracking: AutoCompactTrackingState | undefined
  maxOutputTokensRecoveryCount: number
  hasAttemptedReactiveCompact: boolean
  maxOutputTokensOverride: number | undefined
  pendingToolUseSummary: Promise<ToolUseSummaryMessage | null> | undefined
  stopHookActive: boolean | undefined
  turnCount: number
  transition: Continue | undefined
}

// 更新方式: 函数式 (不可变)
state = { ...state, messages: [...state.messages, ...newMessages] }

特点:
• 三层状态严格分离
• Query Loop 内部状态是完全隔离的
• 函数式更新 → 容易测试和推理
• Bootstrap 和 AppState 通过 getAppState/setAppState 通信
```

### 3.3 状态对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 状态位置 | AIAgent 实例字段 | 三层: Bootstrap / AppState / Query State |
| 更新方式 | Mutable 直接赋值 | 函数式 spread (循环内) + setState (UI) |
| 跨 turn 持久 | 自动 (实例存活) | Bootstrap state 持久化 |
| 跨进程持久 | 无 (需 checkpoint) | JSONL transcript + meta.json |
| 测试友好度 | 低 (全局 mutable) | 高 (依赖注入 QueryDeps) |

---

## 4. 消息构建对比

### 4.1 hermes-agent

```python
# 消息构建: 临时复制 + 追加
def build_api_kwargs(messages, system_prompt, tools):
    api_messages = [{"role": "system", "content": system_prompt}]

    # 复制历史消息
    for msg in messages:
        api_messages.append(msg)

    # 追加顶层上下文 (如果设置了 ephemeral prompt)
    if ephemeral_system_prompt:
        api_messages.insert(1, {"role": "system", "content": ...})

    return {
        "messages": api_messages,
        "tools": tools,
        "model": model,
        "max_tokens": max_tokens,
    }

# 每个循环迭代重新构建 (复制完整的 messages 列表)
# Prompt cache: 依赖 system prompt 不变 → Anthropic 自动缓存
```

### 4.2 Claude Code

```python
# 消息构建: 函数式管道
function buildMessagesForAPI(messages, systemPrompt, userContext, systemContext):

    // 1. 只取 compact boundary 之后的
    let msgs = [...getMessagesAfterCompactBoundary(messages)]

    // 2. Snip (截断旧历史)
    msgs = snipCompactIfNeeded(msgs)

    // 3. MicroCompact (清除旧 tool result)
    msgs = microCompact(msgs, toolUseContext)

    // 4. Context Collapse (深度压缩)
    msgs = projectCollapseView(msgs)

    // 5. ensureToolResultPairing ← Claude Code 独有!
    msgs = normalizeMessagesForAPI(msgs)
    // → 检查每个 tool_use 都有对应 tool_result
    // → 缺失时插入 synthetic error tool_result

    // 6. 注入上下文
    msgs = prependUserContext(msgs, userContext)   // CLAUDE.md, currentDate
    msgs = appendSystemContext(msgs, systemContext) // gitStatus

    return {
        system: systemPrompt,
        messages: msgs,
        tools: toolUseContext.options.tools,
    }

# 特点:
# - 管道式处理: snip → microcompact → collapse → normalize → inject
# - ensureToolResultPairing 防止 API 400
# - getMessagesAfterCompactBoundary 节省 token
```

### 4.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 消息构建 | 复制 messages + 插入 system prompt | 管道式: snip→microcompact→collapse→normalize→inject |
| Tool pairing | ❌ 无验证 | ✅ ensureToolResultPairing |
| 压缩感知 | ❌ 全量发送 | ✅ getMessagesAfterCompactBoundary (只发 boundary 后) |
| 上下文注入 | 直接拼接到 system prompt | 分别注入 userContext + systemContext |
| Token 预算 | ❌ 无 | ✅ 管道各阶段有 token 预算 |

---

## 5. API 调用对比

### 5.1 hermes-agent

```python
# 统一的 OpenAI client (chat.completions.create)
if stream_callback:
    response = openai_client.chat.completions.create(
        **api_kwargs,
        stream=True,
        stream_options={"include_usage": True},
    )
    # 流式处理 → delta callback
else:
    response = openai_client.chat.completions.create(**api_kwargs)

# 多 provider 支持: api_mode 切换
if api_mode == "anthropic_messages":
    → agent/anthropic_adapter.py 转换 OpenAI→Anthropic
elif api_mode == "codex_responses":
    → agent/codex_responses_adapter.py
elif api_mode == "gemini_native":
    → agent/gemini_native_adapter.py
else:  # default chat_completions
    → 直接 OpenAI SDK
```

### 5.2 Claude Code

```typescript
// Anthropic Messages API 原生
const stream = await anthropic.beta.messages.stream({
  model,
  system: systemPrompt,
  messages: apiMessages,
  tools: toolDefinitions,
  max_tokens: maxOutputTokens,
  thinking: thinkingConfig,
  // Anthropic 特有: prompt cache control
  // Anthropic 特有: task_budget (beta)
})

// 流式消费
for await (const event of stream) {
  switch (event.type) {
    case 'content_block_start': ...
    case 'content_block_delta':
      yield { type: 'stream_event', delta: event.delta.text }
    case 'content_block_stop': ...
    case 'message_stop':
      yield assistantMessage
  }
}
```

### 5.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| API | OpenAI chat.completions | Anthropic Messages API (原生) |
| 多 provider | 适配器模式 (OpenAI→Anthropic/Gemini/Codex) | 仅 Anthropic |
| Streaming | OpenAI stream + stream_options | Anthropic beta.messages.stream |
| Thinking | `<REASONING_SCRATCHPAD>` XML 标签 | Native thinking blocks (redacted_thinking) |
| Prompt cache | 隐式 (前缀不变 → API 自动缓存) | 显式 cache_control breakpoints |
| Task budget | ❌ | ✅ task_budget (beta) |
| Service tier | ❌ | ✅ 自动 + scaling |

---

## 6. 工具执行对比

### 6.1 hermes-agent

```python
# agent/tool_executor.py

# 顺序执行 (默认):
for tc in tool_calls:
    result = execute_single_tool(tc)
    results.append(result)

# 并发执行 (实验性):
with ThreadPoolExecutor(max_workers=8) as pool:
    futures = {pool.submit(execute, tc): tc for tc in tool_calls}
    for f in futures:
        results.append(f.result())

# 工具解析: 正则 fallback
if parsed_action := parse_action(response.content):
    tool_name = parsed_action.name
    tool_args = parsed_action.arguments
else:
    # regex 匹配 "Action: tool(args)"
```

### 6.2 Claude Code

```typescript
// services/tools/toolOrchestration.ts

// 智能分批: concurrency-safe 分区
function partitionToolCalls(toolUseMessages, toolUseContext): Batch[] {
  return toolUseMessages.reduce((acc, toolUse) => {
    const tool = findToolByName(tools, toolUse.name)
    const isConcurrencySafe = tool?.isConcurrencySafe(input)

    if (isConcurrencySafe && acc[acc.length - 1]?.isConcurrencySafe) {
      acc[acc.length - 1].blocks.push(toolUse)  // 追加到当前并发批次
    } else {
      acc.push({ isConcurrencySafe, blocks: [toolUse] })  // 新批次
    }
    return acc
  }, [])
}

// 流式执行: StreamingToolExecutor
class StreamingToolExecutor {
  // 工具流式到达 → 立即开始执行
  addTool(block, assistantMessage): void

  // 并发安全工具可并行执行
  // 非安全工具必须串行

  // 结果按到达顺序 yield (但通过缓冲区确保最终按原始顺序)
  async *getRemainingResults(): AsyncGenerator<Message>
}
```

### 6.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **解析** | 正则 fallback (`Action: tool(args)`) | Anthropic native `tool_use` blocks |
| **并发** | ThreadPoolExecutor (8 workers) | partition + 10 workers |
| **分批** | 无分批 (全量) | 连续并发安全 → 同批; 非安全 → 独立批 |
| **流式执行** | ❌ 等完整响应 | ✅ StreamingToolExecutor (边到边执行) |
| **进度** | ❌ 无进度事件 | ✅ ProgressMessage (bash_progress, mcp_progress) |
| **中断** | `_interrupt_requested` 轮询 | AbortController + siblingAbortController |
| **工具结果存储** | `maybe_persist_tool_result()` → 文件 | `applyToolResultBudget()` → 200K 总预算 |
| **Guardrail** | `ToolGuardrailController.check()` | `canUseTool()` + `alwaysAllow/Deny/Ask` rules |

---

## 7. 错误恢复对比

### 7.1 hermes-agent

```python
# agent/conversation_loop.py + error_classifier.py

# 30+ 种 FailoverReason (详见前一天分析)
classified = classify_api_error(response)
switch classified.reason:
  case auth:
      → refresh OAuth token → retry 1x
  case billing:
      → credential_pool.next() → retry
  case rate_limit:
      → read Retry-After → jittered_backoff → retry
  case context_overflow:
      → compress → retry
  case timeout:
      → rebuild HTTP client → retry 1x
  case content_policy_blocked:
      → ABORT (deterministic, retry pointless)
  case format_error:
      → strip problematic parts → retry 1x
  case model_not_found:
      → activate fallback model
  ...

# 重试计数 per error type:
agent._invalid_tool_retries = 0
agent._invalid_json_retries = 0
agent._empty_content_retries = 0
agent._incomplete_scratchpad_retries = 0
# 每种错误有独立的重试上限
```

### 7.2 Claude Code

```typescript
// services/api/errors.ts + query.ts

// 错误分类:
function categorizeRetryableAPIError(error): {
  shouldRetry: boolean
  retryDelay: number
  shouldRotateCredential: boolean
}

// Recovery strategies:
case 'prompt_too_long':
  → compress → retry (reactive compact)
case 'max_output_tokens':
  → increment recovery count → continue (max 3)
case 'auth':
  → refresh OAuth → retry
case 'billing':
  → rotate credential → retry
case 'overloaded':
  → jittered_backoff → retry
case 'image_too_large':
  → resize → retry

// Recovery count tracker:
maxOutputTokensRecoveryCount: number  // 0-→1→2→3→stop
hasAttemptedReactiveCompact: boolean  // 防止死循环

// Fallback model chain:
if (primaryModel fails) {
  if (isOverloaded) → retry with jittered backoff
  else if (isAuthFailure) → abort
  else → fallbackModel
}
```

### 7.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 错误分类 | 30+ FailoverReason enum | categorizeRetryableAPIError() |
| 重试策略 | 独立计数器 per error type | 统一 recovery count (max 3) + fallback model |
| 压缩触发 | should_compress prompt_tokens | 3层: auto + reactive(max_output_tokens) + preflight |
| Credential 管理 | CredentialPool (池) | OAuth refresh + credential rotation |
| 熔断 | ❌ 无明确熔断 | ✅ recovery count + hasAttempted flags |

---

## 8. 流式处理对比

### 8.1 hermes-agent

```python
# 流式→回调模式
if stream_callback:
    for chunk in response:
        delta = chunk.choices[0].delta
        if delta.content:
            stream_callback(delta.content)

# StreamingContexScrubber: 过滤 <memory-context> 标签
scrubber = StreamingContextScrubber()
for delta in stream:
    visible = scrubber.feed(delta)
    if visible:
        stream_callback(visible)

# 工具调用: 等完整响应到达后解析
# 不支持流式即执行工具
```

### 8.2 Claude Code

```typescript
// 原生流式→AsyncGenerator
for await (const event of anthropic.beta.messages.stream(...)) {
  switch (event.type) {
    case 'content_block_start':
      if (event.content_block.type === 'tool_use') {
        // Tool 块开始 → 立即通知 StreamingToolExecutor
        streamingExecutor.addTool(event.content_block, ...)
      }
      break
    case 'content_block_delta':
      yield { type: 'stream_event', delta: event.delta.text }
      break
    case 'content_block_stop':
      // 累积完整 content block
      break
    case 'message_stop':
      yield finalizeAssistantMessage(event)
      break
  }
}

// StreamingToolExecutor:
// - tool_use 块边到边时即开始执行
// - 并发安全工具: 立即并行启动
// - 非安全工具: 等待前一个完成
// - discard(): 流式 fallback 时丢弃所有进行中的工具
```

### 8.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 流式回传 | 回调函数 (单项) | AsyncGenerator yield (双向) |
| 工具执行时机 | 完整响应后 | 流式即执行 (StreamingToolExecutor) |
| 取消 | `_interrupt_requested` 轮询 | AbortController + discard() |
| Content scrubbing | StreamingContextScrubber | 无 (Anthropic 原生不返回 memory 标签) |
| Thinking 过滤 | StreamingThinkScrubber | 原生 redacted_thinking blocks |

---

## 9. 系统提示词对比

### 9.1 hermes-agent: 三层

```
STABLE (会话不变, prompt cache 前缀):
  身份定义 (SOUL.md or default)
  工具指引 (条件化: tools>0)  ← 条件化注入!
  模型特定指引
  环境/平台提示

CONTEXT (会话稳定):
  上下文文件 (AGENTS.md, .cursorrules, CLAUDE.md)
  自定义 system_message

VOLATILE (每轮可能变):
  记忆快照
  USER.md
  时间戳/会话信息
  外部 memory provider
```

### 9.2 Claude Code: 双层

```
System Prompt:
  defaultSystemPrompt (base)
  + customSystemPrompt (CLI --system-prompt)
  + appendSystemPrompt

User Context (prepended):
  CLAUDE.md (从 cwd 遍历)
  currentDate

System Context (appended):
  gitStatus
  cacheBreaker (开发用)
```

### 9.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 层数 | 3 (stable/context/volatile) | 2 (prompt + contexts) |
| 条件化注入 | ✅ tools>0 → 注入工具指引 | ❌ 不条件化 |
| 身份来源 | SOUL.md or hardcoded | Hardcoded (internal) |
| CLAUDE.md 发现 | ✅ 遍历 git root | ✅ 遍历 git root |
| Git 状态 | ✅ git status + log + branch | ✅ git status + log + branch |
| Prompt cache 策略 | 整个 system prompt 不变 | Anthropic cache_control breakpoints |

---

## 10. 生命周期对比

### 10.1 hermes-agent

```
AIAgent 生命周期:
  创建: AIAgent.__init__()  — 60+ 参数初始化
        ├── Provider 自动检测
        ├── Credential 解析
        ├── Tool 注册
        └── MemoryManager 初始化

  存活: 跨多次 run_conversation() 调用
        ├── Gateway: 1 agent per (platform, chat_id)
        └── CLI: 1 agent per session

  销毁: 进程退出 or agent cache eviction (1h TTL)
        └── on_session_end() → MemoryManager.shutdown_all()
```

### 10.2 Claude Code

```
QueryEngine 生命周期:
  创建: new QueryEngine(config)
        └── config 包含: tools, commands, mcp, agents, canUseTool, ...

  存活: 一个 conversation
        ├── SDK: 1 QueryEngine per query()
        ├── CLI REPL: 1 QueryEngine per session
        └── Bridge: 1 QueryEngine per remote session

  使用: 多次 submitMessage() 调用 (每个 user turn)
        └── 每次调用 = 一个新的 AsyncGenerator

  销毁: QueryEngine 被垃圾回收 or session 结束
```

### 10.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 创建成本 | 高 (~1400行初始化) | 低 (Config 对象传递) |
| 缓存策略 | Gateway LRU cache (128上限, 1h TTL) | Bridge session 重建 |
| Session 恢复 | SQLite get_messages() | JSONL loadTranscriptFile() |
| System prompt 恢复 | sessions.system_prompt 列 | SQLite 缓存 (不重复构建) |
| Process 模型 | 长寿命 AIAgent 实例 | QueryEngine 短寿命 or 复用 |

---

## 11. Back Pressure 控制对比

### 11.1 hermes-agent

```python
# 迭代预算 (阻止无限循环)
agent.iteration_budget = IterationBudget(max_iterations)
if not agent.iteration_budget.consume():
    break  # 预算耗尽

# 工具执行延迟 (防止 API rate limit)
tool_delay: float = 1.0  # 工具间延迟 (秒)

# 文件修改验证 (end-of-turn)
# 检查 write_file/patch 是否真的生效
agent._turn_failed_file_mutations
```

### 11.2 Claude Code

```typescript
// 多层 back pressure:

// 1. 迭代限制
maxTurns?: number

// 2. Token 预算
taskBudget?: { total: number }  // 总预算
checkTokenBudget(budgetTracker)  // 每次迭代后检查

// 3. 成本预算
maxBudgetUsd?: number

// 4. Tool result 预算
applyToolResultBudget(messages, ...)  // 200K chars per turn

// 5. 自动续行 (token budget 续行)
if (budgetTracker && budgetTracker.remaining() > 0) {
  continue  // +500k 自动续行
}

// 6. Stream 超时
MAX_STREAMING_MS
```

### 11.3 对比

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 循环上限 | max_iterations (默认 90) | maxTurns (默认无限制) |
| Token 预算 | ❌ | ✅ taskBudget (总+每次剩余) |
| 成本预算 | ❌ | ✅ maxBudgetUsd |
| 自动续行 | ❌ | ✅ +500k token budget |
| Tool 预算 | ❌ | ✅ 200K chars per turn |
| 工具延迟 | tool_delay: 1.0s | ❌ 无 (分批次自然控制) |

---

## 12. 对 mochagent 的评估与建议

### 12.1 mochagent 当前状态

```
✅ 已有:
  ReActAgent → 基础 ReAct 循环
  ToolExecutor → 顺序+批处理工具执行
  MemoryManager → 步骤追踪 (正在重构)
  EventBus → 事件通知

❌ 缺失:
  - Generator/Streaming 返回模式 (当前是同步 return)
  - ensureToolResultPairing
  - 流式工具执行 (StreamingToolExecutor)
  - 多层次 back pressure (token budget, cost budget)
  - 管道式消息构建 (snip→microcompact→collapse→normalize)
  - Zustand-like 分层状态管理
  - 独立重试计数器 per error type
  - 条件化系统提示词注入 (tools>0)
```

### 12.2 推荐架构

```java
// 核心: AgentEngine (类 QueryEngine)
public class AgentEngine {
    private final AgentConfig config;
    private final List<Message> messages;
    private final ToolUseContext toolUseContext;
    private int turnCount;

    // AsyncGenerator 模式: 用 Java Stream/Iterator
    public Stream<AgentEvent> submitMessage(String prompt) {
        return Stream.generate(() -> {
            // yield-style: each iteration produces one event
            // ...
            if (hasToolUse) {
                return ToolExecutionEvent.of(results);
            }
            return TerminalEvent.of(content, usage);
        }).takeWhile(event -> !(event instanceof TerminalEvent));
    }
}

// 推荐的状态层次:
// 1. BootstrapState — 类似 Claude Code bootstrap/state.ts
//    (sessionId, cwd, modelUsage, totalCost)
// 2. AgentState — 类似 Query Loop 内的 State
//    (messages, toolUseContext, recoveryCounts, turnCount)
// 3. AppState — UI 状态 (如果做 TUI/Web)
```

### 12.3 优先级实现路线

| 优先级 | 改进项 | 来源于 |
|--------|--------|--------|
| **P0** | ensureToolResultPairing (防 API 400) | Claude Code |
| **P0** | Generator/streaming 返回模式 | Claude Code |
| **P0** | 管道式消息构建 (normalize→compact→inject) | Claude Code |
| **P1** | 并发安全工具智能分批 | 两者 |
| **P1** | 独立重试计数器 per error type | hermes-agent |
| **P1** | Token 预算 + 成本预算 | Claude Code |
| **P2** | 流式工具执行预留 | Claude Code |
| **P2** | 条件化系统提示词注入 | hermes-agent |
| **P2** | 分层状态管理 (Bootstrap/Agent/UI) | Claude Code |
| **P3** | Streaming 工具即执行 | Claude Code |
| **P3** | task_budget 自动续行 | Claude Code |
