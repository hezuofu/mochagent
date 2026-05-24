import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Modal, Form, Input, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { userApi, User } from '@/api/user';
import { roleApi, Role } from '@/api/role';
import FilterBox from '@/components/FilterBox';
import { rules } from '@/utils/validation';

const statusColors: Record<string, string> = { ACTIVE: 'green', DISABLED: 'red', LOCKED: 'orange' };
const statusLabels: Record<string, string> = { ACTIVE: '正常', DISABLED: '禁用', LOCKED: '锁定' };

const UserList: React.FC = () => {
  const [data, setData] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [form] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    userApi.list({ page, size: 10, keyword: keyword || undefined, status: statusFilter }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);
  useEffect(() => { roleApi.list({ page: 1, size: 100 }).then((res) => setAllRoles(res.data.list)); }, []);

  const handleSearch = () => { setPage(1); fetchData(); };
  const handleReset = () => { setKeyword(''); setStatusFilter(undefined); setPage(1); };

  const handleSave = () => {
    form.validateFields().then((values) => {
      const api = editingId ? userApi.update(editingId, values) : userApi.create(values);
      api.then(() => { message.success('保存成功'); setModalOpen(false); fetchData(); })
        .catch((e) => message.error(e.response?.data?.message || '保存失败'));
    });
  };

  const handleEdit = (record: User) => {
    setEditingId(record.id);
    form.setFieldsValue({ nickname: record.nickname, email: record.email, phone: record.phone, status: record.status });
    setModalOpen(true);
  };

  const handleDelete = (id: string) => {
    userApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleToggle = (id: string) => {
    userApi.toggleStatus(id).then(() => { message.success('状态已切换'); fetchData(); });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={statusColors[s]}>{statusLabels[s]}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 220, render: (_: unknown, r: User) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" size="small" onClick={() => handleToggle(r.id)}>
            {r.status === 'ACTIVE' ? '禁用' : '启用'}
          </Button>
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
        <Form.Item label="用户名">
          <Input
            placeholder="搜索用户名"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 180 }}
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
              { label: '正常', value: 'ACTIVE' },
              { label: '禁用', value: 'DISABLED' },
              { label: '锁定', value: 'LOCKED' },
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
      <Modal title={editingId ? '编辑用户' : '新建用户'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)}>
        <Form form={form} layout="vertical">
          {!editingId && (
            <>
              <Form.Item name="username" label="用户名" rules={rules.username()}>
                <Input placeholder="登录用户名" />
              </Form.Item>
              <Form.Item name="password" label="密码" rules={rules.password()}>
                <Input.Password placeholder="用户密码" />
              </Form.Item>
            </>
          )}
          <Form.Item name="nickname" label="昵称">
            <Input placeholder="显示昵称" />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={rules.optionalEmail()}>
            <Input placeholder="邮箱地址" />
          </Form.Item>
          <Form.Item name="phone" label="手机号" rules={rules.optionalPhone()}>
            <Input placeholder="手机号码" maxLength={11} />
          </Form.Item>
          {editingId && (
            <Form.Item name="status" label="状态">
              <Select options={[
                { label: '正常', value: 'ACTIVE' },
                { label: '禁用', value: 'DISABLED' },
                { label: '锁定', value: 'LOCKED' },
              ]} />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
};

export default UserList;
