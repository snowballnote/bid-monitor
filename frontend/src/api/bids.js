const DEFAULT_PAGE_SIZE = 20;

export class BidCollectionRequestError extends Error {
  constructor(status, result = null) {
    super(`입찰공고 수집 요청 실패: ${status}`);
    this.name = 'BidCollectionRequestError';
    this.status = status;
    this.result = result;
  }
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export async function getBidNotices(filters = {}, signal) {
  const query = new URLSearchParams({
    page: String(filters.page ?? 0),
    size: String(filters.size ?? DEFAULT_PAGE_SIZE),
  });
  if (filters.startDate) query.set('startDate', filters.startDate);
  if (filters.endDate) query.set('endDate', filters.endDate);
  if (filters.sourceCode) query.set('sourceCode', filters.sourceCode);

  const response = await fetch(`/api/bid-notices?${query}`, {
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error(`저장 공고 조회 실패: ${response.status}`);

  const result = await response.json();
  if (!result || !Array.isArray(result.items)
      || !Number.isInteger(result.page) || !Number.isInteger(result.size)
      || !Number.isInteger(result.totalCount) || !Number.isInteger(result.totalPages)) {
    throw new Error('저장 공고 응답 형식이 올바르지 않습니다.');
  }
  return result;
}

export async function collectBidNotices({ startDate, endDate }, signal) {
  const response = await fetch('/api/bid-collections', {
    method: 'POST',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ startDate, endDate }),
  });
  const result = await readJson(response);
  if (!response.ok) throw new BidCollectionRequestError(response.status, result);
  if (!result || !['SUCCESS', 'PARTIAL_SUCCESS'].includes(result.status)
      || !Array.isArray(result.sources)) {
    throw new BidCollectionRequestError(503);
  }
  return result;
}

export async function getBidSourceStatuses(signal) {
  const response = await fetch('/api/bid-sources/status', {
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error(`출처 상태 조회 실패: ${response.status}`);
  const statuses = await response.json();
  if (!Array.isArray(statuses)) throw new Error('출처 상태 응답 형식이 올바르지 않습니다.');
  return statuses;
}
