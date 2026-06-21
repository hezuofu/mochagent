import React, { useEffect, useState } from 'react';
import { Form, Input, Button, Card, message, Spin, Space, Slider, Select, Row, Col, Divider, InputNumber } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { digitalHumanApi, DigitalHuman } from '@/api/digitalHuman';
import { rules } from '@/utils/validation';

const GEOMETRY_OPTIONS = [
  { label: '球体 (ORB)', value: 'ORB' },
  { label: '粒子 (PARTICLE)', value: 'PARTICLE' },
  { label: '波 (WAVE)', value: 'WAVE' },
  { label: '网格 (GRID)', value: 'GRID' },
  { label: '分形 (FRACTAL)', value: 'FRACTAL' },
];

/** 五大人格简写 */
const BIG_FIVE: { key: string; label: string }[] = [
  { key: 'openness', label: '开放性' },
  { key: 'conscientiousness', label: '尽责性' },
  { key: 'extraversion', label: '外向性' },
  { key: 'agreeableness', label: '宜人性' },
  { key: 'neuroticism', label: '神经质' },
];

const BEHAVIOR_PARAMS: { key: string; label: string }[] = [
  { key: 'creativity', label: '创造力' },
  { key: 'precision', label: '精确度' },
  { key: 'verbosity', label: '话量' },
  { key: 'curiosity', label: '好奇心' },
];

/** 性格雷达图 SVG 组件 */
const BigFiveRadar: React.FC<{ values: Record<string, number> }> = ({ values }) => {
  const size = 160, cx = 80, cy = 80, r = 60;
  const keys = ['openness', 'conscientiousness', 'extraversion', 'agreeableness', 'neuroticism'];
  const labels = ['开放性', '尽责性', '外向性', '宜人性', '神经质'];
  const points = keys.map((k, i) => {
    const angle = (Math.PI * 2 * i) / 5 - Math.PI / 2;
    const val = values[k] ?? 0.5;
    return { x: cx + r * val * Math.cos(angle), y: cy + r * val * Math.sin(angle), label: labels[i], angle };
  });
  const polygonPoints = points.map((p) => `${p.x},${p.y}`).join(' ');
  const gridLevels = [0.25, 0.5, 0.75, 1.0];

  return (
    <svg width={size} height={size} style={{ background: '#fafafa', borderRadius: 8 }}>
      {gridLevels.map((lv) => {
        const gp = keys.map((_, i) => {
          const a = (Math.PI * 2 * i) / 5 - Math.PI / 2;
          return `${cx + r * lv * Math.cos(a)},${cy + r * lv * Math.sin(a)}`;
        }).join(' ');
        return <polygon key={lv} points={gp} fill="none" stroke="#e8e8e8" strokeWidth={0.5} />;
      })}
      {keys.map((_, i) => {
        const a = (Math.PI * 2 * i) / 5 - Math.PI / 2;
        return <line key={`axis-${i}`} x1={cx} y1={cy} x2={cx + r * Math.cos(a)} y2={cy + r * Math.sin(a)} stroke="#e8e8e8" strokeWidth={0.5} />;
      })}
      <polygon points={polygonPoints} fill="rgba(255,119,0,0.2)" stroke="#f70" strokeWidth={1.5} />
      {points.map((p, i) => (
        <g key={`pt-${i}`}>
          <circle cx={p.x} cy={p.y} r={3} fill="#f70" />
          <text x={cx + (r + 14) * Math.cos(p.angle)} y={cy + (r + 14) * Math.sin(p.angle)}
            textAnchor="middle" dominantBaseline="middle" fontSize={9} fill="#666">{p.label}</text>
        </g>
      ))}
    </svg>
  );
};

/** 视觉预览组件 */
const VisualPreview: React.FC<{ color: string; secondary: string; geometry: string }> = ({ color, secondary, geometry }) => {
  const borderRadius = geometry === 'ORB' ? '50%' : geometry === 'WAVE' ? '0' : '8px';
  return (
    <div style={{
      width: 80, height: 80, borderRadius,
      background: `linear-gradient(135deg, ${color || '#f70'}, ${secondary || '#ff8c33'})`,
      boxShadow: `0 0 16px ${color || '#f70'}60`,
      transition: 'all 0.3s',
    }} />
  );
};

const DigitalHumanForm: React.FC = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const isEdit = !!id;

  const colorPrimary = Form.useWatch('colorPrimary', form) || '#f70';
  const colorSecondary = Form.useWatch('colorSecondary', form) || '#ff8c33';
  const geometryType = Form.useWatch('geometryType', form) || 'ORB';

  const bigFiveValues: Record<string, number> = {};
  BIG_FIVE.forEach((f) => { bigFiveValues[f.key] = form.getFieldValue(f.key) ?? 0.5; });

  useEffect(() => {
    if (id) {
      setLoading(true);
      digitalHumanApi.getById(id).then((res) => form.setFieldsValue(res.data)).finally(() => setLoading(false));
    }
  }, [id]);

  const onFinish = (values: Record<string, unknown>) => {
    const api = isEdit ? digitalHumanApi.update(id!, values) : digitalHumanApi.create(values);
    api.then(() => { message.success(isEdit ? '更新成功' : '创建成功'); navigate('/digital-humans.html'); });
  };

  return (
    <Card title={isEdit ? '编辑数字人' : '创建数字人'}>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical" onFinish={onFinish} initialValues={{
          openness: 0.5, conscientiousness: 0.5, extraversion: 0.5, agreeableness: 0.5, neuroticism: 0.5,
          creativity: 0.5, precision: 0.5, verbosity: 0.5, curiosity: 0.5,
          geometryType: 'ORB', colorPrimary: '#f70', colorSecondary: '#ff8c33',
          pitch: 0.5, speed: 0.5, temperature: 0.7,
        }}>
          {/* 基本信息 */}
          <Divider orientation="left">基本信息</Divider>
          <Row gutter={24}>
            <Col span={12}>
              <Form.Item name="name" label="名称" rules={rules.name(30)}>
                <Input placeholder="数字人名称" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="数字人的能力与定位描述" />
          </Form.Item>

          {/* 性格向量 */}
          <Divider orientation="left">
            性格向量
            <span style={{ fontSize: 12, color: '#999', marginLeft: 8 }}>BigFive · 0.00-1.00</span>
          </Divider>
          <Row gutter={24}>
            <Col span={14}>
              {BIG_FIVE.map((f) => (
                <Form.Item key={f.key} name={f.key} label={f.label} style={{ marginBottom: 8 }}>
                  <Slider min={0} max={1} step={0.01} tooltip={{ formatter: (v) => v?.toFixed(2) }} />
                </Form.Item>
              ))}
            </Col>
            <Col span={10} style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <BigFiveRadar values={bigFiveValues} />
            </Col>
          </Row>

          {/* 行为参数 */}
          <Divider orientation="left">
            行为参数
            <span style={{ fontSize: 12, color: '#999', marginLeft: 8 }}>0.00-1.00</span>
          </Divider>
          <Row gutter={24}>
            {BEHAVIOR_PARAMS.map((b) => (
              <Col span={12} key={b.key}>
                <Form.Item name={b.key} label={b.label} style={{ marginBottom: 8 }}>
                  <Slider min={0} max={1} step={0.01} tooltip={{ formatter: (v) => v?.toFixed(2) }} />
                </Form.Item>
              </Col>
            ))}
          </Row>

          {/* 视觉 DNA */}
          <Divider orientation="left">视觉 DNA</Divider>
          <Row gutter={24} align="middle">
            <Col span={6}>
              <Form.Item name="colorPrimary" label="主色">
                <Input type="color" style={{ width: 60, height: 36, padding: 2 }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="colorSecondary" label="辅色">
                <Input type="color" style={{ width: 60, height: 36, padding: 2 }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="geometryType" label="几何形态" rules={rules.required('请选择形态')}>
                <Select options={GEOMETRY_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={6} style={{ display: 'flex', justifyContent: 'center', paddingTop: 8 }}>
              <VisualPreview color={colorPrimary} secondary={colorSecondary} geometry={geometryType} />
            </Col>
          </Row>

          {/* 音色参数 */}
          <Divider orientation="left">音色参数</Divider>
          <Row gutter={24}>
            <Col span={8}>
              <Form.Item name="voiceId" label="音色引擎">
                <Input placeholder="音色 ID" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="pitch" label="音高">
                <Slider min={0} max={1} step={0.01} tooltip={{ formatter: (v) => v?.toFixed(2) }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="speed" label="语速">
                <Slider min={0} max={1} step={0.01} tooltip={{ formatter: (v) => v?.toFixed(2) }} />
              </Form.Item>
            </Col>
          </Row>

          {/* 运行时设置 */}
          <Divider orientation="left">运行时设置</Divider>
          <Row gutter={24}>
            <Col span={12}>
              <Form.Item name="temperature" label="Temperature">
                <Slider min={0} max={2} step={0.01} tooltip={{ formatter: (v) => v?.toFixed(2) }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="agentId" label="关联 Agent">
                <Input placeholder="Agent ID（可选）" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">{isEdit ? '更新' : '创建'}</Button>
              <Button onClick={() => navigate('/digital-humans.html')}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Spin>
    </Card>
  );
};

export default DigitalHumanForm;
