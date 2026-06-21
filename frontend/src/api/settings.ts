import request from './request';

export const settingsApi = {
  getAll() {
    return request.get('/settings');
  },
  get(key: string) {
    return request.get(`/settings/${key}`);
  },
  update(values: Record<string, string>) {
    return request.put('/settings', values);
  },
};
