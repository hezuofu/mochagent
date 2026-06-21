import request from './request';

export interface KnowledgeBase {
  id: string;
  digitalHumanId: string;
  name: string;
  description?: string;
  documentCount: number;
  createTime?: string;
  updateTime?: string;
}

export const knowledgeBaseApi = {
  list(params: { digitalHumanId: string; page?: number; size?: number; keyword?: string }) {
    return request.get('/knowledge-bases', { params });
  },
  listAll(digitalHumanId: string) {
    return request.get('/knowledge-bases/all', { params: { digitalHumanId } });
  },
  getById(id: string) {
    return request.get(`/knowledge-bases/${id}`);
  },
  create(data: { digitalHumanId: string; name: string; description?: string }) {
    return request.post('/knowledge-bases', data);
  },
  update(id: string, data: { name: string; description?: string }) {
    return request.put(`/knowledge-bases/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/knowledge-bases/${id}`);
  },
};
