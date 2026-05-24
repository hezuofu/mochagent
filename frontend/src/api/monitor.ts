import request from './request';

export const monitorApi = {
  overview() {
    return request.get('/monitor/overview');
  },
  agentMonitor(id: string) {
    return request.get(`/monitor/agents/${id}`);
  },
  stats(params: { granularity?: string }) {
    return request.get('/monitor/stats', { params });
  },
  performance() {
    return request.get('/monitor/performance');
  },
  health() {
    return request.get('/monitor/health');
  },
  logs(params: { keyword?: string; level?: string; page?: number; size?: number }) {
    return request.get('/monitor/logs', { params });
  },
};
