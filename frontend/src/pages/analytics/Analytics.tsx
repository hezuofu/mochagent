import React, { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic, Tabs, Table, Segmented, Spin } from 'antd';
import { ThunderboltOutlined, DollarOutlined, BarChartOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { analyticsApi } from '@/api/analytics';

const Analytics: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [days, setDays] = useState(30);
  const [overview, setOverview] = useState<Record<string, unknown>>({});
  const [usageData, setUsageData] = useState<Record<string, unknown>>({});
  const [costData, setCostData] = useState<Record<string, unknown>>({});
  const [modelData, setModelData] = useState<Record<string, unknown>>({});

  const fetchAll = (d: number) => {
    setLoading(true);
    Promise.all([
      analyticsApi.overview({ days: d }),
      analyticsApi.usage({ days: d }),
      analyticsApi.cost({ days: d }),
      analyticsApi.models({ days: d }),
    ]).then(([r1, r2, r3, r4]) => {
      setOverview(r1.data as Record<string, unknown>);
      setUsageData(r2.data as Record<string, unknown>);
      setCostData(r3.data as Record<string, unknown>);
      setModelData(r4.data as Record<string, unknown>);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchAll(days); }, [days]);

  const usageChartOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['调用次数', 'Token 消耗(K)'] },
    xAxis: { type: 'category', data: (usageData.dates as string[]) || [] },
    yAxis: [
      { type: 'value', name: '调用次数' },
      { type: 'value', name: 'Token(K)' },
    ],
    series: [
      { name: '调用次数', type: 'bar', data: (usageData.callCounts as number[]) || [], itemStyle: { color: '#f70' } },
      { name: 'Token 消耗(K)', type: 'line', yAxisIndex: 1, smooth: true,
        data: ((usageData.tokenCounts as number[]) || []).map((v: number) => Math.round(v / 100) / 10),
        itemStyle: { color: '#52c41a' } },
    ],
  };

  const costChartOption = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: (costData.dailyDates as string[]) || [] },
    yAxis: { type: 'value', name: '费用($)' },
    series: [{
      name: '每日费用', type: 'line', smooth: true,
      data: (costData.dailyCosts as number[]) || [],
      areaStyle: {}, itemStyle: { color: '#f70' },
    }],
  };

  const modelPieOption = {
    tooltip: { trigger: 'item', formatter: '{b}: ${c}' },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      data: ((costData.modelBreakdown as Array<{ modelName: string; cost: number }>) || [])
        .map((m) => ({ name: m.modelName, value: m.cost })),
    }],
  };

  const modelBarOption = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category',
      data: ((modelData.ranking as Array<{ modelName: string }>) || []).map((m) => m.modelName),
      axisLabel: { rotate: 30 },
    },
    yAxis: { type: 'value', name: '调用次数' },
    series: [{
      type: 'bar',
      data: ((modelData.ranking as Array<{ callCount: number }>) || []).map((m) => m.callCount),
      itemStyle: { color: '#722ed1' },
    }],
  };

  const modelColumns = [
    { title: '模型名称', dataIndex: 'modelName', key: 'modelName' },
    { title: '调用次数', dataIndex: 'callCount', key: 'callCount', sorter: (a: any, b: any) => a.callCount - b.callCount },
    { title: 'Token 消耗', dataIndex: 'tokenCount', key: 'tokenCount',
      render: (v: number) => v?.toLocaleString() || '-' },
    { title: '费用($)', dataIndex: 'cost', key: 'cost',
      render: (v: number) => v?.toFixed(4) || '-' },
  ];

  const tabItems = [
    {
      key: 'overview',
      label: '用量概览',
      children: (
        <Spin spinning={loading}>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Card><Statistic title="总调用数" value={overview.totalCalls as number || 0} prefix={<ThunderboltOutlined />} /></Card>
            </Col>
            <Col span={6}>
              <Card><Statistic title="总 Token" value={overview.totalTokens as number || 0} prefix="🔤" /></Card>
            </Col>
            <Col span={6}>
              <Card><Statistic title="总费用($)" value={overview.totalCost as number || 0} precision={4} prefix={<DollarOutlined />} /></Card>
            </Col>
            <Col span={6}>
              <Card><Statistic title="活跃模型" value={overview.activeModels as number || 0} prefix={<BarChartOutlined />} /></Card>
            </Col>
          </Row>
          <Row gutter={[16, 16]}>
            <Col span={12}>
              <Card title="每日调用量"><ReactECharts option={usageChartOption} style={{ height: 300 }} /></Card>
            </Col>
            <Col span={12}>
              <Card title="Token 消耗趋势"><ReactECharts option={{
                tooltip: { trigger: 'axis' },
                xAxis: { type: 'category', data: (usageData.dates as string[]) || [] },
                yAxis: { type: 'value', name: 'Token' },
                series: [{ name: 'Token', type: 'line', smooth: true, areaStyle: {},
                  data: (usageData.tokenCounts as number[]) || [], itemStyle: { color: '#13c2c2' } }],
              }} style={{ height: 300 }} /></Card>
            </Col>
          </Row>
        </Spin>
      ),
    },
    {
      key: 'cost',
      label: '费用分析',
      children: (
        <Spin spinning={loading}>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col span={8}>
              <Card><Statistic title="近30天总费用($)" value={costData.totalCost as number || 0} precision={4} prefix={<DollarOutlined />} /></Card>
            </Col>
          </Row>
          <Row gutter={[16, 16]}>
            <Col span={12}>
              <Card title="每日费用趋势"><ReactECharts option={costChartOption} style={{ height: 300 }} /></Card>
            </Col>
            <Col span={12}>
              <Card title="按模型费用分布"><ReactECharts option={modelPieOption} style={{ height: 300 }} /></Card>
            </Col>
          </Row>
        </Spin>
      ),
    },
    {
      key: 'models',
      label: '模型排行',
      children: (
        <Spin spinning={loading}>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col span={12}>
              <Card title="调用次数排行"><ReactECharts option={modelBarOption} style={{ height: 300 }} /></Card>
            </Col>
            <Col span={12}>
              <Card title="模型详情">
                <Table columns={modelColumns}
                  dataSource={(modelData.ranking as any[]) || []}
                  rowKey="modelName" size="small"
                  pagination={false} />
              </Card>
            </Col>
          </Row>
        </Spin>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>用量与费用分析</h3>
        <Segmented options={[
          { label: '7 天', value: 7 },
          { label: '14 天', value: 14 },
          { label: '30 天', value: 30 },
        ]} value={days} onChange={(v) => setDays(v as number)} />
      </div>
      <Tabs items={tabItems} />
    </div>
  );
};

export default Analytics;
