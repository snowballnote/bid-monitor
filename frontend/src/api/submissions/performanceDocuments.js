import { fetchList } from '../client';

export async function getPerformanceEntries(projectId, signal) {
  const rows = await fetchList(`/api/performance-projects/${encodeURIComponent(projectId)}/entries`, signal);
  if (rows.some(row => !row || !row.info || typeof row.info !== 'object')) {
    throw new Error('실적증빙 목록 응답을 확인할 수 없습니다.');
  }
  return rows;
}

export async function getPerformanceCandidates(projectId, entryId, signal) {
  const response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/entries/${encodeURIComponent(entryId)}/candidates`, {
    signal, headers: { Accept: 'application/json' },
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    const message = response.status === 503 ? 'FMS 후보를 조회하지 못했습니다.'
      : response.status === 404 ? '실적 entry를 찾을 수 없습니다.'
        : typeof data?.message === 'string' ? data.message : 'FMS 후보를 조회하지 못했습니다.';
    throw new Error(message);
  }
  if (!data || !Array.isArray(data.candidates) || data.candidates.some(candidate => !candidate?.file
    || !candidate.file.driveFileId || typeof candidate.file.originalFilename !== 'string')) {
    throw new Error('FMS 후보 응답을 확인할 수 없습니다.');
  }
  return data;
}
