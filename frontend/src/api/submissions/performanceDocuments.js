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

const entryFields = [
  ['pptNumber', 'PPT 번호', 100], ['businessName', '사업명', 1000], ['businessPeriod', '사업기간', 500],
  ['contractAmount', '계약금액', 200], ['client', '발주처', 500],
];

export function validatePerformanceEntry(values) {
  const errors = {};
  for (const [name, label, max] of entryFields) {
    const value = values?.[name];
    if (typeof value !== 'string' || !value.trim() || value.length > max) errors[name] = `${label}을(를) 확인하세요.`;
  }
  return errors;
}

export function validatePerformanceEntryMetadata(values) {
  const errors = {};
  if (!['', 'COMPLETED', 'IN_PROGRESS'].includes(values?.businessStatus ?? '')) errors.businessStatus = '사업상태를 확인하세요.';
  if (!['NEEDED', 'REQUESTED', 'RECEIVED'].includes(values?.kitcStatus)) {
    errors.kitcStatus = 'KITC 상태가 필요합니다.'; return errors;
  }
  const requested = values.requestedAt || null; const replied = values.repliedAt || null;
  const valid = values.kitcStatus === 'NEEDED' ? requested == null && replied == null
    : values.kitcStatus === 'REQUESTED' ? requested != null && replied == null
      : requested != null && replied != null && replied >= requested;
  if (!valid) errors._form = 'KITC 상태에 맞는 요청일·회신일을 직접 입력하세요. 회신일은 요청일 이후여야 합니다.';
  return errors;
}

const fieldFor = message => entryFields.find(([, label]) => message?.includes(label))?.[0] || '_form';
const safeEntryMessage = (message, fallback) => typeof message === 'string' && (
  /^(PPT 번호|사업명|사업기간|계약금액|발주처)을\(를\) 확인하세요\.$/.test(message)
  || message === '사업기간 날짜를 확인하세요.'
  || message === '사업기간은 시작~종료 날짜로 입력하거나 상세 수정에서 사업 상태를 지정하세요.'
  || message === '이미 저장된 PPT 번호입니다.'
  || message === '이미 저장된 PPT 번호입니다. 기존 실적을 수정하세요.'
  || message === 'KITC 상태에 맞는 요청일·회신일을 직접 입력하세요. 회신일은 요청일 이후여야 합니다.'
  || message === '입력 형식과 날짜를 확인하세요.'
  || message === '수행중 사업은 계약서를 연결하세요.'
  || message === '붙여넣기는 200만 자 이내로 입력하세요.'
  || message === '붙여넣을 실적 행이 없습니다.'
  || message === '한 번에 500행까지 붙여넣을 수 있습니다.'
  || message === '번호 / 사업명 / 사업기간 / 계약금액 / 발주처의 5개 셀이 필요합니다.'
  || message === '병합 셀을 확인하고 5개 열로 나누어 입력하세요.'
  || message === '닫히지 않은 따옴표를 확인하세요.'
) ? message : fallback;

function entryError(message) {
  const error = new Error(message); error.field = fieldFor(message); return error;
}

export async function importPerformanceEntries(projectId, text, html) {
  const response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/import`, {
    method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, html }),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw entryError(safeEntryMessage(data?.message, '실적 일괄 저장에 실패했습니다.'));
  if (!data || !Array.isArray(data.saved) || !Array.isArray(data.errors)) throw entryError('실적 저장 응답을 확인할 수 없습니다.');
  return { ...data, errors: data.errors.map(error => ({ ...error,
    message: safeEntryMessage(error?.message, '실적 입력값을 확인하세요.') })) };
}

export async function createPerformanceEntry(projectId, values) {
  const quote = value => /[\t\r\n"]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
  const text = entryFields.map(([name]) => quote(values[name].trim())).join('\t');
  const data = await importPerformanceEntries(projectId, text, null);
  if (data.errors.length) throw entryError(safeEntryMessage(data.errors[0]?.message, '실적 입력값을 확인하세요.'));
  if (data.saved.length !== 1 || !data.saved[0]?.info) throw entryError('실적 저장 응답을 확인할 수 없습니다.');
  return data.saved[0];
}

export async function updatePerformanceEntry(projectId, entry, values) {
  const response = await fetch(`/api/performance-projects/${encodeURIComponent(projectId)}/entries/${encodeURIComponent(entry.id)}`, {
    method: 'PUT', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...entry.info, ...Object.fromEntries(entryFields.map(([name]) => [name, values[name].trim()])),
      businessStatus: values.businessStatus || null, kitcStatus: values.kitcStatus,
      requestedAt: values.requestedAt || null, repliedAt: values.repliedAt || null }),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw entryError(safeEntryMessage(data?.message, '실적 저장에 실패했습니다.'));
  if (!data || data.id !== entry.id || !data.info) throw entryError('실적 저장 응답을 확인할 수 없습니다.');
  return data;
}
