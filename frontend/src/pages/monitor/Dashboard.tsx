import React, { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic } from 'antd';
import { RobotOutlined, ApiOutlined, ToolOutlined, MessageOutlined, ThunderboltOutlined, DollarOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { monitorApi } from '@/api/monitor';
import { analyticsApi } from '@/api/analytics';

const Dashboard: React.FC = () => {
  const [overview, setOverview] = useState<Record<string, unknown>>({});
  const [analyticsOverview, setAnalyticsOverview] = useState<Record<string, unknown>>({});
  const [dailyData, setDailyData] = useState<Record<string, unknown>>({});
  const [statsData, setStatsData] = useState<Record<string, unknown>>({});

  useEffect(() => {
    Promise.all([
      monitorApi.overview(),
      analyticsApi.overview({ days: 7 }),
      analyticsApi.daily({ days: 7 }),
      monitorApi.stats({}),
    ]).then(([r1, r2, r3, r4]) => {
      setOverview(r1.data);
      setAnalyticsOverview(r2.data as Record<string, unknown>);
      setDailyData(r3.data as Record<string, unknown>);
      setStatsData(r4.data as Record<string, unknown>);
    });
  }, []);

  const statsCards = [
    { title: 'Agent 总数', value: overview.agentCount as number, icon: <RobotOutlined />, color: '#f70' },
    { title: '活跃 Agent', value: overview.activeAgentCount as number, icon: <RobotOutlined />, color: '#52c41a' },
    { title: '模型数量', value: overview.modelCount as number, icon: <ApiOutlined />, color: '#722ed1' },
    { title: '工具数量', value: overview.toolCount as number, icon: <ToolOutlined />, color: '#fa8c16' },
    { title: '活跃对话', value: overview.activeConversationCount as number, icon: <MessageOutlined />, color: '#13c2c2' },
    { title: '今日调用', value: overview.todayApiCalls as number, icon: <ThunderboltOutlined />, color: '#eb2f96' },
    { title: '7天调用', value: analyticsOverview.totalCalls as number, icon: <ThunderboltOutlined />, color: '#f70' },
    { title: '7天费用($)', value: analyticsOverview.totalCost as number, precision: 4, icon: <DollarOutlined />, color: '#eb2f96' },
  ];

  const dailyList = (dailyData.daily as Array<{ date: string; callCount: number; tokenCount: number; cost: number }>) || [];
  const callChartOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dailyList.map((d) => d.date.slice(5)) },
    yAxis: { type: 'value', name: '调用次数' },
    series: [{ name: 'API 调用', data: dailyList.map((d) => d.callCount), type: 'line', smooth: true, areaStyle: {}, itemStyle: { color: '#f70' } }],
  };

  const endpointBreakdown = (statsData.endpointBreakdown as Array<[string, number]>) || [];
  const pieOption = {
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      data: endpointBreakdown.map(([name, count]) => ({ name, value: count })),
    }],
  };

  return (
    <div>
      <Row gutter={[16, 16]}>
        {statsCards.map((card) => (
          <Col span={3} key={card.title}>
            <Card>
              <Statistic title={card.title} value={card.value ?? '-'} prefix={<span style={{ color: card.color }}>{card.icon}</span>} />
            </Card>
          </Col>
        ))}
      </Row>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={16}>
          <Card title="API 调用趋势">
            <ReactECharts option={callChartOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="调用分布">
            <ReactECharts option={pieOption} style={{ height: 300 }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
