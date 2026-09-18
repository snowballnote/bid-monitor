import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import * as api from '../../api/performances';
import Button from '../../components/Button';
import PerformanceProjectDialog from '../../components/performances/PerformanceProjectDialog';
import { performanceDday, performanceStatus } from './performanceView';
import sharedStyles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import './performances.css';

export default function PerformanceProjects() {
  const navigate = useNavigate();
  const mounted = useRef(false); const locked = useRef(false);
  const [state, setState] = useState({ status: 'loading', rows: [] });
  const [editor, setEditor] = useState(null); const [busy, setBusy] = useState(false); const [formError, setFormError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController(); setState(current => ({ ...current, status: 'loading', message: '' }));
    api.getPerformanceProjects(controller.signal).then(rows => {
      if (!controller.signal.aborted) setState({ status: 'success', rows });
    }).catch(error => { if (!controller.signal.aborted) setState({ status: 'error', rows: [], message: error.message }); });
    return () => controller.abort();
  }, [revision]);

  async function save(values) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setFormError('');
    try {
      const saved = editor?.id ? await api.updatePerformanceProject(editor.id, values) : await api.createPerformanceProject(values);
      if (!mounted.current) return;
      if (editor?.id) {
        setState(current => ({ ...current, rows: current.rows.map(row => row.id === saved.id ? saved : row) }));
        setEditor(null);
      } else navigate(`/performances/${encodeURIComponent(saved.id)}`);
    } catch (error) { if (mounted.current) setFormError(error.message); }
    finally { locked.current = false; if (mounted.current) setBusy(false); }
  }

  return <main className="react-performances"><style>{sharedStyles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span aria-hidden="true">/</span><strong>실적 관리</strong></header>
    <div className="page-container submission-page">
      <header className="page-header page-heading performance-page-heading"><div><span className="page-eyebrow">PERFORMANCE</span><h1>실적 프로젝트</h1>
        <p>실적과 증빙 파일을 프로젝트별로 관리합니다.</p></div>
        <Button className="ui-button ui-button-primary" disabled={state.status !== 'success' || busy}
          onClick={() => { setFormError(''); setEditor({}); }}>+ 새 프로젝트</Button></header>
      <section className="surface-card performance-project-list" aria-label="실적 프로젝트 목록">
        {state.status === 'loading' && <p className="performance-project-state" role="status">프로젝트 목록을 불러오는 중입니다.</p>}
        {state.status === 'error' && <div className="performance-project-state"><p role="alert">{state.message}</p>
          <Button className="ui-button ui-button-secondary" onClick={() => setRevision(value => value + 1)}>다시 시도</Button></div>}
        {state.status === 'success' && !state.rows.length && <p className="performance-project-state">등록된 실적 프로젝트가 없습니다.</p>}
        {state.status === 'success' && state.rows.length > 0 && <div className="performance-project-scroll" tabIndex={0}>
          <table className="submission-project-table"><thead><tr>{['프로젝트명', '마감일', 'D-day', '상태', '관리'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
            <tbody>{state.rows.map(project => <tr key={project.id}>
              <th scope="row"><Link to={`/performances/${encodeURIComponent(project.id)}`}>{project.name}</Link></th>
              <td>{project.deadline}</td><td>{performanceDday(project.daysRemaining)}</td>
              <td><span className="count-badge">{performanceStatus(project.status)}</span></td>
              <td><Button className="ui-button ui-button-secondary" disabled={busy}
                onClick={() => { setFormError(''); setEditor(project); }}>수정</Button></td>
            </tr>)}</tbody></table></div>}
      </section>
    </div>
    {editor && <PerformanceProjectDialog key={editor.id || 'new'} project={editor.id ? editor : null} busy={busy} error={formError}
      onSave={save} onClose={() => { if (!busy) setEditor(null); }} />}
  </main>;
}
