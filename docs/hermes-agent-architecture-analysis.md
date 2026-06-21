# Hermes-Agent Architecture Analysis

hermes-agent 是生产级 Python AI Agent 系统（~15K 行 run_agent.py），已部署运行。

## 1. 系统提示词体系（7层组装）

`run_agent.py:5275 _build_system_prompt()` 按优先级逐层组装：

```python
# Layer 1: Agent 身份 — SOUL.md 文件 > DEFAULT_AGENT_IDENTITY 硬编码
# Layer 2: Hermes 自指引导（HERMES_AGENT_HELP_GUIDANCE）
# Layer 3: 工具感知引导（MEMORY_GUIDANCE/SKILLS_GUIDANCE/KANBAN_GUIDANCE）
# Layer 4: 平台特定（计算机使用等）
# Layer 5: 工具使用强制（TOOL_USE_ENFORCEMENT_GUIDANCE）+ 模型特定引导
# Layer 6: 用户自定义 system_message
# Layer 7: 持久记忆 + 外部 MemoryProvider
```

**关键设计：**
- 整个 system prompt **每个会话构建一次**，跨所有轮次缓存（`self._cached_system_prompt`）
- 最大化 API prefix cache 命中率
- 压缩事件后才重建
- `ephemeral_system_prompt` 不进入缓存，仅在 API 调用时注入

## 2. Agent 执行流程

### 2.1 主入口 `run_conversation()` (line 10978)

```
1. 初始化：reset 6种重试计数器 + iteration_budget
2. 预取：memory_manager.prefetch_all() 外部记忆
3. 预检：context_compressor 检查 token 是否超阈值
4. 主循环：while (api_call_count < max_iterations AND budget > 0)
   ├── 检查中断信号
   ├── 调用 step_callback（网关钩子）
   ├── 构建 API 消息
   ├── 调用 LLM API（流式/非流式）
   ├── 检查响应：空内容/无效工具/JSON错误 → 重试
   ├── 执行工具调用：并发 > 顺序
   └── 检查停止条件：finish_reason == "stop"
5. 后处理：记忆持久化、会话存储、callback
```

### 2.2 工具执行策略 (line 9799)

```
_execute_tool_calls()
├── 工具是否正确申明？（tool_guardrails 验证）
├── 是否并发安全？
│   ├── 是 → _execute_tool_calls_concurrent()
│   └── 否 → _execute_tool_calls_sequential()
└── 结果注入 messages 列表
```

### 2.3 终止条件

```python
while (api_call_count < self.max_iterations and self.iteration_budget.remaining > 0) or self._budget_grace_call:
```

- `max_iterations`：默认 90（构造函数参数）
- `iteration_budget`：Token 预算控制器
- `_budget_grace_call`：预算耗尽前的最后一次免费调用

## 3. 结构化输出保证

### 3.1 工具使用强制（TOOL_USE_ENFORCEMENT_GUIDANCE）

在 system prompt 中注入强制指令，告诉模型**必须**调用工具而不是描述意图：

```yaml
# config.yaml: agent.tool_use_enforcement
# "auto"  — 匹配 TOOL_USE_ENFORCEMENT_MODELS 列表
# true    — 所有模型强制注入
# false   — 不注入
# list    — 自定义模型名匹配
```

### 3.2 模型特定适配

| 适配器 | 用途 |
|--------|------|
| `gemini_schema.py` | Gemini 结构化输出 schema |
| `codex_responses_adapter.py` | OpenAI Codex Responses API |
| `moonshot_schema.py` | Moonshot 模型 schema |

### 3.3 6种重试机制

每个回合开始重置所有重试计数器：

```python
self._invalid_tool_retries = 0      # 工具调用格式无效
self._invalid_json_retries = 0       # JSON参数解析失败  
self._empty_content_retries = 0      # 模型返回空内容
self._incomplete_scratchpad_retries = 0  # 草稿不完整
self._codex_incomplete_retries = 0   # Codex 响应不完整
self._thinking_prefill_retries = 0   # 思考预填充失败
```

### 3.4 工具调用解析

支持多种格式（line 3290）：
- `<function_call>…</function_call>` — XML 格式
- `<function_calls>…</function_calls>` — 批量 XML
- OpenAI native `tool_calls` — JSON 格式
- Anthropic native `tool_use` — Content Block 格式

## 4. 架构特点对比

| 维度 | hermes-agent | mochagent |
|------|-------------|-----------|
| Agent | AIAgent God class (~10K行) | Agent接口 + Faculty组装 |
| 循环 | 硬编码 while 循环 | 5个可插拔 AgentLoop |
| 提示词 | 7层组装 + 缓存策略 | PromptTemplate 简单渲染 |
| 上下文 | ContextEngine + 预检压缩 | Context接口 + 策略模式 |
| 重试 | 6种细粒度重试 | 简单 try-catch |
| 结构化输出 | 模型特定适配器 | 无（纯文本解析） |
| 记忆 | MemoryManager 委派 | AgentMemory 统一 |

## 5. 可借鉴到 mochagent 的点

1. **System prompt 分层缓存** — 一次构建、跨轮次复用、最大化 prefix cache
2. **细粒度重试分类** — 6 种重试类型针对不同失败模式
3. **Context 预检压缩** — 进入循环前检查 token 预算，主动压缩
4. **工具使用强制** — system prompt 注入 "必须使用工具" 指令
5. **模型特定适配** — 不同 LLM 的 schema/格式差异用适配器隔离
