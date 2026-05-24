import React, { useCallback, useEffect, useState, useRef } from 'react';
import {
  ReactFlow, Controls, Background, MiniMap, useNodesState, useEdgesState,
  addEdge, Connection, Node, Edge, BackgroundVariant,
  NodeProps, Handle, Position, MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  Button, Space, Input, message, Modal, Select, Slider, InputNumber,
  Typography, Divider, Alert, Tag,
} from 'antd';
import {
  SaveOutlined, SendOutlined,
  PlayCircleOutlined, ArrowLeftOutlined,
  BranchesOutlined, CodeOutlined, BookOutlined, FileTextOutlined,
  ThunderboltOutlined, PartitionOutlined, ApiOutlined,
  WarningOutlined, CheckCircleOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { workflowApi } from '@/api/workflow';
import { agentApi, Agent } from '@/api/agent';
import { modelApi, ModelConfig } from '@/api/model';
import { toolApi, Tool } from '@/api/tool';
import { knowledgeBaseApi, KnowledgeBase } from '@/api/knowledgeBase';
import { promptApi, Prompt } from '@/api/prompt';
import { validate } from '@/utils/validation';

const { Text } = Typography;

// ─── Design Token System ───

const T = {
  start:     { bg: '#f0fdf4', border: '#4ade80', text: '#14532d', icon: '#22c55e', accent: '#bbf7d0' },
  end:       { bg: '#fef2f2', border: '#f87171', text: '#7f1d1d', icon: '#ef4444', accent: '#fecaca' },
  agent:     { bg: '#eef2ff', border: '#818cf8', text: '#312e81', icon: '#6366f1', accent: '#c7d2fe' },
  llm:       { bg: '#eff6ff', border: '#60a5fa', text: '#1e3a5f', icon: '#3b82f6', accent: '#bfdbfe' },
  tool:      { bg: '#fff7ed', border: '#fb923c', text: '#7c2d12', icon: '#f97316', accent: '#fed7aa' },
  condition: { bg: '#fdf2f8', border: '#f472b6', text: '#831843', icon: '#ec4899', accent: '#fbcfe8' },
  parallel:  { bg: '#faf5ff', border: '#a78bfa', text: '#4c1d95', icon: '#8b5cf6', accent: '#ddd6fe' },
  code:      { bg: '#1e293b', border: '#475569', text: '#e2e8f0', icon: '#64748b', accent: '#334155' },
  knowledge: { bg: '#ecfeff', border: '#22d3ee', text: '#164e63', icon: '#06b6d4', accent: '#a5f3fc' },
  prompt:    { bg: '#f7fee7', border: '#a3e635', text: '#365314', icon: '#84cc16', accent: '#d9f99d' },
} as const;

const SHADOW = {
  sm: '0 1px 2px rgba(0,0,0,.04)',
  md: '0 4px 12px -2px rgba(0,0,0,.08), 0 2px 4px -2px rgba(0,0,0,.04)',
  lg: '0 12px 24px -6px rgba(0,0,0,.10), 0 4px 8px -4px rgba(0,0,0,.05)',
  glow: (color: string) => `0 0 0 3px ${color}40, 0 0 12px ${color}30`,
};

const NODE_LABELS: Record<string, string> = {
  start: '开始', end: '结束', agent: 'Agent', llm: 'LLM', tool: '工具',
  condition: '条件', parallel: '并行', code: '代码', knowledge: '知识检索', prompt: '提示词',
};

// ─── Shared card wrapper ───

const cardBase: React.CSSProperties = {
  borderRadius: 10,
  boxShadow: SHADOW.md,
  transition: 'box-shadow .2s ease, transform .15s ease',
  cursor: 'default',
  overflow: 'hidden',
};

// ─── Handle & Port helpers ───

const handleStyle = (color: string): React.CSSProperties => ({
  width: 10, height: 10,
  background: '#fff',
  border: `2.5px solid ${color}`,
  transition: 'transform .15s ease, box-shadow .15s ease',
});

const PortLabel: React.FC<{ text: string; side: 'in' | 'out'; color: string }> = ({ text, side, color }) => (
  <span style={{
    fontSize: 9, fontWeight: 600, color, letterSpacing: '.4px',
    position: 'absolute',
    ...(side === 'in' ? { top: -16, left: 10 } : { bottom: -16, left: 10 }),
  }}>{text}</span>
);

// ─── Type badge ───

const TypeBadge: React.FC<{ label: string; color: string; bg: string }> = ({ label, color, bg }) => (
  <div style={{
    position: 'absolute', top: 8, right: 10,
    fontSize: 8, fontWeight: 700, letterSpacing: '.6px',
    color, background: bg, borderRadius: 4,
    padding: '1px 6px', lineHeight: '16px',
  }}>{label}</div>
);

// ─── Custom Nodes ───

const StartNode: React.FC<NodeProps> = ({ data }) => (
  <div style={{
    width: 68, height: 68, borderRadius: '50%',
    background: `linear-gradient(135deg, ${T.start.icon}, #16a34a)`,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    color: '#fff', fontWeight: 700, fontSize: 13,
    boxShadow: `0 4px 16px ${T.start.icon}50`,
    letterSpacing: 1,
  }}>
    <Handle type="source" position={Position.Bottom}
      style={handleStyle(T.start.icon)} />
    {data.label as string}
  </div>
);

const EndNode: React.FC<NodeProps> = ({ data }) => (
  <div style={{
    width: 68, height: 68, borderRadius: '50%',
    background: `linear-gradient(135deg, ${T.end.icon}, #dc2626)`,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    color: '#fff', fontWeight: 700, fontSize: 13,
    boxShadow: `0 4px 16px ${T.end.icon}50`,
    letterSpacing: 1,
  }}>
    <Handle type="target" position={Position.Top}
      style={handleStyle(T.end.icon)} />
    {data.label as string}
  </div>
);

const AgentNode: React.FC<NodeProps> = ({ data }) => {
  const agentName = data.agentName ? String(data.agentName) : '';
  return (
  <div style={{ ...cardBase,
    background: `linear-gradient(180deg, #fafbff 0%, ${T.agent.bg} 100%)`,
    border: `1.5px solid ${T.agent.border}60`,
    borderTop: `3px solid ${T.agent.icon}`,
    minWidth: 168, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.agent.icon)} />
    <PortLabel text="输入" side="in" color={T.agent.icon} />
    <TypeBadge label="AGENT" color={T.agent.icon} bg={T.agent.accent} />
    <div style={{ padding: '12px 14px 10px', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{
        width: 34, height: 34, borderRadius: 9,
        background: `linear-gradient(135deg, ${T.agent.icon}18, ${T.agent.accent})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <ThunderboltOutlined style={{ color: T.agent.icon, fontSize: 17 }} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.agent.text, lineHeight: 1.3 }}>{String(data.label)}</div>
        {agentName && <div style={{ fontSize: 11, color: '#64748b', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{agentName}</div>}
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle(T.agent.icon)} />
    <PortLabel text="输出" side="out" color={T.agent.icon} />
  </div>
);
};

const LLMNode: React.FC<NodeProps> = ({ data }) => {
  const modelName = data.modelName ? String(data.modelName) : '';
  const tempStr = data.temperature != null ? `T=${data.temperature}` : '';
  return (
  <div style={{ ...cardBase,
    background: `linear-gradient(180deg, #fafcff 0%, ${T.llm.bg} 100%)`,
    border: `1.5px solid ${T.llm.border}60`,
    borderTop: `3px solid ${T.llm.icon}`,
    minWidth: 168, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.llm.icon)} />
    <PortLabel text="提示词" side="in" color={T.llm.icon} />
    <TypeBadge label="LLM" color={T.llm.icon} bg={T.llm.accent} />
    <div style={{ padding: '12px 14px 10px', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{
        width: 34, height: 34, borderRadius: 9,
        background: `linear-gradient(135deg, ${T.llm.icon}18, ${T.llm.accent})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <ApiOutlined style={{ color: T.llm.icon, fontSize: 17 }} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.llm.text, lineHeight: 1.3 }}>{String(data.label)}</div>
        <div style={{ fontSize: 10, color: '#64748b', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {modelName || '未选择'}{tempStr && <span style={{ color: T.llm.icon, marginLeft: 4 }}>{tempStr}</span>}
        </div>
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle(T.llm.icon)} />
    <PortLabel text="回复" side="out" color={T.llm.icon} />
  </div>
);
};

const ToolNode: React.FC<NodeProps> = ({ data }) => {
  const toolName = data.toolName ? String(data.toolName) : '';
  return (
  <div style={{ ...cardBase,
    background: `linear-gradient(180deg, #fffcf8 0%, ${T.tool.bg} 100%)`,
    border: `1.5px solid ${T.tool.border}60`,
    borderTop: `3px solid ${T.tool.icon}`,
    minWidth: 156, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.tool.icon)} />
    <PortLabel text="参数" side="in" color={T.tool.icon} />
    <TypeBadge label="TOOL" color={T.tool.icon} bg={T.tool.accent} />
    <div style={{ padding: '11px 14px 9px', display: 'flex', alignItems: 'center', gap: 9 }}>
      <div style={{
        width: 30, height: 30, borderRadius: 7,
        background: `linear-gradient(135deg, ${T.tool.icon}18, ${T.tool.accent})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <PartitionOutlined style={{ color: T.tool.icon, fontSize: 15 }} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.tool.text }}>{String(data.label)}</div>
        {toolName && <div style={{ fontSize: 11, color: '#64748b', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{toolName}</div>}
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle(T.tool.icon)} />
    <PortLabel text="结果" side="out" color={T.tool.icon} />
  </div>
);
};

const ConditionNode: React.FC<NodeProps> = ({ data }) => {
  const expr = data.conditionExpr ? String(data.conditionExpr) : '';
  return (
  <div style={{
    ...cardBase, minWidth: 130, position: 'relative',
    background: `linear-gradient(180deg, #fefafc 0%, ${T.condition.bg} 100%)`,
    border: `1.5px solid ${T.condition.border}60`,
    borderTop: `3px solid ${T.condition.icon}`,
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.condition.icon)} />
    <TypeBadge label="IF" color={T.condition.icon} bg={T.condition.accent} />
    <div style={{ padding: '12px 16px', textAlign: 'center' }}>
      <div style={{ fontWeight: 700, fontSize: 13, color: T.condition.text, marginBottom: expr ? 4 : 0 }}>{String(data.label)}</div>
      {expr && <div style={{ fontSize: 10, color: T.condition.icon, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontFamily: 'monospace', background: T.condition.accent, borderRadius: 4, padding: '2px 6px', display: 'inline-block' }}>{expr}</div>}
    </div>
    <Handle type="source" position={Position.Bottom} id="true" style={{ ...handleStyle(T.start.icon), left: '30%' }} />
    <Handle type="source" position={Position.Bottom} id="false" style={{ ...handleStyle(T.end.icon), left: '70%' }} />
    <div style={{ position: 'absolute', bottom: -17, left: '30%', fontSize: 9, fontWeight: 700, color: T.start.icon, transform: 'translateX(-50%)' }}>T</div>
    <div style={{ position: 'absolute', bottom: -17, left: '70%', fontSize: 9, fontWeight: 700, color: T.end.icon, transform: 'translateX(-50%)' }}>F</div>
  </div>
);
};

const ParallelNode: React.FC<NodeProps> = ({ data }) => (
  <div style={{
    ...cardBase, minWidth: 150, position: 'relative',
    background: `linear-gradient(180deg, #fdfbff 0%, ${T.parallel.bg} 100%)`,
    border: `1.5px solid ${T.parallel.border}60`,
    borderTop: `3px solid ${T.parallel.icon}`,
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.parallel.icon)} />
    <TypeBadge label="FORK" color={T.parallel.icon} bg={T.parallel.accent} />
    <div style={{ padding: '12px 16px 8px', textAlign: 'center' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center', marginBottom: 4 }}>
        <BranchesOutlined style={{ color: T.parallel.icon, fontSize: 14 }} />
        <div style={{ fontWeight: 700, fontSize: 13, color: T.parallel.text }}>{data.label as string}</div>
      </div>
      <div style={{ fontSize: 10, color: T.parallel.icon, opacity: .85, background: T.parallel.accent, borderRadius: 4, padding: '1px 8px', display: 'inline-block' }}>
        {data.parallelStrategy === 'any' ? '任一完成' : '全部完成'}
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} id="branch-0" style={{ ...handleStyle(T.parallel.icon), left: '20%' }} />
    <Handle type="source" position={Position.Bottom} id="branch-1" style={{ ...handleStyle(T.parallel.icon), left: '50%' }} />
    <Handle type="source" position={Position.Bottom} id="branch-2" style={{ ...handleStyle(T.parallel.icon), left: '80%' }} />
  </div>
);

const CodeNode: React.FC<NodeProps> = ({ data }) => (
  <div style={{
    ...cardBase,
    background: `linear-gradient(180deg, #1e293b 0%, ${T.code.bg} 100%)`,
    border: `1.5px solid ${T.code.border}`,
    borderTop: `3px solid #22c55e`,
    minWidth: 156, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle('#64748b')} />
    <PortLabel text="入参" side="in" color="#64748b" />
    <TypeBadge label="CODE" color="#22c55e" bg="#334155" />
    <div style={{ padding: '11px 14px 9px', display: 'flex', alignItems: 'center', gap: 9 }}>
      <div style={{
        width: 28, height: 28, borderRadius: 6,
        background: 'rgba(34,197,94,.12)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <CodeOutlined style={{ color: '#22c55e', fontSize: 15 }} />
      </div>
      <div>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.code.text }}>{data.label as string}</div>
        <div style={{ fontSize: 10, color: '#94a3b8', fontFamily: 'monospace', marginTop: 2 }}>{(data.language as string) || 'python'}</div>
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle('#64748b')} />
    <PortLabel text="返回" side="out" color="#64748b" />
  </div>
);

const KnowledgeNode: React.FC<NodeProps> = ({ data }) => {
  const kbName = data.kbName ? String(data.kbName) : '';
  const topK = data.topK != null ? String(data.topK) : '5';
  return (
  <div style={{ ...cardBase,
    background: `linear-gradient(180deg, #fafeff 0%, ${T.knowledge.bg} 100%)`,
    border: `1.5px solid ${T.knowledge.border}60`,
    borderTop: `3px solid ${T.knowledge.icon}`,
    minWidth: 168, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.knowledge.icon)} />
    <PortLabel text="查询" side="in" color={T.knowledge.icon} />
    <TypeBadge label="RAG" color={T.knowledge.icon} bg={T.knowledge.accent} />
    <div style={{ padding: '12px 14px 10px', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{
        width: 34, height: 34, borderRadius: 9,
        background: `linear-gradient(135deg, ${T.knowledge.icon}18, ${T.knowledge.accent})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <BookOutlined style={{ color: T.knowledge.icon, fontSize: 16 }} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.knowledge.text, lineHeight: 1.3 }}>{String(data.label)}</div>
        {kbName && <div style={{ fontSize: 11, color: '#64748b', marginTop: 1 }}>{kbName}<span style={{ color: T.knowledge.icon, marginLeft: 3 }}>topK={topK}</span></div>}
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle(T.knowledge.icon)} />
    <PortLabel text="文档" side="out" color={T.knowledge.icon} />
  </div>
);
};

const PromptNode: React.FC<NodeProps> = ({ data }) => {
  const promptName = data.promptName ? String(data.promptName) : '';
  return (
  <div style={{ ...cardBase,
    background: `linear-gradient(180deg, #fdfff8 0%, ${T.prompt.bg} 100%)`,
    border: `1.5px dashed ${T.prompt.border}80`,
    borderTop: `3px solid ${T.prompt.icon}`,
    minWidth: 168, position: 'relative',
  }}>
    <Handle type="target" position={Position.Top} style={handleStyle(T.prompt.icon)} />
    <PortLabel text="变量" side="in" color={T.prompt.icon} />
    <TypeBadge label="PROMPT" color={T.prompt.icon} bg={T.prompt.accent} />
    <div style={{ padding: '12px 14px 10px', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{
        width: 34, height: 34, borderRadius: 9,
        background: `linear-gradient(135deg, ${T.prompt.icon}18, ${T.prompt.accent})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <FileTextOutlined style={{ color: T.prompt.icon, fontSize: 16 }} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, color: T.prompt.text, lineHeight: 1.3 }}>{String(data.label)}</div>
        {promptName && <div style={{ fontSize: 11, color: '#64748b', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{promptName}</div>}
      </div>
    </div>
    <Handle type="source" position={Position.Bottom} style={handleStyle(T.prompt.icon)} />
    <PortLabel text="渲染" side="out" color={T.prompt.icon} />
  </div>
);
};

const nodeTypes = {
  start: StartNode, end: EndNode, agent: AgentNode, llm: LLMNode,
  tool: ToolNode, condition: ConditionNode, parallel: ParallelNode,
  code: CodeNode, knowledge: KnowledgeNode, prompt: PromptNode,
};

// ─── Palette Groups ───

interface PaletteItem { type: string; label: string; color: string; group: string; }

const paletteGroups: { name: string; items: PaletteItem[] }[] = [
  {
    name: '流程控制',
    items: [
      { type: 'start', label: '开始', color: T.start.icon, group: '流程控制' },
      { type: 'end', label: '结束', color: T.end.icon, group: '流程控制' },
      { type: 'condition', label: '条件', color: T.condition.icon, group: '流程控制' },
      { type: 'parallel', label: '并行', color: T.parallel.icon, group: '流程控制' },
    ],
  },
  {
    name: 'AI 能力',
    items: [
      { type: 'agent', label: 'Agent', color: T.agent.icon, group: 'AI 能力' },
      { type: 'llm', label: 'LLM', color: T.llm.icon, group: 'AI 能力' },
      { type: 'prompt', label: '提示词', color: T.prompt.icon, group: 'AI 能力' },
    ],
  },
  {
    name: '数据处理',
    items: [
      { type: 'tool', label: '工具', color: T.tool.icon, group: '数据处理' },
      { type: 'knowledge', label: '知识检索', color: T.knowledge.icon, group: '数据处理' },
      { type: 'code', label: '代码', color: T.code.icon, group: '数据处理' },
    ],
  },
];

// ─── Validation ───

interface ValidationError { nodeId?: string; message: string; }

function validateDAG(nodes: Node[], edges: Edge[]): ValidationError[] {
  const errors: ValidationError[] = [];
  const nodeMap = new Map(nodes.map((n) => [n.id, n]));

  const startNodes = nodes.filter((n) => n.type === 'start');
  const endNodes = nodes.filter((n) => n.type === 'end');

  if (startNodes.length === 0) errors.push({ message: '缺少开始节点' });
  if (startNodes.length > 1) errors.push({ message: '只能有一个开始节点' });
  if (endNodes.length === 0) errors.push({ message: '缺少结束节点' });
  if (endNodes.length > 1) errors.push({ message: '只能有一个结束节点' });

  // start has no incoming edges
  for (const s of startNodes) {
    if (edges.some((e) => e.target === s.id)) errors.push({ nodeId: s.id, message: '开始节点不应有入边' });
  }
  for (const e of endNodes) {
    if (edges.some((ed) => ed.source === e.id)) errors.push({ nodeId: e.id, message: '结束节点不应有出边' });
  }

  // Check for isolated nodes
  for (const n of nodes) {
    if (n.type === 'start') continue;
    const hasIncoming = edges.some((e) => e.target === n.id);
    const hasOutgoing = edges.some((e) => e.source === n.id);
    if (!hasIncoming && !hasOutgoing) {
      errors.push({ nodeId: n.id, message: `节点 "${n.data.label}" 是孤立节点` });
    }
  }

  // Check resource nodes have selected resources
  for (const n of nodes) {
    if (n.type === 'agent' && !n.data.agentId) {
      errors.push({ nodeId: n.id, message: `Agent 节点 "${n.data.label}" 未选择 Agent` });
    }
    if (n.type === 'llm' && !n.data.modelId) {
      errors.push({ nodeId: n.id, message: `LLM 节点 "${n.data.label}" 未选择模型` });
    }
    if (n.type === 'tool' && !n.data.toolId) {
      errors.push({ nodeId: n.id, message: `工具节点 "${n.data.label}" 未选择工具` });
    }
  }

  // Cycle detection via DFS
  const visited = new Set<string>();
  const stack = new Set<string>();
  function hasCycle(nodeId: string): boolean {
    if (stack.has(nodeId)) return true;
    if (visited.has(nodeId)) return false;
    visited.add(nodeId);
    stack.add(nodeId);
    const outgoing = edges.filter((e) => e.source === nodeId);
    for (const e of outgoing) {
      if (hasCycle(e.target)) return true;
    }
    stack.delete(nodeId);
    return false;
  }
  for (const n of nodes) {
    if (hasCycle(n.id)) {
      errors.push({ message: '工作流包含循环依赖' });
      break;
    }
  }

  return errors;
}

// ─── Typed Port Schemas (analogous to LangChain's typed IO) ───

interface PortDef { name: string; type: string; description?: string; }

const NODE_PORTS: Record<string, { inputs: PortDef[]; outputs: PortDef[] }> = {
  start:    { inputs: [],                       outputs: [{ name: 'output', type: 'object' }] },
  end:      { inputs: [{ name: 'input', type: 'any' }], outputs: [] },
  agent:    { inputs: [{ name: 'input', type: 'object' }], outputs: [{ name: 'output', type: 'object' }, { name: 'agentResponse', type: 'string' }] },
  llm:      { inputs: [{ name: 'prompt', type: 'string' }], outputs: [{ name: 'output', type: 'string' }, { name: 'tokens', type: 'number' }] },
  tool:     { inputs: [{ name: 'params', type: 'object' }], outputs: [{ name: 'output', type: 'object' }] },
  condition:{ inputs: [{ name: 'input', type: 'any' }],  outputs: [{ name: 'true', type: 'any' }, { name: 'false', type: 'any' }] },
  parallel: { inputs: [{ name: 'input', type: 'any' }],  outputs: [{ name: 'merged', type: 'object' }] },
  code:     { inputs: [{ name: 'input', type: 'any' }],  outputs: [{ name: 'output', type: 'any' }] },
  knowledge:{ inputs: [{ name: 'query', type: 'string' }], outputs: [{ name: 'documents', type: 'object' }] },
  prompt:   { inputs: [{ name: 'variables', type: 'object' }], outputs: [{ name: 'rendered', type: 'string' }] },
};

// ─── Execution Status Types ───

interface ExecNodeStatus {
  status: 'running' | 'success' | 'error' | 'skipped';
  output?: unknown;
  error?: string;
  durationMs?: number;
}

// ─── Main Component ───

const WorkflowEditor: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = id === 'new' || !id;
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<Edge | null>(null);
  const [saving, setSaving] = useState(false);
  const [workflowId, setWorkflowId] = useState<string | null>(null);
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const reactFlowWrapper = useRef<HTMLDivElement>(null);

  // Execution state
  const [executing, setExecuting] = useState(false);
  const [execNodeStatuses, setExecNodeStatuses] = useState<Record<string, ExecNodeStatus>>({});
  const [execProgress, setExecProgress] = useState({ total: 0, completed: 0 });
  const [execResult, setExecResult] = useState<Record<string, unknown> | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Resource lists
  const [agents, setAgents] = useState<Agent[]>([]);
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [tools, setTools] = useState<Tool[]>([]);
  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  // Load resources
  useEffect(() => {
    agentApi.list({ size: 200 }).then((res) => setAgents(res.data.list || []));
    modelApi.list({ size: 200 }).then((res) => setModels(res.data.list || []));
    toolApi.list({ size: 200 }).then((res) => setTools(res.data.list || []));
    promptApi.list({ size: 200 }).then((res) => setPrompts(res.data.list || []));
  }, []);

  // Load existing workflow
  useEffect(() => {
    if (!isNew) {
      workflowApi.getById(id!).then((res) => {
        const wf = res.data;
        setName(wf.name);
        setDescription(wf.description || '');
        setWorkflowId(wf.id);
        if (wf.definition) {
          try {
            const def = JSON.parse(wf.definition);
            if (def.nodes) setNodes(def.nodes);
            if (def.edges) setEdges(def.edges);
          } catch { /* ignore */ }
        }
      });
    }
  }, [id]);

  // Load KBs for selected digital human (simplified: load all)
  useEffect(() => {
    // Try to infer digitalHumanId from existing agent nodes, or load all
    knowledgeBaseApi.list({ digitalHumanId: '', page: 1, size: 200 }).then((res) => {
      // If no digitalHumanId, try listAll — fallback to empty
    }).catch(() => {});
    // Use listAll for now
    const agentWithDH = agents.find((a) => a.digitalHumanId);
    if (agentWithDH?.digitalHumanId) {
      knowledgeBaseApi.listAll(agentWithDH.digitalHumanId).then((res) =>
        setKnowledgeBases(res.data || [])
      );
    }
  }, [agents]);

  // ─── Callbacks ───

  const onConnect = useCallback(
    (connection: Connection) => {
      const sourceNode = nodes.find((n) => n.id === connection.source);
      const edgeColor = sourceNode?.type && (T as Record<string, {icon: string}>)[sourceNode.type]
        ? (T as Record<string, {icon: string}>)[sourceNode.type].icon + '99'
        : '#94a3b8';
      setEdges((eds) => addEdge({
        ...connection,
        type: 'smoothstep',
        animated: true,
        markerEnd: { type: MarkerType.ArrowClosed, width: 20, height: 20, color: edgeColor },
        style: { stroke: edgeColor, strokeWidth: 2.5, strokeLinecap: 'round' },
      }, eds));
    },
    [setEdges, nodes],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
    setSelectedEdge(null);
  }, []);

  const onEdgeClick = useCallback((_: React.MouseEvent, edge: Edge) => {
    setSelectedEdge(edge);
    setSelectedNode(null);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
    setSelectedEdge(null);
  }, []);

  // Drag and drop
  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const type = event.dataTransfer.getData('application/reactflow-type');
      const label = event.dataTransfer.getData('application/reactflow-label');
      if (!type) return;
      const reactFlowBounds = reactFlowWrapper.current?.getBoundingClientRect();
      const position = reactFlowBounds
        ? { x: event.clientX - reactFlowBounds.left - 75, y: event.clientY - reactFlowBounds.top - 25 }
        : { x: event.clientX - 280, y: event.clientY - 120 };

      const newNode: Node = {
        id: `${type}-${Date.now()}`,
        type,
        position,
        data: { label: label || NODE_LABELS[type] || type, nodeType: type },
      };
      setNodes((nds) => [...nds, newNode]);
    },
    [setNodes],
  );

  const onDragStart = (event: React.DragEvent, type: string, label: string) => {
    event.dataTransfer.setData('application/reactflow-type', type);
    event.dataTransfer.setData('application/reactflow-label', label);
    event.dataTransfer.effectAllowed = 'move';
  };

  // Update node data
  const updateNodeData = (nodeId: string, data: Record<string, unknown>) => {
    setNodes((nds) => nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n)));
    setSelectedNode((prev) => prev && prev.id === nodeId ? { ...prev, data: { ...prev.data, ...data } } : prev);
  };

  // Update edge data
  const updateEdgeData = (edgeId: string, data: Record<string, unknown>) => {
    setEdges((eds) => eds.map((e) => (e.id === edgeId ? { ...e, ...data } : e)));
    setSelectedEdge((prev) => prev && prev.id === edgeId ? { ...prev, ...data } : prev);
  };

  // ─── Save / Publish ───

  const getDefinition = () => JSON.stringify({ nodes, edges });

  const handleValidate = () => {
    const errs = validateDAG(nodes, edges);
    setValidationErrors(errs);
    if (errs.length > 0) {
      message.warning(`验证发现 ${errs.length} 个问题`);
    } else {
      message.success('验证通过');
    }
  };

  const handleSave = async () => {
    if (!validate.notEmpty(name)) { message.warning('请输入工作流名称'); return; }
    const errs = validateDAG(nodes, edges);
    if (errs.length > 0) {
      setValidationErrors(errs);
      Modal.confirm({
        title: '验证未通过',
        content: `发现 ${errs.length} 个问题，是否仍然保存？`,
        okText: '仍然保存',
        cancelText: '取消',
        onOk: () => doSave(),
      });
      return;
    }
    await doSave();
  };

  const doSave = async () => {
    setSaving(true);
    const def = getDefinition();
    try {
      if (isNew) {
        const res = await workflowApi.create({ name, description, definition: def });
        setWorkflowId(res.data.id);
        message.success('创建成功');
        navigate(`/workflows/${res.data.id}/edit.html`, { replace: true });
      } else {
        await workflowApi.update(workflowId!, { name, description, definition: def });
        message.success('保存成功');
      }
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!workflowId) { message.warning('请先保存工作流'); return; }
    const errs = validateDAG(nodes, edges);
    if (errs.length > 0) { message.error('请先修复验证问题再发布'); setValidationErrors(errs); return; }
    await doSave();
    await workflowApi.publish(workflowId!);
    message.success('已发布');
  };

  // ─── Streaming Execution ───

  const handleExecuteStream = async () => {
    if (!workflowId) { message.warning('请先保存工作流'); return; }
    const errs = validateDAG(nodes, edges);
    if (errs.length > 0) { setValidationErrors(errs); message.error('请先修复验证问题'); return; }

    setExecuting(true);
    setExecNodeStatuses({});
    setExecProgress({ total: 0, completed: 0 });
    setExecResult(null);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const token = localStorage.getItem('token');
      const response = await fetch(`/api/workflows/${workflowId}/execute-stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: token ? `Bearer ${token}` : '',
        },
        body: JSON.stringify({ input: '' }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const err = await response.text();
        throw new Error(err || `HTTP ${response.status}`);
      }

      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEventType = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed === '') {
            currentEventType = ''; // blank line resets SSE block
            continue;
          }
          if (trimmed.startsWith('event:')) {
            currentEventType = trimmed.slice(6).trim();
            continue;
          }
          if (trimmed.startsWith('data:')) {
            try {
              const payload = JSON.parse(trimmed.slice(5).trim());
              if (currentEventType === 'workflow-result') {
                setExecResult(payload);
              } else {
                applySSEEvent(currentEventType || payload.eventType || 'unknown', payload);
              }
            } catch { /* malformed JSON — skip */ }
            currentEventType = '';
          }
        }
      }

      message.success('工作流执行完成');
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        message.info('执行已取消');
      } else {
        message.error(`执行失败: ${err instanceof Error ? err.message : String(err)}`);
      }
    } finally {
      setExecuting(false);
      abortRef.current = null;
    }
  };

  const applySSEEvent = (eventType: string, event: Record<string, unknown>) => {
    const nodeId = event.nodeId as string;

    switch (eventType) {
      case 'node-start':
        setExecProgress({ total: event.totalNodes as number, completed: event.completedNodes as number });
        setExecNodeStatuses((prev) => ({
          ...prev,
          [nodeId]: { status: 'running' },
        }));
        break;
      case 'node-complete':
        setExecNodeStatuses((prev) => ({
          ...prev,
          [nodeId]: { status: 'success', output: event.output, durationMs: event.durationMs as number },
        }));
        break;
      case 'node-error':
        setExecNodeStatuses((prev) => ({
          ...prev,
          [nodeId]: { status: 'error', error: event.error as string },
        }));
        break;
      case 'workflow-complete':
        setExecProgress((p) => ({ ...p, completed: p.total }));
        break;
    }
  };

  const handleCancelExecution = () => {
    abortRef.current?.abort();
  };

  // ─── Render Properties Panel ───

  // Shared form primitives
  const inputStyle: React.CSSProperties = { borderRadius: 6, border: '1px solid #e2e8f0' };
  const SectionCard: React.FC<{ title: string; color?: string; children: React.ReactNode }> = ({ title, color, children }) => (
    <div style={{
      background: '#fff', borderRadius: 8, border: '1px solid #e8ecf0',
      padding: '10px 12px', marginBottom: 10,
      borderLeft: `3px solid ${color || '#e2e8f0'}`,
    }}>
      <div style={{ fontWeight: 600, fontSize: 11, color: '#475569', marginBottom: 8, letterSpacing: '.3px', textTransform: 'uppercase' }}>{title}</div>
      {children}
    </div>
  );
  const FieldLabel: React.FC<{ text: string; hint?: string }> = ({ text, hint }) => (
    <div style={{ marginBottom: 4 }}>
      <span style={{ fontSize: 12, fontWeight: 500, color: '#334155' }}>{text}</span>
      {hint && <span style={{ fontSize: 10, color: '#94a3b8', marginLeft: 6 }}>{hint}</span>}
    </div>
  );

  const renderNodeConfig = (node: Node) => {
    const d = node.data;
    const typeColor = (T as Record<string, {icon: string}>)[node.type || '']?.icon || '#94a3b8';

    switch (node.type) {
      case 'agent':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="绑定资源" color={typeColor}>
              <FieldLabel text="选择 Agent" hint="选择要绑定的智能体实例" />
              <Select size="small" showSearch optionFilterProp="label" value={d.agentId as string} onChange={(v) => {
                const a = agents.find((ag) => ag.id === v);
                updateNodeData(node.id, { agentId: v, agentName: a?.name, label: a?.name || d.label });
              }} placeholder="选择智能体" options={agents.map((a) => ({ label: a.name, value: a.id }))}
                style={{ width: '100%', borderRadius: 6 }} />
            </SectionCard>
            <SectionCard title="高级配置" color={typeColor}>
              <FieldLabel text="输入映射" hint="JSON 格式" />
              <Input.TextArea size="small" rows={3} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace' }} placeholder='{"input": "{{prev.output}}"}' value={(d.inputMapping as string) || ''} onChange={(e) => updateNodeData(node.id, { inputMapping: e.target.value })} />
            </SectionCard>
          </div>
        );

      case 'llm':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="模型配置" color={typeColor}>
              <FieldLabel text="选择模型" hint="选择 LLM 模型及提供商" />
              <Select size="small" showSearch optionFilterProp="label" value={d.modelId as string} onChange={(v) => {
                const m = models.find((md) => md.id === v);
                updateNodeData(node.id, { modelId: v, modelName: m?.name, label: m?.name || d.label });
              }} placeholder="选择模型" options={models.map((m) => ({ label: `${m.name} (${m.provider})`, value: m.id }))}
                style={{ width: '100%', borderRadius: 6 }} />
            </SectionCard>
            <SectionCard title="生成参数" color={typeColor}>
              <FieldLabel text={`Temperature · ${d.temperature != null ? d.temperature : 0.7}`} />
              <Slider min={0} max={2} step={0.1} value={d.temperature as number || 0.7} onChange={(v) => updateNodeData(node.id, { temperature: v })} />
              <div style={{ height: 8 }} />
              <FieldLabel text="Max Tokens" />
              <InputNumber size="small" min={1} max={128000} value={d.maxTokens as number || 4096} onChange={(v) => updateNodeData(node.id, { maxTokens: v })}
                style={{ ...inputStyle, width: '100%' }} />
              <div style={{ height: 8 }} />
              <FieldLabel text="System Prompt" hint="系统级提示词" />
              <Input.TextArea size="small" rows={3} style={{ ...inputStyle, fontSize: 11 }} value={(d.systemPrompt as string) || ''} onChange={(e) => updateNodeData(node.id, { systemPrompt: e.target.value })} placeholder="系统提示词..." />
            </SectionCard>
          </div>
        );

      case 'tool':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="工具绑定" color={typeColor}>
              <FieldLabel text="选择工具" hint="选择要调用的工具" />
              <Select size="small" showSearch optionFilterProp="label" value={d.toolId as string} onChange={(v) => {
                const t = tools.find((tk) => tk.id === v);
                updateNodeData(node.id, { toolId: v, toolName: t?.name, label: t?.name || d.label });
              }} placeholder="选择工具" options={tools.map((t) => ({ label: `${t.name} (${t.category || '通用'})`, value: t.id }))}
                style={{ width: '100%', borderRadius: 6 }} />
            </SectionCard>
            <SectionCard title="调用参数" color={typeColor}>
              <FieldLabel text="参数" hint="JSON 格式" />
              <Input.TextArea size="small" rows={3} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace' }} placeholder='{"param1": "{{prev.output}}"}' value={(d.params as string) || ''} onChange={(e) => updateNodeData(node.id, { params: e.target.value })} />
            </SectionCard>
          </div>
        );

      case 'condition':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="分支条件" color={typeColor}>
              <FieldLabel text="条件表达式" hint="JavaScript 表达式" />
              <Input.TextArea size="small" rows={2} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace' }} placeholder="output.status == 'success'" value={(d.conditionExpr as string) || ''} onChange={(e) => updateNodeData(node.id, { conditionExpr: e.target.value })} />
              <div style={{ fontSize: 10, color: '#94a3b8', marginTop: 6, padding: '4px 8px', background: '#f8fafc', borderRadius: 4 }}>
                True 分支 → 左侧输出 &nbsp;|&nbsp; False 分支 → 右侧输出
              </div>
            </SectionCard>
          </div>
        );

      case 'parallel':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="并行策略" color={typeColor}>
              <FieldLabel text="完成策略" />
              <Select size="small" value={(d.parallelStrategy as string) || 'all'} onChange={(v) => updateNodeData(node.id, { parallelStrategy: v })}
                options={[
                  { label: '全部完成 (all)', value: 'all' },
                  { label: '任一完成 (any)', value: 'any' },
                ]} style={{ width: '100%', borderRadius: 6 }} />
              <div style={{ height: 8 }} />
              <FieldLabel text="最大并发数" />
              <InputNumber size="small" min={1} max={10} value={(d.maxConcurrency as number) || 3} onChange={(v) => updateNodeData(node.id, { maxConcurrency: v })}
                style={{ ...inputStyle, width: '100%' }} />
            </SectionCard>
          </div>
        );

      case 'code':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="运行环境" color={typeColor}>
              <FieldLabel text="语言" />
              <Select size="small" value={(d.language as string) || 'python'} onChange={(v) => updateNodeData(node.id, { language: v })}
                options={[
                  { label: 'Python', value: 'python' },
                  { label: 'JavaScript', value: 'javascript' },
                ]} style={{ width: '100%', borderRadius: 6 }} />
              <div style={{ height: 8 }} />
              <FieldLabel text="超时" hint="秒" />
              <InputNumber size="small" min={1} max={300} value={(d.timeoutSecs as number) || 30} onChange={(v) => updateNodeData(node.id, { timeoutSecs: v })}
                style={{ ...inputStyle, width: '100%' }} />
            </SectionCard>
            <SectionCard title="代码" color="#22c55e">
              <Input.TextArea size="small" rows={5} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace', background: '#1e293b', color: '#e2e8f0' }} value={(d.code as string) || ''} onChange={(e) => updateNodeData(node.id, { code: e.target.value })} placeholder="# 输入变量: input&#10;result = input.upper()&#10;output = result" />
            </SectionCard>
          </div>
        );

      case 'knowledge':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="知识库配置" color={typeColor}>
              <FieldLabel text="选择知识库" hint="选择检索目标" />
              <Select size="small" showSearch optionFilterProp="label" value={d.knowledgeBaseId as string} onChange={(v) => {
                const kb = knowledgeBases.find((k) => k.id === v);
                updateNodeData(node.id, { knowledgeBaseId: v, kbName: kb?.name });
              }} placeholder="选择知识库" options={knowledgeBases.map((k) => ({ label: `${k.name} (${k.documentCount}文档)`, value: k.id }))}
                style={{ width: '100%', borderRadius: 6 }} />
            </SectionCard>
            <SectionCard title="检索参数" color={typeColor}>
              <FieldLabel text="检索数量" hint="topK" />
              <InputNumber size="small" min={1} max={20} value={(d.topK as number) || 5} onChange={(v) => updateNodeData(node.id, { topK: v })}
                style={{ ...inputStyle, width: '100%' }} />
              <div style={{ height: 8 }} />
              <FieldLabel text={`相似度阈值 · ${(d.scoreThreshold as number) || 0.7}`} />
              <Slider min={0} max={1} step={0.05} value={(d.scoreThreshold as number) || 0.7} onChange={(v) => updateNodeData(node.id, { scoreThreshold: v })} />
            </SectionCard>
          </div>
        );

      case 'prompt':
        return (
          <div>
            <SectionCard title="基本属性" color={typeColor}>
              <FieldLabel text="标签" />
              <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
            </SectionCard>
            <SectionCard title="模板配置" color={typeColor}>
              <FieldLabel text="选择提示词模板" />
              <Select size="small" showSearch optionFilterProp="label" value={d.promptId as string} onChange={(v) => {
                const p = prompts.find((pr) => pr.id === v);
                updateNodeData(node.id, { promptId: v, promptName: p?.name, label: p?.name || d.label });
              }} placeholder="选择提示词模板" options={prompts.map((p) => ({ label: p.name, value: p.id }))}
                style={{ width: '100%', borderRadius: 6 }} />
            </SectionCard>
            <SectionCard title="变量绑定" color={typeColor}>
              <FieldLabel text="变量绑定" hint="JSON 格式" />
              <Input.TextArea size="small" rows={3} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace' }} placeholder='{"topic": "{{prev.output}}"}' value={(d.variableBindings as string) || ''} onChange={(e) => updateNodeData(node.id, { variableBindings: e.target.value })} />
            </SectionCard>
          </div>
        );

      default:
        return (
          <SectionCard title="基本属性" color={typeColor}>
            <FieldLabel text="标签" />
            <Input size="small" style={inputStyle} value={d.label as string} onChange={(e) => updateNodeData(node.id, { label: e.target.value })} />
          </SectionCard>
        );
    }
  };

  const renderEdgeConfig = (edge: Edge) => {
    const sourceNode = nodes.find((n) => n.id === edge.source);
    const isConditionEdge = sourceNode?.type === 'condition';
    const srcLabel = String(sourceNode?.data?.label || sourceNode?.id || '');
    const tgtLabel = String(nodes.find((n) => n.id === edge.target)?.data?.label || edge.target || '');
    return (
      <div>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 6, marginBottom: 14,
          padding: '6px 10px', background: '#f1f5f9', borderRadius: 6,
          fontSize: 11, color: '#475569', fontWeight: 500,
        }}>
          <span style={{ color: '#64748b' }}>{srcLabel}</span>
          <span style={{ color: '#94a3b8' }}>→</span>
          <span style={{ color: '#64748b' }}>{tgtLabel}</span>
        </div>
        <SectionCard title="基本配置" color="#94a3b8">
          {isConditionEdge && (
            <>
              <FieldLabel text="分支条件" hint="JavaScript 表达式" />
              <Input size="small" style={inputStyle} placeholder="e.g. output.score > 0.8" value={(edge.data?.condition as string) || ''} onChange={(e) => updateEdgeData(edge.id, { data: { ...edge.data, condition: e.target.value } })} />
              <div style={{ height: 8 }} />
            </>
          )}
          <FieldLabel text="显示标签" />
          <Input size="small" style={inputStyle} value={(edge.label as string) || ''} onChange={(e) => updateEdgeData(edge.id, { label: e.target.value })} />
        </SectionCard>
        <SectionCard title="数据映射" color="#94a3b8">
          <FieldLabel text="映射规则" hint="JSON 格式" />
          <Input.TextArea size="small" rows={3} style={{ ...inputStyle, fontSize: 11, fontFamily: 'monospace' }} placeholder='{"key": "{{source.output}}"}' value={(edge.data?.dataMapping as string) || ''} onChange={(e) => updateEdgeData(edge.id, { data: { ...edge.data, dataMapping: e.target.value } })} />
        </SectionCard>
        <Button size="small" danger block style={{ borderRadius: 7, height: 30, fontWeight: 500, marginTop: 4 }}
          onClick={() => { setEdges((eds) => eds.filter((e) => e.id !== edge.id)); setSelectedEdge(null); }}>
          删除连线
        </Button>
      </div>
    );
  };

  // ─── Augment nodes with execution status ───

  const displayNodes = executing
    ? nodes.map((n) => {
        const s = execNodeStatuses[n.id];
        if (!s) return n;
        const cls: string[] = [];
        if (s.status === 'running') cls.push('node-executing');
        else if (s.status === 'success') cls.push('node-success');
        else if (s.status === 'error') cls.push('node-error');
        return { ...n, className: cls.join(' '), data: { ...n.data, execStatus: s.status, execError: s.error } };
      })
    : nodes;

  // ─── Render ───

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* Animations & hover effects */}
      <style>{`
        @keyframes nodePulse {
          0%, 100% { box-shadow: 0 0 0 3px ${T.agent.icon}40, 0 0 16px ${T.agent.icon}30; }
          50% { box-shadow: 0 0 0 6px ${T.agent.icon}20, 0 0 24px ${T.agent.icon}50; }
        }
        @keyframes nodeSuccess {
          0% { box-shadow: 0 0 0 2px ${T.start.icon}60; }
          50% { box-shadow: 0 0 0 6px ${T.start.icon}20; }
          100% { box-shadow: 0 0 0 2px ${T.start.icon}40; }
        }
        @keyframes nodeError {
          0%, 100% { box-shadow: 0 0 0 2px rgba(239,68,68,.6); }
          50% { box-shadow: 0 0 0 6px rgba(239,68,68,.2); }
        }
        @keyframes edgeFlow {
          from { stroke-dashoffset: 24; }
          to { stroke-dashoffset: 0; }
        }
        
        /* Node hover — subtle lift */
        .react-flow__node > div {
          transition: transform .2s cubic-bezier(.34,1.56,.64,1), box-shadow .2s ease !important;
        }
        .react-flow__node:hover > div {
          transform: translateY(-2px) scale(1.03);
          box-shadow: 0 8px 25px -4px rgba(0,0,0,.12), 0 3px 8px -2px rgba(0,0,0,.06) !important;
        }

        /* Edge hover glow */
        .react-flow__edge-path {
          transition: stroke-width .15s ease, filter .15s ease;
        }
        .react-flow__edge:hover .react-flow__edge-path {
          stroke-width: 3.5 !important;
          filter: drop-shadow(0 2px 4px rgba(0,0,0,.15));
          cursor: pointer;
        }
        
        /* Handle port glow on hover */
        .react-flow__handle {
          transition: transform .15s ease, box-shadow .15s ease;
        }
        .react-flow__handle:hover {
          transform: scale(1.6);
          box-shadow: 0 0 0 4px rgba(100,150,255,.25);
        }

        .node-executing > div {
          animation: nodePulse 1.2s ease-in-out infinite;
          border-radius: 10px;
        }
        .node-success > div::after {
          content: '✓';
          position: absolute;
          top: -7px;
          right: -7px;
          width: 20px;
          height: 20px;
          background: ${T.start.icon};
          color: #fff;
          border-radius: 50%;
          font-size: 11px;
          font-weight: 700;
          line-height: 20px;
          text-align: center;
          z-index: 10;
          box-shadow: 0 2px 6px ${T.start.icon}50;
          animation: nodeSuccess .6s ease-out;
        }
        .node-error > div::after {
          content: '✗';
          position: absolute;
          top: -7px;
          right: -7px;
          width: 20px;
          height: 20px;
          background: #ef4444;
          color: #fff;
          border-radius: 50%;
          font-size: 11px;
          font-weight: 700;
          line-height: 20px;
          text-align: center;
          z-index: 10;
          box-shadow: 0 2px 6px rgba(239,68,68,.5);
        }
      `}</style>

      {/* Toolbar — glass ribbon */}
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 12, flexWrap: 'wrap', gap: 10,
        padding: '10px 18px',
        background: 'rgba(255,255,255,.9)', backdropFilter: 'blur(16px) saturate(180%)',
        borderRadius: 12, border: '1px solid rgba(226,232,240,.7)',
        boxShadow: '0 1px 3px rgba(0,0,0,.03), 0 2px 8px rgba(0,0,0,.04)',
        flexShrink: 0,
      }}>
        <Space wrap size={10}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflows.html')}
            style={{ borderRadius: 8, fontWeight: 500, border: '1px solid #e2e8f0' }}>返回</Button>
          <Input style={{ width: 200, borderRadius: 8, border: '1px solid #e2e8f0' }}
            placeholder="工作流名称" value={name}
            onChange={(e) => setName(e.target.value)} />
          <Input style={{ width: 250, borderRadius: 8, border: '1px solid #e2e8f0' }}
            placeholder="描述（可选）" value={description}
            onChange={(e) => setDescription(e.target.value)} />
        </Space>
        <Space wrap size={8}>
          {executing ? (
            <Button danger onClick={handleCancelExecution}
              style={{ borderRadius: 8, fontWeight: 500 }}>取消执行</Button>
          ) : (
            <>
              <Button onClick={handleValidate}
                style={{ borderRadius: 8, border: '1px solid #e2e8f0', fontWeight: 500 }}>验证</Button>
              <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}
                style={{ borderRadius: 8, fontWeight: 500, boxShadow: '0 2px 6px rgba(255,119,0,.25)' }}>保存</Button>
              <Button icon={<SendOutlined />} onClick={handlePublish} disabled={!workflowId || isNew}
                style={{ borderRadius: 8, fontWeight: 500 }}>发布</Button>
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={handleExecuteStream}
                disabled={!workflowId || isNew}
                style={{
                  borderRadius: 8, fontWeight: 600,
                  background: `linear-gradient(135deg, ${T.start.icon}, #16a34a)`,
                  borderColor: T.start.icon,
                  boxShadow: `0 2px 8px ${T.start.icon}40`,
                }}
              >
                运行
              </Button>
            </>
          )}
        </Space>
      </div>

      {/* Execution progress bar */}
      {executing && (
        <div style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <Tag color="processing" icon={<PlayCircleOutlined />}>执行中</Tag>
            <Text style={{ fontSize: 12, color: '#8c8c8c' }}>
              节点 {execProgress.completed}/{execProgress.total}
            </Text>
            <div style={{ flex: 1, height: 4, background: '#f0f0f0', borderRadius: 2, overflow: 'hidden' }}>
              <div style={{
                height: '100%',
                width: `${execProgress.total > 0 ? (execProgress.completed / execProgress.total) * 100 : 0}%`,
                background: `linear-gradient(90deg, ${T.agent.icon}, ${T.start.icon})`,
                borderRadius: 2,
                transition: 'width 0.3s ease',
              }} />
            </div>
          </div>
        </div>
      )}

      {/* Validation errors */}
      {validationErrors.length > 0 && (
        <Alert type="warning" showIcon icon={<WarningOutlined />} closable onClose={() => setValidationErrors([])}
          message={`${validationErrors.length} 个验证问题`}
          description={<ul style={{ margin: 0, paddingLeft: 16 }}>{validationErrors.map((e, i) => <li key={i}>{e.message}</li>)}</ul>}
          style={{ marginBottom: 8 }} />
      )}

      {/* Main area */}
      <div style={{
        display: 'flex', flex: 1, gap: 0,
        borderRadius: 12, overflow: 'hidden',
        border: '1px solid #e2e8f0',
        boxShadow: '0 1px 3px rgba(0,0,0,.04), 0 4px 16px rgba(0,0,0,.03)',
        background: '#fff',
      }}>
        {/* Left palette */}
        <div style={{
          width: 154, background: '#fafbfc',
          borderRight: '1px solid #e8ecf0',
          overflow: 'auto', flexShrink: 0, padding: '10px 0',
        }}>
          <div style={{
            fontSize: 11, fontWeight: 700, color: '#475569',
            padding: '4px 12px 10px', letterSpacing: '.3px',
          }}>组件面板</div>
          {paletteGroups.map((group) => (
            <div key={group.name} style={{ padding: '2px 8px 8px' }}>
              <div style={{
                fontSize: 10, fontWeight: 600, color: '#94a3b8',
                textTransform: 'uppercase', letterSpacing: '1px',
                marginBottom: 6, padding: '2px 6px',
                background: 'rgba(148,163,184,.06)', borderRadius: 4,
              }}>{group.name}</div>
              {group.items.map((item) => (
                <div key={item.type} draggable onDragStart={(e) => onDragStart(e, item.type, item.label)}
                  style={{
                    padding: '7px 10px', marginBottom: 5, borderRadius: 7,
                    background: `linear-gradient(135deg, #fff, ${item.color}08)`,
                    border: `1px solid ${item.color}20`,
                    borderLeft: `3px solid ${item.color}`,
                    cursor: 'grab', fontWeight: 600,
                    fontSize: 12, color: '#334155',
                    boxShadow: '0 1px 2px rgba(0,0,0,.02)',
                    transition: 'all .18s cubic-bezier(.34,1.56,.64,1)',
                    display: 'flex', alignItems: 'center', gap: 8,
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.transform = 'translateX(4px) scale(1.02)';
                    e.currentTarget.style.boxShadow = `0 6px 16px ${item.color}18, 0 2px 4px rgba(0,0,0,.04)`;
                    e.currentTarget.style.borderLeftWidth = '4px';
                    e.currentTarget.style.background = `linear-gradient(135deg, ${item.color}0c, ${item.color}04)`;
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.transform = 'translateX(0) scale(1)';
                    e.currentTarget.style.boxShadow = '0 1px 2px rgba(0,0,0,.02)';
                    e.currentTarget.style.borderLeftWidth = '3px';
                    e.currentTarget.style.background = `linear-gradient(135deg, #fff, ${item.color}08)`;
                  }}
                >
                  <div style={{
                    width: 7, height: 7, borderRadius: '50%',
                    background: `linear-gradient(135deg, ${item.color}, ${item.color}cc)`,
                    flexShrink: 0, boxShadow: `0 0 6px ${item.color}40`,
                  }} />
                  {item.label}
                </div>
              ))}
            </div>
          ))}
        </div>

        {/* Center canvas — inset board */}
        <div ref={reactFlowWrapper} style={{
          flex: 1,
          background: '#f1f5f9',
          boxShadow: 'inset 0 2px 8px rgba(0,0,0,.06), inset 0 0 1px rgba(0,0,0,.08)',
        }}>
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            onEdgeClick={onEdgeClick}
            onPaneClick={onPaneClick}
            onDragOver={onDragOver}
            onDrop={onDrop}
            nodeTypes={nodeTypes}
            fitView
            deleteKeyCode={['Backspace', 'Delete']}
          >
            <Controls />
            <MiniMap nodeColor={(n) => {
              const colors: Record<string, string> = {
                start: T.start.icon, end: T.end.icon, agent: T.agent.icon, llm: T.llm.icon,
                tool: T.tool.icon, condition: T.condition.icon, parallel: T.parallel.icon,
                code: T.code.icon, knowledge: T.knowledge.icon, prompt: T.prompt.icon,
              };
              return colors[n.type || ''] || '#94a3b8';
            }} />
            <Background variant={BackgroundVariant.Lines} gap={24} size={0.6} color="#cbd5e1" />
          </ReactFlow>
        </div>

        {/* Right properties panel */}
        <div style={{
          width: 274, background: '#fafbfc',
          borderLeft: '1px solid #e8ecf0',
          overflow: 'auto', flexShrink: 0, padding: '16px 14px',
        }}>
          {selectedNode ? (
            <div>
              <div style={{
                display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6,
              }}>
                <div style={{
                  width: 36, height: 36, borderRadius: 10,
                  background: `linear-gradient(135deg, ${(T as Record<string, {icon: string}>)[selectedNode.type || '']?.icon || '#94a3b8'}20, ${(T as Record<string, {icon: string}>)[selectedNode.type || '']?.icon || '#94a3b8'}08)`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  flexShrink: 0,
                }}>
                  <div style={{
                    width: 10, height: 10, borderRadius: 3,
                    background: (T as Record<string, {icon: string}>)[selectedNode.type || '']?.icon || '#94a3b8',
                  }} />
                </div>
                <div>
                  <div style={{ fontWeight: 700, fontSize: 14, color: '#1e293b' }}>
                    {NODE_LABELS[selectedNode.type || ''] || selectedNode.type}
                  </div>
                  <div style={{ fontSize: 10, color: '#94a3b8', fontFamily: 'monospace', marginTop: 1 }}>{selectedNode.id}</div>
                </div>
              </div>
              {/* Port schema indicator */}
              {NODE_PORTS[selectedNode.type || ''] && (
                <div style={{
                  display: 'flex', gap: 10, marginBottom: 14,
                  padding: '8px 10px', background: '#f1f5f9', borderRadius: 8,
                }}>
                  {NODE_PORTS[selectedNode.type!].inputs.length > 0 && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 10, color: '#64748b', fontWeight: 500 }}>
                      <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: '#94a3b8' }} />
                      入: {NODE_PORTS[selectedNode.type!].inputs.map(p => p.name).join(', ')}
                    </div>
                  )}
                  {NODE_PORTS[selectedNode.type!].outputs.length > 0 && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 10, color: '#16a34a', fontWeight: 500 }}>
                      <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: '#22c55e' }} />
                      出: {NODE_PORTS[selectedNode.type!].outputs.map(p => p.name).join(', ')}
                    </div>
                  )}
                </div>
              )}
              {renderNodeConfig(selectedNode)}
              <Divider style={{ margin: '14px 0' }} />
              <Button size="small" danger block style={{ borderRadius: 7, height: 30, fontWeight: 500 }}
                onClick={() => { setNodes((nds) => nds.filter((n) => n.id !== selectedNode.id)); setSelectedNode(null); }}>
                删除节点
              </Button>
            </div>
          ) : selectedEdge ? (
            <div>
              <div style={{ fontWeight: 700, fontSize: 14, color: '#1e293b', marginBottom: 4 }}>连线配置</div>
              <div style={{ fontSize: 10, color: '#94a3b8', marginBottom: 14, fontFamily: 'monospace' }}>{selectedEdge.id}</div>
              {renderEdgeConfig(selectedEdge)}
            </div>
          ) : (
            <div style={{ textAlign: 'center', paddingTop: 48 }}>
              <div style={{
                width: 48, height: 48, borderRadius: 12,
                background: 'linear-gradient(135deg, #e2e8f0, #f1f5f9)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                margin: '0 auto 12px',
              }}>
                <BranchesOutlined style={{ fontSize: 22, color: '#94a3b8' }} />
              </div>
              <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.6, display: 'block' }}>
                点击节点或连线<br />查看和编辑属性
              </Text>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default WorkflowEditor;
