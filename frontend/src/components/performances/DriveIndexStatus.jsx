import { useEffect, useRef, useState } from 'react';
import { getDriveIndexStatus, refreshDriveIndex } from '../../api/performances';
import Button from '../Button';

const labels = { NOT_BUILT: '미구축', REFRESHING: '갱신 중', SUCCESS: '성공', FAILED: '실패' };
const classes = { NOT_BUILT: 'attention', REFRESHING: 'attention', SUCCESS: 'complete', FAILED: 'attention' };

function timestamp(value) {
  if (!value) return '없음';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '확인 불가' : date.toLocaleString('ko-KR');
}

export default function DriveIndexStatus() {
  const mounted = useRef(false); const locked = useRef(false);
  const [state, setState] = useState({ status: 'loading', rows: null, error: '' });
  const [refreshing, setRefreshing] = useState(false);
  useEffect(() => {
    mounted.current = true; const controller = new AbortController();
    getDriveIndexStatus(controller.signal).then(rows => {
      if (!controller.signal.aborted) setState({ status: 'success', rows, error: '' });
    }).catch(error => { if (!controller.signal.aborted) setState({ status: 'error', rows: null, error: error.message }); });
    return () => { mounted.current = false; controller.abort(); };
  }, []);

  async function refresh() {
    if (locked.current) return;
    locked.current = true; setRefreshing(true); setState(current => ({ ...current, error: '' }));
    try {
      await refreshDriveIndex();
      const rows = await getDriveIndexStatus();
      if (mounted.current) setState({ status: 'success', rows, error: '' });
    } catch (error) {
      if (mounted.current) setState(current => ({ ...current, status: current.rows ? 'success' : 'error', error: error.message }));
    } finally {
      locked.current = false; if (mounted.current) setRefreshing(false);
    }
  }

  const remoteRefreshing = state.rows?.some(root => root.state.status === 'REFRESHING') || false;
  return <section className="surface-card drive-index-panel" aria-labelledby="drive-index-title">
    <header className="document-picker-heading"><div><span className="panel-kicker">DRIVE INDEX</span><h2 id="drive-index-title">Drive 검색 인덱스</h2></div>
      <Button className="ui-button ui-button-secondary" disabled={refreshing || remoteRefreshing || state.status === 'loading'} onClick={refresh}>
        {refreshing ? '갱신 중…' : 'Drive 인덱스 갱신'}</Button></header>
    {state.status === 'loading' && <p className="drive-index-state" role="status">인덱스 상태 확인 중…</p>}
    {state.error && <p className="drive-index-state error" role="alert">{state.error}</p>}
    {state.status === 'success' && state.rows.length === 0 && <p className="drive-index-state">검색 폴더 설정이 필요합니다.</p>}
    {state.rows?.length > 0 && <div className="drive-index-scroll" tabIndex={0}>
      <table className="submission-project-table"><thead><tr>{['root', '상태', '마지막 성공', '파일 수'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{state.rows.map(root => <tr key={root.label}>
          <th scope="row">{root.label}</th>
          <td><span className={`requirement-state ${classes[root.state.status] || 'attention'}`}>{labels[root.state.status] || '확인 필요'}</span>
            {root.state.status === 'FAILED' && <small>FMS 연결·설정·접근 권한·탐색 제한을 확인하세요.</small>}</td>
          <td>{timestamp(root.state.lastSuccessAt)}</td><td>{root.state.fileCount.toLocaleString('ko-KR')}개</td>
        </tr>)}</tbody></table></div>}
  </section>;
}
