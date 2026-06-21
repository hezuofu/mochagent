import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, Popconfirm, message, Form, Select } from 'antd';
import { PlusOutlined, MessageOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { conversationApi, Conversation } from '@/api/conversation';
import FilterBox from '@/components/FilterBox';
import dayjs from 'dayjs';

const ConversationList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    conversationApi.list({ page, size: 10 }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleCreate = () => {
    conversationApi.create({ title: '新对话 ' + dayjs().format('MM-DD HH:mm') }).then(() => {
      message.success('对话已创建');
      fetchData();
    });
  };

  const handleDelete = (id: string) => {
    conversationApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleArchive = (id: string) => {
    conversationApi.archive(id).then(() => { message.success('已归档'); fetchData(); });
  };

  const handleClear = (id: string) => {
    conversationApi.clearMessages(id).then(() => { message.success('消息已清空'); fetchData(); });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    { title: '标题', dataIndex: 'title', key: 'title', render: (t: string, r: Conversation) => <a onClick={() => navigate(`/conversations/${r.id}`)}>{t}</a> },
    { title: '消息数', dataIndex: 'messageCount', key: 'messageCount', width: 100 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '活跃' : '归档'}</Tag> },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 240, render: (_: unknown, r: Conversation) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<MessageOutlined />} onClick={() => navigate(`/conversations/${r.id}`)}>对话</Button>
          <Button type="link" size="small" onClick={() => handleArchive(r.id)}>归档</Button>
          <Button type="link" size="small" icon={<DeleteOutlined />} onClick={() => handleClear(r.id)}>清空</Button>
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
            placeholder="状态"
            allowClear
            style={{ width: 120 }}
            value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { label: '活跃', value: 'ACTIVE' },
              { label: '归档', value: 'ARCHIVED' },
            ]}
          />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
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

export default ConversationList;
