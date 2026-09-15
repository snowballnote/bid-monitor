import { groups, deadlineLabel } from '../../pages/submissions/detailView';
import { legacyDetailUrl } from '../../api/submissions';

export function Progress({ prepared, total, label }) {
  const percent = total ? Math.round(prepared / total * 100) : 0;
  return <div className="detail-progress"><span>{prepared} / {total} · {percent}%</span>
    <div className="progress-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
      <span style={{ width: `${percent}%` }} /></div></div>;
}

export default function DetailSummary({ project, view }) {
  return <>
    <section className="case-overview surface-card" aria-labelledby="case-project-name">
      <div className="case-identity"><h2 id="case-project-name">{project.projectName}</h2>
        <p>발주기관 {project.organizationName || project.orderingAgency || project.agencyName || '미등록'}</p>
        <p>마감일 {project.deadline || '미설정'} <span className="count-badge">{deadlineLabel(project.deadline)}</span></p></div>
      <div className="case-progress"><strong>전체 준비율</strong><Progress prepared={view.prepared} total={view.total} label="전체 준비율" /></div>
    </section>
    <div className="category-progress" aria-label="카테고리별 준비율">
      {view.summary.map(row => <section key={row.group} className="surface-card category-progress-card" aria-label={groups[row.group]}>
        <h3>{groups[row.group]}</h3><Progress {...row} label={`${groups[row.group]} 준비율`} />
        <a href={legacyDetailUrl(project.id, row.group === 'PERSONNEL' ? 'personnel-panel' : row.group === 'PERFORMANCE' ? 'requirement-title' : 'document-picker')}>상세 관리 →</a>
      </section>)}
    </div>
  </>;
}
