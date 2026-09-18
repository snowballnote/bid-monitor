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
