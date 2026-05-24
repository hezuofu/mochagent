# Claude Code 模块划分与结构化输出分析

> 基于 `E:\ai-work\mochagent\claude-code\src\` 源码和 `claude-code-llm-interaction-spec.md` 规格文档。

---

## 一、核心模块划分

```
claude-code/src/
├── query.ts (1729行)          ← 核心查询循环，整个 agent 的"心脏"
├── query/                      ← 查询子系统
├── Task.ts                     ← 任务抽象（后台 shell/agent/bash 任务）
├── tasks/                      ← 任务管理子系统
├── Tool.ts                     ← 工具接口 + 注册
├── tools/                      ← 工具实现
├── assistant/                  ← 助理逻辑（会话历史）
│   └── sessionHistory.ts
├── constants/
│   └── prompts.ts              ← 系统提示词（18 段组装）
├── context.ts                  ← 上下文管理入口
├── context/                    ← 上下文子系统
├── services/
│   ├── compact/                ← 自动压缩（autoCompact + reactiveCompact）
│   ├── contextCollapse/        ← 上下文折叠
│   ├── toolUseSummary/         ← 工具结果摘要
│   ├── skillSearch/            ← 技能发现
│   └── api/                    ← API 调用 + 重试
├── utils/
│   ├── messages.ts             ← 消息构建（ContentBlock 创建 + 标准化）
│   ├── api.ts                  ← API 调用辅助
│   ├── attachments.ts          ← 附件注入（记忆 + 技能）
│   └── messageQueueManager.ts  ← 用户输入优先级队列
├── hooks/                      ← 钩子系统（useCanUseTool 等）
├── skills/                     ← 技能系统
├── plugins/                    ← 插件系统
├── server/                     ← MCP 服务器
├── cli/                        ← 命令行界面
├── commands/                   ← 斜杠命令
├── state/                      ← 状态管理
├── types/                      ← 类型定义
└── entrypoints/                ← 入口点
```

## 二、核心查询循环（query.ts:307）

### 2.1 完整流程图

```
while (true):
  ┌── 1. 预取阶段 ──────────────────────────────────────────┐
  │  skillPrefetch.startSkillDiscoveryPrefetch()              │
  │  memoryPrefetch                                           │
  ├── 2. 上下文准备阶段 ─────────────────────────────────────┤
  │  microCompact (token 预算检查)                             │
  │  contextCollapse (折叠长工具输出)                          │
  │  autoCompact (压缩旧消息)                                  │
  │  normalizeMessagesForAPI (标准化为 API 格式)               │
  ├── 3. API 调用阶段 ───────────────────────────────────────┤
  │  callModel(messages, tools, systemPrompt)                 │
  │  └→ 流式处理（yield StreamEvent）                         │
  │     ├── content_block_start: text → 累积文本              │
  │     ├── content_block_start: tool_use → 收集工具调用      │
  │     ├── content_block_start: thinking → 累积思考          │
  │     ├── content_block_delta → 增量更新                    │
  │     └── content_block_stop → 完成                         │
  ├── 4. 错误恢复阶段 ───────────────────────────────────────┤
  │  prompt_too_long → 压缩重试                               │
  │  maxOutputTokens → 续写                                   │
  │  fallback → 降级模型                                      │
  ├── 5. 工具执行阶段 ───────────────────────────────────────┤
  │  if has_tool_use_blocks:                                  │
  │    StreamingToolExecutor.run(toolUses)                    │
  │    └→ 并发执行: Zod 验证 → 权限检查 → Hook → 执行         │
  │    工具结果注入 messages                                  │
  ├── 6. 附件注入阶段 ───────────────────────────────────────┤
  │  memoryAttachments → 记忆附件注入                         │
  │  skillAttachments → 技能发现注入                          │
  ├── 7. 停止检查阶段 ───────────────────────────────────────┤
  │  stopHook → 用户停止信号                                  │
  │  budget exhausted → 终止                                  │
  ├── 8. 决策阶段 ───────────────────────────────────────────┤
  │  hasToolResults? → continue (下一轮)                     │
  │  noToolResults → break (最终输出)                         │
  └──────────────────────────────────────────────────────────┘
```

### 2.2 关键决策点

**何时继续：** `hasToolResults === true`
- 执行了至少一个工具调用
- 工具结果已注入 messages
- 下一轮 LLM 看到工具结果并决定下一步
- 注意：**stop_reason === 'tool_use' 不可靠**（代码注释 line 554），改用 content block 类型判断

**何时停止：** `hasToolResults === false`
- 本轮响应没有 tool_use 块 → 纯文本 → 最终答案
- 或所有 tool_use 块的执行结果为空/错误且无法恢复

## 三、LLM 响应分类（Content Block 类型）

### 3.1 Anthropic API 原生 Content Block

Claude Code 使用 Anthropic Messages API，LLM 响应自动包含类型化内容块。**不需要文本解析**——API 保证内容块类型正确：

```typescript
// 从源码 types/message.ts + query.ts 推断的类型系统
type ContentBlock =
  | { type: 'text', text: string }
  | { type: 'tool_use', id: string, name: string, input: object }
  | { type: 'thinking', thinking: string, signature: string }
  | { type: 'redacted_thinking', data: string }
  | { type: 'tool_result', tool_use_id: string, content: string, is_error?: boolean }
```

### 3.2 框架的分类逻辑（query.ts ~line 830）

```typescript
// 检测本轮是否有工具调用
const toolUseBlocks = assistantMessage.content.filter(
  content => content.type === 'tool_use'
)

if (toolUseBlocks.length > 0) {
  // 有 tool_use → 执行工具 → 继续循环
  await runTools(toolUseBlocks)
  continue
} else {
  // 无 tool_use → 纯文本 → 视为最终回答 → 退出循环
  break
}
```

### 3.3 关键洞察

1. **LLM 自主决定何时调用工具** — 系统提示词描述可用工具，LLM 自行决定使用哪个、何时使用
2. **框架不强制格式** — 不像 mochagent 的 `Action: tool(args)` 文本格式
3. **thinking 块不影响流程** — 纯粹用于展示，不改变循环决策
4. **text 块可出现在任意位置** — LLM 可以在工具调用前后自由输出文本

## 四、系统提示词体系（constants/prompts.ts）

### 4.1 静态前缀（跨会话缓存，scope: global）

```
1. SimpleIntro        — "You are an interactive agent..."
2. System             — 系统规则（工具、权限、压缩）
3. DoingTasks         — 任务执行准则（不要过度设计等）
4. Actions            — 风险操作警告
5. UsingYourTools     — 工具使用指引
6. ToneAndStyle       — 语气风格
7. OutputEfficiency   — 输出效率要求
```

### 4.2 动态后缀（每会话变化，scope: session）

```
8.  SessionGuidance    — 工具使用指引
9.  Memory             — 记忆操作
10. EnvInfo            — 环境信息（工作目录、平台、日期）
11. Language           — 语言偏好
12. OutputStyle        — 自定义输出风格
13. MCPInstructions    — MCP 服务指令
14. Scratchpad         — 临时文件目录
15. FRC                — 函数结果清理
16. Summarize          — 工具结果总结
17. TokenBudget        — Token 预算（实验性）
18. Brief              — 自主模式（实验性）
```

边界标记：`__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__`

### 4.3 System Prompt 关键规则（DoingTasks 节选）

```
- 用户请求软件工程任务；模糊请求视为工程问题
- 不添加功能/重构/抽象超出任务需求
- 不添加不可能发生的错误处理
- 不为一两次使用创建工具/抽象
- 默认不写注释；仅当 WHY 非显而易见时写
- 避免向后兼容 hack
- 用 GitHub-flavored markdown 输出
- 工具权限由用户控制；被拒绝后不重试相同调用
```

## 五、消息标准化（utils/messages.ts）

### 5.1 normalizeMessagesForAPI()

将所有内部消息格式标准化为 Anthropic API 的 `MessageParam[]`：

```
UserMessage → { role: 'user', content: string | ContentBlock[] }
AssistantMessage → { role: 'assistant', content: ContentBlock[] }
ToolResultMessage → { role: 'user', content: [{ type: 'tool_result', ... }] }
SystemMessage → 提取到 system 参数（不在 messages 数组中）
```

### 5.2 消息配对保证（ensureToolResultPairing）

```
- 去重 tool_use blocks（同 id 只保留一个）
- 剥离服务端 tool_use（不匹配本地工具的）
- 为孤儿 tool_use 插入空 content 占位符
- 确保每个 tool_use 有对应 tool_result
```

## 六、与 mochagent 的关键差异

| 维度 | Claude Code | mochagent |
|------|-------------|-----------|
| LLM 响应格式 | **原生 ContentBlock**（text/tool_use/thinking） | **文本解析**（正则匹配 Action:） |
| 工具调用检测 | `block.type === 'tool_use'` | `parseAction(text)` 正则 |
| 循环决策 | 有 tool_use → continue，无 → break | 条件判断 + hasFinalAnswer |
| 格式强制 | 不强制，LLM 自主决定 | **MANDATORY Action: 格式** |
| 系统提示词 | 18 段分层（static prefix + dynamic suffix） | 单段模板渲染 |
| 消息管理 | ContentBlock 类型化数组 | `Map<String, String>` 平面结构 |
| 压缩策略 | 4 级（micro→snip→collapse→autoCompact） | 1 级（compress） |
| 消息配对 | ensureToolResultPairing | 无 |

## 七、mochagent 可借鉴的改进方向

1. **ContentBlock 类型化消息** — 用 `Message` 的 typed content blocks 代替 `Map<String, String>`
2. **响应分类代替格式强制** — 检查响应是否包含工具调用，而不是强制 LLM 输出特定格式
3. **分层系统提示词 + 缓存** — 静态 prefix 全局缓存，动态 suffix 按会话变化
4. **多级上下文压缩** — microCompact（轻量）+ autoCompact（重量）+ reactiveCompact（错误触发）
5. **消息配对保证** — 确保 tool_use/tool_result 严格配对
