import axios from 'axios';
import { message } from 'antd';

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

// 请求拦截 — 注入 Token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

request.interceptors.response.use(
  (response) => {
    const res = response.data;
    if (res.code !== 200) {
      message.error(res.message || 'Request failed');
      return Promise.reject(new Error(res.message));
    }
    return res;
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login.html';
      return Promise.reject(error);
    }
    message.error(error.message || 'Network error');
    return Promise.reject(error);
  },
);

export default request;
