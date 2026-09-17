import { useEffect, useRef, useState } from 'react';
import { getOtherCandidates } from '../../api/submissions/otherDocuments';
import Button from '../Button';

const date = value => {
  const parsed = value && new Date(value);
  return parsed && !Number.isNaN(parsed.getTime()) ? new Intl.DateTimeFormat('ko-KR').format(parsed) : '수정일 미확인';
};

export default function OtherCandidates({ projectId, requirement, selection, busy, uncertain, error, onSelect, onClose }) {
  const dialog = useRef(null);
  const mounted = useRef(false);
  const selecting = useRef(false);
  const [saving, setSaving] = useState(false);
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState({ status: 'loading' });
  useEffect(() => { mounted.current = true; dialog.current.showModal(); return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getOtherCandidates(projectId, requirement.id, controller.signal).then(rows => {
      if (!controller.signal.aborted) setState({ status: 'success', rows });
    }).catch(fetchError => {
      if (!controller.signal.aborted) setState({ status: 'error', message: fetchError.message });
    });
    return () => controller.abort();
  }, [projectId, requirement.id, revision]);
  async function select(candidate) {
    if (selecting.current || busy || uncertain || selection?.fileId === candidate.fileId) return;
    selecting.current = true; setSaving(true);
    try { await onSelect(candidate); }
    finally { selecting.current = false; if (mounted.current) setSaving(false); }
  }
  function close() {
    if (selecting.current || busy) return;
    dialog.current.close(); onClose();
  }
  return <dialog ref={dialog} className="common-document-dialog other-candidates" aria-labelledby="other-candidates-title"
    onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="other-candidates-title">{requirement.documentName} 파일 관리</h2></header>
    <section className="other-current-file" aria-label="현재 연결 파일">
      <h3>현재 연결 파일</h3><p>{selection?.originalFilename || '파일 미등록'}</p>
    </section>
    {(saving || busy) && <p role="status">파일 연결 상태 저장 중…</p>}
    {error && <p role="alert">{error}</p>}
    {uncertain && <p role="alert">최신 기타 서류 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.</p>}
    <section aria-label="FMS 후보 목록" aria-busy={state.status === 'loading'}>
      <h3>FMS 후보 목록</h3>
      {state.status === 'loading' && <p role="status">FMS 후보 조회 중…</p>}
      {state.status === 'error' && <><p role="alert">{state.message}</p><Button className="ui-button ui-button-secondary"
        disabled={saving || busy} onClick={() => setRevision(value => value + 1)}>다시 조회</Button></>}
      {state.status === 'success' && (state.rows.length ? <ul className="other-candidate-list">
        {state.rows.map(candidate => {
          const selected = selection?.fileId === candidate.fileId;
          return <li key={candidate.fileId} className={selected ? 'selected' : ''}>
            <p>{candidate.originalFilename}</p>
            <small>{candidate.fileExt?.toUpperCase() || '파일'} · {date(candidate.fileModifiedAt || candidate.updatedAt)}</small>
            {candidate.matchReasons?.length > 0 && <small>{candidate.matchReasons.join(' · ')}</small>}
            <div className="other-candidate-actions">
              <span className={`match-badge ${candidate.matchLevel === 'EXACT' ? 'exact' : 'recommended'}`}>{candidate.matchLevel === 'EXACT' ? '정확한 일치' : '추천'}</span>
              <Button className="ui-button ui-button-secondary" disabled={saving || busy || uncertain || selected}
                onClick={() => select(candidate)}>{selected ? '선택됨' : '선택'}</Button>
            </div>
          </li>;
        })}
      </ul> : <p role="status">FMS 후보가 없습니다.</p>)}
    </section>
    <footer><Button className="ui-button ui-button-secondary" autoFocus disabled={saving || busy} onClick={close}>닫기</Button></footer>
  </dialog>;
}
