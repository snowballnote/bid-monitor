import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../Button';
import { openPerformanceLink } from '../../api/submissions/performanceLink';

export default function PerformanceLink({ project, required }) {
  const navigate = useNavigate();
  const alive = useRef(true); const locked = useRef(false);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  if (!required) return null;
  async function open() {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    try {
      const saved = await openPerformanceLink(project.id, required);
      if (alive.current) navigate(`/performances/${encodeURIComponent(saved.performanceProjectId)}?caseId=${encodeURIComponent(project.id)}`);
    } catch {
      if (alive.current) setError('실적 프로젝트 연결에 실패했습니다. 기존 정보는 유지됩니다. 다시 시도해 주세요.');
    } finally { locked.current = false; if (alive.current) setBusy(false); }
  }
  return <div className="performance-row-actions" aria-label="실적 프로젝트 연결">
    <span>{project.performanceProjectId ? `연결된 실적 프로젝트: ${project.performanceProjectId}` : '연결된 실적 프로젝트가 없습니다.'}</span>
    <Button className="ui-button ui-button-secondary" disabled={busy} onClick={open}>
      {busy ? '연결 확인 중…' : project.performanceProjectId ? '실적 프로젝트 관리' : '실적 프로젝트 생성·연결'}</Button>
    {error && <p role="alert">{error}</p>}
  </div>;
}
