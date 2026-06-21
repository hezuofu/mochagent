import request from './request';

export interface Tool {
  id: string;
  name: string;
  description: string;
  category: string;
  status: string;
  callCount: number;
  createTime: string;
}

export const toolApi = {
  list(params: { page?: number; size?: number; keyword?: string; category?: string; status?: string }) {
    return request.get('/tools', { params });
  },
  getById(id: string) {
    return request.get(`/tools/${id}`);
  },
  create(data: Partial<Tool>) {
    return request.post('/tools', data);
  },
  update(id: string, data: Partial<Tool>) {
    return request.put(`/tools/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/tools/${id}`);
  },
  toggleStatus(id: string) {
    return request.patch(`/tools/${id}/status`);
  },
  execute(id: string, params: Record<string, unknown>) {
    return request.post(`/tools/${id}/execute`, params);
  },
  test(id: string) {
    return request.post(`/tools/${id}/test`);
  },
  stats(id: string) {
    return request.get(`/tools/${id}/stats`);
  },
  marketplace(params: { page?: number; size?: number }) {
    return request.get('/tools/marketplace', { params });
  },
};
