import request from './request';

export interface ModelConfig {
  id: string;
  name: string;
  provider: string;
  endpoint: string;
  apiKey: string;
  modelVersion: string;
  status: string;
  maxTokens: number;
  costPer1kTokens: number;
  createTime: string;
}

export const modelApi = {
  list(params: { page?: number; size?: number; provider?: string; status?: string }) {
    return request.get('/models', { params });
  },
  getById(id: string) {
    return request.get(`/models/${id}`);
  },
  create(data: Partial<ModelConfig>) {
    return request.post('/models', data);
  },
  update(id: string, data: Partial<ModelConfig>) {
    return request.put(`/models/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/models/${id}`);
  },
  toggleStatus(id: string) {
    return request.patch(`/models/${id}/status`);
  },
  test(id: string) {
    return request.post(`/models/${id}/test`);
  },
  metrics(id: string) {
    return request.get(`/models/${id}/metrics`);
  },
};
