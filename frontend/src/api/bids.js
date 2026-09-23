const DEFAULT_PAGE_SIZE = 20;

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
