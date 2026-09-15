const BASE = '/api/submission-cases';

async function request(path = '', options = {}) {
  const response = await fetch(BASE + path, { ...options, headers: {
    Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}),
  } });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    const message = response.status === 503 ? '회사 DB에 연결할 수 없습니다. 관리자에게 연결 상태를 확인해 주세요.'
      : response.status === 404 ? '제출서류 작업을 찾을 수 없습니다.'
        : response.status === 400 && typeof data?.message === 'string' ? data.message
          : '제출서류 작업을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
    throw new Error(message);
  }
  if (options.method !== 'DELETE' && data == null) throw new Error('응답을 확인할 수 없습니다.');
  return data;
}

export async function getProjects(signal) {
  const rows = await request('', { signal });
  if (!Array.isArray(rows) || rows.some(row => !row || row.id == null)) throw new Error('프로젝트 목록을 확인할 수 없습니다.');
  return rows;
}

function fields(values) {
  if (!values.projectName.trim() || values.projectName.length > 2000 || !values.deadline) {
    throw new Error('프로젝트명과 마감일을 확인해 주세요.');
  }
  return JSON.stringify({ projectName: values.projectName, deadline: values.deadline });
}

async function save(path, method, values) {
  const project = await request(path, { method, body: fields(values) });
  if (!project || project.id == null) throw new Error('저장 결과를 확인할 수 없습니다.');
  return project;
}

export const createProject = values => save('', 'POST', values);
export const updateProject = (id, values) => save('/' + encodeURIComponent(id), 'PUT', values);
export const deleteProject = id => request('/' + encodeURIComponent(id), { method: 'DELETE' });
export const detailUrl = id => '#/submissions/' + encodeURIComponent(id);
export const legacyDetailUrl = (id, section = '') => '/submissions/?caseId=' + encodeURIComponent(id) + (section ? '#' + section : '');
export const getCaseResource = (id, suffix, signal) => request('/' + encodeURIComponent(id) + suffix, { signal });
export const collectCommonDocuments = (id, requirements) => request('/' + encodeURIComponent(id) + '/collect?preserveExistingSelections=true', {
  method: 'POST', body: JSON.stringify(requirements),
});
