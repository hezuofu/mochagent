import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Input, Tag, Popconfirm, message, Modal, Form, Select } from 'antd';
import { PlusOutlined, CopyOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { agentApi, Agent } from '@/api/agent';
import FilterBox from '@/components/FilterBox';

const AgentList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [executeModal, setExecuteModal] = useState<{ open: boolean; agent?: Agent }>({ open: false });
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    agentApi.list({ page, size: 10, keyword: keyword || undefined, status: statusFilter }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleDelete = (id: string) => {
    agentApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleToggle = (id: string) => {
    agentApi.toggleStatus(id).then(() => { message.success('状态已切换'); fetchData(); });
  };

  const handleCopy = (agent: Agent) => {
    agentApi.copy(agent.id, `${agent.name} (Copy)`).then(() => { message.success('已复制'); fetchData(); });
  };

  const handleSearch = () => { setPage(1); fetchData(); };
  const handleReset = () => { setKeyword(''); setStatusFilter(undefined); setPage(1); };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '名称', dataIndex: 'name', key: 'name', render: (t: string, r: Agent) => <a onClick={() => navigate(`/agents/${r.id}/edit`)}>{t}</a> },
    { title: '类型', dataIndex: 'type', key: 'type', width: 100 },
    { title: '版本', dataIndex: 'version', key: 'version', width: 100 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100, render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'red'}>{s === 'ACTIVE' ? '启用' : '禁用'}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
    {
      title: '操作', key: 'action', width: 260, render: (_: unknown, r: Agent) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => setExecuteModal({ open: true, agent: r })}>执行</Button>
          <Button type="link" size="small" onClick={() => handleToggle(r.id)}>{r.status === 'ACTIVE' ? '禁用' : '启用'}</Button>
          <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(r)}>复制</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ margin: -24 }}>
      <FilterBox onSearch={handleSearch} onReset={handleReset}>
        <Form.Item label="名称">
          <Input
            placeholder="搜索 Agent 名称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
            allowClear
          />
        </Form.Item>
        <Form.Item label="状态">
          <Select
            placeholder="状态"
            allowClear
            style={{ width: 120 }}
            value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { label: '启用', value: 'ACTIVE' },
              { label: '禁用', value: 'INACTIVE' },
            ]}
          />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/agents/new')}>
            新建
          </Button>
        </div>
      </div>
      <div style={{ padding: '0 24px' }}>
        <Table columns={columns} dataSource={data} rowKey="id" loading={loading}
          pagination={{
            current: page, total, pageSize: 10, onChange: (p) => setPage(p),
            showTotal: (t) => `共 ${t} 条`,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
          }} />
      </div>
      <Modal title={`执行 Agent: ${executeModal.agent?.name}`} open={executeModal.open}
        onCancel={() => setExecuteModal({ open: false })}
        onOk={() => { message.info('已提交执行'); setExecuteModal({ open: false }); }}>
        <p>即将执行 Agent，请确认。</p>
      </Modal>
    </div>
  );
};

export default AgentList;
