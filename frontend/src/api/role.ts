import request from './request';

export interface Role {
  id: string;
  roleName: string;
  roleCode: string;
  description: string;
  status: string;
  permissions?: Permission[];
  createTime: string;
  updateTime: string;
}

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

export interface RoleQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
}

export const roleApi = {
  list(params: RoleQuery) {
    return request.get('/roles', { params });
  },
  getById(id: string) {
    return request.get(`/roles/${id}`);
  },
  create(data: Partial<Role>) {
    return request.post('/roles', data);
  },
  update(id: string, data: Partial<Role>) {
    return request.put(`/roles/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/roles/${id}`);
  },
  assignPermissions(id: string, permIds: string[]) {
    return request.put(`/roles/${id}/permissions`, { permIds });
  },
};
