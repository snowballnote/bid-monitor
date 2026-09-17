export const groups = { COMPANY_COMMON: '회사 공통', PERSONNEL: '인력·자격', PERFORMANCE: '실적증빙', OTHER: '기타' };
const normalized = value => String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko-KR');
const defaultGroup = category => ['PERSONNEL', 'PERFORMANCE', 'OTHER'].includes(category) ? category : 'COMPANY_COMMON';

// 저장된 응답의 표시용 집계만 수행한다. 파일 선택·수집·연결 상태는 변경하지 않는다.
export function detailView({ requirements, selections, people, masters, entries }) {
  entries = Array.isArray(entries) ? entries : [];
  const files = new Map(selections.map(file => [file.requirementId, file]));
  const evidencePrepared = entries.filter(({ info }) => info?.selectedFileId != null
    || info?.selectedDriveFileId != null || info?.selectedUploadedFileId != null).length;
  const performanceReady = entries.length > 0 && entries.length === evidencePrepared;
  const rows = requirements.map(requirement => {
    const common = requirement.companyCommon || masters.some(master => master.category === 'COMPANY_COMMON'
      && (master.sourceReference === requirement.sourceReference
        || (master.name === requirement.documentName && master.requirementCategory === requirement.category)));
    const master = masters.find(master => master.requirementCategory === requirement.category
      && (master.sourceReference === requirement.sourceReference || normalized(master.name) === normalized(requirement.documentName)));
    const group = common ? 'COMPANY_COMMON' : requirement.performanceSelectionRequired ? 'PERFORMANCE'
      : master?.category || defaultGroup(requirement.category);
    const file = files.get(requirement.id);
    const ready = !common && requirement.performanceSelectionRequired ? performanceReady : !!file;
    return { ...requirement, group, file, ready, status: !common && requirement.performanceSelectionRequired
      ? `${evidencePrepared} / ${entries.length} 준비` : ready ? (common ? '준비 완료' : '선택 완료') : '파일 미등록' };
  });
  const personnel = people.flatMap(person => person.documents.filter(doc => doc.needed)
    .map(doc => ({ ...doc, personName: person.name })));
  const hasPerformance = rows.some(row => row.group === 'PERFORMANCE');
  const summary = Object.keys(groups).map(group => {
    const items = rows.filter(row => row.group === group);
    if (group === 'PERFORMANCE') return { group, total: hasPerformance ? entries.length : 0,
      prepared: hasPerformance ? evidencePrepared : 0 };
    return { group, total: items.length + (group === 'PERSONNEL' ? personnel.length : 0),
      prepared: items.filter(row => row.ready).length + (group === 'PERSONNEL' ? personnel.filter(doc => doc.filename).length : 0) };
  });
  return { rows, personnel, summary, total: summary.reduce((sum, row) => sum + row.total, 0),
    prepared: summary.reduce((sum, row) => sum + row.prepared, 0),
    downloadable: selections.some(file => file.uploadedFileId) || personnel.some(doc => doc.filename) };
}

export function deadlineLabel(deadline) {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const days = deadline ? Math.round((new Date(deadline + 'T00:00:00') - today) / 86400000) : NaN;
  return Number.isNaN(days) ? '마감일 미설정' : days === 0 ? 'D-day' : days > 0 ? `D-${days}` : `D+${-days}`;
}
