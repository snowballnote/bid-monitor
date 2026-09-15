import { useEffect, useRef, useState } from 'react';
import * as api from '../../api/submissions';
import Button from '../../components/Button';
import ProjectList from '../../components/submissions/ProjectList';
import ProjectForm from '../../components/submissions/ProjectForm';
import styles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import './submissions.css';

const VIEW_KEY = 'biz-assist.submissions.view';
export default function SubmissionProjects() {
  const [rows, setRows] = useState([]);
  const [status, setStatus] = useState('loading');
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [reload, setReload] = useState(0);
  const [view, setView] = useState(() => { try { return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'card'; } catch { return 'card'; } });
  const [create, setCreate] = useState(false);
  const [editing, setEditing] = useState(null);
  const [menuId, setMenuId] = useState(null);
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading'); setError('');
    api.getProjects(controller.signal).then(data => {
      if (!controller.signal.aborted) { setRows(data); setStatus('success'); }
    }).catch(error => {
      if (!controller.signal.aborted) { setError(error.message); setStatus('error'); }
    });
    return () => controller.abort();
  }, [reload]);
  function changeView(next) {
    setMenuId(null); setView(next);
    try { localStorage.setItem(VIEW_KEY, next); } catch { /* 저장 제한 시에도 현재 보기는 유지 */ }
  }
  async function save(values) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setFormError('');
    try {
      const project = editing ? await api.updateProject(editing.id, values) : await api.createProject(values);
      if (!mounted.current) return;
      if (editing) { setRows(items => items.map(item => item.id === editing.id ? { ...item, ...project } : item)); setEditing(null); }
      else window.location.assign(api.detailUrl(project.id));
    } catch (error) { if (mounted.current) setFormError(error.message); }
    finally { locked.current = false; if (mounted.current) setBusy(false); }
  }
  async function remove(project) {
    if (locked.current || !window.confirm('이 프로젝트와 선택한 제출서류 연결을 삭제할까요? 원본 파일은 삭제되지 않습니다.')) return;
    locked.current = true; setBusy(true); setError('');
    try {
      await api.deleteProject(project.id);
      if (mounted.current) setRows(items => items.filter(item => item.id !== project.id));
    } catch (error) { if (mounted.current) setError(error.message); }
    finally { locked.current = false; if (mounted.current) setBusy(false); }
  }
  return <main className="react-submissions">
    <style>{styles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span aria-hidden="true">/</span><strong>서류 모으기</strong></header>
    <div className="page-container submission-page">
      <header className="page-header page-heading"><span className="page-eyebrow">SUBMISSION</span><h1>서류 모으기</h1><p>필요한 회사 공통서류를 체크하고 저장된 현재 파일을 모읍니다.</p></header>
      <section id="submission-project-list" className="surface-card">
        <header className="document-picker-heading"><h2>프로젝트 목록</h2><div className="project-controls">
          <Button className="ui-button ui-button-primary" disabled={busy || status !== 'success'} onClick={() => { setCreate(!create); setFormError(''); }}>+ 새 프로젝트</Button>
          <Button className="ui-button ui-button-secondary" aria-pressed={view === 'card'} onClick={() => changeView('card')}>카드형</Button>
          <Button className="ui-button ui-button-secondary" aria-pressed={view === 'list'} onClick={() => changeView('list')}>리스트형</Button>
        </div></header>
        {create && <ProjectForm busy={busy} error={formError} onSave={save} />}
        <p id="submission-project-message" role={error ? 'alert' : 'status'}>{error || (status === 'loading' ? '프로젝트 목록을 불러오는 중입니다.' : !rows.length ? '등록된 프로젝트가 없습니다.' : '')}</p>
        {status === 'error' && <Button className="ui-button ui-button-secondary" onClick={() => setReload(value => value + 1)}>다시 시도</Button>}
        {status === 'success' && <ProjectList rows={rows} view={view} menuId={menuId} setMenuId={setMenuId} busy={busy}
          onEdit={project => { setEditing(project); setFormError(''); setCreate(false); }} onDelete={remove} />}
      </section>
      {editing && <ProjectForm key={editing.id} project={editing} busy={busy} error={formError} onSave={save} onCancel={() => setEditing(null)} />}
    </div>
  </main>;
}
