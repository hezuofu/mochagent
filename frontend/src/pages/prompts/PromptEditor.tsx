import React, { useState, useEffect, useMemo } from 'react';
import { Button, Space, Input, Select, Card, message, Spin, Drawer, List, Tag, Popconfirm } from 'antd';
import { SaveOutlined, SendOutlined, ThunderboltOutlined, ArrowLeftOutlined, HistoryOutlined, EyeOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import { promptApi, categoryApi, PromptVersion } from '@/api/prompt';
import { validate } from '@/utils/validation';

const PromptEditor: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id;
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [content, setContent] = useState('');
  const [category, setCategory] = useState('');
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);

  // Variables
  const [varValues, setVarValues] = useState<Record<string, string>>({});

  // Render preview
  const [rendered, setRendered] = useState('');
  const [rendering, setRendering] = useState(false);

  // Test result
  const [testResult, setTestResult] = useState<Record<string, unknown> | null>(null);

  // Version drawer
  const [versionDrawer, setVersionDrawer] = useState(false);
  const [versions, setVersions] = useState<PromptVersion[]>([]);

  // Categories
  const [categories, setCategories] = useState<{ label: string; value: string }[]>([]);

  // Extract variables from content
  const variables = useMemo(() => {
    const matches = content.match(/\{\{(\w+)\}\}/g) || [];
    return [...new Set(matches.map((m) => m.slice(2, -2)))];
  }, [content]);

  useEffect(() => {
    categoryApi.list().then((res) => {
      setCategories((res.data || []).map((c: { name: string }) => ({ label: c.name, value: c.name })));
    });
  }, []);

  useEffect(() => {
    if (!isNew) {
      setLoading(true);
      promptApi.getById(id!).then((res) => {
        const p = res.data;
        setName(p.name);
        setDescription(p.description || '');
        setContent(p.content);
        setCategory(p.category || '');
      }).finally(() => setLoading(false));
    }
  }, [id]);

  // Reset var values when variables change
  useEffect(() => {
    const init: Record<string, string> = {};
    variables.forEach((v) => { if (!(v in varValues)) init[v] = ''; });
    if (Object.keys(init).length > 0) setVarValues((prev) => ({ ...prev, ...init }));
  }, [variables]);

  const handleSave = async () => {
    if (!validate.notEmpty(name)) { message.warning('请输入提示词名称'); return; }
    setSaving(true);
    try {
      if (isNew) {
        const res = await promptApi.create({ name, description, content, category });
        message.success('创建成功');
        navigate(`/prompts/${res.data.id}/edit`, { replace: true });
      } else {
        await promptApi.update(id!, { name, description, content, category });
        message.success('保存成功');
      }
    } finally { setSaving(false); }
  };

  const handlePublish = async () => {
    if (isNew) { await handleSave(); return; }
    await handleSave();
    await promptApi.publish(id!);
    message.success('已发布');
  };

  const handleRender = () => {
    if (isNew) { setRendered(renderLocal()); return; }
    setRendering(true);
    promptApi.render(id!, varValues).then((res) => {
      setRendered(res.data.rendered);
    }).finally(() => setRendering(false));
  };

  const renderLocal = () => {
    return content.replace(/\{\{(\w+)\}\}/g, (_, key) => varValues[key] || `{{${key}}}`);
  };

  const handleTest = () => {
    if (isNew) return;
    promptApi.test(id!, varValues).then((res) => {
      setTestResult(res.data);
      message.success(`测试完成: ${res.data.latencyMs}ms`);
    });
  };

  const handleOptimize = () => {
    if (isNew) return;
    promptApi.optimize(id!).then((res) => {
      message.success('优化建议已生成');
      setRendered(res.data.optimized);
    });
  };

  const handleLoadVersions = () => {
    if (isNew) return;
    promptApi.getVersions(id!).then((res) => { setVersions(res.data); setVersionDrawer(true); });
  };

  const handleRollback = (version: number) => {
    promptApi.rollback(id!, version).then(() => {
      message.success('已回滚');
      setVersionDrawer(false);
      promptApi.getById(id!).then((res) => setContent(res.data.content));
    });
  };

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 180px)' }}>
        {/* Toolbar */}
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/prompts')}>返回</Button>
            <Input style={{ width: 200 }} placeholder="提示词名称" value={name} onChange={(e) => setName(e.target.value)} />
            <Select style={{ width: 140 }} placeholder="分类" value={category || undefined} onChange={setCategory} options={categories} />
            <Input style={{ width: 250 }} placeholder="描述（可选）" value={description} onChange={(e) => setDescription(e.target.value)} />
          </Space>
          <Space>
            <Button icon={<HistoryOutlined />} onClick={handleLoadVersions} disabled={isNew}>版本</Button>
            <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>保存</Button>
            <Button icon={<SendOutlined />} onClick={handlePublish} disabled={isNew}>发布</Button>
          </Space>
        </div>

        {/* Main area */}
        <div style={{ display: 'flex', flex: 1, gap: 12 }}>
          {/* Editor */}
          <div style={{ flex: 1, border: '1px solid #d9d9d9', borderRadius: 8, overflow: 'hidden' }}>
            <div style={{ background: '#fafafa', padding: '4px 12px', fontSize: 12, color: '#8c8c8c', borderBottom: '1px solid #d9d9d9' }}>
              模板内容 — 使用 {'{{variable}}'} 定义变量
            </div>
            <Editor
              height="100%"
              defaultLanguage="markdown"
              value={content}
              onChange={(v) => setContent(v || '')}
              theme="vs"
              options={{ minimap: { enabled: false }, fontSize: 13, wordWrap: 'on', lineNumbers: 'on' }}
            />
          </div>

          {/* Right panel */}
          <div style={{ width: 300, display: 'flex', flexDirection: 'column', gap: 12 }}>
            {/* Variables */}
            <Card size="small" title="变量" extra={<Button size="small" icon={<EyeOutlined />} onClick={handleRender} loading={rendering}>渲染</Button>}>
              {variables.length === 0 ? (
                <div style={{ color: '#bfbfbf', fontSize: 12 }}>模板中未定义变量</div>
              ) : (
                variables.map((v) => (
                  <div key={v} style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 12, color: '#1677ff', marginBottom: 2 }}>{`{{${v}}}`}</div>
                    <Input size="small" value={varValues[v] || ''}
                      onChange={(e) => setVarValues((prev) => ({ ...prev, [v]: e.target.value }))}
                      placeholder="输入变量值..." />
                  </div>
                ))
              )}
            </Card>

            {/* Actions */}
            <Space direction="vertical" style={{ width: '100%' }}>
              <Button block icon={<ThunderboltOutlined />} onClick={handleTest} disabled={isNew}>
                LLM 测试
              </Button>
              <Button block onClick={handleOptimize} disabled={isNew}>
                AI 优化
              </Button>
            </Space>

            {/* Render preview */}
            {rendered && (
              <Card size="small" title="渲染预览">
                <div style={{ whiteSpace: 'pre-wrap', fontSize: 13, maxHeight: 200, overflow: 'auto', background: '#fafafa', padding: 8, borderRadius: 4 }}>
                  {rendered}
                </div>
              </Card>
            )}

            {/* Test result */}
            {testResult && (
              <Card size="small" title="测试结果">
                <div style={{ fontSize: 13 }}>
                  <div>延迟: {testResult.latencyMs as number}ms</div>
                  <div>Tokens: {testResult.tokensUsed as number}</div>
                  <div style={{ marginTop: 8, background: '#f6ffed', padding: 8, borderRadius: 4, whiteSpace: 'pre-wrap' }}>
                    {testResult.response as string}
                  </div>
                </div>
              </Card>
            )}
          </div>
        </div>

        {/* Version Drawer */}
        <Drawer title="版本历史" open={versionDrawer} onClose={() => setVersionDrawer(false)} width={400}>
          <List
            dataSource={versions}
            renderItem={(v) => (
              <List.Item actions={[
                <Button size="small" onClick={() => handleRollback(v.version)}>回滚</Button>,
              ]}>
                <List.Item.Meta
                  title={<Tag color="blue">v{v.version}</Tag>}
                  description={`${v.changelog} — ${v.createTime}`}
                />
              </List.Item>
            )}
          />
        </Drawer>
      </div>
    </Spin>
  );
};

export default PromptEditor;
