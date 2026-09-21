import { getCaseResource } from './index';
import { fetchList } from '../client';

const pending = new Map();
export function openPerformanceLink(caseId, required) {
  if (pending.has(caseId)) return pending.get(caseId);
  const work = connect(caseId, required).finally(() => pending.delete(caseId));
  pending.set(caseId, work);
  return work;
}

async function connect(caseId, required) {
  if (!/^[1-9]\d*$/.test(String(caseId))) throw new Error('제출서류 번호를 확인해 주세요.');
  const path = '/api/submission-cases/' + encodeURIComponent(caseId);
  const check = project => {
    if (String(project?.id) !== String(caseId)) throw new Error('연결 응답을 확인할 수 없습니다.');
    return project;
  };
  let project = check(await getCaseResource(caseId, ''));
  const key = 'biz-assist.performance-project.' + caseId;
  async function write(url, method, body) {
    const response = await fetch(url, { method, headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    if (!response.ok) throw new Error('실적 프로젝트 연결에 실패했습니다. 다시 시도해 주세요.');
    const saved = check(await response.json());
    if (!saved.performanceProjectId) throw new Error('실적 프로젝트 연결 결과를 확인할 수 없습니다.');
    return saved;
  }
  if (!project.performanceProjectId && !project.performanceLinkInitialized && required) {
    let legacy = '';
    try { legacy = localStorage.getItem(key) || ''; } catch { /* unavailable storage */ }
    if (legacy) {
      const projects = await fetchList('/api/performance-projects');
      if (projects.some(row => row.id === legacy)) {
        project = await write(path, 'PUT', { performanceProjectId: legacy, initializePerformanceOnly: true });
        try { localStorage.removeItem(key); } catch { /* confirmed server link takes priority */ }
      }
    }
  }
  if (!project.performanceProjectId) project = await write(path + '/performance-project', 'POST');
  return project;
}
