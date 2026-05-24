import request from './request';

/** 数字人实体类型，与后端 DigitalHumanEntity 对齐 */
export interface DigitalHuman {
  id: string;
  name: string;
  description?: string;
  openness?: number;
  conscientiousness?: number;
  extraversion?: number;
  agreeableness?: number;
  neuroticism?: number;
  creativity?: number;
  precision?: number;
  verbosity?: number;
  curiosity?: number;
  colorPrimary?: string;
  colorSecondary?: string;
  geometryType?: string;
  voiceId?: string;
  pitch?: number;
  speed?: number;
  domainVectorJson?: string;
  temperature?: number;
  status?: string;
  agentId?: string;
  createTime?: string;
  updateTime?: string;
}

export const digitalHumanApi = {
  list(params?: { page?: number; size?: number; keyword?: string; status?: string }) {
    return request.get('/digital-humans', { params });
  },
  getById(id: string) {
    return request.get(`/digital-humans/${id}`);
  },
  create(data: Partial<DigitalHuman>) {
    return request.post('/digital-humans', data);
  },
  update(id: string, data: Partial<DigitalHuman>) {
    return request.put(`/digital-humans/${id}`, data);
  },
  delete(id: string) {
    return request.delete(`/digital-humans/${id}`);
  },
};
