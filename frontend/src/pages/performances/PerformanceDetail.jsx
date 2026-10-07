import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams, useLocation } from 'react-router-dom';
import * as api from '../../api/performances';
import Button from '../../components/Button';
import PerformanceProjectDialog from '../../components/performances/PerformanceProjectDialog';
import DriveIndexStatus from '../../components/performances/DriveIndexStatus';
import PerformanceDocuments, { performanceEntryReady } from '../../components/submissions/PerformanceDocuments';
import { performanceDday, performanceStatus } from './performanceView';
import sharedStyles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import '../submissions/submissions.css';
import './performances.css';
import { getCaseResource } from '../../api/submissions';

export default function PerformanceDetail() {
  const { projectId } = useParams();
  const { search } = useLocation();
  const caseId = new URLSearchParams(search).get('caseId');
  const [returnContext, setReturnContext] = useState(null);
  useEffect(() => {
    const controller = new AbortController(); setReturnContext(null);
    if (/^[1-9]\d*$/.test(caseId || '')) getCaseResource(caseId, '', controller.signal).then(project => {
      if (!controller.signal.aborted && String(project.id) === caseId && project.performanceProjectId === projectId)
        setReturnContext({ caseId, projectId });
    }).catch(() => {});
    return () => controller.abort();
  }, [caseId, projectId]);
  const mounted = useRef(false); const locked = useRef(false); const downloadLocked = useRef(false); const entriesLoaded = useRef(false);
  const [state, setState] = useState({ status: 'loading' }); const [revision, setRevision] = useState(0);
  const [editing, setEditing] = useState(false); const [busy, setBusy] = useState(false); const [formError, setFormError] = useState('');
  const [entries, setEntries] = useState(null); const [downloading, setDownloading] = useState(false); const [downloadError, setDownloadError] = useState('');
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const refreshProject = useCallback(async () => {
    try {
      const project = await api.getPerformanceProject(projectId);
      if (mounted.current) setState({ status: 'success', project });
    } catch { /* entry mutation succeeded; retain the last confirmed project header */ }
  }, [projectId]);
  const syncAfterEntryChange = useCallback(rows => {
    setEntries(rows); setDownloadError('');
    if (entriesLoaded.current) refreshProject();
    else entriesLoaded.current = true;
  }, [refreshProject]);
  useEffect(() => {
    const controller = new AbortController(); entriesLoaded.current = false; setEntries(null); setDownloadError(''); setState({ status: 'loading' });
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
  async function download() {
    if (downloadLocked.current || !entries?.some(performanceEntryReady)) return;
    downloadLocked.current = true;
    setDownloading(true); setDownloadError('');
    try {
      const result = await api.downloadPerformanceEvidence(projectId);
      const url = URL.createObjectURL(result.blob); const link = document.createElement('a');
      link.href = url; link.download = result.filename; document.body.append(link); link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { if (mounted.current) setDownloadError(error.message); }
    finally { downloadLocked.current = false; if (mounted.current) setDownloading(false); }
  }
  const project = state.project;
  const selectedFiles = entries?.filter(performanceEntryReady).length ?? 0;
  const totalEntries = entries?.length ?? 0;
  const progress = totalEntries ? Math.round(selectedFiles / totalEntries * 100) : 0;
  return <main className="react-performances react-performance-detail"><style>{sharedStyles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span aria-hidden="true">/</span>
      <Link to="/performances">실적 관리</Link><span aria-hidden="true">/</span><strong>프로젝트 상세</strong></header>
    <div className="page-container submission-page">
      {state.status === 'loading' && <p className="workspace-state" role="status">실적 프로젝트를 불러오는 중입니다.</p>}
      {state.status === 'error' && <div className="workspace-state"><p role="alert">{state.message}</p>
        <Button className="ui-button ui-button-secondary" onClick={() => setRevision(value => value + 1)}>다시 시도</Button></div>}
      {state.status === 'success' && <>
        <header className="performance-detail-heading"><span className="page-eyebrow">PERFORMANCE</span><h1>실적증빙 관리</h1>
          <p>실적 명세를 등록하고 관련 증빙 파일을 찾아 준비합니다.</p></header>
        <section className="surface-card performance-project-overview"><div className="performance-project-name"><small>프로젝트</small><h2>{project.name}</h2></div>
          <dl><div><dt>마감일</dt><dd>{project.deadline}</dd></div><div><dt>D-day</dt><dd>{performanceDday(project.daysRemaining)}</dd></div>
            <div><dt>상태</dt><dd>{performanceStatus(project.status)}</dd></div></dl>
          <div className="performance-download-state"><span>{entries === null ? '선택 파일 확인 중…' : selectedFiles ? `선택 파일 ${selectedFiles}개` : '선택된 증빙파일이 없습니다.'}</span>
            {downloadError && <small role="alert">{downloadError}</small>}</div>
          <div className="performance-overview-actions"><Link className="ui-button ui-button-secondary" to="/performances">목록</Link>
            {returnContext?.caseId === caseId && returnContext?.projectId === projectId && <Link className="ui-button ui-button-secondary" to={`/submissions/${caseId}`}>원래 제출서류로 돌아가기</Link>}
            <Button className="ui-button ui-button-secondary" disabled={downloading || entries === null || selectedFiles === 0}
              onClick={download}>{downloading ? 'ZIP 생성 중…' : 'ZIP 다운로드'}</Button>
            <Button className="ui-button ui-button-primary" disabled={busy} onClick={() => { setFormError(''); setEditing(true); }}>프로젝트 수정</Button></div></section>
        <DriveIndexStatus compact />
        <div className="performance-workspace">
          <PerformanceDocuments project={{ performanceProjectId: project.id }} required onLoaded={syncAfterEntryChange} />
          <aside className="performance-aside" aria-label="실적 준비 보조 정보">
            <section className="surface-card performance-readiness" aria-labelledby="performance-readiness-title">
              <header><h2 id="performance-readiness-title">필요 서류 / 준비 현황</h2><strong>{progress}%</strong></header>
              <div className="progress-track" role="progressbar" aria-label="실적증빙 준비율" aria-valuemin="0" aria-valuemax="100" aria-valuenow={progress}>
                <span style={{ width: `${progress}%` }} /></div>
              <dl><div><dt>전체</dt><dd>{entries === null ? '확인 중' : `${totalEntries}건`}</dd></div>
                <div><dt>준비 완료</dt><dd>{entries === null ? '—' : `${selectedFiles}건`}</dd></div>
                <div><dt>미준비</dt><dd>{entries === null ? '—' : `${Math.max(totalEntries - selectedFiles, 0)}건`}</dd></div></dl>
            </section>
            <section className="surface-card performance-guide" aria-labelledby="performance-guide-title"><h2 id="performance-guide-title">진행 가이드</h2>
              <ol><li>실적표를 붙여넣거나 실적을 직접 추가합니다.</li><li>Drive에서 관련 파일을 찾아 연결합니다.</li>
                <li>등록된 실적과 파일을 검토하고 필요 시 수정합니다.</li><li>준비가 끝나면 ZIP으로 내려받습니다.</li></ol>
            </section>
          </aside>
        </div>
      </>}
    </div>
    {editing && <PerformanceProjectDialog project={project} busy={busy} error={formError} onSave={save}
      onClose={() => { if (!busy) setEditing(false); }} />}
  </main>;
}
