import { useEffect, useRef, useState } from 'react';
import { getPerformanceCandidates, validatePerformanceUpload } from '../../api/submissions/performanceDocuments';
import Button from '../Button';

const date = value => {
  const parsed = value && new Date(value);
  return parsed && !Number.isNaN(parsed.getTime()) ? new Intl.DateTimeFormat('ko-KR').format(parsed) : '수정일 미확인';
};

export const evidenceLabel = { CERTIFICATE: '실적증명서', CONTRACT: '계약서', TAX_INVOICE: '세금계산서' };
const sourceLabel = { COMPANY_FILE: '회사 파일', FMS_DRIVE: 'FMS', DIRECT_UPLOAD: 'PC 직접 업로드' };

export default function PerformanceCandidates({ projectId, entry, onAddCandidate, onReplaceCandidate,
  onUpload, onDelete, onDownload, onClose }) {
  const dialog = useRef(null);
  const fileInput = useRef(null);
  const mounted = useRef(false);
  const locked = useRef(false);
  const [state, setState] = useState({ status: 'loading' });
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [editor, setEditor] = useState(null);
  const types = entry.resolvedStatus === 'IN_PROGRESS'
    ? [['CONTRACT', '계약서'], ['TAX_INVOICE', '세금계산서']]
    : [['CERTIFICATE', '실적증명서'], ['CONTRACT', '계약서'], ['TAX_INVOICE', '세금계산서']];
  const [evidenceType, setEvidenceType] = useState(types[0][0]);
  const [file, setFile] = useState(null);
  const [uploadError, setUploadError] = useState('');
  const evidences = Array.isArray(entry.evidences) ? entry.evidences : [];

  useEffect(() => { mounted.current = true; dialog.current.showModal(); return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPerformanceCandidates(projectId, entry.id, controller.signal).then(result => {
      if (!controller.signal.aborted) setState({ status: 'success', ...result });
    }).catch(candidateError => {
      if (!controller.signal.aborted) setState({ status: 'error', message: candidateError.message });
    });
    return () => controller.abort();
  }, [projectId, entry.id]);

  function begin(nextEditor) {
    setEditor(nextEditor);
    setEvidenceType(nextEditor?.evidence?.evidenceType || types[0][0]);
    setFile(null); setError(''); setUploadError('');
    if (fileInput.current) fileInput.current.value = '';
  }

  async function mutate(key, action) {
    if (locked.current) return;
    locked.current = true; setBusy(key); setError('');
    try {
      await action();
      if (mounted.current) begin(null);
    } catch (mutationError) {
      if (mounted.current) setError(mutationError.message);
    } finally {
      locked.current = false;
      if (mounted.current) setBusy('');
    }
  }

  async function select(candidate) {
    const replacing = editor?.mode === 'replace';
    await mutate(`candidate-${candidate.file.driveFileId}`, () => replacing
      ? onReplaceCandidate(editor.evidence, candidate, evidenceType)
      : onAddCandidate(candidate, evidenceType));
  }

  async function upload(event) {
    event.preventDefault();
    if (locked.current) return;
    const validation = validatePerformanceUpload(file);
    setUploadError(validation);
    if (validation) return;
    const replacing = editor?.mode === 'replace';
    locked.current = true; setBusy('upload'); setError('');
    try {
      await onUpload(file, evidenceType, replacing ? editor.evidence : null);
      if (mounted.current) begin(null);
    } catch (uploadFailure) {
      if (mounted.current) setUploadError(uploadFailure.message);
    } finally {
      locked.current = false;
      if (mounted.current) setBusy('');
    }
  }

  function remove(evidence) {
    if (!window.confirm(`${evidenceLabel[evidence.evidenceType] || '증빙'} 파일을 삭제할까요? 다른 증빙은 유지됩니다.`)) return;
    mutate(`delete-${evidence.evidenceId}`, () => onDelete(evidence));
  }

  function close() { if (locked.current) return; dialog.current.close(); onClose(); }
  const businessName = entry.info.businessName || '실적명 미등록';
  const replacing = editor?.mode === 'replace';
  return <dialog ref={dialog} className="common-document-dialog performance-candidates" aria-label={`${businessName} 증빙 관리`}
    onCancel={event => { event.preventDefault(); close(); }}>
    <header className="performance-candidates-heading"><span className="panel-kicker">EVIDENCE MANAGEMENT</span>
      <h2 id="performance-candidates-title">실적 증빙자료</h2><p>{businessName}</p></header>
    <section className="performance-evidence-section" aria-labelledby="performance-evidence-list-title">
      <header><div><h3 id="performance-evidence-list-title">증빙자료</h3><small>총 {evidences.length}개</small></div>
        <Button className="ui-button ui-button-primary" disabled={Boolean(busy)} onClick={() => begin({ mode: 'add' })}>+ 증빙 추가</Button></header>
      {!evidences.length ? <p className="performance-evidence-empty">증빙 없음</p>
        : <ul className="performance-evidence-list">{evidences.map(evidence => <li key={evidence.evidenceId}>
          <div className="performance-evidence-info"><strong>{evidenceLabel[evidence.evidenceType] || evidence.evidenceType}</strong>
            <span>{evidence.filename}</span><small>{sourceLabel[evidence.sourceType] || evidence.sourceType}</small></div>
          <div className="performance-evidence-actions">
            <Button className="ui-button ui-button-secondary" disabled={Boolean(busy)}
              onClick={() => mutate(`download-${evidence.evidenceId}`, () => onDownload(evidence))}>
              {busy === `download-${evidence.evidenceId}` ? '다운로드 중…' : '보기/다운로드'}</Button>
            <Button className="ui-button ui-button-secondary" disabled={Boolean(busy)}
              onClick={() => begin({ mode: 'replace', evidence })}>교체</Button>
            <Button className="ui-button ui-button-secondary performance-disconnect" disabled={Boolean(busy)}
              onClick={() => remove(evidence)}>{busy === `delete-${evidence.evidenceId}` ? '삭제 중…' : '삭제'}</Button>
          </div>
        </li>)}</ul>}
    </section>
    {error && <p role="alert">{error}</p>}
    {editor && <section className="performance-evidence-editor" aria-label={replacing ? '증빙 교체' : '증빙 추가'}>
      <header><div><h3>{replacing ? '증빙 교체' : '새 증빙 추가'}</h3>
        {replacing && <small>{editor.evidence.filename}</small>}</div>
        <Button className="ui-button ui-button-secondary" disabled={Boolean(busy)} onClick={() => begin(null)}>취소</Button></header>
      <label className="performance-evidence-type">증빙유형 <select value={evidenceType} disabled={Boolean(busy)}
        onChange={event => setEvidenceType(event.target.value)}>
        {types.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      <section className="performance-candidate-section" aria-label="FMS 후보 목록" aria-busy={state.status === 'loading'}>
        <h3>FMS 후보에서 선택</h3>
        {state.status === 'loading' && <p role="status">FMS 후보 조회 중…</p>}
        {state.status === 'error' && <p role="alert">{state.message}</p>}
        {state.status === 'success' && <>
          {state.nextAction && <p className="performance-candidate-guidance">{state.nextAction}</p>}
          {state.candidates.length ? <ul className="performance-candidate-list">
            {state.candidates.map(candidate => <li key={candidate.file.driveFileId}>
              <p>{candidate.file.originalFilename}</p>
              <small>FMS · {candidate.file.fileExt?.toUpperCase() || '파일'} · {date(candidate.file.lastModified)}</small>
              <div className="performance-candidate-meta"><span className="match-badge recommended">추천</span>
                <span>{candidate.reason || '추천 사유 없음'}</span>
                <Button className="ui-button ui-button-secondary" disabled={Boolean(busy)}
                  onClick={() => select(candidate)}>{busy === `candidate-${candidate.file.driveFileId}` ? '저장 중…' : replacing ? '이 파일로 교체' : '증빙 추가'}</Button>
              </div>
            </li>)}
          </ul> : <p role="status">FMS 후보가 없습니다.</p>}
        </>}
      </section>
      <section className="performance-upload" aria-labelledby="performance-upload-title">
        <h3 id="performance-upload-title">PC에서 직접 업로드</h3>
        <form onSubmit={upload} aria-busy={busy === 'upload'}>
          <label>업로드 파일 <input ref={fileInput} type="file" disabled={Boolean(busy)}
            onChange={event => { setFile(event.target.files[0] || null); setUploadError(''); }} /></label>
          <p className="performance-upload-filename">{file ? file.name : '선택된 파일 없음'}</p>
          <small>비어 있지 않은 20MB 이하 파일</small>
          {uploadError && <p role="alert">{uploadError}</p>}
          <Button className="ui-button ui-button-primary" type="submit" disabled={Boolean(busy)}>
            {busy === 'upload' ? '업로드 중…' : replacing ? '업로드하여 교체' : '업로드하여 추가'}</Button>
        </form>
      </section>
    </section>}
    <footer><Button className="ui-button ui-button-secondary" autoFocus disabled={Boolean(busy)} onClick={close}>닫기</Button></footer>
  </dialog>;
}
