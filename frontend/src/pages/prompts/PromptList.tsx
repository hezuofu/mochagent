import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, Modal, message, Select, Input, Form } from 'antd';
import { PlusOutlined, EditOutlined, EyeOutlined, SendOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { promptApi, Prompt } from '@/api/prompt';
import FilterBox from '@/components/FilterBox';

const statusColors: Record<string, string> = { DRAFT: 'default', PUBLISHED: 'green', ARCHIVED: 'orange' };
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' };

const PromptList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<Prompt[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [categoryFilter, setCategoryFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');

  const fetchData = () => {
    setLoading(true);
    const api = keyword
      ? promptApi.search(keyword, { page, size: 10 })
      : promptApi.list({ page, size: 10, status: statusFilter, category: categoryFilter });
    api.then((res) => { setData(res.data.list); setTotal(res.data.total); }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page, statusFilter, categoryFilter]);

  const handleSearch = () => { setPage(1); fetchData(); };

  const handleDelete = (id: string) => {
    promptApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handlePublish = (id: string) => {
    promptApi.publish(id).then(() => { message.success('已发布'); fetchData(); });
  };

  const handleRender = (id: string) => {
    promptApi.render(id, {}).then((res) => {
      Modal.info({ title: '渲染预览', content: res.data.rendered, width: 600 });
    });
  };

  const handleTest = (id: string) => {
    promptApi.test(id, {}).then((res) => {
      message.success(`测试完成: ${res.data.latencyMs}ms, tokens: ${res.data.tokensUsed}`);
    });
  };

  const handleOptimize = (id: string) => {
    promptApi.optimize(id).then((res) => {
      message.success('优化建议已生成');
    });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '名称', dataIndex: 'name', key: 'name', render: (t: string, r: Prompt) => <a onClick={() => navigate(`/prompts/${r.id}/edit`)}>{t}</a> },
    { title: '分类', dataIndex: 'category', key: 'category', width: 100, render: (c: string) => c ? <Tag>{c}</Tag> : '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={statusColors[s]}>{statusLabels[s]}</Tag> },
    { title: '版本', dataIndex: 'version', key: 'version', width: 60, render: (v: number) => `v${v}` },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 320, render: (_: unknown, r: Prompt) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => navigate(`/prompts/${r.id}/edit`)}>编辑</Button>
          {r.status === 'DRAFT' && (
            <Button type="link" size="small" icon={<SendOutlined />} onClick={() => handlePublish(r.id)}>发布</Button>
          )}
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleRender(r.id)}>预览</Button>
          <Button type="link" size="small" icon={<ThunderboltOutlined />} onClick={() => handleTest(r.id)}>测试</Button>
          <Button type="link" size="small" onClick={() => handleOptimize(r.id)}>优化</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const handleReset = () => { setKeyword(''); setStatusFilter(undefined); setCategoryFilter(undefined); setPage(1); };

  return (
    <div style={{ margin: -24 }}>
      <FilterBox onSearch={handleSearch} onReset={handleReset}>
        <Form.Item label="名称">
          <Input
            placeholder="搜索提示词"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
            allowClear
          />
        </Form.Item>
        <Form.Item label="状态">
          <Select placeholder="状态" allowClear style={{ width: 100 }}
            value={statusFilter} onChange={(v) => { setStatusFilter(v); setPage(1); }}
            options={[{ label: '草稿', value: 'DRAFT' }, { label: '已发布', value: 'PUBLISHED' }]} />
        </Form.Item>
        <Form.Item label="分类">
          <Select placeholder="分类" allowClear style={{ width: 120 }}
            value={categoryFilter} onChange={(v) => { setCategoryFilter(v); setPage(1); }}
            options={[{ label: 'System', value: 'System' }, { label: 'User', value: 'User' }, { label: 'Tool', value: 'Tool' }]} />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/prompts/new')}>
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
    </div>
  );
};

export default PromptList;
