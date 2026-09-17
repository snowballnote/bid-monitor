import { useEffect, useRef, useState } from 'react';
import { getPerformanceEntries } from '../../api/submissions/performanceDocuments';

export const performanceEntryReady = entry => entry?.info?.selectedFileId != null
  || entry?.info?.selectedDriveFileId != null || entry?.info?.selectedUploadedFileId != null;

export default function PerformanceDocuments({ project, required, onLoaded }) {
  const callback = useRef(onLoaded); callback.current = onLoaded;
  const [state, setState] = useState(() => required && project.performanceProjectId
    ? { status: 'loading' } : { status: 'empty' });
  useEffect(() => {
    if (!required || !project.performanceProjectId) {
      setState({ status: 'empty' }); callback.current([]); return undefined;
    }
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPerformanceEntries(project.performanceProjectId, controller.signal).then(entries => {
      if (!controller.signal.aborted) { setState({ status: 'success', entries }); callback.current(entries); }
    }).catch(error => {
      if (!controller.signal.aborted) { setState({ status: 'error', message: error.message }); callback.current([]); }
    });
    return () => controller.abort();
  }, [project.performanceProjectId, required]);
  const entries = state.status === 'success' ? state.entries : [];
  const prepared = entries.filter(performanceEntryReady).length;
  return <section id="performance-documents" className="surface-card performance-documents" aria-label="실적증빙 목록">
    <header className="panel-header"><h2 id="performance-documents-title">실적증빙</h2>
      {state.status === 'success' && <strong>{prepared} / {entries.length}</strong>}</header>
    {state.status === 'loading' && <p className="panel-state" role="status">실적증빙 목록을 불러오는 중입니다.</p>}
    {state.status === 'error' && <p className="panel-state error" role="alert">{state.message}</p>}
    {state.status === 'empty' && <p className="panel-state">{required ? '연결된 실적 프로젝트가 없습니다.' : '필요한 실적증빙이 없습니다.'}</p>}
    {state.status === 'success' && (!entries.length ? <p className="panel-state">등록된 실적이 없습니다.</p>
      : <div className="performance-documents-scroll" tabIndex={0} aria-label="실적증빙 준비 현황 표">
        <table className="submission-project-table"><thead><tr>{['상태', '실적명', '발주기관', '수행기간', '현재 파일'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
          <tbody>{entries.map((entry, index) => {
            const ready = performanceEntryReady(entry); const info = entry.info;
            return <tr key={entry.id ?? index}>
              <td><span className={`requirement-state${ready ? ' complete' : ' attention'}`}>{ready ? '준비됨' : '미준비'}</span></td>
              <th scope="row">{info.businessName || '실적명 미등록'}</th>
              <td>{info.client || '—'}</td><td>{info.businessPeriod || '—'}</td>
              <td className="performance-filename" title={entry.selectedFilename || ''}>{entry.selectedFilename || (ready ? '파일명 미확인' : '—')}</td>
            </tr>;
          })}</tbody></table>
      </div>)}
  </section>;
}
