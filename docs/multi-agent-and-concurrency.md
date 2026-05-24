# 多 Agent 协作与并发机制

> hermes-agent 和 Claude Code 的完整分析

---

## 一、多 Agent 协作

### 1.1 hermes-agent — 三种协作模式

**模式 A: delegate_task（显式委派）**

```
Parent Agent
  │ Action: delegate_task(prompt="review this code", agent="reviewer")
  ▼
_dispatch_delegate_task()
  ├── 创建子 AIAgent (同 model/credentials/tools)
  ├── _cap_delegate_task_calls() 截断超出 max_concurrent_children 的调用
  ├── 子 agent 运行在独立线程
  │   ├── 权限: _subagent_auto_deny (危险命令自动拒绝)
  │   └── 结果返回父 agent 的 tool_result
  └── 结果注入父对话的 messages 列表

关键代码: run_agent.py:9822 _dispatch_delegate_task()
         tools/delegate_tool.py
```

**模式 B: Background Review / Curator（自动触发）**

```
turn_end_callback()
  │ 检测: 是否需要 memory/skill 保存?
  ▼
_spawn_background_review()                    (run_agent.py:3792)
  ├── 触发条件:
  │   ├── review_memory=True  → _MEMORY_REVIEW_PROMPT
  │   ├── review_skills=True  → _SKILL_REVIEW_PROMPT
  │   └── 两者都有           → _COMBINED_REVIEW_PROMPT
  ├── 创建独立 AIAgent fork:
  │   ├── 继承父 agent 的 model/provider/credentials
  │   ├── 共享 _memory_store + skill stores
  │   ├── quiet_mode + redirect stdout/stderr
  │   └── auto-deny 所有危险命令
  ├── 运行在独立线程
  └── 不修改主对话历史, 不产生用户可见输出
```

**模式 C: Kanban 看板（跨进程任务分发）**

```
共享状态: ~/.hermes/kanban.db (SQLite)

Orchestrator (分解者):
  kanban_create(title, assignee=specialist, parents=[parent_task])
  └── 创建子任务卡片

Worker (执行者):
  kanban_show() → 获取任务详情
  ├── worker_context: 预格式化的上下文
  ├── comment thread: 历史讨论
  └── prior attempts: 之前的尝试记录
  kanban_heartbeat(note="...") → 长任务心跳
  kanban_block(reason="...")   → 阻塞等待人工决策
  kanban_complete(summary, metadata) → 交付
  kanban_create(follow_up)     → 创建后续任务

关键规则:
  - Worker 在 $HERMES_KANBAN_WORKSPACE 内工作
  - 不修改工作空间外的文件
  - 不自己执行 follow-up → 创建子任务指派给专家
```

### 1.2 Claude Code — 两种协作模式

**模式 A: Task 系统（同进程后台任务）**

```typescript
TaskType: local_bash | local_agent | remote_agent | in_process_teammate | local_workflow

TaskStateBase {
  id, type, status (pending→running→completed/failed/killed)
  description, toolUseId, outputFile (JSONL), outputOffset
  startTime, endTime, notified
}

ProgressTracker {
  toolUseCount, latestInputTokens, cumulativeOutputTokens
  recentActivities: ToolActivity[] (max 5)
}

TaskManager:
  submit(type, id, desc, work) → ManagedTask<T>
  ├── 分配线程池执行
  ├── 进度跟踪 + 通知
  └── cleanup registry (onComplete/onFail/onKill)

ManagedTask<T>:
  future: CompletableFuture<T>
  get(timeoutMs) / get() → 阻塞等待结果
```

**模式 B: AgentTool（工具级子 agent）**

```
ToolCallingAgent
  │ Action: Agent(prompt="analyze this module")
  ▼
AgentTool.call()
  ├── 派生新 ToolCallingAgent
  │   ├── 继承 toolRegistry + LLM
  │   ├── 独立 maxSteps 循环
  │   └── 结果序列化返回
  └── 父 agent 看到 tool_result
```

---

## 二、并发与中断机制

### 2.1 hermes-agent — Thread-scoped 中断

**核心数据结构:**

```python
# tools/interrupt.py
_interrupted_threads: set[int] = set()  # 被中断的线程 ID 集合

set_interrupt(active: bool, thread_id: int)
  → thread_id 加入/移出 _interrupted_threads

is_interrupted() → bool
  → 检查当前线程是否在 _interrupted_threads 中
```

**Gateway 模式:**

```
每条 WebSocket 消息 → 新 AIAgent 实例 → 新线程
  ├── 线程 A: 用户 A 的对话 → execution_thread_id = A
  ├── 线程 B: 用户 A 的新消息 → execution_thread_id = B
  │   └── set_interrupt(True, thread_A)  ← 中断旧对话
  └── 线程 C: 用户 B 的对话 → 不受影响

_interrupt_requested 标志:
  while loop 每轮开始检查:
    if self._interrupt_requested:
        interrupted = True
        break
```

**steer() vs interrupt():**

```
steer(msg):
  ├── 设置 _steer_message = msg
  ├── 不设置 _interrupt_requested (不中断)
  └── 等当前工具批次完成后注入

interrupt(msg):
  ├── 设置 _interrupt_message = msg
  ├── 设置 _interrupt_requested = True
  └── 下次循环检查时退出
```

**工具内中断检查:**

```python
# 任何工具都可以检查中断
from tools.interrupt import is_interrupted
if is_interrupted():
    return {"output": "[interrupted]", "returncode": 130}
```

**子 agent 中断传播:**

```python
self._active_children = []  # 运行中的子 AIAgent 列表
# interrupt() 时遍历 _active_children, 逐个调用 child.interrupt()
```

### 2.2 Claude Code — AbortController + 优先级队列

**AbortController 模式:**

```typescript
const abortController = new AbortController()

// 6+ 个检查点分布在 query.ts 的 while(true) 循环中:
1. API 调用前检查       (line 664)
2. 流式处理中检查       (line 839, 849)
3. 错误处理后检查       (line 1015)
4. 工具执行后检查       (line 1418)
5. 工具结果处理后检查   (line 1485)
6. 循环返回前检查       (line 1728)

signal.reason 区分类型:
  'interrupt'          → 用户中断, 跳过中断消息
  'submit-interrupt'  → 提交中断, 静默返回
  其他                 → 异常中断, 显示错误
```

**消息优先级队列:**

```typescript
// messageQueueManager.ts
优先级: 'now' > 'next' > 'later'

now:   用户中断 → 立即处理
next:  用户输入 → 下一轮处理 (默认)
later: 通知/附件 → 延迟处理

enqueue({ command, priority? })
dequeue(filter?) → 取出最高优先级

getCommandsByMaxPriority() → query.ts line 1566
  └── 每轮结束后取出并处理所有排队命令
```

**流式工具执行:**

```typescript
// 关键创新: LLM 还在输出时, 工具已开始并行执行
streamingToolExecutor.addTool(toolBlock, message)  ← 每个 tool_use block 到达时立即添加
streamingToolExecutor.getCompletedResults()          ← 实时获取已完成结果

// 不等 LLM 完成 → 工具结果与文本流交错 yield
while (stream.next()) {
  if (type === 'tool_use') addTool()
  if (type === 'completed_result') yield result
}
```

---

## 三、对比总结

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **并发模型** | 多线程 (gateway 每消息一线程) | 单线程事件循环 + AbortController |
| **中断检测** | 每轮循环开始时 1 次检查 | 循环中 6+ 个检查点 |
| **新消息处理** | 创建新 AIAgent 实例 (隔离) | 入队优先级队列 (同实例) |
| **中断粒度** | Thread-scoped (精确到线程) | Signal-scoped + reason 区分 |
| **工具中断** | 工具自行调用 is_interrupted() | 工具通过 abort signal 感知 |
| **实时反馈** | step_callback 每步推送 | yield 生成器流式输出 |
| **注入不中断** | steer() — 等工具批次完成 | submit-interrupt (reason 区分) |
| **工具并发** | ThreadPoolExecutor 并行执行 | streamingToolExecutor 流式并发 |
| **子 agent 中断** | 遍历 _active_children 传播 | 通过 TaskManager 管理 |
| **后台任务** | Background Review (自动) | Task 系统 (显式提交) |
| **跨进程** | Kanban SQLite 看板 | 无 |
