import React, { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, Tabs, Divider, message } from 'antd';
import {
  UserOutlined,
  LockOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  PhoneOutlined,
  SafetyCertificateOutlined,
  WechatOutlined,
  QqOutlined,
  AlipayCircleOutlined,
} from '@ant-design/icons';
import { useAuth } from '../contexts/AuthContext';

import { rules } from '@/utils/validation';

const Login: React.FC = () => {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [activeTab, setActiveTab] = useState('account');
  const [loading, setLoading] = useState(false);
  const [showPwd, setShowPwd] = useState(false);
  const [smsCountdown, setSmsCountdown] = useState(0);
  const [qrPlatform, setQrPlatform] = useState<'wechat' | 'qq' | 'alipay'>('wechat');
  const timerRef = useRef<ReturnType<typeof setInterval>>();

  // 账号登录
  const handleAccountLogin = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      await login(values.username, values.password);
      message.success('登录成功');
      navigate('/', { replace: true });
    } catch {
      message.error('用户名或密码错误');
    } finally {
      setLoading(false);
    }
  };

  // 手机登录
  const handlePhoneLogin = (values: { phone: string; smsCode: string }) => {
    setLoading(true);
    message.info('手机登录功能开发中，请使用账号登录');
    setLoading(false);
  };

  // 发送验证码
  const sendSms = () => {
    if (smsCountdown > 0) return;
    message.success('验证码已发送（演示）');
    setSmsCountdown(60);
    timerRef.current = setInterval(() => {
      setSmsCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(timerRef.current);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  };

  // 第三方登录
  const socialLogin = (platform: string) => {
    message.info(`${platform} 登录功能开发中`);
  };

  const qrPlatforms = [
    { key: 'wechat' as const, label: '微信', color: '#07C160', icon: <WechatOutlined /> },
    { key: 'qq' as const, label: 'QQ', color: '#12B7F5', icon: <QqOutlined /> },
    { key: 'alipay' as const, label: '支付宝', color: '#1677FF', icon: <AlipayCircleOutlined /> },
  ];

  const activeQr = qrPlatforms.find((p) => p.key === qrPlatform)!;

  return (
    <div className="login-page">
      {/* 主体 */}
      <div className="login-body">
        {/* 左侧插画 */}
        <div className="login-illustration">
          <img
            src="/assets/login_illustration.png"
            alt="MochaAgents 智能体管理平台"
            className="login-illustration-img"
          />
        </div>

        {/* 右侧登录卡片 */}
        <div className="login-card">
          <div className="login-card-header">
            <div className="login-card-logo">
              <div className="login-card-logo-icon">M</div>
            </div>
            <h2 className="login-card-title">欢迎登录</h2>
            <p className="login-card-desc">MochaAgents 智能体管理平台</p>
          </div>

          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            centered
            className="login-tabs"
            items={[
              {
                key: 'account',
                label: '账号登录',
                children: (
                  <Form
                    name="login-account"
                    size="large"
                    onFinish={handleAccountLogin}
                    autoComplete="off"
                  >
                    <Form.Item
                      name="username"
                      rules={rules.required('请输入用户名')}
                      style={{ marginBottom: 24 }}
                    >
                      <Input
                        prefix={<UserOutlined style={{ color: '#bfbfbf' }} />}
                        placeholder="请输入用户名"
                        className="login-input"
                      />
                    </Form.Item>

                    <Form.Item
                      name="password"
                      rules={rules.password()}
                      style={{ marginBottom: 0 }}
                    >
                      <Input
                        prefix={<LockOutlined style={{ color: '#bfbfbf' }} />}
                        type={showPwd ? 'text' : 'password'}
                        placeholder="请输入密码"
                        className="login-input"
                        suffix={
                          <span
                            onClick={() => setShowPwd(!showPwd)}
                            style={{ cursor: 'pointer', color: '#bfbfbf', display: 'flex', alignItems: 'center' }}
                          >
                            {showPwd ? <EyeOutlined /> : <EyeInvisibleOutlined />}
                          </span>
                        }
                      />
                    </Form.Item>

                    <Form.Item style={{ marginTop: 32, marginBottom: 0 }}>
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={loading}
                        block
                        className="login-btn"
                      >
                        登 录
                      </Button>
                    </Form.Item>
                  </Form>
                ),
              },
              {
                key: 'phone',
                label: '手机登录',
                children: (
                  <Form
                    name="login-phone"
                    size="large"
                    onFinish={handlePhoneLogin}
                    autoComplete="off"
                  >
                    <Form.Item
                      name="phone"
                      rules={rules.phone()}
                      style={{ marginBottom: 24 }}
                    >
                      <Input
                        prefix={<PhoneOutlined style={{ color: '#bfbfbf' }} />}
                        placeholder="请输入手机号"
                        className="login-input"
                        maxLength={11}
                      />
                    </Form.Item>

                    <Form.Item
                      name="smsCode"
                      rules={rules.required('请输入验证码')}
                      style={{ marginBottom: 0 }}
                    >
                      <Input
                        prefix={<SafetyCertificateOutlined style={{ color: '#bfbfbf' }} />}
                        placeholder="请输入验证码"
                        className="login-input"
                        maxLength={6}
                        suffix={
                          <Button
                            type="link"
                            size="small"
                            disabled={smsCountdown > 0}
                            onClick={sendSms}
                            style={{ padding: '0 4px', fontSize: 13, minWidth: 80 }}
                          >
                            {smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码'}
                          </Button>
                        }
                      />
                    </Form.Item>

                    <Form.Item style={{ marginTop: 32, marginBottom: 0 }}>
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={loading}
                        block
                        className="login-btn"
                      >
                        登 录
                      </Button>
                    </Form.Item>
                  </Form>
                ),
              },
              {
                key: 'qrcode',
                label: '扫码登录',
                children: (
                  <div className="login-qrcode-panel">
                    {/* 平台切换 */}
                    <div className="login-qrcode-tabs">
                      {qrPlatforms.map((p) => (
                        <span
                          key={p.key}
                          className={`login-qrcode-tab${qrPlatform === p.key ? ' active' : ''}`}
                          onClick={() => setQrPlatform(p.key)}
                        >
                          {p.label}
                        </span>
                      ))}
                    </div>

                    {/* 二维码占位 */}
                    <div className="login-qrcode-box">
                      <div className="login-qrcode-placeholder">
                        <div className="login-qrcode-icon" style={{ background: activeQr.color }}>
                          {activeQr.icon}
                        </div>
                        <p className="login-qrcode-hint">
                          请使用{activeQr.label}扫描二维码
                        </p>
                        <div className="login-qrcode-mock">
                          {[...Array(5)].map((_, row) => (
                            <div key={row} className="login-qrcode-row">
                              {[...Array(5)].map((_, col) => (
                                <span
                                  key={col}
                                  className={`login-qrcode-dot${(row + col) % 3 === 0 ? ' filled' : ''}`}
                                />
                              ))}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>

                    <p className="login-qrcode-tip">扫码成功后自动登录</p>
                  </div>
                ),
              },
            ]}
          />

          {/* 第三方登录 */}
          <Divider plain style={{ fontSize: 13, color: '#999' }}>其他登录方式</Divider>
          <div className="login-social">
            <div className="login-social-icon" onClick={() => socialLogin('微信')} title="微信登录">
              <WechatOutlined style={{ fontSize: 22, color: '#07C160' }} />
            </div>
            <div className="login-social-icon" onClick={() => socialLogin('QQ')} title="QQ 登录">
              <QqOutlined style={{ fontSize: 22, color: '#12B7F5' }} />
            </div>
            <div className="login-social-icon" onClick={() => socialLogin('支付宝')} title="支付宝登录">
              <AlipayCircleOutlined style={{ fontSize: 22, color: '#1677FF' }} />
            </div>
          </div>
        </div>
      </div>

      {/* 底部版权 */}
      <div className="login-footer">
        Copyright © 2026 MochaAgents. All rights reserved.
      </div>
    </div>
  );
};

export default Login;
