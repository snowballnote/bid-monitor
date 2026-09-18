import { useEffect, useRef, useState } from 'react';
import { validatePerformanceProject } from '../../api/performances';
import Button from '../Button';

export default function PerformanceProjectDialog({ project, busy, error, onSave, onClose }) {
  const dialog = useRef(null);
  const [values, setValues] = useState(() => ({ name: project?.name || '', deadline: project?.deadline || '' }));
  const [errors, setErrors] = useState({});
  const editing = Boolean(project);
  useEffect(() => { dialog.current.showModal(); }, []);

  function change(event) {
    const { name, value } = event.target;
    setValues(current => ({ ...current, [name]: value }));
    setErrors(current => ({ ...current, [name]: '' }));
  }
  function submit(event) {
    event.preventDefault();
    if (busy) return;
    const next = validatePerformanceProject(values);
    setErrors(next);
    if (!Object.keys(next).length) onSave(values);
  }

  return <dialog ref={dialog} className="common-document-dialog performance-project-dialog"
    aria-labelledby="performance-project-dialog-title"
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <form onSubmit={submit} aria-busy={busy} noValidate>
      <header><h2 id="performance-project-dialog-title">실적 프로젝트 {editing ? '수정' : '생성'}</h2></header>
      <label>프로젝트명
        <input name="name" required maxLength={500} autoFocus value={values.name} disabled={busy} onChange={change}
          aria-invalid={Boolean(errors.name)} aria-describedby={errors.name ? 'performance-project-name-error' : undefined} />
        {errors.name && <small id="performance-project-name-error" className="field-error">{errors.name}</small>}
      </label>
      <label>마감일
        <input name="deadline" type="date" required value={values.deadline} disabled={busy} onChange={change}
          aria-invalid={Boolean(errors.deadline)} aria-describedby={errors.deadline ? 'performance-project-deadline-error' : undefined} />
        {errors.deadline && <small id="performance-project-deadline-error" className="field-error">{errors.deadline}</small>}
      </label>
      {error && <p role="alert">{error}</p>}
      <footer><Button className="ui-button ui-button-secondary" disabled={busy} onClick={onClose}>취소</Button>
        <Button className="ui-button ui-button-primary" type="submit" disabled={busy}>{busy ? '저장 중…' : editing ? '저장' : '생성'}</Button></footer>
    </form>
  </dialog>;
}
