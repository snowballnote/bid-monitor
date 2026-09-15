export async function fetchList(url, signal) {
  const response = await fetch(url, { signal, headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`데이터 조회 실패: ${response.status}`);
  const rows = await response.json();
  if (!Array.isArray(rows)) throw new Error('목록을 확인할 수 없습니다.');
  return rows;
}
