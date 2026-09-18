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

export async function selectPerformanceCandidate(projectId, entry, candidate) {
  const response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/entries/${encodeURIComponent(entry.id)}`, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...entry.info,
      selectedFileId: null,
      selectedDriveFileId: candidate.file.driveFileId,
      selectedUploadedFileId: null,
      evidenceType: candidate.evidenceType,
    }),
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    throw new Error(typeof data?.message === 'string' ? data.message : 'FMS 후보 연결에 실패했습니다.');
  }
  if (!data || data.id !== entry.id || !data.info || typeof data.info !== 'object') {
    throw new Error('실적증빙 저장 응답을 확인할 수 없습니다.');
  }
  return data;
}

export async function disconnectPerformanceFile(projectId, entry) {
  const response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/entries/${encodeURIComponent(entry.id)}`, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...entry.info,
      selectedFileId: null,
      selectedDriveFileId: null,
      selectedUploadedFileId: null,
      evidenceType: null,
    }),
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    throw new Error(typeof data?.message === 'string' ? data.message : '파일 연결 해제에 실패했습니다.');
  }
  if (!data || data.id !== entry.id || !data.info || typeof data.info !== 'object') {
    throw new Error('실적증빙 저장 응답을 확인할 수 없습니다.');
  }
  return data;
}

export function validatePerformanceUpload(file) {
  return !file || file.size === 0 || file.size > 20 * 1024 * 1024
    ? '비어 있지 않은 20MB 이하 파일을 선택하세요.' : '';
}

export async function uploadPerformanceFile(projectId, entry, file, evidenceType) {
  const validation = validatePerformanceUpload(file);
  if (validation) throw new Error(validation);
  const form = new FormData(); form.append('file', file); form.append('evidenceType', evidenceType);
  let response;
  try {
    response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/entries/${encodeURIComponent(entry.id)}/upload`, {
      method: 'POST', headers: { Accept: 'application/json' }, body: form,
    });
  } catch { throw new Error('파일 업로드에 실패했습니다. 다시 시도해 주세요.'); }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const safeMessages = [
      '비어 있지 않은 20MB 이하 파일을 선택하세요.', '20MB 이하 파일을 선택하세요.',
      '비어 있는 파일은 등록할 수 없습니다.', '파일명을 확인하세요.', '파일 확장자를 확인하세요.',
      '증빙유형을 선택하세요.', '수행중 사업은 계약서를 등록하세요.',
    ];
    throw new Error(response.status === 413 ? '20MB 이하 파일을 선택하세요.'
      : response.status === 400 && safeMessages.includes(data?.message) ? data.message
        : '파일 업로드에 실패했습니다. 다시 시도해 주세요.');
  }
  if (!data || data.id !== entry.id || !data.info || typeof data.info !== 'object') {
    throw new Error('업로드 결과를 확인할 수 없습니다.');
  }
  return data;
}
