import React, { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Tabs, message, Spin } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { settingsApi } from '@/api/settings';
import { modelApi } from '@/api/model';

const SettingsPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [models, setModels] = useState<{ name: string }[]>([]);
  const [form] = Form.useForm();

  useEffect(() => {
    setLoading(true);
    Promise.all([
      settingsApi.getAll(),
      modelApi.list({ page: 1, size: 100 }),
    ]).then(([res1, res2]) => {
      const data = res1.data as Record<string, string>;
      setSettings(data);
      form.setFieldsValue({
        apiKey: data['api.key'] || '',
        defaultModel: data['default.model'] || '',
        defaultProvider: data['default.provider'] || '',
        systemName: data['system.name'] || 'MochaAgents',
        maxAgents: data['max.agents'] || '50',
        maxTokensPerCall: data['max.tokensPerCall'] || '4096',
      });
      setModels(res2.data.list || []);
    }).finally(() => setLoading(false));
  }, []);

  const handleSave = () => {
    form.validateFields().then((values) => {
      setSaving(true);
      const payload: Record<string, string> = {
        'api.key': values.apiKey || '',
        'default.model': values.defaultModel || '',
        'default.provider': values.defaultProvider || '',
        'system.name': values.systemName || 'MochaAgents',
        'max.agents': String(values.maxAgents || '50'),
        'max.tokensPerCall': String(values.maxTokensPerCall || '4096'),
      };
      settingsApi.update(payload).then(() => {
        message.success('设置已保存');
      }).finally(() => setSaving(false));
    });
  };

  const apiTabItems = [
    {
      key: 'api',
      label: 'API 配置',
      children: (
        <Spin spinning={loading}>
          <Form layout="vertical" form={form} style={{ maxWidth: 500 }}>
            <Form.Item name="apiKey" label="API Key">
              <Input.Password placeholder="sk-..." />
            </Form.Item>
            <Form.Item name="defaultProvider" label="默认提供商">
              <Input placeholder="OpenAI / DeepSeek / Anthropic" />
            </Form.Item>
            <Form.Item name="defaultModel" label="默认模型">
              <Input placeholder="gpt-4 / deepseek-chat" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
                保存配置
              </Button>
            </Form.Item>
          </Form>
        </Spin>
      ),
    },
    {
      key: 'system',
      label: '系统参数',
      children: (
        <Spin spinning={loading}>
          <Form layout="vertical" form={form} style={{ maxWidth: 500 }}>
            <Form.Item name="systemName" label="系统名称">
              <Input placeholder="MochaAgents" />
            </Form.Item>
            <Form.Item name="maxAgents" label="最大 Agent 数">
              <Input type="number" placeholder="50" />
            </Form.Item>
            <Form.Item name="maxTokensPerCall" label="单次调用最大 Token">
              <Input type="number" placeholder="4096" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
                保存配置
              </Button>
            </Form.Item>
          </Form>
        </Spin>
      ),
    },
  ];

  return (
    <div>
      <Card title="系统设置">
        <Tabs items={apiTabItems} />
      </Card>
    </div>
  );
};

export default SettingsPage;
