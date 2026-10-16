import { fetchList } from './client';
import { getBidNotices } from './bids';

export const getSubmissions = signal => fetchList('/api/submission-cases', signal);
export const getDocuments = signal => fetchList('/api/submission-document-masters', signal);
export async function getBids(signal) {
  const size = 100;
  const first = await getBidNotices({ page: 0, size }, signal);
  const notices = [...first.items];

  for (let page = 1; page < first.totalPages; page++) {
    const next = await getBidNotices({ page, size }, signal);
    notices.push(...next.items);
  }

  return notices;
}
export const getNotices = signal => fetchList('/api/external-notices?piaRelated=true', signal);

export async function getPerformances(signal) {
  const projects = await fetchList('/api/performance-projects', signal);
  const evidence = new Map();
  const pending = [...projects];
  // 기존 화면과 동일하게 증빙 조회 동시 실행을 최대 4개로 제한한다.
  await Promise.all(Array.from({ length: Math.min(4, pending.length) }, async () => {
    while (pending.length && !signal?.aborted) {
      const project = pending.shift();
      try {
        const entries = await fetchList(`/api/performance-projects/${encodeURIComponent(project.id)}/entries`, signal);
        const prepared = entries.filter(({ info }) => info?.selectedFileId != null
          || info?.selectedDriveFileId != null || info?.selectedUploadedFileId != null).length;
        evidence.set(project.id, { total: entries.length, prepared });
      } catch (error) {
        if (signal?.aborted) throw error;
        evidence.set(project.id, null);
      }
    }
  }));
  return { projects, evidence };
}
