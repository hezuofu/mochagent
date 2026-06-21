import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Select, Form } from 'antd';
import { PlusOutlined, EditOutlined, PlayCircleOutlined, SendOutlined, CopyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workflowApi, Workflow } from '@/api/workflow';
import FilterBox from '@/components/FilterBox';

const statusColors: Record<string, string> = {
  DRAFT: 'default',
  PUBLISHED: 'green',
  ARCHIVED: 'orange',
};

const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};

const WorkflowList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    workflowApi.list({ page, size: 10, status: statusFilter }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page, statusFilter]);

  const handleDelete = (id: string) => {
    workflowApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handlePublish = (id: string) => {
    workflowApi.publish(id).then(() => { message.success('已发布'); fetchData(); });
  };

  const handleCopy = (id: string) => {
    workflowApi.copy(id).then(() => { message.success('已复制'); fetchData(); });
  };

  const handleExecute = (id: string) => {
    workflowApi.execute(id).then((res) => {
      message.success(`执行完成: ${res.data.status}`);
      fetchData();
    });
  };

  const handleTest = (id: string) => {
    workflowApi.test(id).then((res) => {
      message.success(`测试通过: ${res.data.executionTimeMs}ms`);
    });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '名称', dataIndex: 'name', key: 'name', render: (t: string, r: Workflow) => <a onClick={() => navigate(`/workflows/${r.id}/edit.html`)}>{t}</a> },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100, render: (s: string) => <Tag color={statusColors[s]}>{statusLabels[s]}</Tag> },
    { title: '版本', dataIndex: 'version', key: 'version', width: 80, render: (v: number) => `v${v}` },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 320, render: (_: unknown, r: Workflow) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => navigate(`/workflows/${r.id}/edit.html`)}>编辑</Button>
          {r.status === 'DRAFT' && (
            <Button type="link" size="small" icon={<SendOutlined />} onClick={() => handlePublish(r.id)}>发布</Button>
          )}
          <Button type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => handleExecute(r.id)}>执行</Button>
          <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(r.id)}>复制</Button>
          <Button type="link" size="small" onClick={() => handleTest(r.id)}>测试</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ margin: -24 }}>
      <FilterBox onSearch={() => { setPage(1); fetchData(); }} onReset={() => { setStatusFilter(undefined); setPage(1); }}>
        <Form.Item label="状态">
          <Select
            placeholder="筛选状态"
            allowClear
            style={{ width: 120 }}
            value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { label: '草稿', value: 'DRAFT' },
              { label: '已发布', value: 'PUBLISHED' },
              { label: '已归档', value: 'ARCHIVED' },
            ]}
          />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/workflows/new/edit.html')}>
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

export default WorkflowList;
