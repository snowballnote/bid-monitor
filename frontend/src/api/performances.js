import { fetchList } from './client';

const BASE = '/api/performance-projects';

function validProject(project) {
  return project && typeof project.id === 'string' && typeof project.name === 'string'
    && typeof project.deadline === 'string' && typeof project.daysRemaining === 'number'
    && typeof project.status === 'string';
}

async function request(path = '', options = {}) {
  const { read = false, ...requestOptions } = options;
  const response = await fetch(BASE + path, { ...requestOptions, headers: {
    Accept: 'application/json', ...(requestOptions.body ? { 'Content-Type': 'application/json' } : {}),
  } });
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const safe = response.status === 400 && [
      '프로젝트명과 마감일이 필요합니다.', '프로젝트명을(를) 확인하세요.', '입력 형식과 날짜를 확인하세요.',
    ].includes(data?.message) ? data.message : response.status === 404 ? '실적 프로젝트를 찾을 수 없습니다.'
      : read ? '실적 프로젝트를 불러오지 못했습니다.' : '실적 프로젝트를 저장하지 못했습니다. 다시 시도해 주세요.';
    throw new Error(safe);
  }
  if (!validProject(data)) throw new Error('실적 프로젝트 응답을 확인할 수 없습니다.');
  return data;
}

export async function getPerformanceProjects(signal) {
  const rows = await fetchList(BASE, signal);
  if (rows.some(row => !validProject(row))) throw new Error('실적 프로젝트 목록을 확인할 수 없습니다.');
  return rows;
}

export const getPerformanceProject = (id, signal) => request('/' + encodeURIComponent(id), { signal, read: true });

export function validatePerformanceProject(values) {
  const errors = {};
  if (typeof values?.name !== 'string' || !values.name.trim() || values.name.length > 500) {
    errors.name = '프로젝트명을(를) 확인하세요.';
  }
  if (typeof values?.deadline !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(values.deadline)) {
    errors.deadline = '마감일을 확인하세요.';
  }
  return errors;
}

async function save(path, method, values) {
  return request(path, { method, body: JSON.stringify({ name: values.name, deadline: values.deadline }) });
}

export const createPerformanceProject = values => save('', 'POST', values);
export const updatePerformanceProject = (id, values) => save('/' + encodeURIComponent(id), 'PUT', values);

function validDriveIndexRoot(root) {
  const state = root?.state;
  return typeof root?.label === 'string' && state && typeof state.status === 'string'
    && (state.lastSuccessAt == null || typeof state.lastSuccessAt === 'string')
    && typeof state.fileCount === 'number';
}

async function driveIndexRequest(path = '', options = {}) {
  let response;
  try { response = await fetch('/api/drive-index' + path, { ...options, headers: { Accept: 'application/json' } }); }
  catch { throw new Error(options.method === 'POST' ? 'Drive index 갱신에 실패했습니다.' : 'Drive index 상태를 불러오지 못했습니다.'); }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const safe = ['Drive 인덱스를 이미 갱신하고 있습니다.', 'Drive 검색 폴더를 설정하세요.'];
    throw new Error(safe.includes(data?.message) ? data.message
      : options.method === 'POST' ? 'Drive index 갱신에 실패했습니다.' : 'Drive index 상태를 불러오지 못했습니다.');
  }
  if (!Array.isArray(data) || data.some(root => !validDriveIndexRoot(root))) throw new Error('Drive index 상태 응답을 확인할 수 없습니다.');
  return data;
}

export const getDriveIndexStatus = signal => driveIndexRequest('', { signal });
export const refreshDriveIndex = () => driveIndexRequest('/refresh', { method: 'POST' });

function downloadFilename(disposition) {
  if (typeof disposition !== 'string') return 'performance-evidence.zip';
  const encoded = disposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename\s*=\s*(?:"([^"]+)"|([^;]+))/i);
  let value = encoded ? (() => { try { return decodeURIComponent(encoded); } catch { return ''; } })()
    : plain?.[1] || plain?.[2]?.trim() || '';
  value = value.split(/[\\/]/).at(-1)?.replace(/[\u0000-\u001f\u007f]/g, '').trim() || '';
  return value || 'performance-evidence.zip';
}

export async function downloadPerformanceEvidence(projectId) {
  let response;
  try {
    response = await fetch(`${BASE}/${encodeURIComponent(projectId)}/download`, { headers: { Accept: 'application/zip, application/json' } });
  } catch { throw new Error('ZIP 다운로드에 실패했습니다. 다시 시도해 주세요.'); }
  if (!response.ok) {
    const data = await response.json().catch(() => null);
    const safeMessages = [
      '먼저 증빙파일을 선택하세요.', 'ZIP 파일명이 중복됩니다. PPT 번호나 사업명을 수정하세요.',
      '선택한 FMS 파일의 다운로드 권한이 없습니다.',
      'ZIP을 만들 수 없습니다. 파일 상태·NAS 연결·원본 합계 100MB 제한을 확인하세요.',
    ];
    throw new Error(safeMessages.includes(data?.message) ? data.message : 'ZIP 다운로드에 실패했습니다. 다시 시도해 주세요.');
  }
  if (!response.headers.get('content-type')?.toLowerCase().includes('application/zip')) {
    throw new Error('ZIP 다운로드 응답을 확인할 수 없습니다.');
  }
  return { blob: await response.blob(), filename: downloadFilename(response.headers.get('content-disposition')) };
}
