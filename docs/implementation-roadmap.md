# MochaAgent 待实现计划

> 基于 hermes-agent、Claude Code、GenericAgent 深度分析，按优先级排序。

---

## P0 — 核心体验（立即实现）

### 1. 响应分类代替格式强制

**现状：** `ToolCallingAgent` 强制 LLM 输出 `Action: tool(args)` 文本格式
**目标：** 框架智能分类 LLM 响应，不强制格式

```
LLM 响应 → ContentBlock 分类：
  ├── tool_use 块 (Anthropic native) → 执行工具 → continue
  ├── text 块 → 是否包含工具调用意图?
  │   ├── 有 → 映射为工具调用 → 执行
  │   └── 无 → 最终答案 → break
  └── thinking 块 → 记录推理 → continue
```

**参考：** Claude Code `query.ts:830` — `content.filter(c => c.type === 'tool_use')`
**实现要点：**
- `BaseApiLLM.typedMessagesToJson()` 已支持 ContentBlock 结构
- 需要改造 AgentLoop 的终止条件：`有 tool_use → continue, 无 → break`
- 去掉 `buildSystemPrompt()` 中的 `## Response Format (MANDATORY)` 强制格式

### 2. 对话中断与并发

**现状：** 无中断机制
**目标：** 用户可在对话进行中发送新消息，优雅中断当前执行

```
hermes-agent 模式:
  _interrupted_threads: Set<thread_id>
  工具内调用 is_interrupted() 自检

Claude Code 模式:
  abortController.signal.aborted 多检查点
  消息队列: now > next > later
```

**实现要点：**
- `AgentContext` 增加 `AbortController abortController`
- AgentLoop 每轮开始检查 `abortController.signal.aborted`
- 工具执行前/后检查中断信号
- `messageQueueManager` 优先级队列

---

## P1 — 生产级能力（近期实现）

### 3. 系统提示词分层缓存

**参考：** hermes-agent `_build_system_prompt()` 7层组装 + `_cached_system_prompt`

```
静态前缀 (全局缓存, scope: global):
  1. Agent 身份 (SOUL.md > DEFAULT_IDENTITY)
  2. 系统规则 (权限/压缩/钩子)
  3. 任务执行准则 (不写注释/不造工具)
  4. 风险操作警告
  5. 工具使用指引

__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__

动态后缀 (每会话, scope: session):
  6. 工具感知引导 (按已加载工具条件注入)
  7. 模型特定指引 (Google/OpenAI/Gemini)
  8. 持久记忆 + 外部 MemoryPlugin
  9. 环境信息 (cwd/git状态/平台/日期)
  10. 工作记忆 + 全局记忆
```

**实现要点：**
- `PromptTemplate` 支持 static/dynamic 分割
- 静态部分全局缓存，动态部分按会话构建
- 最大化 API prefix cache 命中

### 4. 工具使用强制提示词

**参考：** hermes-agent `TOOL_USE_ENFORCEMENT_GUIDANCE`

```
# Tool-use enforcement
You MUST use your tools to take action — do not describe what you would do.
Every response should either (a) contain tool calls that make progress, or
(b) deliver a final result to the user.
```

**参考：** hermes-agent `OPENAI_MODEL_EXECUTION_GUIDANCE` (5个XML块)

```
<tool_persistence> — 不提前停止，重试不同策略
<mandatory_tool_use> — 数学/哈希/时间必须用工具
<act_dont_ask> — 有默认解释时直接行动
<prerequisite_checks> — 先做前置发现
<verification> — 验证正确性/事实/格式/安全
<missing_context> — 信息缺失时查工具，不幻想
```

### 5. 多 agent 协作

**参考：** hermes-agent delegate_task + Kanban

```
模式 A: delegate_task (显式委派)
  Parent → delegate_task(prompt, agent) → 子 AIAgent → 返回结果

模式 B: Background Review (自动触发)
  turn_end → _spawn_background_review() → 独立线程审查 memory/skill

模式 C: Kanban 看板 (跨进程分发)
  Orchestrator → kanban_create → SQLite 任务池
  Worker → kanban_show → 执行 → kanban_complete
```

**实现要点：**
- `AgentTool` 已存在，需增强：子 agent 权限隔离、并发限制
- Kanban 模式需要 SQLite 共享状态 + 独立进程 worker
- 后台 Review 模式需要 `SettlementHook`

---

## P2 — 自主学习（中期实现）

### 6. GenericAgent 三层学习

```
LearningLoop 已设计，待接线到 ReActAgent:

L1 工作记忆 (update_checkpoint):
  每任务开始设置 key_info + related_sop
  每轮 anchor prompt 注入

L2 全局记忆 (start_long_term_update):
  任务完成后显式结算
  只记录"行动验证成功"的信息
  声明式事实 ("Project uses X") ✓，指令式 ("Always do X") ✗

L3 SOP 技能:
  复杂任务经验 → 可复用 SOP 文件
  关键坑点/前置条件/重要步骤
```

**实现要点：**
- `LearningLoop` 已定义 (`learn/LearningLoop.java`)
- 需要接线到 `ReActAgent.run()` 的每轮循环
- `turn_end_callback` 注入 summary 强制 + 反遗忘警告
- `settle()` 时验证闸门：只记录 verified 信息

### 7. 反遗忘机制

```
turn % 10 == 0 → 重新注入 globalContext()
turn % 7  == 0 → [DANGER] 禁止无效重试，切换策略
turn % 65 == 0 → [DANGER] 强制 ask_user，不允许继续
turn % 5  == 0 (plan模式) → 确认当前步骤
turn % 90 == 0 (plan模式) → 必须 ask_user
```

### 8. 消息队列 + 优先级

**参考：** Claude Code `messageQueueManager.ts`

```
消息优先级: now > next > later
  now:   用户中断 → 立即处理
  next:  用户输入 → 下一轮处理
  later: 通知/附件 → 延迟处理

同优先级 FIFO
getCommandsByMaxPriority() → 取出最高优先级命令
```

---

## P3 — 架构优化（长期实现）

### 9. ContentBlock 类型化消息

**现状：** `Map<String, String>` 平面消息结构
**目标：** `Message` 接口支持 typed ContentBlock 数组

```java
Message {
  role: "user" | "assistant" | "system"
  content: ContentBlock[]
}

ContentBlock:
  TextBlock { text: String }
  ToolUseBlock { id, name, input: Map }
  ToolResultBlock { toolUseId, content, isError }
  ThinkingBlock { thought, signature }
```

**参考：** Claude Code `utils/messages.ts` — normalizeMessagesForAPI, ensureToolResultPairing

### 10. 多级上下文压缩

```
4 级压缩 (Claude Code 模式):
  snip         → 移除旧工具结果
  microCompact → 清除单个工具结果 (tool_use_id 级别)
  collapse     → 折叠长工具输出
  autoCompact  → 总结对话历史 (LLM 调用)
```

**参考：** Claude Code `services/compact/`
- `microCompact.ts` — 工具结果级别轻量清理
- `autoCompact.ts` — 含 circuit breaker (3次连续失败停止)
- `compact.ts` — 缓存共享 fork + 禁用 thinking 的压缩 API 调用

### 11. Session 恢复与跨会话搜索

**现状：** `SessionStore` 已定义但未接线
**目标：** 用户重新登录后可看到完整对话历史

```
SessionStore (JSONL):
  ~/.mocha/projects/{projectHash}/
    {sessionId}.jsonl          — 完整转录
    {sessionId}.meta.json      — 标题/标签/时间戳

listSessions() → 按时间排序
loadTranscript() → 消息顺序列表
session_search 工具 → 跨会话搜索
```

---

## 参考文档索引

| 文档 | 内容 |
|------|------|
| `docs/hermes-agent-design.md` | hermes-agent 完整设计 (核心对象/执行流程/提示词全文/对话历史) |
| `docs/claude-code-module-analysis.md` | Claude Code 模块划分/ContentBlock分类/查询循环/4级压缩 |
| `docs/cognitive-capabilities-comparison.md` | 三个框架的感知/推理/规划/学习深度对比 |
| `docs/hermes-agent-architecture-analysis.md` | hermes-agent 架构概览 |
| `claude-code-llm-interaction-spec.md` | Claude Code LLM 交互技术规格 (提示词体系/API结构/流式处理) |
| `claude-code源码分析.md` | Claude Code 源码中文分析 |
