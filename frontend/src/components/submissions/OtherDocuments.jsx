import { useEffect, useMemo, useRef, useState } from 'react';
import { getOtherSavedState, saveOtherRequirements, saveOtherSelections } from '../../api/submissions/otherDocuments';
import Button from '../Button';
import OtherCandidates from './OtherCandidates';

const normalize = value => String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko-KR');
const key = row => JSON.stringify([row.category, normalize(row.documentName)]);
const manual = row => ({ category: row.category, documentName: row.documentName, sourceReference: row.sourceReference });

export default function OtherDocuments({ data, view, onSaved, onBusy }) {
  const saved = useRef(data); saved.current = data;
  const callbacks = useRef({ onSaved, onBusy }); callbacks.current = { onSaved, onBusy };
  const mounted = useRef(false);
  const task = useRef(null);
  const [desired, setDesired] = useState(() => data.requirements.map(manual));
  const desiredRef = useRef(desired);
  const [name, setName] = useState('');
  const [message, setMessage] = useState('');
  const [failed, setFailed] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [busy, setBusy] = useState(false);
  const [active, setActive] = useState(null);
  const [selectionError, setSelectionError] = useState('');
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (!task.current) {
      const next = data.requirements.map(manual); desiredRef.current = next; setDesired(next);
    }
  }, [data.requirements]);
  const other = view.rows.filter(row => row.group === 'OTHER');
  const options = useMemo(() => {
    const rows = []; const represented = new Set();
    data.masters.filter(master => master.category === 'OTHER').forEach(master => {
      const existing = other.find(row => (master.sourceReference && master.sourceReference === row.sourceReference)
        || (master.requirementCategory === row.category && normalize(master.name) === normalize(row.documentName)));
      if (existing) represented.add(existing.id);
      rows.push({ master, requirement: existing || { category: master.requirementCategory, documentName: master.name, sourceReference: master.sourceReference }, existing });
    });
    other.filter(row => !represented.has(row.id)).forEach(row => rows.push({ requirement: row, existing: row }));
    desired.filter(row => (row.category === 'OTHER' || other.some(item => key(item) === key(row))) && !rows.some(option => key(option.requirement) === key(row)))
      .forEach(row => rows.push({ requirement: row }));
    return rows;
  }, [data.masters, other, desired]);

  async function reconcile() {
    const result = await getOtherSavedState(data.project.id);
    saved.current = { ...saved.current, ...result };
    if (mounted.current) callbacks.current.onSaved(result);
    return result;
  }
  async function drain(current) {
    try {
      while (current.pending) {
        const requested = current.pending; current.pending = null;
        await saveOtherRequirements(current.id, requested);
        await reconcile();
      }
      if (mounted.current) { setMessage('저장됨'); setFailed(false); }
    } catch (error) {
      current.pending = null;
      try { await reconcile(); } catch { if (mounted.current) setUncertain(true); }
      if (mounted.current) { setMessage(`${error.message} 기존 상태를 다시 확인해 주세요.`); setFailed(true); }
    } finally {
      if (task.current === current) task.current = null;
      if (mounted.current) {
        const next = saved.current.requirements.map(manual); desiredRef.current = next; setDesired(next);
        setBusy(false); callbacks.current.onBusy(false);
      }
    }
  }
  function queue(next) {
    desiredRef.current = next; setDesired(next); setMessage('저장 중…'); setFailed(false);
    if (task.current) { task.current.pending = next; return; }
    const current = { id: data.project.id, pending: next, promise: null };
    task.current = current; setBusy(true); callbacks.current.onBusy(true);
    current.promise = drain(current);
  }
  function change(option, checked) {
    if (uncertain) return;
    const optionKey = key(option.requirement);
    const linked = saved.current.selections.some(file => file.requirementId === option.existing?.id);
    if (!checked && linked && !window.confirm('연결된 파일이 있는 서류입니다. 프로젝트에서 서류와 파일 연결을 제거할까요? 원본 파일은 유지됩니다.')) return;
    const next = desiredRef.current.filter(row => key(row) !== optionKey);
    if (checked) next.push(manual(option.requirement));
    if (!checked && active?.id === option.existing?.id) setActive(null);
    queue(next);
  }
  function add(event) {
    event.preventDefault();
    const documentName = name.trim().replace(/\s+/g, ' ');
    if (!documentName) { setMessage('추가할 서류명을 입력해 주세요.'); setFailed(true); return; }
    if (documentName.length > 200) { setMessage('서류명은 200자 이하로 입력해 주세요.'); setFailed(true); return; }
    if (desiredRef.current.some(row => normalize(row.documentName) === normalize(documentName))) {
      setMessage('이미 선택하거나 추가한 서류입니다.'); setFailed(true); return;
    }
    const customCount = desiredRef.current.filter(row => row.category === 'OTHER' && String(row.sourceReference || '').startsWith('CUSTOM_')).length;
    setName(''); queue([...desiredRef.current, { category: 'OTHER', documentName, sourceReference: `CUSTOM_${customCount + 1}` }]);
  }
  async function select(candidate) {
    if (busy || uncertain || !active) return false;
    setBusy(true); callbacks.current.onBusy(true); setSelectionError('');
    try {
      const selections = saved.current.selections.filter(file => file.requirementId !== active.id)
        .map(file => ({ requirementId: file.requirementId, fileId: file.fileId }));
      selections.push({ requirementId: active.id, fileId: candidate.fileId });
      await saveOtherSelections(data.project.id, selections);
      await reconcile();
      return true;
    } catch (error) {
      try { await reconcile(); } catch { if (mounted.current) setUncertain(true); }
      if (mounted.current) setSelectionError(error.message);
      return false;
    } finally {
      if (mounted.current) { setBusy(false); callbacks.current.onBusy(false); }
    }
  }
  return <section id="other-documents" className="surface-card other-documents" aria-labelledby="other-documents-title" aria-busy={busy}>
    <header className="panel-header"><h2 id="other-documents-title">기타 필요 서류</h2>
      <strong>{other.filter(row => row.ready).length} / {other.length}</strong></header>
    <p className="detail-help">프로젝트별 기타 서류를 선택하고 기존 FMS 후보 파일을 연결합니다.</p>
    <form className="other-document-add" onSubmit={add}><label>기타 서류 추가 <input value={name} maxLength={200}
      disabled={busy || uncertain} onChange={event => setName(event.target.value)} placeholder="직접 입력할 서류명" /></label>
      <Button className="ui-button ui-button-secondary" type="submit" disabled={busy || uncertain}>추가</Button></form>
    <p className="detail-help" role={failed ? 'alert' : 'status'}>{message}{uncertain ? ' 새로고침 후 다시 시도해 주세요.' : ''}</p>
    {!options.length ? <p className="panel-state">등록된 기타 서류가 없습니다.</p> : <div className="other-documents-scroll">
      <table className="submission-project-table"><thead><tr>{['서류명', '필요', '현재 파일', '상태', '관리'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{options.map(option => {
          const included = desired.some(row => key(row) === key(option.requirement));
          const existing = option.existing || other.find(row => key(row) === key(option.requirement));
          const selection = data.selections.find(file => file.requirementId === existing?.id);
          return <tr key={key(option.requirement)}>
            <th scope="row">{option.requirement.documentName}{!option.master && existing && <small>프로젝트에 보존된 서류</small>}</th>
            <td><input type="checkbox" aria-label={`${option.requirement.documentName} 필요`} checked={included} disabled={uncertain}
              onChange={event => change({ ...option, existing }, event.target.checked)} /></td>
            <td className="other-filename">{included ? selection?.originalFilename || '—' : '—'}</td>
            <td><span className={`requirement-state${included && selection ? ' complete' : ' attention'}`}>{!included ? '미선택' : selection ? '준비됨' : '미준비'}</span></td>
            <td><Button className="ui-button ui-button-secondary" disabled={!included || !existing?.id || busy || uncertain}
              onClick={() => { setSelectionError(''); setActive(existing); }}>파일 관리</Button></td>
          </tr>;
        })}</tbody></table>
    </div>}
    {active && <OtherCandidates projectId={data.project.id} requirement={active}
      selection={data.selections.find(file => file.requirementId === active.id)} busy={busy} uncertain={uncertain}
      error={selectionError} onSelect={select} onClose={() => setActive(null)} />}
  </section>;
}
