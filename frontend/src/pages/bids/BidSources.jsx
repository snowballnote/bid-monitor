import { useEffect, useRef, useState } from 'react';
import {
  BidSourceRegistrationRequestError,
  getBidSourceRegistrations,
  registerBidSource,
} from '../../api/bidSources';
import './bid-sources.css';

const STATUS_NAMES = {
  PENDING_REVIEW: '검토 대기',
  UNDER_REVIEW: '검토 중',
  APPROVED: '승인',
  REJECTED: '반려',
};

const METHOD_NAMES = {
  UNDETERMINED: '미확정',
  OFFICIAL_API: '공식 API',
  PUBLIC_PAGE: '공개 페이지',
  RSS: 'RSS',
};

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—'
    : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(date);
}

function validateForm({ sourceName, siteUrl }) {
  if (!sourceName.trim()) return '수집처 이름을 입력해 주세요.';
  if (!siteUrl.trim()) return '사이트 URL을 입력해 주세요.';
  try {
    const parsed = new URL(siteUrl.trim());
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('invalid protocol');
  } catch {
    return 'HTTP 또는 HTTPS 형식의 사이트 URL을 입력해 주세요.';
  }
  return '';
}

function registrationErrorMessage(error) {
  if (!(error instanceof BidSourceRegistrationRequestError)) {
    return '수집처를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }
  if (error.status === 409) return '이미 등록된 사이트 URL입니다.';
  if (error.status === 400) return '사이트 URL을 확인해 주세요. 공개된 HTTP 또는 HTTPS 주소만 등록할 수 있습니다.';
  return '수집처를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.';
}

function RegistrationRow({ registration }) {
  const statusName = STATUS_NAMES[registration.registrationStatus] || '상태 확인 필요';
  return <tr>
    <td data-label="수집처 이름"><strong>{registration.sourceName}</strong></td>
    <td data-label="사이트 URL" className="bid-source-url">{registration.siteUrl}</td>
    <td data-label="등록일"><time dateTime={registration.createdAt}>{formatDate(registration.createdAt)}</time></td>
    <td data-label="검토 상태"><span className={`registration-status registration-status-${registration.registrationStatus.toLowerCase()}`}>
      {statusName}
    </span></td>
    <td data-label="수집방식">{METHOD_NAMES[registration.collectionMethod] || '확인 필요'}</td>
    <td data-label="자동수집"><span className={registration.executionEnabled ? 'collection-enabled' : 'collection-disabled'}>
      {registration.executionEnabled ? '활성' : '비활성'}
    </span></td>
  </tr>;
}

export default function BidSources() {
  const [form, setForm] = useState({ sourceName: '', siteUrl: '' });
  const [resource, setResource] = useState({ status: 'loading', data: [] });
  const [submission, setSubmission] = useState({ status: 'idle', message: '' });
  const [reloadVersion, setReloadVersion] = useState(0);
  const submittingRef = useRef(false);
  const registrationControllerRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    setResource(current => ({ status: 'loading', data: current.data }));
    getBidSourceRegistrations(controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setResource({ status: 'success', data });
      })
      .catch(() => {
        if (!controller.signal.aborted) setResource(current => ({ status: 'error', data: current.data }));
      });
    return () => controller.abort();
  }, [reloadVersion]);

  useEffect(() => () => registrationControllerRef.current?.abort(), []);

  async function submit(event) {
    event.preventDefault();
    if (submittingRef.current) return;
    const validation = validateForm(form);
    if (validation) {
      setSubmission({ status: 'error', message: validation });
      return;
    }

    const controller = new AbortController();
    registrationControllerRef.current = controller;
    submittingRef.current = true;
    setSubmission({ status: 'loading', message: '' });
    try {
      await registerBidSource({
        sourceName: form.sourceName.trim(),
        siteUrl: form.siteUrl.trim(),
      }, controller.signal);
      if (controller.signal.aborted) return;
      setForm({ sourceName: '', siteUrl: '' });
      setSubmission({ status: 'success', message: '수집처가 등록되었습니다. 검토 후 수집 대상으로 연결됩니다.' });
      setReloadVersion(current => current + 1);
    } catch (error) {
      if (!controller.signal.aborted) {
        setSubmission({ status: 'error', message: registrationErrorMessage(error) });
      }
    } finally {
      if (registrationControllerRef.current === controller) {
        registrationControllerRef.current = null;
        submittingRef.current = false;
      }
    }
  }

  return <main className="page-container bid-sources-page">
    <header className="page-heading bid-sources-heading">
      <div><span className="page-eyebrow">BID SOURCES</span><h1>입찰공고 수집처 관리</h1>
        <p>나라장터에 없는 입찰 사이트를 등록하고 검토 현황을 확인합니다.</p></div>
      <a href="#/bids" className="bid-sources-back-link">입찰공고 목록으로</a>
    </header>

    <aside className="bid-source-review-notice" aria-label="수집처 등록 안내">
      <strong>등록한 사이트는 수집 가능 여부 검토 후 수집 대상으로 연결됩니다.</strong>
      <span>URL 등록만으로 공고를 방문하거나 자동수집하지 않습니다.</span>
    </aside>

    <div className="bid-sources-layout">
      <section className="surface-card bid-source-form-card" aria-labelledby="bid-source-form-title">
        <div className="bid-source-section-heading"><h2 id="bid-source-form-title">새 수집처 등록</h2>
          <p>사이트 이름과 공개 URL만 입력해 주세요.</p></div>
        <form onSubmit={submit} noValidate>
          <label>수집처 이름<input name="sourceName" value={form.sourceName} maxLength="200"
            onChange={event => setForm(current => ({ ...current, sourceName: event.target.value }))}
            placeholder="예: 공공기관 입찰정보" autoComplete="organization" /></label>
          <label>사이트 URL<input name="siteUrl" type="url" inputMode="url" value={form.siteUrl} maxLength="2048"
            onChange={event => setForm(current => ({ ...current, siteUrl: event.target.value }))}
            placeholder="https://example.go.kr/bids" autoComplete="url" /></label>
          <button type="submit" disabled={submission.status === 'loading'}>
            {submission.status === 'loading' ? '등록 중…' : '수집처 등록'}
          </button>
        </form>
        {submission.status === 'success' && <p className="bid-source-form-message success" role="status">{submission.message}</p>}
        {submission.status === 'error' && <p className="bid-source-form-message error" role="alert">{submission.message}</p>}
      </section>

      <section className="surface-card bid-source-list-card" aria-labelledby="bid-source-list-title">
        <div className="bid-source-section-heading"><h2 id="bid-source-list-title">등록 현황</h2>
          <p>{resource.status === 'success' ? `전체 ${resource.data.length}개 수집처` : '등록된 수집처를 확인합니다.'}</p></div>
        {resource.status === 'loading' && <div className="bid-source-list-state" role="status">등록된 수집처를 불러오는 중입니다.</div>}
        {resource.status === 'error' && <div className="bid-source-list-state error" role="alert">등록 현황을 불러오지 못했습니다.</div>}
        {resource.status === 'success' && resource.data.length === 0
          && <div className="bid-source-list-state">아직 등록된 수집처가 없습니다.</div>}
        {resource.status === 'success' && resource.data.length > 0 && <div className="bid-source-table-wrap">
          <table className="bid-source-table">
            <thead><tr><th>수집처 이름</th><th>사이트 URL</th><th>등록일</th><th>검토 상태</th>
              <th>수집방식</th><th>자동수집</th></tr></thead>
            <tbody>{resource.data.map(registration => <RegistrationRow registration={registration}
              key={registration.sourceId} />)}</tbody>
          </table>
        </div>}
      </section>
    </div>
  </main>;
}
