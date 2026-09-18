import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import * as api from '../../api/performances';
import Button from '../../components/Button';
import PerformanceProjectDialog from '../../components/performances/PerformanceProjectDialog';
import PerformanceDocuments from '../../components/submissions/PerformanceDocuments';
import { performanceDday, performanceStatus } from './performanceView';
import sharedStyles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import '../submissions/submissions.css';
import './performances.css';

export default function PerformanceDetail() {
  const { projectId } = useParams();
  const mounted = useRef(false); const locked = useRef(false); const entriesLoaded = useRef(false);
  const [state, setState] = useState({ status: 'loading' }); const [revision, setRevision] = useState(0);
  const [editing, setEditing] = useState(false); const [busy, setBusy] = useState(false); const [formError, setFormError] = useState('');
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const refreshProject = useCallback(async () => {
    try {
      const project = await api.getPerformanceProject(projectId);
      if (mounted.current) setState({ status: 'success', project });
    } catch { /* entry mutation succeeded; retain the last confirmed project header */ }
  }, [projectId]);
  const syncAfterEntryChange = useCallback(() => {
    if (entriesLoaded.current) refreshProject();
    else entriesLoaded.current = true;
  }, [refreshProject]);
  useEffect(() => {
    const controller = new AbortController(); entriesLoaded.current = false; setState({ status: 'loading' });
    api.getPerformanceProject(projectId, controller.signal).then(project => {
      if (!controller.signal.aborted) setState({ status: 'success', project });
    }).catch(error => { if (!controller.signal.aborted) setState({ status: 'error', message: error.message }); });
    return () => controller.abort();
  }, [projectId, revision]);

  async function save(values) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setFormError('');
    try {
      const project = await api.updatePerformanceProject(projectId, values);
      if (mounted.current) { setState({ status: 'success', project }); setEditing(false); }
    } catch (error) { if (mounted.current) setFormError(error.message); }
    finally { locked.current = false; if (mounted.current) setBusy(false); }
  }
  const project = state.project;
  return <main className="react-performances react-performance-detail"><style>{sharedStyles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span aria-hidden="true">/</span>
      <Link to="/performances">실적 관리</Link><span aria-hidden="true">/</span><strong>프로젝트 상세</strong></header>
    <div className="page-container submission-page">
      {state.status === 'loading' && <p className="workspace-state" role="status">실적 프로젝트를 불러오는 중입니다.</p>}
      {state.status === 'error' && <div className="workspace-state"><p role="alert">{state.message}</p>
        <Button className="ui-button ui-button-secondary" onClick={() => setRevision(value => value + 1)}>다시 시도</Button></div>}
      {state.status === 'success' && <>
        <section className="surface-card performance-project-overview"><div><span className="page-eyebrow">PERFORMANCE</span><h1>{project.name}</h1></div>
          <dl><div><dt>마감일</dt><dd>{project.deadline}</dd></div><div><dt>D-day</dt><dd>{performanceDday(project.daysRemaining)}</dd></div>
            <div><dt>상태</dt><dd>{performanceStatus(project.status)}</dd></div></dl>
          <div className="performance-overview-actions"><Link className="ui-button ui-button-secondary" to="/performances">목록</Link>
            <Button className="ui-button ui-button-primary" disabled={busy} onClick={() => { setFormError(''); setEditing(true); }}>프로젝트 수정</Button></div></section>
        <PerformanceDocuments project={{ performanceProjectId: project.id }} required onLoaded={syncAfterEntryChange} />
      </>}
    </div>
    {editing && <PerformanceProjectDialog project={project} busy={busy} error={formError} onSave={save}
      onClose={() => { if (!busy) setEditing(false); }} />}
  </main>;
}
