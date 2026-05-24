import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Input, Popconfirm, message, Modal, Form, Select } from 'antd';
import { PlusOutlined, EditOutlined, FileTextOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { knowledgeBaseApi, KnowledgeBase } from '@/api/knowledgeBase';
import { digitalHumanApi, DigitalHuman } from '@/api/digitalHuman';
import FilterBox from '@/components/FilterBox';

const KnowledgeBaseList: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [data, setData] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [digitalHumanId, setDigitalHumanId] = useState(searchParams.get('digitalHumanId') || '');
  const [digitalHumans, setDigitalHumans] = useState<DigitalHuman[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    digitalHumanApi.list({ size: 100 }).then((res) => setDigitalHumans(res.data.list || []));
  }, []);

  const fetchData = () => {
    if (!digitalHumanId) {
      setData([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    knowledgeBaseApi
      .list({ digitalHumanId, page, size: 10, keyword: keyword || undefined })
      .then((res) => {
        setData(res.data.list);
        setTotal(res.data.total);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchData();
  }, [page, digitalHumanId]);

  const handleSearch = () => {
    setPage(1);
    fetchData();
  };
  const handleReset = () => {
    setKeyword('');
    setPage(1);
  };

  const handleCreate = () => {
    setEditingId(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: KnowledgeBase) => {
    setEditingId(record.id);
    form.setFieldsValue({ name: record.name, description: record.description });
    setModalOpen(true);
  };

  const handleSave = () => {
    form.validateFields().then((values) => {
      if (editingId) {
        knowledgeBaseApi.update(editingId, values).then(() => {
          message.success('已更新');
          setModalOpen(false);
          fetchData();
        });
      } else {
        knowledgeBaseApi.create({ ...values, digitalHumanId }).then(() => {
          message.success('已创建');
          setModalOpen(false);
          fetchData();
        });
      }
    });
  };

  const handleDelete = (id: string) => {
    knowledgeBaseApi.delete(id).then(() => {
      message.success('已删除');
      fetchData();
    });
  };

  const columns = [
    {
      title: '序号',
      key: 'seq',
      width: 60,
      render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1,
    },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (t: string, r: KnowledgeBase) => (
        <a onClick={() => handleEdit(r)}>{t}</a>
      ),
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '文档数', dataIndex: 'documentCount', key: 'documentCount', width: 80 },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: unknown, r: KnowledgeBase) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(r)}
          >
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            icon={<FileTextOutlined />}
            onClick={() => navigate(`/knowledge-bases/${r.id}/documents.html`)}
          >
            文档
          </Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" danger size="small">
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ margin: -24 }}>
      <FilterBox onSearch={handleSearch} onReset={handleReset}>
        <Form.Item label="数字人">
          <Select
            placeholder="选择数字人"
            allowClear
            style={{ width: 220 }}
            value={digitalHumanId || undefined}
            onChange={(v) => setDigitalHumanId(v || '')}
            options={digitalHumans.map((d) => ({
              label: d.name,
              value: d.id,
            }))}
          />
        </Form.Item>
        <Form.Item label="名称">
          <Input
            placeholder="搜索知识库名称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
            allowClear
          />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleCreate}
            disabled={!digitalHumanId}
          >
            新建知识库
          </Button>
        </div>
      </div>
      <div style={{ padding: '0 24px' }}>
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          locale={{ emptyText: digitalHumanId ? '暂无知识库' : '请先选择数字人' }}
          pagination={{
            current: page,
            total,
            pageSize: 10,
            onChange: (p) => setPage(p),
            showTotal: (t) => `共 ${t} 条`,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
          }}
        />
      </div>

      <Modal
        title={editingId ? '编辑知识库' : '新建知识库'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入知识库名称' }]}
          >
            <Input placeholder="知识库名称" maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="知识库描述" rows={3} maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default KnowledgeBaseList;
