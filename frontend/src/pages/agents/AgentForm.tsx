import React, { useEffect, useState } from 'react';
import { Form, Input, Button, Select, Card, message, Spin, Space } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { agentApi } from '@/api/agent';
import { rules } from '@/utils/validation';

const AgentForm: React.FC = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const isEdit = !!id;

  useEffect(() => {
    if (id) {
      setLoading(true);
      agentApi.getById(id).then((res) => form.setFieldsValue(res.data)).finally(() => setLoading(false));
    }
  }, [id]);

  const onFinish = (values: Record<string, unknown>) => {
    const api = isEdit ? agentApi.update(id!, values) : agentApi.create(values);
    api.then(() => { message.success(isEdit ? '更新成功' : '创建成功'); navigate('/agents'); });
  };

  return (
    <Card title={isEdit ? '编辑 Agent' : '创建 Agent'}>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical" onFinish={onFinish} style={{ maxWidth: 600 }}>
          <Form.Item name="name" label="名称" rules={rules.name(30)}>
            <Input placeholder="Agent 名称" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="描述" />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={rules.required('请选择类型')}>
            <Select placeholder="选择类型" options={[
              { label: 'CodeAgent', value: 'CodeAgent' },
              { label: 'ToolCallingAgent', value: 'ToolCallingAgent' },
              { label: 'MultiStepAgent', value: 'MultiStepAgent' },
            ]} />
          </Form.Item>
          <Form.Item name="version" label="版本" rules={rules.semver()}>
            <Input placeholder="1.0.0" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">{isEdit ? '更新' : '创建'}</Button>
              <Button onClick={() => navigate('/agents')}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Spin>
    </Card>
  );
};

export default AgentForm;
