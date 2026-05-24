import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Input, Tag, Popconfirm, message, Form, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { digitalHumanApi, DigitalHuman } from '@/api/digitalHuman';
import FilterBox from '@/components/FilterBox';

const GEOMETRY_LABELS: Record<string, string> = {
  PARTICLE: '粒子',
  WAVE: '波',
  ORB: '球体',
  GRID: '网格',
  FRACTAL: '分形',
};

const DigitalHumanList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<DigitalHuman[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const fetchData = () => {
    setLoading(true);
    digitalHumanApi.list({ page, size: 10, keyword: keyword || undefined, status: statusFilter }).then((res) => {
      setData(res.data.list);
      setTotal(res.data.total);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page]);

  const handleDelete = (id: string) => {
    digitalHumanApi.delete(id).then(() => { message.success('已删除'); fetchData(); });
  };

  const handleSearch = () => { setPage(1); fetchData(); };
  const handleReset = () => { setKeyword(''); setStatusFilter(undefined); setPage(1); };

  const columns = [
    { title: '序号', key: 'seq', width: 60, render: (_: unknown, __: unknown, i: number) => (page - 1) * 10 + i + 1 },
    {
      title: '视觉签名', key: 'visualSig', width: 80,
      render: (_: unknown, r: DigitalHuman) => (
        <div style={{
          width: 32, height: 32, borderRadius: r.geometryType === 'ORB' ? '50%' : r.geometryType === 'WAVE' ? '0' : '4px',
          background: r.colorPrimary || '#f70',
          boxShadow: `0 0 8px ${r.colorPrimary || '#f70'}40`,
        }} />
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name', render: (t: string, r: DigitalHuman) => <a onClick={() => navigate(`/digital-humans/${r.id}/edit.html`)}>{t}</a> },
    { title: '主色', dataIndex: 'colorPrimary', key: 'colorPrimary', width: 100, render: (c: string) => c ? <span><span style={{ display: 'inline-block', width: 12, height: 12, background: c, borderRadius: 2, marginRight: 6, verticalAlign: 'middle' }} />{c}</span> : '-' },
    { title: '形态', dataIndex: 'geometryType', key: 'geometryType', width: 80, render: (g: string) => <Tag>{GEOMETRY_LABELS[g] || g}</Tag> },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => {
        const map: Record<string, { color: string; label: string }> = {
          IDLE: { color: 'default', label: '空闲' },
          RESPONDING: { color: 'processing', label: '响应中' },
          STREAMING: { color: 'success', label: '流式' },
        };
        const info = map[s] || { color: 'default', label: s };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
    {
      title: '操作', key: 'action', width: 120, render: (_: unknown, r: DigitalHuman) => (
        <Space size={0}>
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
        <Form.Item label="名称">
          <Input
            placeholder="搜索数字人名称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
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
              { label: '空闲', value: 'IDLE' },
              { label: '响应中', value: 'RESPONDING' },
              { label: '流式', value: 'STREAMING' },
            ]}
          />
        </Form.Item>
      </FilterBox>
      <div className="operator-box" style={{ padding: '0 24px' }}>
        <div className="left">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/digital-humans/new.html')}>
            新建数字人
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

export default DigitalHumanList;
