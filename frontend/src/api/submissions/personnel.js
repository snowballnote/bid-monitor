const base = id => `/api/submission-cases/${encodeURIComponent(id)}/people`;
async function request(id, suffix = '', options = {}) {
  const response = await fetch(base(id) + suffix, { ...options, headers: {
    Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}),
  } });
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new Error(body?.message || '인력 서류를 처리하지 못했습니다.');
  if (!Array.isArray(body)) throw new Error('인력 목록을 확인할 수 없습니다.');
  return body;
}
function people(rows) {
  if (rows.some(person => !person || !Array.isArray(person.documents))) throw new Error('인력 목록을 확인할 수 없습니다.');
  return rows;
}
export const getPeople = async (id, signal) => people(await request(id, '', { signal }));
export const searchPeople = async (id, query, signal) => {
  const rows = await request(id, '/search?q=' + encodeURIComponent(query), { signal });
  if (rows.some(row => !row || typeof row.name !== 'string')) throw new Error('인력 검색 결과를 확인할 수 없습니다.');
  return rows;
};
export const addPerson = async (id, option) => people(await request(id, '', {
  method: 'POST', body: JSON.stringify({ name: option.name, department: option.department || '' }),
}));
export const removePerson = async (id, personId) => people(await request(id, '/' + encodeURIComponent(personId), { method: 'DELETE' }));

export const setDocumentNeeded = async (id, personId, type, needed) => people(await request(id,
  '/' + encodeURIComponent(personId) + '/documents/' + encodeURIComponent(type), {
    method: 'PUT', body: JSON.stringify({ needed }),
  }));

export async function getDocumentCandidates(id, personId, type, signal) {
  const rows = await request(id, '/' + encodeURIComponent(personId) + '/documents/' + encodeURIComponent(type) + '/candidates', { signal });
  if (rows.some(row => !row || typeof row.id !== 'string' || typeof row.filename !== 'string'
      || typeof row.recommended !== 'boolean')) throw new Error('FMS 후보 목록을 확인할 수 없습니다.');
  return rows;
}
