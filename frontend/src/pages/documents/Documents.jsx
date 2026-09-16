import { useEffect, useRef, useState } from 'react';
import Button from '../../components/Button';
import * as api from '../../api/documents';
import sharedStyles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import documentStyles from '../../../../src/main/resources/static/documents/documents.css?inline';
import './documents.css';

export default function Documents() {
  const [rows, setRows] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [editor, setEditor] = useState(null);
  const [formError, setFormError] = useState('');
  const dialog = useRef(null);
  const mounted = useRef(false);
  const locked = useRef(false);

  async function load() {
    if (mounted.current) setLoading(true);
    try {
      const result = await api.listDocuments();
      if (mounted.current) setRows(result);
      return true;
    } catch (failure) {
      if (mounted.current) { setRows(null); setError(failure.message + ' 목록을 다시 조회해 주세요.'); }
      return false;
    } finally { if (mounted.current) setLoading(false); }
  }
  useEffect(() => { mounted.current = true; load(); return () => { mounted.current = false; }; }, []);
  useEffect(() => { if (editor) dialog.current.showModal(); }, [editor]);
  function closeEditor() { dialog.current?.close(); setEditor(null); }
  async function mutate(action, success, editing = false) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError(''); setMessage(''); setFormError('');
    try {
      await action();
      if (mounted.current) { setMessage(success); if (editing) closeEditor(); }
    } catch (failure) {
      if (mounted.current) { setError(failure.message); if (editing) setFormError(failure.message); }
    } finally {
      if (mounted.current) await load();
      locked.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  function save(event) {
    event.preventDefault();
    const name = new FormData(event.currentTarget).get('name').trim();
    if (!name) { setFormError('서류명을 입력하세요.'); return; }
    mutate(() => api.saveDocument(editor.id, name), editor.id == null ? '서류를 추가했습니다.' : '서류명을 수정했습니다.', true);
  }
  function upload(item, event) {
    const file = event.target.files[0];
    event.target.value = '';
    if (!file) return;
    if (file.size === 0 || file.size > 20 * 1024 * 1024) {
      setMessage(''); setError('비어 있지 않은 20MB 이하 파일을 선택하세요.'); return;
    }
    mutate(() => api.uploadDocument(item.id, file), '현재 파일을 저장했습니다. 기존 프로젝트 연결은 유지됩니다.');
  }
  const disabled = busy || loading || rows === null;
  return <main className="react-documents">
    <style>{sharedStyles + documentStyles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span aria-hidden="true">/</span><strong>서류 관리</strong></header>
    <div className="page-container submission-page">
      <header className="page-header page-heading page-heading-actions">
        <div><h1>서류 관리</h1><p>회사 공통서류의 현재 파일을 등록하고 교체합니다.</p></div>
        <Button className="ui-button ui-button-primary" disabled={disabled} onClick={() => { setFormError(''); setEditor({ name: '' }); }}>+ 서류 추가</Button>
      </header>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
      {rows === null && !loading && <Button onClick={() => { setError(''); load(); }}>다시 조회</Button>}
      <section className="surface-card documents-list" aria-label="회사 공통서류" aria-busy={busy || loading} tabIndex={0}>
        <table className="submission-project-table documents-table">
          <thead><tr>{['서류명', '현재 파일', '상태', '관리'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
          <tbody>
            {loading ? <tr><td colSpan={4} className="documents-empty" role="status">서류를 불러오는 중…</td></tr> : rows?.length === 0 ? <tr><td colSpan={4} className="documents-empty">등록된 회사 공통서류가 없습니다.</td></tr> : rows?.map(item => {
              const registered = Boolean(item.uploadedFileId || item.currentFileId);
              return <tr key={item.id} data-id={item.id}>
                <th scope="row" className="document-name">{item.name}</th>
                <td className="document-file"><p>{item.currentFilename || '파일 미등록'}</p>
                  {item.sizeBytes != null && <small>{new Intl.NumberFormat('ko-KR').format(item.sizeBytes)} bytes{item.uploadedAt ? ' · ' + new Date(item.uploadedAt).toLocaleString('ko-KR') : ''}</small>}</td>
                <td><span className="count-badge">{registered ? '등록됨' : '파일 미등록'}</span></td>
                <td><div className="document-actions">
                  <DocumentUpload item={item} registered={registered} disabled={disabled} onUpload={upload} />
                  <Button className="ui-button ui-button-secondary" disabled={disabled} onClick={() => { setFormError(''); setEditor(item); }}>서류명 수정</Button>
                  <Button className="ui-button ui-button-secondary" disabled={disabled} onClick={() => {
                    if (window.confirm('서류 목록에서 삭제할까요? 기존 프로젝트에 연결된 파일은 유지됩니다.')) mutate(() => api.deleteDocument(item.id), '서류를 삭제했습니다.');
                  }}>삭제</Button>
                </div></td>
              </tr>;
            })}
          </tbody>
        </table>
      </section>
    </div>
    {editor && <dialog ref={dialog} className="common-document-dialog" aria-labelledby="document-editor-title" onCancel={event => { event.preventDefault(); if (!busy) closeEditor(); }}>
      <form onSubmit={save} aria-busy={busy}>
        <header><h2 id="document-editor-title">{editor.id == null ? '서류 추가' : '서류명 수정'}</h2></header>
        <label>서류명 <input name="name" required maxLength={200} autoFocus defaultValue={editor.name} disabled={busy} /></label>
        {formError && <p role="alert">{formError}</p>}
        <footer><Button className="ui-button ui-button-secondary" disabled={busy} onClick={closeEditor}>취소</Button>
          <Button className="ui-button ui-button-primary" type="submit" disabled={disabled}>{editor.id == null ? '추가' : '저장'}</Button></footer>
      </form>
    </dialog>}
  </main>;
}

function DocumentUpload({ item, registered, disabled, onUpload }) {
  const input = useRef(null);
  return <><Button className="ui-button ui-button-primary" disabled={disabled} onClick={() => input.current.click()}>{registered ? '파일 교체' : '파일 등록'}</Button>
    <input ref={input} type="file" hidden disabled={disabled} aria-label={item.name + ' 파일 선택'} onChange={event => onUpload(item, event)} /></>;
}
