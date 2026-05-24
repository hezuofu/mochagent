import React, { useState, useEffect, useMemo } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Breadcrumb, Dropdown, Avatar, Badge } from 'antd';
import type { ItemType } from 'antd/es/menu/interface';
import {
  DashboardOutlined,
  RobotOutlined,
  ApiOutlined,
  ToolOutlined,
  MessageOutlined,
  NodeIndexOutlined,
  FileTextOutlined,
  BarChartOutlined,
  SettingOutlined,
  BellOutlined,
  UserOutlined,
  LogoutOutlined,
  KeyOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  LockOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  DatabaseOutlined,
  AppstoreOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { menuApi, MenuItem } from '../api/menu';
import { useAuth } from '../contexts/AuthContext';

const { Header, Sider, Content } = Layout;

// 图标名 → 组件映射
const iconMap: Record<string, React.ReactNode> = {
  AppstoreOutlined: <AppstoreOutlined />,
  DashboardOutlined: <DashboardOutlined />,
  RobotOutlined: <RobotOutlined />,
  ApiOutlined: <ApiOutlined />,
  ToolOutlined: <ToolOutlined />,
  DatabaseOutlined: <DatabaseOutlined />,
  MessageOutlined: <MessageOutlined />,
  FileTextOutlined: <FileTextOutlined />,
  NodeIndexOutlined: <NodeIndexOutlined />,
  SettingOutlined: <SettingOutlined />,
  TeamOutlined: <TeamOutlined />,
  SafetyCertificateOutlined: <SafetyCertificateOutlined />,
  LockOutlined: <LockOutlined />,
  BarChartOutlined: <BarChartOutlined />,
  ExperimentOutlined: <ExperimentOutlined />,
};

/** 将后端 MenuVO 转为 Ant Design Menu ItemType */
function buildMenuItems(menus: MenuItem[]): ItemType[] {
  return menus.map((m) => {
    const item: ItemType = {
      key: m.key,
      label: m.label,
      icon: m.icon ? iconMap[m.icon] : undefined,
    };
    if (m.children && m.children.length > 0) {
      (item as any).children = buildMenuItems(m.children);
    }
    return item;
  });
}

/** 扁平化菜单叶子节点 */
function flattenMenus(menus: MenuItem[]): MenuItem[] {
  return menus.flatMap((m) =>
    m.children && m.children.length > 0 ? flattenMenus(m.children) : [m]
  );
}

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const [collapsed, setCollapsed] = useState(false);

  // ── 菜单数据 ──
  const [rawMenus, setRawMenus] = useState<MenuItem[]>([]);
  const [menuLoading, setMenuLoading] = useState(true);

  useEffect(() => {
    menuApi.fetchMenus().then((data) => {
      setRawMenus(data);
      setMenuLoading(false);
    }).catch(() => setMenuLoading(false));
  }, []);

  const antdMenuItems = useMemo(() => buildMenuItems(rawMenus), [rawMenus]);
  const flatMenuItems = useMemo(() => flattenMenus(rawMenus), [rawMenus]);

  const selectedKey = (() => {
    const firstSeg = (location.pathname.split('/').filter(Boolean)[0] || '').replace(/\.html$/, '');
    return firstSeg ? `/${firstSeg}.html` : '/';
  })();

  // 自动展开包含当前路由的分组
  const [openKeys, setOpenKeys] = useState<string[]>([]);

  useEffect(() => {
    const group = rawMenus.find((g) =>
      g.children && g.children.some((c) => c.key === selectedKey)
    );
    if (group) {
      setOpenKeys((prev) => prev.includes(group.key) ? prev : [group.key]);
    }
  }, [selectedKey, rawMenus]);

  // 面包屑路径（strip .html 后处理，拼接时加回 .html）
  const pathParts = location.pathname.split('/').filter(Boolean).map((p) => p.replace(/\.html$/, ''));
  const breadcrumbItems = [
    { title: '首页', path: '/' },
    ...pathParts.map((part, idx) => {
      const path = '/' + pathParts.slice(0, idx + 1).join('/') + '.html';
      const menuItem = flatMenuItems.find((m) => m.key === path);
      if (menuItem) return { title: menuItem.label, path };
      // 子路由映射
      const subMap: Record<string, string> = {
        new: '新建', edit: '编辑', analytics: '用量分析', settings: '系统设置',
        conversations: '对话管理', workflows: '工作流管理', prompts: '提示词管理',
        agents: '智能体管理', models: '模型管理', tools: '工具管理',
        'digital-humans': '数字人管理',
        'knowledge-bases': '知识库管理',
        users: '用户管理', roles: '角色管理', permissions: '权限管理',
      };
      return { title: subMap[part] || part, path };
    }),
  ];

  // 用户下拉菜单
  const userMenuItems = [
    { key: 'profile', icon: <UserOutlined />, label: '个人信息' },
    { key: 'password', icon: <KeyOutlined />, label: '修改密码' },
    { type: 'divider' as const },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
  ];

  return (
    <Layout style={{ height: '100vh' }}>
      {/* ── 暗色侧栏 ── */}
      <Sider
        width={220}
        collapsible
        collapsed={collapsed}
        trigger={null}
        style={{
          background: '#2d3039',
          boxShadow: '0 1px 4px rgba(0, 21, 41, 0.08)',
          overflow: 'hidden',
        }}
        breakpoint="lg"
        collapsedWidth={64}
        onBreakpoint={(broken) => setCollapsed(broken)}
      >
        {/* Logo 区域 */}
        <div
          className="sidebar-logo-area"
          style={{
            height: 60,
            display: 'flex',
            alignItems: 'center',
            paddingLeft: 20,
            gap: 10,
          }}
        >
          <div
            style={{
              width: 36,
              height: 36,
              background: '#f70',
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
              fontSize: 18,
              fontWeight: 'bold',
              flexShrink: 0,
            }}
          >
            M
          </div>
          <span
            className="sidebar-title-text"
            style={{
              color: '#fff',
              fontSize: 18,
              fontWeight: 'bold',
              whiteSpace: 'nowrap',
            }}
          >
            MochaAgents
          </span>
        </div>

        {/* 菜单 */}
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          openKeys={collapsed ? [] : openKeys}
          onOpenChange={(keys) => setOpenKeys(keys)}
          items={menuLoading ? [] : antdMenuItems}
          onClick={({ key }) => { if (key.startsWith('/')) navigate(key); }}
          style={{
            background: 'transparent',
            borderRight: 0,
            marginTop: 8,
            flex: 1,
          }}
        />

        {/* ── Docker 停靠区 ── */}
        <div
          className="sidebar-docker"
          style={{
            height: 48,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderTop: '1px solid rgba(255,255,255,0.08)',
            flexShrink: 0,
            cursor: 'pointer',
            color: '#a4a5a7',
            transition: 'color 0.2s, background 0.2s',
          }}
          onClick={() => setCollapsed(!collapsed)}
          onMouseEnter={(e) => { e.currentTarget.style.color = '#fff'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.color = '#a4a5a7'; e.currentTarget.style.background = 'transparent'; }}
        >
          {collapsed ? <MenuUnfoldOutlined style={{ fontSize: 18 }} /> : <MenuFoldOutlined style={{ fontSize: 18 }} />}
        </div>
      </Sider>

      {/* ── 右侧主体 ── */}
      <Layout style={{ background: '#f5f8f9' }}>
        {/* ── 白色顶栏 ── */}
        <Header
          style={{
            background: '#fff',
            height: 60,
            lineHeight: '60px',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            boxShadow: '0 2px 10px 1px rgba(65, 64, 133, 0.1)',
            zIndex: 1,
          }}
        >
          {/* 系统名称 — OrangeForms 风格 */}
          <span
            style={{
              fontSize: 18,
              fontWeight: 'bold',
              color: '#434344',
              marginRight: 24,
              whiteSpace: 'nowrap',
            }}
          >
            MochaAgents
          </span>

          {/* 面包屑 */}
          <Breadcrumb
            style={{ flex: 1 }}
            items={breadcrumbItems.map((item) => ({
              title: item.path === location.pathname
                ? item.title
                : <a onClick={() => navigate(item.path)}>{item.title}</a>,
            }))}
          />

          {/* 右侧用户区 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            {/* 消息铃铛 */}
            <Badge dot offset={[-2, 4]}>
              <BellOutlined style={{ fontSize: 16, color: '#333', cursor: 'pointer' }} />
            </Badge>

            {/* 分隔线 */}
            <span
              style={{
                display: 'inline-block',
                width: 1,
                height: 24,
                background: '#e8e8e8',
              }}
            />

            {/* 用户头像 + 下拉 */}
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: ({ key }) => {
                  if (key === 'logout') {
                    logout();
                    navigate('/login.html', { replace: true });
                  }
                },
              }}
              trigger={['click']}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  cursor: 'pointer',
                  gap: 8,
                  color: '#909399',
                  fontSize: 14,
                }}
              >
                <Avatar size={30} icon={<UserOutlined />} style={{ background: '#c0c4cc' }} />
                <span>{user?.nickname || user?.username || '管理员'}</span>
              </div>
            </Dropdown>
          </div>
        </Header>

        {/* ── 内容区 ── */}
        <Content
          style={{
            margin: 16,
            padding: 24,
            background: '#fff',
            borderRadius: 4,
            overflow: 'auto',
            minHeight: 0,
            flex: 1,
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
