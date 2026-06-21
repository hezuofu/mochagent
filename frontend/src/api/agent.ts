import request from './request';

export interface Agent {
  id: string;
  name: string;
  description: string;
  type: string;
  status: string;
  modelId: string;
  toolIds: string;
  version: string;
  digitalHumanId?: string;
  createTime: string;
  updateTime: string;
}

export interface AgentQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
}

export const agentApi = {
  list(params: AgentQuery) {
    return request.get('/agents', { params });
  },
  getById(id: string) {
    return request.get(`/agents/${id}`);
  },
  create(data: Partial<Agent>) {
    return request.post('/agents', data);
  },
  update(id: string, data: Partial<Agent>) {
    return request.put(`/agents/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/agents/${id}`);
  },
  toggleStatus(id: string) {
    return request.patch(`/agents/${id}/status`);
  },
  copy(id: string, name: string) {
    return request.post(`/agents/${id}/copy`, { name });
  },
  execute(agentId: string, input: string) {
    return request.post('/agents/execute', { agentId, input });
  },
};
