import { useEffect, useState } from 'react';
import * as api from '../api/dashboard';
import Button from '../components/Button';
import StatusBadge from '../components/StatusBadge';

function useResource(load) {
  const [state, setState] = useState({ status: 'loading', data: null });
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).then(data => {
      if (!controller.signal.aborted) setState({ status: 'success', data });
    }).catch(() => {
      if (!controller.signal.aborted) setState({ status: 'error', data: null });
    });
    return () => controller.abort();
  }, [load]);
  return state;
}

const recent = rows => [...rows].sort((a, b) => (Date.parse(b.updatedAt || b.createdAt) || 0)
  - (Date.parse(a.updatedAt || a.createdAt) || 0)).slice(0, 3);
const validProgress = row => Number.isInteger(row.prepared) && Number.isInteger(row.total)
  && row.prepared >= 0 && row.total >= row.prepared;

function Summary({ id, href, icon, title, resource, count, message, failure = '조회 실패' }) {
  return <a className="summary-card" href={href}>
    <span className="summary-icon" aria-hidden="true">{icon}</span>
    <div><h2>{title}</h2><strong id={`${id}-count`} aria-live="polite">
      {resource.status === 'success' && count != null ? `${count}건` : resource.status === 'error' && id === 'bid-check' ? '-' : '—'}
    </strong><small id={`${id}-message`}>{resource.status === 'loading' ? '확인 중'
      : resource.status === 'error' ? failure : message}</small></div>
    <span className="summary-arrow" aria-hidden="true">›</span>
  </a>;
}

function State({ error = false, children }) {
  return <p className={`dashboard-state${error ? ' error' : ''}`}>{children}</p>;
}

function Deadline({ deadline }) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const days = deadline ? Math.round((new Date(deadline + 'T00:00:00') - today) / 86400000) : NaN;
  return <StatusBadge tone={days <= 3 ? 'attention' : ''}>{Number.isNaN(days) ? '마감일 미설정'
    : days === 0 ? 'D-day' : days > 0 ? `D-${days}` : `D+${-days}`}</StatusBadge>;
}

function SubmissionWork({ resource }) {
  if (resource.status === 'loading') return <State>서류 프로젝트 확인 중</State>;
  if (resource.status === 'error') return <State error>서류 프로젝트를 불러오지 못했습니다.</State>;
  if (!resource.data.length) return <State>등록된 서류 프로젝트가 없습니다.</State>;
  return recent(resource.data).map(row => {
    const percent = row.total ? Math.round(row.prepared / row.total * 100) : 0;
    return <article className="work-item" key={row.id}>
      <div className="work-heading"><h3>{row.projectName || '사업명 미등록'}</h3><Deadline deadline={row.deadline} /></div>
      {validProgress(row) ? <div className="work-progress">
        <progress max="100" value={percent} aria-label={`${row.projectName || '서류'} 준비율`} />
        <span>{row.prepared} / {row.total} · {percent}%</span>
      </div> : <p className="work-meta">진행률 확인 필요</p>}
      <div className="work-footer"><span className="work-meta">{row.deadline ? `마감일 ${row.deadline}` : '마감일 미설정'}</span>
        <Button href={`/submissions/?caseId=${encodeURIComponent(row.id)}`}>계속 작업</Button></div>
    </article>;
  });
}

function PerformanceWork({ resource }) {
  if (resource.status === 'loading') return <State>실적증빙 확인 중</State>;
  if (resource.status === 'error') return <State error>실적 프로젝트를 불러오지 못했습니다.</State>;
  const { projects, evidence } = resource.data;
  if (!projects.length) return <State>등록된 실적 프로젝트가 없습니다.</State>;
  return recent(projects).map(project => {
    const row = evidence.get(project.id);
    return <article className="work-item" key={project.id}>
      <h3>{project.name || '사업명 미등록'}</h3>
      <div className="work-badges">{!row ? <StatusBadge tone="attention">증빙 조회 실패</StatusBadge>
        : !row.total ? <StatusBadge>등록된 실적 없음</StatusBadge> : <>
          {row.prepared > 0 && <StatusBadge tone="ready">증빙 등록 {row.prepared}건</StatusBadge>}
          {row.total > row.prepared && <StatusBadge tone="attention">파일 미등록 {row.total - row.prepared}건</StatusBadge>}
        </>}</div>
      <div className="work-footer"><span className="work-meta">{row ? `전체 실적 ${row.total}건` : '파일 관리에서 확인하세요'}</span>
        <Button href={`/performances/index.html?project=${encodeURIComponent(project.id)}`}>파일 관리</Button></div>
    </article>;
  });
}

function formatDateTime(value) {
  if (!value) return '확인 기록 없음';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value
    : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function Notices({ resource }) {
  const notices = resource.data || [];
  const latest = notices.map(notice => notice.lastSeenAt).filter(Boolean).sort().at(-1);
  return <section className="dashboard-panel" aria-labelledby="recent-notice-title">
    <div className="dashboard-panel-header"><div><h2 id="recent-notice-title">최근 중요공지</h2>
      <p><span id="pia-notice-count">{resource.status === 'success' ? `${notices.length}건` : resource.status === 'error' ? '-' : '—'}</span>
        {' · 최근 확인 '}<span id="notice-last-checked">{resource.status === 'success' ? formatDateTime(latest) : resource.status === 'error' ? '-' : '—'}</span></p>
    </div><a href="/notices/">전체 보기</a></div>
    <p id="notice-summary-message" className="summary-message" aria-live="polite">{resource.status === 'error' ? '외부공지 현황을 불러오지 못했습니다.' : ''}</p>
    {resource.status === 'loading' && <div id="recent-notice-loading" className="dashboard-state">중요공지를 확인하는 중입니다.</div>}
    {resource.status === 'error' && <div id="recent-notice-error" className="dashboard-state error">중요공지를 불러오지 못했습니다.</div>}
    {resource.status === 'success' && (!notices.length
      ? <div id="recent-notice-empty" className="dashboard-state">표시할 PIA 관련 공지가 없습니다.</div>
      : <div id="recent-notice-list" className="recent-notice-list">{notices.slice(0, 3).map(notice =>
        <article className="recent-notice-item" key={notice.id}>
          <time className="recent-notice-date">{notice.publishedDate || '게시일 미상'}</time>
          <div className="recent-notice-content"><a className="recent-notice-title" href={`/notices/?noticeId=${encodeURIComponent(notice.id)}`}>{notice.title || '제목 없음'}</a>
            {Array.isArray(notice.matchedKeywords) && <div className="recent-keywords">{notice.matchedKeywords.slice(0, 4).map((word, index) => <span className="recent-keyword" key={index}>{word}</span>)}</div>}
          </div><span className="recent-notice-arrow" aria-hidden="true">›</span>
        </article>)}</div>)}
  </section>;
}

export default function Dashboard() {
  const submissions = useResource(api.getSubmissions);
  const performances = useResource(api.getPerformances);
  const documents = useResource(api.getDocuments);
  const bids = useResource(api.getBids);
  const notices = useResource(api.getNotices);
  const cases = submissions.data || [];
  const projects = performances.data?.projects || [];
  const evidence = [...(performances.data?.evidence.values() || [])];
  const complete = evidence.every(Boolean);
  const common = (documents.data || []).filter(row => row.category === 'COMPANY_COMMON');
  return <main className="page-container dashboard-page">
    <header className="page-heading dashboard-heading"><h1>Biz Assist</h1><p>업무 현황과 필요한 작업을 한눈에 확인하세요.</p></header>
    <section className="dashboard-summary-grid" aria-label="업무 요약">
      <Summary id="submission" href="#/submissions" icon="▣" title="진행 중 서류 프로젝트" resource={submissions}
        count={cases.every(validProgress) ? cases.filter(row => !row.total || row.prepared < row.total).length : null}
        message={cases.length ? '서류 준비가 남은 프로젝트' : '등록된 프로젝트 없음'} />
      <Summary id="performance" href="/performances/index.html" icon="✓" title="준비 완료 실적" resource={performances}
        count={complete ? evidence.reduce((sum, row) => sum + row.prepared, 0) : null}
        message={!complete ? '일부 증빙 조회 실패' : projects.length ? '증빙 파일이 연결된 실적' : '등록된 실적 없음'} />
      <Summary id="document" href="/documents/" icon="▤" title="파일 미등록 문서" resource={documents}
        count={common.filter(row => !row.uploadedFileId && !row.currentFileId).length}
        message={common.length ? '회사 공통서류 기준' : '등록된 공통서류 없음'} />
      <Summary id="bid-check" href="/bids/" icon="⌕" title="확인 필요한 입찰공고" resource={bids}
        count={(bids.data || []).filter(row => row.reviewStatus === '추가확인필요').length}
        message="추가 확인 필요 공고" failure="입찰공고 현황을 불러오지 못했습니다." />
    </section>
    <section aria-labelledby="recent-work-title"><h2 id="recent-work-title" className="section-title">최근 작업</h2>
      <div className="recent-work-grid">
        <section className="dashboard-panel" aria-labelledby="submission-title">
          <div className="dashboard-panel-header"><h2 id="submission-title">서류 모으기 진행 현황</h2><a href="#/submissions">전체보기 →</a></div>
          <div id="recent-submissions" aria-live="polite"><SubmissionWork resource={submissions} /></div>
        </section>
        <section className="dashboard-panel" aria-labelledby="performance-title">
          <div className="dashboard-panel-header"><h2 id="performance-title">실적증빙 현황</h2><a href="/performances/index.html">전체보기 →</a></div>
          <div id="recent-performances" aria-live="polite"><PerformanceWork resource={performances} /></div>
        </section>
      </div>
    </section>
    <section className="quick-section" aria-labelledby="quick-title"><h2 id="quick-title" className="section-title">빠른 작업</h2>
      <div className="quick-grid">{[['#/submissions', '▣', '서류 프로젝트'], ['/performances/index.html', '▥', '실적 붙여넣기'],
        ['/documents/', '▤', '공통서류 등록'], ['/bids/', '⌕', '입찰공고 조회']].map(([href, icon, label]) =>
        <a href={href} key={href}><span aria-hidden="true">{icon}</span>{label}<span aria-hidden="true">→</span></a>)}</div>
    </section>
    <Notices resource={notices} />
  </main>;
}
