import request from './request';

export interface Prompt {
  id: string;
  name: string;
  description: string;
  content: string;
  category: string;
  status: string;
  version: number;
  variables: string;
  createTime: string;
  updateTime: string;
}

export interface PromptVersion {
  id: string;
  promptId: string;
  version: number;
  content: string;
  changelog: string;
  createTime: string;
}

export interface PromptCategory {
  id: string;
  name: string;
  description: string;
  sortOrder: number;
}

export const promptApi = {
  list(params: { page?: number; size?: number; status?: string; category?: string }) {
    return request.get('/prompts', { params });
  },
  search(keyword: string, params: { page?: number; size?: number }) {
    return request.get('/prompts/search', { params: { keyword, ...params } });
  },
  getById(id: string) {
    return request.get(`/prompts/${id}`);
  },
  create(data: Partial<Prompt>) {
    return request.post('/prompts', data);
  },
  update(id: string, data: Partial<Prompt>) {
    return request.put(`/prompts/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/prompts/${id}`);
  },
  render(id: string, values: Record<string, string>) {
    return request.post(`/prompts/${id}/render`, values);
  },
  publish(id: string) {
    return request.post(`/prompts/${id}/publish`);
  },
  getVersions(id: string) {
    return request.get(`/prompts/${id}/versions`);
  },
  rollback(id: string, version: number) {
    return request.post(`/prompts/${id}/rollback`, { version });
  },
  compare(id: string, v1: number, v2: number) {
    return request.get(`/prompts/${id}/compare`, { params: { v1, v2 } });
  },
  test(id: string, values: Record<string, string>) {
    return request.post(`/prompts/${id}/test`, values);
  },
  optimize(id: string) {
    return request.post(`/prompts/${id}/optimize`);
  },
};

export const categoryApi = {
  list() {
    return request.get('/prompt-categories');
  },
  create(data: Partial<PromptCategory>) {
    return request.post('/prompt-categories', data);
  },
  update(id: string, data: Partial<PromptCategory>) {
    return request.put(`/prompt-categories/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/prompt-categories/${id}`);
  },
};
