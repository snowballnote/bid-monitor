const API = '/api/submission-document-masters';

async function request(path = '', options = {}) {
  const response = await fetch(API + path, options);
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { /* DELETE may have no body. */ }
  if (!response.ok) throw new Error(response.status === 413 ? '20MB 이하 파일을 선택하세요.' : body?.message || '서류를 처리하지 못했습니다.');
  return body;
}

export async function listDocuments() {
  const rows = await request();
  if (!Array.isArray(rows) || rows.some(row => !row || row.id == null)) throw new Error('서류 목록을 확인할 수 없습니다.');
  return rows.filter(row => row.category === 'COMPANY_COMMON');
}
export function saveDocument(id, name) {
  return request(id == null ? '' : '/' + encodeURIComponent(id), {
    method: id == null ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, category: 'COMPANY_COMMON' }),
  });
}
export const deleteDocument = id => request('/' + encodeURIComponent(id), { method: 'DELETE' });
export function uploadDocument(id, file) {
  const body = new FormData();
  body.append('file', file);
  return request('/' + encodeURIComponent(id) + '/upload', { method: 'POST', body });
}
