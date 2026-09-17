import { fetchList } from '../client';

export async function getPerformanceEntries(projectId, signal) {
  const rows = await fetchList(`/api/performance-projects/${encodeURIComponent(projectId)}/entries`, signal);
  if (rows.some(row => !row || !row.info || typeof row.info !== 'object')) {
    throw new Error('실적증빙 목록 응답을 확인할 수 없습니다.');
  }
  return rows;
}
