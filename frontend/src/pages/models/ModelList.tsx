import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Modal, Form, Input, Select } from 'antd';
import { PlusOutlined, ApiOutlined } from '@ant-design/icons';
import { modelApi, ModelConfig } from '@/api/model';
import FilterBox from '@/components/FilterBox';

const providers = ['OpenAI', 'DeepSeek', 'Anthropic', 'Google', 'Azure', 'Local'];

const ModelList: React.FC = () => {
  const [data, setData] = useState<ModelConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [providerFilter, setProviderFilter] = useState<string | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    modelApi.list({ page, size: 10 }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleSave = () => {
    form.validateFields().then((values) => {
      const api = editingId ? modelApi.update(editingId, values) : modelApi.create(values);
      api.then(() => { message.success('保存成功'); setModalOpen(false); fetchData(); });
    });
  };

  const handleEdit = (record: ModelConfig) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = (id: string) => {
    modelApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleToggle = (id: string) => {
    modelApi.toggleStatus(id).then(() => { message.success('状态已切换'); fetchData(); });
  };

  const handleTest = (id: string) => {
    modelApi.test(id).then((res) => message.success(`测试成功: ${res.data.latencyMs}ms`));
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '提供商', dataIndex: 'provider', key: 'provider', width: 120, render: (p: string) => <Tag color="blue">{p}</Tag> },
    { title: '版本', dataIndex: 'modelVersion', key: 'modelVersion', width: 100 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'red'}>{s === 'ACTIVE' ? '启用' : '禁用'}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 240, render: (_: unknown, r: ModelConfig) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<ApiOutlined />} onClick={() => handleTest(r.id)}>测试</Button>
          <Button type="link" size="small" onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" size="small" onClick={() => handleToggle(r.id)}>{r.status === 'ACTIVE' ? '禁用' : '启用'}</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ margin: -24 }}>
      <FilterBox onSearch={() => { setPage(1); fetchData(); }} onReset={() => { setProviderFilter(undefined); setStatusFilter(undefined); setPage(1); }}>
        <Form.Item label="提供商">
          <Select
            placeholder="提供商"
            allowClear
            style={{ width: 140 }}
            value={providerFilter}
            onChange={(v) => setProviderFilter(v)}
            options={providers.map((p) => ({ label: p, value: p }))}
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
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingId(null); form.resetFields(); setModalOpen(true); }}>
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
      <Modal title={editingId ? '编辑模型' : '注册模型'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="provider" label="提供商" rules={[{ required: true }]}>
            <Select options={providers.map((p) => ({ label: p, value: p }))} />
          </Form.Item>
          <Form.Item name="endpoint" label="Endpoint"><Input placeholder="https://api.openai.com/v1" /></Form.Item>
          <Form.Item name="apiKey" label="API Key"><Input.Password /></Form.Item>
          <Form.Item name="modelVersion" label="模型版本"><Input placeholder="gpt-4" /></Form.Item>
          <Form.Item name="maxTokens" label="最大 Token"><Input type="number" /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ModelList;
