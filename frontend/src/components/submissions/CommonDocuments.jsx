import { useEffect, useRef, useState } from 'react';
import { collectCommonDocuments } from '../../api/submissions';
import { getSavedDocuments } from '../../api/submissions/commonDocuments';

const normalize = value => String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko-KR');
const key = row => JSON.stringify([row.category, normalize(row.documentName)]);
const manual = row => ({ category: row.category, documentName: row.documentName, sourceReference: row.sourceReference });

export default function CommonDocuments({ data, view, onSaved, onBusy }) {
  const saved = useRef(data);
  saved.current = data;
  const callbacks = useRef({ onSaved, onBusy });
  callbacks.current = { onSaved, onBusy };
  const mounted = useRef(true);
  const task = useRef(null);
  const [desired, setDesired] = useState(() => data.requirements.map(manual));
  const desiredRef = useRef(desired);
  const [message, setMessage] = useState('');
  const [failed, setFailed] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    mounted.current = true;
    // 목록·관리 링크로 이동할 때 진행 중인 체크 저장을 먼저 마친다.
    const navigate = event => {
      const anchor = event.target.closest?.('a[href]');
      if (!task.current || !anchor || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
      const href = anchor.href;
      event.preventDefault(); event.stopPropagation();
      task.current.promise.then(() => { if (mounted.current) window.location.assign(href); });
    };
    document.addEventListener('click', navigate, true);
    return () => { mounted.current = false; document.removeEventListener('click', navigate, true); };
  }, []);
  useEffect(() => {
    if (!task.current) {
      const next = data.requirements.map(manual);
      desiredRef.current = next; setDesired(next);
    }
  }, [data.requirements]);

  const common = view.rows.filter(row => row.group === 'COMPANY_COMMON');
  const represented = new Set();
  const options = data.masters.filter(master => master.category === 'COMPANY_COMMON').map(master => {
    const existing = common.find(row => (master.sourceReference && master.sourceReference === row.sourceReference)
      || (master.requirementCategory === row.category && normalize(master.name) === normalize(row.documentName)));
    if (existing) represented.add(existing.id);
    return { master, requirement: existing || { category: master.requirementCategory, documentName: master.name, sourceReference: master.sourceReference }, existing };
  });
  common.filter(row => !represented.has(row.id)).forEach(row => options.push({ requirement: row, existing: row }));

  async function drain(current) {
    try {
      while (current.pending) {
        const requested = current.pending; current.pending = null; current.inFlight = requested;
        await collectCommonDocuments(current.id, requested);
        const result = await getSavedDocuments(current.id);
        saved.current = { ...saved.current, ...result };
        if (mounted.current) callbacks.current.onSaved(result);
        if (JSON.stringify(current.pending) === JSON.stringify(requested)) current.pending = null;
      }
      if (mounted.current) { setMessage('저장됨'); setFailed(false); }
    } catch (error) {
      current.pending = null;
      try {
        const result = await getSavedDocuments(current.id);
        saved.current = { ...saved.current, ...result };
        if (mounted.current) callbacks.current.onSaved(result);
      } catch {
        if (mounted.current) setUncertain(true);
      }
      if (mounted.current) { setMessage(error.message + ' 체크 상태를 확인하고 다시 선택해 주세요.'); setFailed(true); }
    } finally {
      if (task.current === current) task.current = null;
      if (mounted.current) {
        const next = saved.current.requirements.map(manual);
        desiredRef.current = next; setDesired(next); setBusy(false); callbacks.current.onBusy(false);
      }
    }
  }
  function change(option, checked) {
    if (uncertain) return;
    const id = key(option.requirement);
    const linked = saved.current.selections.some(file => file.requirementId === option.existing?.id);
    const pendingFile = task.current?.inFlight?.some(row => key(row) === id)
      && !!(option.master?.currentFileId || option.master?.uploadedFileId);
    if (!checked && (linked || pendingFile) && !window.confirm('연결된 파일이 있는 서류입니다. 이 프로젝트에서 서류와 파일 연결을 제거할까요? 원본 파일은 유지됩니다.')) return;
    const next = desiredRef.current.filter(row => key(row) !== id);
    if (checked) next.push(manual(option.requirement));
    desiredRef.current = next; setDesired(next); setMessage('저장 중…'); setFailed(false);
    if (task.current) { task.current.pending = next; return; }
    const current = { id: data.project.id, pending: next, inFlight: null, promise: null };
    task.current = current; setBusy(true); callbacks.current.onBusy(true);
    current.promise = drain(current);
  }
  return <section id="common-documents" className="surface-card common-documents" aria-labelledby="common-documents-title" aria-busy={busy}>
    <header className="panel-header"><h2 id="common-documents-title">회사 공통서류</h2><a href="/documents/" className="ui-button ui-button-secondary">서류 관리</a></header>
    <p className="detail-help">체크하면 프로젝트에 포함됩니다. 연결된 프로젝트 파일은 공통서류 파일을 교체해도 유지됩니다.</p>
    <p className="detail-help" role={failed ? 'alert' : 'status'}>{message}{uncertain ? ' 새로고침 후 다시 시도해 주세요.' : ''}</p>
    {!options.length ? <p className="panel-state">등록된 회사 공통서류가 없습니다.</p> : <div className="common-documents-scroll">
      <table className="submission-project-table"><thead><tr>{['상태', '선택', '서류명', '현재 파일', '관리'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
        <tbody>{options.map(option => {
          const id = key(option.requirement);
          const included = desired.some(row => key(row) === id);
          const file = data.selections.find(file => file.requirementId === option.existing?.id);
          const pending = busy && included !== !!option.existing;
          const status = pending ? '저장 중' : !included ? '미선택' : file ? '준비됨' : '미준비';
          return <tr key={id}>
            <td><span className={`requirement-state${included && file ? ' complete' : ' attention'}`}>{status}</span></td>
            <td><input type="checkbox" aria-label={`${option.requirement.documentName} 선택`} checked={included} disabled={uncertain} onChange={event => change(option, event.target.checked)} /></td>
            <th scope="row">{option.requirement.documentName}{!option.master && <small className="common-snapshot-label">프로젝트에 보존된 서류</small>}</th>
            <td>{included ? file?.originalFilename || '파일 없음' : option.master?.currentFilename || '파일 없음'}
              {included && file && <small className="common-snapshot-label">프로젝트 연결 파일</small>}</td>
            <td><a href="/documents/">{included && !file ? '파일 등록' : '서류 관리'}</a></td>
          </tr>;
        })}</tbody></table>
    </div>}
  </section>;
}
