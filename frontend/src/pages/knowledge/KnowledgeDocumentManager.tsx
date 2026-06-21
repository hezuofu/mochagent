import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Table, Button, Space, Input, Popconfirm, message, Modal, Form, Descriptions, Card, Tag,
} from 'antd';
import {
  ArrowLeftOutlined, PlusOutlined, DeleteOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { knowledgeBaseApi, KnowledgeBase } from '@/api/knowledgeBase';
import { knowledgeDocumentApi, KnowledgeDocument } from '@/api/knowledgeDocument';

const SOURCE_TYPE_TAG: Record<string, string> = {
  TEXT: 'blue',
  FILE: 'green',
  URL: 'purple',
};

const KnowledgeDocumentManager: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [chunks, setChunks] = useState<KnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [uploadLoading, setUploadLoading] = useState(false);
  const [form] = Form.useForm();

  const fetchKb = () => {
    if (!id) return;
    knowledgeBaseApi.getById(id).then((res) => setKb(res.data));
  };

  const fetchChunks = () => {
    if (!id) return;
    setLoading(true);
    knowledgeDocumentApi
      .getChunks(id)
      .then((res) => setChunks(res.data || []))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchKb();
    fetchChunks();
  }, [id]);

  const handleUpload = () => {
    form.validateFields().then((values) => {
      setUploadLoading(true);
      knowledgeDocumentApi
        .create({
          knowledgeBaseId: id!,
          sourceName: values.sourceName,
          sourceType: values.sourceType || 'TEXT',
          content: values.content,
          metadataJson: values.metadataJson || undefined,
        })
        .then(() => {
          message.success('文档已上传并切片');
          setUploadModalOpen(false);
          form.resetFields();
          fetchChunks();
          fetchKb(); // refresh document count
        })
        .finally(() => setUploadLoading(false));
    });
  };

  const handleDeleteChunk = (chunkId: string) => {
    knowledgeDocumentApi.delete(chunkId).then(() => {
      message.success('已删除');
      fetchChunks();
      fetchKb();
    });
  };

  const handleDeleteAll = () => {
    knowledgeDocumentApi.deleteAll(id!).then(() => {
      message.success('已清空');
      fetchChunks();
      fetchKb();
    });
  };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => i + 1 },
    { title: '来源名称', dataIndex: 'sourceName', key: 'sourceName', width: 150 },
    {
      title: '类型',
      dataIndex: 'sourceType',
      key: 'sourceType',
      width: 80,
      render: (t: string) => <Tag color={SOURCE_TYPE_TAG[t] || 'default'}>{t}</Tag>,
    },
    {
      title: '内容预览',
      dataIndex: 'content',
      key: 'content',
      ellipsis: true,
      render: (c: string) => (
        <span title={c}>{c.length > 120 ? c.substring(0, 120) + '…' : c}</span>
      ),
    },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, r: KnowledgeDocument) => (
        <Popconfirm title="确定删除?" onConfirm={() => handleDeleteChunk(r.id)}>
          <Button type="link" danger size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ margin: -24 }}>
      <div style={{ padding: '16px 24px', borderBottom: '1px solid #f0f0f0' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          type="text"
          onClick={() => navigate('/knowledge-bases.html')}
        >
          返回知识库列表
        </Button>
      </div>

      <Card
        style={{ margin: 16, marginTop: 0 }}
        size="small"
        title={kb ? `知识库：${kb.name}` : '加载中…'}
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => { fetchKb(); fetchChunks(); }}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                form.resetFields();
                setUploadModalOpen(true);
              }}
            >
              上传文档
            </Button>
            {chunks.length > 0 && (
              <Popconfirm title="确定清空所有文档?" onConfirm={handleDeleteAll}>
                <Button danger icon={<DeleteOutlined />}>
                  清空全部
                </Button>
              </Popconfirm>
            )}
          </Space>
        }
      >
        {kb && (
          <Descriptions size="small" column={3} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="描述">{kb.description || '-'}</Descriptions.Item>
            <Descriptions.Item label="文档数">{kb.documentCount}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{kb.createTime || '-'}</Descriptions.Item>
          </Descriptions>
        )}
        <Table
          columns={columns}
          dataSource={chunks}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 个切片` }}
          locale={{ emptyText: '暂无文档切片，请上传文档' }}
        />
      </Card>

      <Modal
        title="上传文档"
        open={uploadModalOpen}
        onOk={handleUpload}
        onCancel={() => setUploadModalOpen(false)}
        confirmLoading={uploadLoading}
        destroyOnClose
        width={640}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="sourceName"
            label="来源名称"
            rules={[{ required: true, message: '请输入来源名称' }]}
          >
            <Input placeholder="例如：产品手册、FAQ文档" maxLength={200} />
          </Form.Item>
          <Form.Item
            name="sourceType"
            label="来源类型"
            initialValue="TEXT"
          >
            <Input placeholder="TEXT / FILE / URL" maxLength={50} />
          </Form.Item>
          <Form.Item
            name="content"
            label="文档内容"
            rules={[{ required: true, message: '请输入文档内容' }]}
            extra="内容将自动按段落和2000字符切片，以句号作为断点"
          >
            <Input.TextArea rows={12} placeholder="粘贴或输入文档内容…" />
          </Form.Item>
          <Form.Item
            name="metadataJson"
            label="元数据 (JSON)"
          >
            <Input.TextArea rows={3} placeholder='{"author": "xxx", "version": "1.0"}' />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default KnowledgeDocumentManager;
