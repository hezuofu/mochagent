# Agent 认知能力深度对比

> 对比三个框架如何处理感知/意图/推理/规划/学习 — hermes-agent、Claude Code、GenericAgent

---

## 一、感知与意图识别

### hermes-agent
**无显式感知器。** LLM 通过工具（read_file, glob, grep, web_search, session_search）主动获取环境信息。意图识别完全由 LLM 自主完成——提示词指引但不强制。

```
感知 = 工具调用
意图 = LLM 自然语言理解
```

关键提示词：`HERMES_AGENT_HELP_GUIDANCE` 指引模型"当用户问 Hermes 本身时，加载 hermes-agent 技能"
`TOOL_USE_ENFORCEMENT_GUIDANCE` 强制"每个响应要么包含工具调用，要么给出最终结果"

### Claude Code
**无显式感知器。** 完全依赖工具调用获取环境信息。Git 状态、项目结构、平台信息在 system prompt 的 EnvInfo 段注入。感知 = 环境信息注入 + 工具调用。

系统提示词包含：
- `computeEnvInfo()` → 工作目录、git 状态、平台、OS、模型名
- `startRelevantMemoryPrefetch()` → 预取相关记忆附件

### GenericAgent
**无显式感知器。** 通过工具主动探测：
- `web_scan()` → 浏览器页面感知
- `web_execute_js()` → 深度 DOM 操作
- `do_file_read()` → 文件读取
- `procmem_scanner` → 进程内存扫描（桌面自动化）

**关键设计：** GenericAgent 是唯一有"主动探测"概念的项目——它的 web 工具不只是"获取内容"，而是"扫描"和"探测"。

---

## 二、推理

### hermes-agent
**LLM 原生推理。** 使用 Anthropic 的 `thinking` content block 进行思考。`thinking_callback` 将思考流式推送给 UI。推理不改变执行流程——纯粹是展示和日志。

```
thinking block → thinking_callback → UI 展示
```

相关代码：run_agent.py 中 `hasattr(block, "type") == "thinking"` 检测

### Claude Code
**LLM 原生推理。** 与 hermes-agent 相同，使用 `thinking` ContentBlock。关键行为：
- `thinking` 块被**保留**（不剥离），跨 assistant 轨迹持续存在
- `stripSignatureBlocks()` 仅在凭证变更后剥离签名
- `redacted_thinking` 块用于敏感思考

### GenericAgent
**无原生 thinking。** 使用 `<thinking>` XML 标签引导 LLM 结构化其思考过程。`do_no_tool()` 中检测 `<thinking>` 标签并剥离以判断是否只有思考无行动。

```python
# agent_loop.py line 60-61
cleaned = _clean_content(response.content)  # 剥离 thinking 标签
# ga.py line 480
residual = re.sub(r"<thinking>[\s\S]*?</thinking>", "", residual)
```

---

## 三、规划

### hermes-agent
**无显式规划器。** LLM 自行决定执行顺序。planning 完全内化在 LLM 的推理链中。提示词提供指引但不强制执行计划。

KANBAN_GUIDANCE 提供了一种特殊的规划模式——看板任务分解，但这仅在看板 worker 模式下激活。

### Claude Code
**无显式规划器。** LLM 自主规划。plan_mode 是一个独立功能：
- `plan_mode` 启用时切换模型到高上下文版本（200K+ tokens）
- 提示词引导 LLM 先制定计划再执行
- plan mode 下会创建计划文件供 LLM 参考

### GenericAgent
**有显式规划模式。** `plan_sop.md` 定义了完整的规划 SOP：
- `do_update_working_checkpoint()` — 设置当前步骤的关键信息
- Plan 模式检测：`_in_plan_mode()` 检查是否处于计划模式
- 计划完成检查：`_check_plan_completion()` 统计 `[ ]` 残留
- 验证拦截：Plan 模式下检测"任务完成"声明，要求先通过 `[VERIFY]` 验证

```
Plan Mode 流程:
1. LLM 读取 plan_sop.md
2. 分步执行，每步打勾 [x]
3. _check_plan_completion() 检查残留 [ ]
4. 完成后必须通过 VERIFY subagent 验证
5. _exit_plan_mode() 退出
```

---

## 四、自我学习

### hermes-agent
**三层记忆系统（被动学习）：**

```
L1: Session Memory (会话内)
  → trajectory.py 记录步骤轨迹
  → session_search 跨会话搜索

L2: Persistent Memory (持久记忆)
  → memory 工具: 保存声明式事实
  → USER.md: 用户偏好文件
  → 规则: "User prefers X" ✓, "Always do X" ✗

L3: Skill System (技能复用)
  → skill_manage: 将复杂工作流保存为 Skill
  → 自动打补丁: "Skills that aren't maintained become liabilities"
  → skills 目录下 Markdown 文件（SKILL.md + DESCRIPTION.md）
```

**学习触发:** MEMORY_GUIDANCE 注入系统提示词，LLM 自行判断何时保存。`skill_manage` 工具提供保存接口。**无强制结算流程。**

### Claude Code
**三层学习系统（被动 + 主动）：**

```
L1: Sessions (会话历史)
  → SessionStore JSONL 格式
  → resume: 恢复历史会话
  → session_search: 跨会话搜索

L2: CLAUDE.md (项目记忆)
  → 多层优先级: /etc > ~/.claude > .claude/ > CLAUDE.local.md
  → @include 指令
  → .claude/rules/*.md 规则文件

L3: Skills (可复用技能)
  → .claude/skills/ 目录
  → skill 工具加载 + 执行
  → 动态发现: skillPrefetch 异步预取
```

**学习触发:** 完全由 LLM 自主决定。无系统级"必须学习"的强制。记忆通过 memdir 系统管理。

### GenericAgent
**三层学习系统（显式 + 主动 + 防遗忘）：**

```
L1: Working Memory (工作记忆)
  → do_update_working_checkpoint(): 设置 key_info + related_sop
  → working dict 持久化在当前任务内
  → _get_anchor_prompt() 每轮注入

L2: Global Memory (全局记忆 - global_mem.txt)
  → 文件持久化: 环境事实、用户偏好
  → get_global_memory() 每 10 轮重新注入
  → global_mem_insight.txt: 结构化洞察

L3: Insight/SOP Memory (技能记忆)
  → do_start_long_term_update(): **显式结算工具**
  → 流程: 先读现有记忆 → 判断类型 → 最小化更新
  → 规则: "只能提取行动验证成功的信息"
  → 禁止: 未验证信息、通用常识、临时变量
```

**关键差异 — GenericAgent 的独特机制：**

1. **显式结算工具** — `do_start_long_term_update()`: Agent 在**完成一个任务后**主动调用，提炼经验。不是被动的"LLM 觉得该记了"，而是一个显式的结算动作。

2. **验证闸门** — "只能提取行动验证成功的信息"、"如果只是做了但没有验证的信息禁止记录"——防止幻觉污染长期记忆。

3. **每 10 轮重新注入全局记忆** — 避免 LLM 在长对话中遗忘持久记忆。

4. **反遗忘机制** — `turn_end_callback()` 注入危险警告：
   - `turn % 65 == 0` → "必须 ask_user，不允许继续重试"
   - `turn % 7 == 0` → "禁止无效重试，必须切换策略"
   - 防止 LLM 陷入死循环

5. **自主模式** — `reflect/autonomous.py`: 用户离开 30 分钟后，Agent 自激活执行自动任务。

---

## 五、总结表

| 能力 | hermes-agent | Claude Code | GenericAgent | mochagent 当前 |
|------|-------------|-------------|-------------|---------------|
| **感知** | 工具调用（被动） | 工具调用 + EnvInfo注入 | 工具调用 + web扫描 + 进程探测 | Perceptor接口(未真正接入) |
| **意图识别** | LLM 自主 | LLM 自主 | LLM 自主 | 无 |
| **推理** | thinking block(原生) | thinking block(原生) | `<thinking>` XML标签 | Reasoner接口(循环前调用一次) |
| **规划** | LLM 自主 + 看板模式 | LLM 自主 + plan_mode | 显式 Plan SOP + 验证闸门 | Planner接口(关键词匹配跟踪) |
| **学习-L1** | trajectory 记录 | SessionStore JSONL | working memory + anchor注入 | AgentMemory步骤记录 |
| **学习-L2** | memory工具 + USER.md | CLAUDE.md多层 | global_mem.txt每10轮注入 | MemoryStore持久化 |
| **学习-L3** | skill_manage保存技能 | .claude/skills/ | start_long_term_update结算 | 无 |
| **学习触发** | 被动(LLM决定) | 被动(LLM决定) | **主动(显式结算工具)** | 无 |
| **反遗忘** | 无 | 无 | **turn%10重注 + turn%65拦截** | 无 |
| **自主模式** | 无 | 无 | **30min空闲自激活** | 无 |

## 六、关键洞察

1. **三个框架都不使用显式 Perceptor/Reasoner 接口** — 它们通过提示词工程让 LLM 自己做感知/推理，框架只提供工具和上下文。

2. **规划能力差异巨大** — GenericAgent 是唯一有显式规划 SOP + 验证闸门的。Claude Code 的 plan_mode 最轻量（只切换模型+提示词）。

3. **自我学习的关键在于"验证"** — GenericAgent 的 `start_long_term_update` 明确要求"只能记录行动验证成功的信息"，这是防止 LLM 幻觉污染记忆的关键约束。

4. **反遗忘机制是生产级标志** — GenericAgent 的 `turn%10` 重注入和 `turn%65` 强制拦截，是三个项目中唯一认真对待"LLM 在长对话中遗忘"问题的。

5. **mochagent 的接口设计超前于实际需求** — Perceptor/Reasoner/Planner接口定义完善，但三个成熟项目证明"提示词工程 > 代码层认知模拟"。要么删掉这些接口，要么让它们做提示词工程做不到的事（如 GenericAgent 的 Plan SOP 验证流程）。
