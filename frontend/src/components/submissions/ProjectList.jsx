import ProjectMenu from './ProjectMenu';
import { detailUrl } from '../../api/submissions';

function formatDate(value) {
  const date = value ? new Date(value) : null;
  return !date || Number.isNaN(date.getTime()) ? '수정일 미확인'
    : new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}

export default function ProjectList({ rows, view, menuId, setMenuId, busy, onEdit, onDelete }) {
  function item(project) {
    const percent = project.total ? Math.round(project.prepared / project.total * 100) : 0;
    const count = `${project.prepared} / ${project.total}`;
    const deadline = project.deadline || '미설정';
    const open = event => {
      if (event.target.closest('.project-menu') || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      window.location.assign(detailUrl(project.id));
    };
    const props = { tabIndex: 0, role: 'link', 'aria-label': `${project.projectName} 상세`, onClick: open,
      onKeyDown: event => { if (event.target === event.currentTarget && ['Enter', ' '].includes(event.key)) open(event); } };
    const menu = <ProjectMenu project={project} open={menuId === project.id} disabled={busy}
      onToggle={() => setMenuId(menuId === project.id ? null : project.id)} onClose={() => setMenuId(null)} onEdit={onEdit} onDelete={onDelete} />;
    return view === 'card' ? <article key={project.id} className="submission-project-card" {...props}>
      <h3>{project.projectName}</h3><p>마감일 {deadline}</p><p>준비 {count}</p><p>진행률 {percent}%</p>
      <p>최근 수정일 {formatDate(project.updatedAt)}</p>{menu}
    </article> : <tr key={project.id} {...props}>
      <td>{project.projectName}</td><td>{project.orderingAgency || project.agencyName || '—'}</td><td>{deadline}</td><td>{count}</td>
      <td><div className="project-list-progress"><div className="progress-track" role="progressbar" aria-label={`${project.projectName} 준비율`}
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}><span style={{ width: `${percent}%` }} /></div><span>{percent}%</span></div></td>
      <td>{formatDate(project.updatedAt)}</td><td>{menu}</td>
    </tr>;
  }
  return <div id="submission-project-items" className={view === 'card' ? 'project-cards' : ''}>
    {view === 'card' ? rows.map(item) : <table className="submission-project-table project-list-table">
      <thead><tr>{['프로젝트명', '발주기관', '마감일', '준비 현황', '진행률', '최근 수정일', ''].map((label, index) => <th scope="col" key={index}>{label}</th>)}</tr></thead>
      <tbody>{rows.map(item)}</tbody></table>}
  </div>;
}
