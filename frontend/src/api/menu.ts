import request from './request';

export interface MenuItem {
  key: string;
  label: string;
  icon: string;
  path: string;
  sortOrder: number;
  children: MenuItem[];
}

export const menuApi = {
  fetchMenus: (): Promise<MenuItem[]> =>
    request.get('/menus').then((res) => res.data),
};
