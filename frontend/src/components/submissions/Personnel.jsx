import { useEffect, useRef, useState } from 'react';
import Button from '../Button';
import { legacyDetailUrl } from '../../api/submissions';
import * as api from '../../api/submissions/personnel';
import './personnel.css';
import PersonnelCandidates from './PersonnelCandidates';

export default function Personnel({ projectId, people, onSaved, onBusy }) {
  const [searchOpen, setSearchOpen] = useState(false);
  const [candidateTarget, setCandidateTarget] = useState(null);
  const [results, setResults] = useState(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const mounted = useRef(false);
  const task = useRef(null);
  const searchRequest = useRef(null);
  const dialog = useRef(null);
  const callbacks = useRef({ onSaved, onBusy });
  callbacks.current = { onSaved, onBusy };
  useEffect(() => {
    mounted.current = true;
    const navigate = event => {
      const anchor = event.target.closest?.('a[href]');
      if (!task.current || !anchor || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault(); event.stopPropagation();
      const href = anchor.href;
      task.current.then(() => { if (mounted.current) window.location.assign(href); });
    };
    document.addEventListener('click', navigate, true);
    return () => { mounted.current = false; searchRequest.current?.abort(); document.removeEventListener('click', navigate, true); };
  }, []);
  useEffect(() => { if (searchOpen) dialog.current.showModal(); }, [searchOpen]);
  function closeSearch() {
    searchRequest.current?.abort(); dialog.current?.close(); setSearchOpen(false); setSearching(false);
  }
  async function search(event) {
    event.preventDefault();
    const query = new FormData(event.currentTarget).get('query').trim();
    searchRequest.current?.abort();
    if (!query) { setResults(null); setSearching(false); setSearchError('이름을 입력하세요.'); return; }
    const controller = new AbortController(); searchRequest.current = controller;
    setSearching(true); setResults(null); setSearchError('');
    try {
      const rows = await api.searchPeople(projectId, query, controller.signal);
      if (mounted.current && !controller.signal.aborted) setResults(rows);
    } catch (failure) {
      if (mounted.current && !controller.signal.aborted) setSearchError(failure.message);
    } finally { if (mounted.current && !controller.signal.aborted) setSearching(false); }
  }
  function mutate(action, success, close = false) {
    if (task.current || uncertain) return Promise.resolve(false);
    setBusy(true); callbacks.current.onBusy(true); setError(''); setMessage('');
    task.current = (async () => {
      try {
        const rows = await action();
        if (mounted.current) { callbacks.current.onSaved(rows); setMessage(success); if (close) closeSearch(); }
        return true;
      } catch (failure) {
        let recovered = false;
        try {
          const rows = await api.getPeople(projectId);
          if (mounted.current) { callbacks.current.onSaved(rows); recovered = true; }
        } catch { /* Keep the last confirmed state until retry succeeds. */ }
        if (mounted.current) { setError(failure.message); setUncertain(!recovered); }
        return false;
      } finally {
        task.current = null;
        if (mounted.current) { setBusy(false); callbacks.current.onBusy(false); }
      }
    })();
    return task.current;
  }
  async function retry() {
    if (task.current) return;
    setBusy(true); callbacks.current.onBusy(true);
    task.current = (async () => {
      try {
        const rows = await api.getPeople(projectId);
        if (mounted.current) { callbacks.current.onSaved(rows); setUncertain(false); setError(''); }
      } catch (failure) { if (mounted.current) setError(failure.message); }
      finally { task.current = null; if (mounted.current) { setBusy(false); callbacks.current.onBusy(false); } }
    })();
    return task.current;
  }
  const candidatePerson = people.find(person => person.id === candidateTarget?.personId);
  const candidateDocument = candidatePerson?.documents.find(doc => doc.type === candidateTarget?.type);
  const documents = people.flatMap(person => person.documents).filter(doc => doc.needed);
  return <section id="react-personnel" className="surface-card react-personnel" aria-labelledby="react-personnel-title" aria-busy={busy}>
    <header className="panel-header"><h2 id="react-personnel-title">인력·자격 <span className="count-badge">{documents.filter(doc => doc.filename).length} / {documents.length}</span></h2>
      <Button className="ui-button ui-button-primary" disabled={busy || uncertain} onClick={() => { setResults(null); setSearchError(''); setSearchOpen(true); }}>+ 인력 추가</Button></header>
    <p className="detail-help">필요 서류를 체크하면 즉시 저장됩니다. 파일 변경은 파일 관리에서 진행하세요.</p>
    {!searchOpen && !candidateTarget && error && <p className="detail-help" role="alert">{error}</p>}
    {message && <p className="detail-help" role="status">{message}</p>}
    {busy && <p className="detail-help" role="status">인력 상태 확인 중…</p>}
    {uncertain && <p className="detail-help" role="alert">최신 상태를 확인하지 못했습니다. 마지막으로 확인한 상태입니다. <Button onClick={retry} disabled={busy}>인력 다시 조회</Button></p>}
    {!people.length ? <p className="panel-state">선택된 인력이 없습니다.</p> : people.map((person, index) => {
      const needed = person.documents.filter(doc => doc.needed);
      return <div className="react-personnel-person" key={person.id || index} data-person-id={person.id}>
        <header className="react-personnel-heading"><div><strong>{person.name}</strong><small>{person.department || '부서 미등록'}</small></div>
          <span className="count-badge" aria-label={`${person.name} 준비율`}>{needed.filter(doc => doc.filename).length} / {needed.length}</span>
          <Button className="ui-button ui-button-secondary" disabled={busy || uncertain} onClick={() => {
            if (window.confirm(`${person.name} 인력을 프로젝트에서 제외할까요?`)) mutate(() => api.removePerson(projectId, person.id), '인력을 제외했습니다.');
          }}>인력 제거</Button>
          <Button href={legacyDetailUrl(projectId, 'personnel-panel')} className="ui-button ui-button-secondary">파일 관리</Button></header>
        <div className="react-personnel-scroll" tabIndex={0} aria-label={`${person.name} 서류 상태`}>
          <table className="submission-project-table"><thead><tr>{['상태', '필요', '서류명', '연결 파일'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
            <tbody>{person.documents.map(doc => <tr key={doc.type}>
              <td><span className={`requirement-state${!doc.needed ? '' : doc.filename ? ' complete' : ' attention'}`}>{!doc.needed ? '미선택' : doc.filename ? '준비됨' : '미준비'}</span></td>
              <td><input type="checkbox" checked={doc.needed} disabled={busy || uncertain} onChange={event => {
                const needed = event.target.checked;
                mutate(() => api.setDocumentNeeded(projectId, person.id, doc.type, needed), '필요 서류를 저장했습니다.');
              }} aria-label={`${person.name} ${doc.label || doc.type} 필요`} /></td>
              <th scope="row">{doc.label || doc.type}</th><td>{doc.filename || '—'}
                <Button className="ui-button ui-button-secondary personnel-candidates-open" disabled={busy || uncertain}
                  onClick={() => setCandidateTarget({ personId: person.id, type: doc.type })}>후보 조회</Button></td>
            </tr>)}</tbody></table>
        </div>
      </div>;
    })}
    {candidateDocument && <PersonnelCandidates key={JSON.stringify([projectId, candidatePerson.id, candidateDocument.type])}
      projectId={projectId} person={candidatePerson} document={candidateDocument} busy={busy} uncertain={uncertain} error={error}
      onSelect={candidateId => mutate(() => api.selectDocumentCandidate(projectId, candidatePerson.id, candidateDocument.type, candidateId), '파일을 연결했습니다.')}
      onRetry={retry} onClose={() => setCandidateTarget(null)} />}
    {searchOpen && <dialog ref={dialog} className="common-document-dialog react-personnel-search" aria-labelledby="personnel-search-title" onCancel={event => { event.preventDefault(); if (!busy) closeSearch(); }}>
      <form onSubmit={search}><header><h2 id="personnel-search-title">인력 추가</h2></header>
        <label>이름 검색 <input name="query" maxLength={100} required autoFocus disabled={busy} /></label>
        <Button type="submit" className="ui-button ui-button-primary" disabled={busy}>검색</Button>
        {searching && <p role="status">검색 중…</p>}
        {searchError && <p role="alert">{searchError}</p>}
        {error && <p role="alert">{error}</p>}
        {uncertain && <p role="alert">최신 인력 상태를 다시 확인해 주세요. <Button onClick={retry} disabled={busy}>인력 다시 조회</Button></p>}
        {results?.length === 0 && <p>인력 파일 인덱스에 일치하는 이름이 없습니다. 이름과 인덱스를 확인하세요.</p>}
        <ul className="react-personnel-results">{results?.map(option => {
          const added = people.some(person => person.name === option.name);
          return <li key={option.name}><span>{option.name}<small>{option.department || '부서 미등록'}</small></span>
            <Button className="ui-button ui-button-secondary" disabled={busy || uncertain || added} onClick={() => mutate(() => api.addPerson(projectId, option), '인력을 추가했습니다.', true)}>{added ? '추가됨' : '선택'}</Button></li>;
        })}</ul>
        <footer><Button className="ui-button ui-button-secondary" disabled={busy} onClick={closeSearch}>닫기</Button></footer>
      </form>
    </dialog>}
  </section>;
}
