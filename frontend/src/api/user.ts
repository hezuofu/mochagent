import request from './request';

export interface User {
  id: string;
  username: string;
  password?: string;
  nickname: string;
  email: string;
  phone: string;
  avatar: string;
  status: string;
  roles?: Role[];
  createTime: string;
  updateTime: string;
}

export interface Role {
  id: string;
  roleName: string;
  roleCode: string;
  description: string;
  status: string;
  createTime: string;
  updateTime: string;
}

export interface UserQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
}

export const userApi = {
  list(params: UserQuery) {
    return request.get('/users', { params });
  },
  getById(id: string) {
    return request.get(`/users/${id}`);
  },
  create(data: Partial<User>) {
    return request.post('/users', data);
  },
  update(id: string, data: Partial<User>) {
    return request.put(`/users/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/users/${id}`);
  },
  toggleStatus(id: string) {
    return request.patch(`/users/${id}/status`);
  },
  assignRoles(id: string, roleIds: string[]) {
    return request.put(`/users/${id}/roles`, { roleIds });
  },
};
