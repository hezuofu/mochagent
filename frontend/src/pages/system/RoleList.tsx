import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Modal, Form, Input, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { roleApi, Role } from '@/api/role';
import FilterBox from '@/components/FilterBox';
import { rules } from '@/utils/validation';

const statusColors: Record<string, string> = { ACTIVE: 'green', DISABLED: 'red' };
const statusLabels: Record<string, string> = { ACTIVE: '启用', DISABLED: '禁用' };

const RoleList: React.FC = () => {
  const [data, setData] = useState<Role[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    roleApi.list({ page, size: 10, keyword: keyword || undefined }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleSearch = () => { setPage(1); fetchData(); };
  const handleReset = () => { setKeyword(''); setPage(1); };

  const handleSave = () => {
    form.validateFields().then((values) => {
      const api = editingId ? roleApi.update(editingId, values) : roleApi.create(values);
      api.then(() => { message.success('保存成功'); setModalOpen(false); fetchData(); })
        .catch((e) => message.error(e.response?.data?.message || '保存失败'));
    });
  };

  const handleEdit = (record: Role) => {
    setEditingId(record.id);
    form.setFieldsValue({ roleName: record.roleName, description: record.description });
    setModalOpen(true);
  };

  const handleDelete = (id: string) => {
    roleApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName' },
    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode', width: 120 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={statusColors[s]}>{statusLabels[s]}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 150, render: (_: unknown, r: Role) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => handleEdit(r)}>编辑</Button>
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
        <Form.Item label="角色名称">
          <Input
            placeholder="搜索角色名称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
            allowClear
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
      <Modal title={editingId ? '编辑角色' : '新建角色'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="roleName" label="角色名称" rules={rules.name(30)}>
            <Input placeholder="角色名称" />
          </Form.Item>
          {!editingId && (
            <Form.Item name="roleCode" label="角色编码" rules={rules.code()}>
              <Input placeholder="ROLE_XXX" />
            </Form.Item>
          )}
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="角色描述" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default RoleList;
