import request from './request';

export interface Permission {
  id: string;
  permName: string;
  permCode: string;
  permType: string;
  parentId: string | null;
  path: string;
  icon: string;
  sortOrder: number;
  children?: Permission[];
  createTime: string;
  updateTime: string;
}

export const permissionApi = {
  tree() {
    return request.get('/permissions/tree');
  },
  getById(id: string) {
    return request.get(`/permissions/${id}`);
  },
  create(data: Partial<Permission>) {
    return request.post('/permissions', data);
  },
  update(id: string, data: Partial<Permission>) {
    return request.put(`/permissions/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/permissions/${id}`);
  },
};
