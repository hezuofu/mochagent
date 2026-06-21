import request from './request';

export interface Workflow {
  id: string;
  name: string;
  description: string;
  definition: string;
  status: string;
  version: number;
  agentId: string;
  createTime: string;
  updateTime: string;
}

export interface WorkflowVersion {
  id: string;
  workflowId: string;
  version: number;
  definition: string;
  changelog: string;
  createTime: string;
}

export interface WorkflowExecution {
  id: string;
  workflowId: string;
  status: string;
  input: string;
  output: string;
  startTime: string;
  endTime: string;
  progress: number;
  errorMessage: string;
  createTime: string;
}

export const workflowApi = {
  list(params: { page?: number; size?: number; status?: string }) {
    return request.get('/workflows', { params });
  },
  getById(id: string) {
    return request.get(`/workflows/${id}`);
  },
  create(data: Partial<Workflow>) {
    return request.post('/workflows', data);
  },
  update(id: string, data: Partial<Workflow>) {
    return request.put(`/workflows/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/workflows/${id}`);
  },
  publish(id: string) {
    return request.post(`/workflows/${id}/publish`);
  },
  copy(id: string, name?: string) {
    return request.post(`/workflows/${id}/copy`, { name });
  },
  execute(id: string, input?: string) {
    return request.post(`/workflows/${id}/execute`, { input });
  },
  executeAsync(id: string, input?: string) {
    return request.post(`/workflows/${id}/execute-async`, { input });
  },
  getExecutions(id: string, params: { page?: number; size?: number }) {
    return request.get(`/workflows/${id}/executions`, { params });
  },
  getVersions(id: string) {
    return request.get(`/workflows/${id}/versions`);
  },
  rollback(id: string, version: number) {
    return request.post(`/workflows/${id}/rollback`, { version });
  },
  test(id: string, input?: string) {
    return request.post(`/workflows/${id}/test`, { input });
  },
};
