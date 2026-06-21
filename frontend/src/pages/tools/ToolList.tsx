import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Modal, Form, Input, Select } from 'antd';
import { PlusOutlined, ExperimentOutlined } from '@ant-design/icons';
import { toolApi, Tool } from '@/api/tool';
import FilterBox from '@/components/FilterBox';

const categories = ['计算工具', '网络工具', '文件工具', '数据库工具', '搜索工具', '代码执行'];

const ToolList: React.FC = () => {
  const [data, setData] = useState<Tool[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [categoryFilter, setCategoryFilter] = useState<string | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    toolApi.list({ page, size: 10 }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleSave = () => {
    form.validateFields().then((values) => {
      const api = editingId ? toolApi.update(editingId, values) : toolApi.create(values);
      api.then(() => { message.success('保存成功'); setModalOpen(false); fetchData(); });
    });
  };

  const handleEdit = (record: Tool) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = (id: string) => {
    toolApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleToggle = (id: string) => {
    toolApi.toggleStatus(id).then(() => { message.success('状态已切换'); fetchData(); });
  };

  const handleTest = (id: string) => {
    toolApi.test(id).then((res) => message.success(`测试完成: ${res.data.status}`));
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '分类', dataIndex: 'category', key: 'category', width: 100, render: (c: string) => <Tag>{c}</Tag> },
    { title: '调用次数', dataIndex: 'callCount', key: 'callCount', width: 100 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'red'}>{s === 'ACTIVE' ? '启用' : '禁用'}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 220, render: (_: unknown, r: Tool) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<ExperimentOutlined />} onClick={() => handleTest(r.id)}>测试</Button>
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
      <FilterBox onSearch={() => { setPage(1); fetchData(); }} onReset={() => { setCategoryFilter(undefined); setStatusFilter(undefined); setPage(1); }}>
        <Form.Item label="分类">
          <Select
            placeholder="分类"
            allowClear
            style={{ width: 140 }}
            value={categoryFilter}
            onChange={(v) => setCategoryFilter(v)}
            options={categories.map((c) => ({ label: c, value: c }))}
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
      <Modal title={editingId ? '编辑工具' : '注册工具'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="category" label="分类"><Select options={categories.map((c) => ({ label: c, value: c }))} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ToolList;
