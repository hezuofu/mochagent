import { Routes, Route } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AuthProvider } from './contexts/AuthContext';
import PrivateRoute from './components/PrivateRoute';
import MainLayout from './layouts/MainLayout';
import Login from './pages/Login';
import Dashboard from './pages/monitor/Dashboard';
import AgentList from './pages/agents/AgentList';
import AgentForm from './pages/agents/AgentForm';
import ModelList from './pages/models/ModelList';
import ToolList from './pages/tools/ToolList';
import ConversationList from './pages/sessions/ConversationList';
import ChatPanel from './pages/sessions/ChatPanel';
import WorkflowList from './pages/workflows/WorkflowList';
import WorkflowEditor from './pages/workflows/WorkflowEditor';
import DigitalHumanList from './pages/digitalHumans/DigitalHumanList';
import DigitalHumanForm from './pages/digitalHumans/DigitalHumanForm';
import PromptList from './pages/prompts/PromptList';
import PromptEditor from './pages/prompts/PromptEditor';
import KnowledgeBaseList from './pages/knowledge/KnowledgeBaseList';
import KnowledgeDocumentManager from './pages/knowledge/KnowledgeDocumentManager';
import Analytics from './pages/analytics/Analytics';
import SettingsPage from './pages/settings/Settings';
import UserList from './pages/system/UserList';
import RoleList from './pages/system/RoleList';
import PermissionList from './pages/system/PermissionList';
import { validateMessages } from './utils/validation';

function App() {
  return (
    <ConfigProvider
      locale={zhCN}
      form={{ validateMessages }}
      theme={{
        token: {
          colorPrimary: '#f70',
          colorPrimaryBg: 'rgba(255, 119, 0, 0.06)',
          colorPrimaryBgHover: 'rgba(255, 119, 0, 0.15)',
          colorPrimaryBorder: 'rgba(255, 119, 0, 0.3)',
          colorPrimaryHover: '#ff8c33',
          colorPrimaryActive: '#e06600',
          borderRadius: 4,
          fontSize: 14,
          colorText: '#303133',
          colorTextSecondary: '#909399',
        },
      }}
    >
      <AuthProvider>
      <Routes>
        <Route path="/login.html" element={<Login />} />
        <Route path="/" element={
          <PrivateRoute>
            <MainLayout />
          </PrivateRoute>
        }>
        <Route index element={<Dashboard />} />
        <Route path="agents.html" element={<AgentList />} />
        <Route path="agents/new.html" element={<AgentForm />} />
        <Route path="agents/:id/edit.html" element={<AgentForm />} />
        <Route path="models.html" element={<ModelList />} />
        <Route path="tools.html" element={<ToolList />} />
        <Route path="conversations.html" element={<ConversationList />} />
        <Route path="conversations/:id.html" element={<ChatPanel />} />
        <Route path="workflows.html" element={<WorkflowList />} />
        <Route path="workflows/new/edit.html" element={<WorkflowEditor />} />
        <Route path="workflows/:id/edit.html" element={<WorkflowEditor />} />
        <Route path="digital-humans.html" element={<DigitalHumanList />} />
        <Route path="digital-humans/new.html" element={<DigitalHumanForm />} />
        <Route path="digital-humans/:id/edit.html" element={<DigitalHumanForm />} />
        <Route path="prompts.html" element={<PromptList />} />
        <Route path="prompts/new.html" element={<PromptEditor />} />
        <Route path="prompts/:id/edit.html" element={<PromptEditor />} />
        <Route path="knowledge-bases.html" element={<KnowledgeBaseList />} />
        <Route path="knowledge-bases/:id/documents.html" element={<KnowledgeDocumentManager />} />
        <Route path="analytics.html" element={<Analytics />} />
        <Route path="users.html" element={<UserList />} />
        <Route path="roles.html" element={<RoleList />} />
        <Route path="permissions.html" element={<PermissionList />} />
        <Route path="settings.html" element={<SettingsPage />} />
      </Route>
      </Routes>
      </AuthProvider>
    </ConfigProvider>
  );
}

export default App;
