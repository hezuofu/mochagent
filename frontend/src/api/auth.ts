import request from './request';

export interface LoginParams {
  username: string;
  password: string;
}

export interface LoginResult {
  token: string;
  userId: string;
  username: string;
  nickname: string;
  avatar: string;
  roles: string[];
  permissions: string[];
}

export const authApi = {
  login: (params: LoginParams): Promise<LoginResult> =>
    request.post('/auth/login', params).then((res) => res.data),

  logout: (): Promise<void> =>
    request.post('/auth/logout').then(() => undefined),
};
