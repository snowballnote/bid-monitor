import { useEffect, useRef, useState } from 'react';
import { getPerformanceCandidates } from '../../api/submissions/performanceDocuments';
import Button from '../Button';

const date = value => {
  const parsed = value && new Date(value);
  return parsed && !Number.isNaN(parsed.getTime()) ? new Intl.DateTimeFormat('ko-KR').format(parsed) : '수정일 미확인';
};

export default function PerformanceCandidates({ projectId, entry, onClose }) {
  const dialog = useRef(null);
  const [state, setState] = useState({ status: 'loading' });
  useEffect(() => {
    dialog.current.showModal();
    const controller = new AbortController();
    getPerformanceCandidates(projectId, entry.id, controller.signal).then(result => {
      if (!controller.signal.aborted) setState({ status: 'success', ...result });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ status: 'error', message: error.message });
    });
    return () => controller.abort();
  }, [projectId, entry.id]);
  function close() { dialog.current.close(); onClose(); }
  return <dialog ref={dialog} className="common-document-dialog performance-candidates" aria-labelledby="performance-candidates-title"
    onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="performance-candidates-title">{entry.info.businessName || '실적명 미등록'} 파일 관리</h2></header>
    <section className="performance-current-file" aria-label="현재 연결 파일">
      <h3>현재 연결 파일</h3><p>{entry.selectedFilename || '파일 미등록'}</p>
    </section>
    <section aria-label="FMS 후보 목록" aria-busy={state.status === 'loading'}>
      <h3>FMS 후보 목록</h3>
      {state.status === 'loading' && <p role="status">FMS 후보 조회 중…</p>}
      {state.status === 'error' && <p role="alert">{state.message}</p>}
      {state.status === 'success' && <>
        {state.nextAction && <p className="performance-candidate-guidance">{state.nextAction}</p>}
        {state.candidates.length ? <ul className="performance-candidate-list">
          {state.candidates.map(candidate => <li key={candidate.file.driveFileId}>
            <p>{candidate.file.originalFilename}</p>
            <small>FMS · {candidate.file.fileExt?.toUpperCase() || '파일'} · {date(candidate.file.lastModified)}</small>
            <div className="performance-candidate-meta"><span className="match-badge recommended">추천</span>
              <span>{candidate.reason || '추천 사유 없음'}</span></div>
          </li>)}
        </ul> : <p role="status">FMS 후보가 없습니다.</p>}
      </>}
    </section>
    <footer><Button className="ui-button ui-button-secondary" autoFocus onClick={close}>닫기</Button></footer>
  </dialog>;
}
