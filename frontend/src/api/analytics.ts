import request from './request';

export const analyticsApi = {
  overview(params?: { days?: number }) {
    return request.get('/analytics/overview', { params });
  },
  usage(params?: { days?: number }) {
    return request.get('/analytics/usage', { params });
  },
  cost(params?: { days?: number }) {
    return request.get('/analytics/cost', { params });
  },
  models(params?: { days?: number }) {
    return request.get('/analytics/models', { params });
  },
  daily(params?: { days?: number }) {
    return request.get('/analytics/daily', { params });
  },
};
