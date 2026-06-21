import request from './request';

export interface Conversation {
  id: string;
  title: string;
  userId: string;
  agentId: string;
  digitalHumanId?: string;
  status: string;
  messageCount: number;
  createTime: string;
  updateTime: string;
}

export interface Message {
  id: string;
  conversationId: string;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  parameterSnapshot?: string;
  createTime: string;
}

export const conversationApi = {
  list(params: { page?: number; size?: number; userId?: string; status?: string }) {
    return request.get('/conversations', { params });
  },
  getById(id: string) {
    return request.get(`/conversations/${id}`);
  },
  create(data: Partial<Conversation>) {
    return request.post('/conversations', data);
  },
  delete(id: string) {
    return request.delete(`/conversations/${id}`);
  },
  rename(id: string, title: string) {
    return request.patch(`/conversations/${id}`, { title });
  },
  archive(id: string) {
    return request.patch(`/conversations/${id}`, { action: 'archive' });
  },
  clearMessages(id: string) {
    return request.delete(`/conversations/${id}/messages`);
  },
  getMessages(id: string, params: { page?: number; size?: number }) {
    return request.get(`/conversations/${id}/messages`, { params });
  },
  sendMessage(id: string, content: string) {
    return request.post(`/conversations/${id}/messages`, { content });
  },
  deleteMessage(conversationId: string, msgId: string) {
    return request.delete(`/conversations/${conversationId}/messages/${msgId}`);
  },
  feedback(conversationId: string, msgId: string, rating: number) {
    return request.post(`/conversations/${conversationId}/messages/${msgId}/feedback`, { rating });
  },
  exportConversation(id: string) {
    return request.get(`/conversations/${id}/export`);
  },
};
