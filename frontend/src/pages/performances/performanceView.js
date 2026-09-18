export const PERFORMANCE_STATUS = { DRAFT: '작성중', COLLECTING: '수집중', READY: '선택 완료' };

export function performanceStatus(status) {
  return PERFORMANCE_STATUS[status] || status;
}

export function performanceDday(days) {
  return days === 0 ? 'D-day' : days > 0 ? `D-${days}` : `D+${Math.abs(days)}`;
}
