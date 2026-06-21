import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Modal, Form, Input, Select, InputNumber, TreeSelect } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { permissionApi, Permission } from '@/api/permission';
import FilterBox from '@/components/FilterBox';
import { rules } from '@/utils/validation';

const permTypeColors: Record<string, string> = { MENU: 'blue', BUTTON: 'green', API: 'orange' };
const permTypeLabels: Record<string, string> = { MENU: '菜单', BUTTON: '按钮', API: '接口' };

const PermissionList: React.FC = () => {
  const [data, setData] = useState<Permission[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [allPerms, setAllPerms] = useState<Permission[]>([]);
  const [form] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    permissionApi.tree().then((res) => {
      setData(res.data);
      // Also store flat list for TreeSelect
      const flat: Permission[] = [];
      const flatten = (items: Permission[]) => {
        items.forEach((item) => {
          flat.push({ ...item, children: undefined });
          if (item.children) flatten(item.children);
        });
      };
      flatten(res.data);
      setAllPerms(flat);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleSave = () => {
    form.validateFields().then((values) => {
      const api = editingId ? permissionApi.update(editingId, values) : permissionApi.create(values);
      api.then(() => { message.success('保存成功'); setModalOpen(false); fetchData(); });
    });
  };

  const handleEdit = (record: Permission) => {
    setEditingId(record.id);
    form.setFieldsValue({
      permName: record.permName,
      permCode: record.permCode,
      permType: record.permType,
      parentId: record.parentId || undefined,
      path: record.path,
      icon: record.icon,
      sortOrder: record.sortOrder,
    });
    setModalOpen(true);
  };

  const handleDelete = (id: string) => {
    permissionApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const columns = [
    { title: '权限名称', dataIndex: 'permName', key: 'permName' },
    { title: '权限编码', dataIndex: 'permCode', key: 'permCode', width: 180 },
    { title: '类型', dataIndex: 'permType', key: 'permType', width: 80, render: (t: string) => <Tag color={permTypeColors[t]}>{permTypeLabels[t]}</Tag> },
    { title: '路径', dataIndex: 'path', key: 'path', width: 150, render: (v: string) => v || '-' },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 60 },
    {
      title: '操作', key: 'action', width: 120, render: (_: unknown, r: Permission) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => handleEdit(r)}>编辑</Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const treeSelectData = allPerms
    .filter((p) => p.permType === 'MENU')
    .map((p) => ({
      title: p.permName,
      value: p.id,
      key: p.id,
    }));

  return (
    <div style={{ margin: -24 }}>
      <FilterBox hasSearch={false} hasReset={false} />
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingId(null); form.resetFields(); setModalOpen(true); }}>
            新建
          </Button>
        </div>
      </div>
      <div style={{ padding: '0 24px' }}>
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          expandable={{ defaultExpandAllRows: true }}
          pagination={false}
          childrenColumnName="children"
        />
      </div>
      <Modal title={editingId ? '编辑权限' : '新建权限'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="permName" label="权限名称" rules={rules.name(30)}>
            <Input placeholder="权限名称" />
          </Form.Item>
          <Form.Item name="permCode" label="权限编码" rules={rules.code()}>
            <Input placeholder="system:module:action" />
          </Form.Item>
          <Form.Item name="permType" label="权限类型" rules={rules.required('请选择类型')}>
            <Select options={[
              { label: '菜单', value: 'MENU' },
              { label: '按钮', value: 'BUTTON' },
              { label: '接口', value: 'API' },
            ]} />
          </Form.Item>
          <Form.Item name="parentId" label="父级权限">
            <TreeSelect
              treeData={treeSelectData}
              placeholder="选择父级权限（留空为顶级）"
              allowClear
              treeDefaultExpandAll
            />
          </Form.Item>
          <Form.Item name="path" label="路由路径">
            <Input placeholder="/users" />
          </Form.Item>
          <Form.Item name="icon" label="图标">
            <Input placeholder="TeamOutlined" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber placeholder="0" min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default PermissionList;
