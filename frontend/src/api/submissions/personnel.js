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

export const selectDocumentCandidate = async (id, personId, type, candidateId) => {
  if (typeof candidateId !== 'string' || !candidateId) throw new Error('연결할 후보를 선택하세요.');
  return people(await request(id, '/' + encodeURIComponent(personId) + '/documents/' + encodeURIComponent(type) + '/selection', {
    method: 'PUT', body: JSON.stringify({ candidateId }),
  }));
};

export const clearDocumentConnection = async (id, personId, type) => people(await request(id,
  '/' + encodeURIComponent(personId) + '/documents/' + encodeURIComponent(type) + '/selection', {
    method: 'PUT', body: JSON.stringify({ candidateId: null }),
  }));

export function validatePersonnelUpload(file) {
  return !file || file.size === 0 || file.size > 20 * 1024 * 1024
    ? '비어 있지 않은 20MB 이하 파일을 선택하세요.' : '';
}
export async function uploadPersonnelDocument(id, personId, type, file) {
  const validation = validatePersonnelUpload(file);
  if (validation) throw new Error(validation);
  const form = new FormData(); form.append('file', file);
  let response;
  try {
    response = await fetch(base(id) + '/' + encodeURIComponent(personId) + '/documents/' + encodeURIComponent(type) + '/upload', {
      method: 'POST', headers: { Accept: 'application/json' }, body: form,
    });
  } catch { throw new Error('파일 업로드에 실패했습니다. 다시 시도해 주세요.'); }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const safeMessages = ['비어 있지 않은 20MB 이하 파일을 선택하세요.', '파일명을 확인하세요.'];
    throw new Error(response.status === 413 ? '20MB 이하 파일을 선택하세요.'
      : response.status === 400 && safeMessages.includes(body?.message) ? body.message
        : '파일 업로드에 실패했습니다. 다시 시도해 주세요.');
  }
  if (!Array.isArray(body)) throw new Error('업로드 결과를 확인할 수 없습니다.');
  return people(body);
}
