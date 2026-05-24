import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Input, Button, Space, Avatar, Spin, Typography } from 'antd';
import { SendOutlined, UserOutlined, RobotOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { conversationApi, Message } from '@/api/conversation';

const { Text } = Typography;

const ChatPanel: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [conversationTitle, setConversationTitle] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (id) {
      conversationApi.getById(id).then((res) => setConversationTitle(res.data.title));
      conversationApi.getMessages(id, { page: 1, size: 100 }).then((res) => setMessages(res.data.list));
    }
  }, [id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = async () => {
    if (!input.trim() || !id) return;
    const userInput = input;
    setInput('');
    setSending(true);

    // Optimistic user message
    const userMsg: Message = {
      id: Date.now().toString(),
      conversationId: id,
      role: 'USER',
      content: userInput,
      createTime: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMsg]);

    try {
      const res = await conversationApi.sendMessage(id, userInput);
      const assistantMsg: Message = {
        id: res.data.id,
        conversationId: id,
        role: res.data.role,
        content: res.data.content,
        createTime: res.data.createTime,
      };
      setMessages((prev) => [...prev, assistantMsg]);
    } catch {
      // ignore
    } finally {
      setSending(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 140px)' }}>
      <div style={{ padding: '12px 0', borderBottom: '1px solid #f0f0f0', marginBottom: 16 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/conversations')}>返回</Button>
          <Text strong style={{ fontSize: 16 }}>{conversationTitle}</Text>
        </Space>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 16px' }}>
        {messages.map((msg) => (
          <div key={msg.id} style={{ display: 'flex', marginBottom: 16, justifyContent: msg.role === 'USER' ? 'flex-end' : 'flex-start' }}>
            {msg.role === 'ASSISTANT' && <Avatar icon={<RobotOutlined />} style={{ marginRight: 8, background: '#f70' }} />}
            <div style={{
              maxWidth: '70%', padding: '10px 16px', borderRadius: 8,
              background: msg.role === 'USER' ? '#f70' : '#f5f5f5',
              color: msg.role === 'USER' ? '#fff' : '#333',
            }}>
              {msg.content}
            </div>
            {msg.role === 'USER' && <Avatar icon={<UserOutlined />} style={{ marginLeft: 8 }} />}
          </div>
        ))}
        {sending && (
          <div style={{ display: 'flex', marginBottom: 16 }}>
            <Avatar icon={<RobotOutlined />} style={{ marginRight: 8, background: '#f70' }} />
            <div style={{ padding: '10px 16px', borderRadius: 8, background: '#f5f5f5' }}>
              <Spin size="small" /> 思考中...
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>
      <div style={{ padding: '16px 0 0', borderTop: '1px solid #f0f0f0' }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); handleSend(); } }}
            placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
            autoSize={{ minRows: 1, maxRows: 4 }}
            disabled={sending}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={sending}>发送</Button>
        </Space.Compact>
      </div>
    </div>
  );
};

export default ChatPanel;
