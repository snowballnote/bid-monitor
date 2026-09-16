import { useEffect, useRef, useState } from 'react';
import Button from '../Button';
import { getDocumentCandidates } from '../../api/submissions/personnel';

export default function PersonnelCandidates({ projectId, person, document, onClose }) {
  const dialog = useRef(null);
  const [state, setState] = useState({ status: 'loading' });
  const [revision, setRevision] = useState(0);
  useEffect(() => { dialog.current.showModal(); }, []);
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
  function close() { dialog.current.close(); onClose(); }
  return <dialog ref={dialog} className="common-document-dialog personnel-candidates" aria-labelledby="personnel-candidates-title"
    onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="personnel-candidates-title">{document.label || document.type} · {person.name}</h2></header>
    <section aria-label="현재 연결 파일" className="personnel-current-file">
      <h3>현재 연결 파일</h3><p>{document.filename || '파일 미등록'}</p>
      {document.filename && <small>{document.source === 'PC' ? '직접 업로드 · ' : document.source === 'FMS' ? 'FMS · ' : ''}{document.filenameDate || '파일명 날짜 없음'}{document.latestStatus ? ' · ' + document.latestStatus : ''}</small>}
    </section>
    <section aria-label="FMS 후보 목록" aria-busy={state.status === 'loading'}>
      <h3>FMS 후보 목록</h3>
      {state.status === 'loading' && <p role="status">FMS 후보 조회 중…</p>}
      {state.status === 'error' && <><p role="alert">{state.message}</p><Button className="ui-button ui-button-secondary" onClick={() => { setState({ status: 'loading' }); setRevision(value => value + 1); }}>다시 조회</Button></>}
      {state.status === 'success' && (state.rows.length ? <ul className="personnel-candidate-list">
        {state.rows.map(candidate => <li key={candidate.id}>
          <p>{candidate.filename}</p>
          <small>{candidate.filenameDate || '파일명 날짜 없음'}{candidate.note ? ' · ' + candidate.note : ''}</small>
          {candidate.recommended && <span className="requirement-state complete">최신 후보</span>}
        </li>)}
      </ul> : <p role="status">FMS 후보가 없습니다.</p>)}
    </section>
    <footer><Button className="ui-button ui-button-secondary" autoFocus onClick={close}>닫기</Button></footer>
  </dialog>;
}
