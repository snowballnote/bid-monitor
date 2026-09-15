import { useEffect, useRef } from 'react';
import Button from '../Button';

export default function ProjectForm({ project, busy, error, onSave, onCancel }) {
  const dialog = useRef(null);
  useEffect(() => {
    if (project) dialog.current.showModal();
  }, [project]);
  function submit(event) {
    event.preventDefault();
    if (busy) return;
    const data = new FormData(event.currentTarget);
    onSave({ projectName: data.get('projectName'), deadline: data.get('deadline') });
  }
  const form = <form id={project ? 'rename-submission-project' : 'create-submission-project'}
    className={project ? '' : 'project-name-form'} onSubmit={submit} aria-busy={busy}>
    {project && <header><h2 id="project-edit-title">프로젝트 수정</h2></header>}
    <label>프로젝트명 <input name="projectName" required maxLength={2000} autoFocus defaultValue={project?.projectName || ''} disabled={busy} /></label>
    <label>마감일 <input name="deadline" type="date" required defaultValue={project?.deadline || ''} disabled={busy} /></label>
    {error && <p id={project ? 'project-edit-message' : 'project-create-message'} className="submission-message" role="alert">{error}</p>}
    {project ? <footer>
      <Button className="ui-button ui-button-secondary" onClick={onCancel} disabled={busy}>취소</Button>
      <Button className="ui-button ui-button-primary" type="submit" disabled={busy}>프로젝트 저장</Button>
    </footer> : <Button className="ui-button ui-button-primary" type="submit" disabled={busy}>프로젝트 생성</Button>}
  </form>;
  return project ? <dialog ref={dialog} id="project-edit-dialog" className="common-document-dialog"
    aria-labelledby="project-edit-title" onCancel={event => { event.preventDefault(); if (!busy) onCancel(); }}>{form}</dialog> : form;
}
