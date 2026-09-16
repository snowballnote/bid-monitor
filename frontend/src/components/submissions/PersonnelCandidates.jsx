import { useEffect, useRef, useState } from 'react';
import Button from '../Button';
import { getDocumentCandidates } from '../../api/submissions/personnel';

export default function PersonnelCandidates({ projectId, person, document, onClose, onSelect, onClear, onRetry, busy, uncertain, error }) {
  const dialog = useRef(null);
  const selecting = useRef(false);
  const mounted = useRef(false);
  const [saving, setSaving] = useState(false);
  const [state, setState] = useState({ status: 'loading' });
  const [revision, setRevision] = useState(0);
  useEffect(() => { mounted.current = true; dialog.current.showModal(); return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getDocumentCandidates(projectId, person.id, document.type, controller.signal).then(rows => {
      if (!controller.signal.aborted) setState({ status: 'success', rows });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ status: 'error', message: error.message });
    });
    return () => controller.abort();
  }, [projectId, person.id, document.type, revision]);
  async function saveConnection(action) {
    if (selecting.current || busy || uncertain) return;
    selecting.current = true; setSaving(true);
    try {
      await action();
      if (mounted.current) { setState({ status: 'loading' }); setRevision(value => value + 1); }
    } finally {
      selecting.current = false;
      if (mounted.current) setSaving(false);
    }
  }
  function select(candidate) {
    if (document.fmsReferenceId !== candidate.id) return saveConnection(() => onSelect(candidate.id));
  }
  function clear() {
    if (document.filename) return saveConnection(onClear);
  }
  function close() { if (selecting.current || busy) return; dialog.current.close(); onClose(); }
  return <dialog ref={dialog} className="common-document-dialog personnel-candidates" aria-labelledby="personnel-candidates-title"
    onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="personnel-candidates-title">{document.label || document.type} · {person.name}</h2></header>
    <section aria-label="현재 연결 파일" className="personnel-current-file">
      <h3>현재 연결 파일</h3><p>{document.filename || '파일 미등록'}</p>
      {document.filename && <small>{document.source === 'PC' ? '직접 업로드 · ' : document.source === 'FMS' ? 'FMS · ' : ''}{document.filenameDate || '파일명 날짜 없음'}{document.latestStatus ? ' · ' + document.latestStatus : ''}</small>}
      {document.filename && <Button className="ui-button ui-button-secondary personnel-disconnect" disabled={saving || busy || uncertain} onClick={clear}>연결 해제</Button>}
    </section>
    {(saving || busy) && <p role="status">파일 연결 상태 저장 중…</p>}
    {error && <p role="alert">{error}</p>}
    {uncertain && <p role="alert">최신 인력 상태를 확인하지 못했습니다. 마지막으로 확인한 연결 상태입니다.
      <Button className="ui-button ui-button-secondary" disabled={saving || busy} onClick={onRetry}>인력 다시 조회</Button></p>}
    <section aria-label="FMS 후보 목록" aria-busy={state.status === 'loading'}>
      <h3>FMS 후보 목록</h3>
      {state.status === 'loading' && <p role="status">FMS 후보 조회 중…</p>}
      {state.status === 'error' && <><p role="alert">{state.message}</p><Button className="ui-button ui-button-secondary" disabled={saving || busy} onClick={() => { setState({ status: 'loading' }); setRevision(value => value + 1); }}>다시 조회</Button></>}
      {state.status === 'success' && (state.rows.length ? <ul className="personnel-candidate-list">
        {state.rows.map(candidate => <li key={candidate.id}>
          <p>{candidate.filename}</p>
          <small>{candidate.filenameDate || '파일명 날짜 없음'}{candidate.note ? ' · ' + candidate.note : ''}</small>
          <div className="personnel-candidate-actions">
            {candidate.recommended && <span className="requirement-state complete">최신 후보</span>}
            <Button className="ui-button ui-button-secondary" disabled={saving || busy || uncertain || document.fmsReferenceId === candidate.id}
              onClick={() => select(candidate)}>{document.fmsReferenceId === candidate.id ? '선택됨' : '선택'}</Button>
          </div>
        </li>)}
      </ul> : <p role="status">FMS 후보가 없습니다.</p>)}
    </section>
    <footer><Button className="ui-button ui-button-secondary" autoFocus disabled={saving || busy} onClick={close}>닫기</Button></footer>
  </dialog>;
}
