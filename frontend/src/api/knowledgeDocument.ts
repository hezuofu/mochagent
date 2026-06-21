import request from './request';

export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  sourceName: string;
  sourceType: string;
  chunkIndex: number;
  content: string;
  metadataJson?: string;
  createTime: string;
}

export const knowledgeDocumentApi = {
  list(params: { knowledgeBaseId: string; page?: number; size?: number }) {
    return request.get('/knowledge-documents', { params });
  },
  getChunks(knowledgeBaseId: string) {
    return request.get('/knowledge-documents/chunks', { params: { knowledgeBaseId } });
  },
  create(data: { knowledgeBaseId: string; sourceName: string; sourceType: string; content: string; metadataJson?: string }) {
    return request.post('/knowledge-documents', data);
  },
  delete(id: string) {
    return request.delete(`/knowledge-documents/${id}`);
  },
  deleteAll(knowledgeBaseId: string) {
    return request.delete(`/knowledge-documents/by-knowledge-base/${knowledgeBaseId}`);
  },
};
