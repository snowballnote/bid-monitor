import { useEffect, useRef, useState } from 'react';
import {
  BidSourceRegistrationRequestError,
  activateBidSource,
  bindBidSource,
  checkBidSource,
  discoverBidSource,
  getBidSourceCatalog,
  getBidSourceDiscovery,
  getBidSourceRegistrations,
  registerBidSource,
  reviewBidSource,
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

const CHECK_STATUS_NAMES = {
  NOT_CHECKED: '미점검',
  CHECKING: '점검 중',
  REACHABLE: '접근 가능',
  UNREACHABLE: '접근 불가',
  BLOCKED: '접근 제한',
};

const DISCOVERY_STATUS_NAMES = {
  NOT_ANALYZED: '미분석',
  ANALYZING: '분석 중',
  READY: '분석 완료',
  MANUAL_REVIEW: '수동 검토 필요',
  FAILED: '분석 실패',
};

const CONFIDENCE_NAMES = { HIGH: '높음', MEDIUM: '보통', LOW: '낮음', NONE: '없음' };

function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—'
    : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
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

function reviewStatus(item) {
  const statuses = [];
  if (item.registrationStatus) statuses.push(STATUS_NAMES[item.registrationStatus] || item.registrationStatus);
  if (item.reviewStatus) statuses.push(`구조 ${STATUS_NAMES[item.reviewStatus] || item.reviewStatus}`);
  return statuses.length ? statuses.join(' · ') : '해당 없음';
}

function mergeCatalogAndRegistrations(catalog, registrations) {
  const registrationsById = new Map(registrations.map(registration => [registration.sourceId, registration]));
  return catalog.map(item => {
    const registration = registrationsById.get(item.sourceId);
    if (!registration) return item;
    return {
      ...item,
      checkStatus: registration.checkStatus,
      detectedCollectionMethod: registration.detectedCollectionMethod,
      httpStatus: registration.httpStatus,
      contentType: registration.contentType,
      checkedAt: registration.checkedAt,
      safeFailureCode: registration.safeFailureCode,
      siteIdentification: registration.siteIdentification,
      matchedSourceCode: registration.matchedSourceCode,
      registrationBindingSupported: registration.registrationBindingSupported,
    };
  });
}

function identificationLabel(item) {
  if (item.sourceType === 'FIXED') return '기본 지원';
  if (item.siteIdentification === 'SUPPORTED') {
    return `기존 지원 · ${item.matchedSourceCode}`;
  }
  if (item.siteIdentification === 'NEW_SITE') return '신규 사이트';
  return '미확인';
}

function DiscoverySummary({ item }) {
  const discovery = item.discovery;
  if (!discovery) {
    return <span className="bid-source-detail-muted">
      {item.discoveryLoadError ? '저장된 분석 결과 조회 실패' : '분석 정보 없음'}
    </span>;
  }
  return <dl className="bid-source-discovery-details">
    <div><dt>감지 방식</dt><dd>{METHOD_NAMES[discovery.detectedCollectionMethod] || '미확정'}</dd></div>
    <div><dt>식별자</dt><dd>{CONFIDENCE_NAMES[discovery.identifierConfidence] || '미확인'}</dd></div>
    <div><dt>제목</dt><dd>{CONFIDENCE_NAMES[discovery.titleConfidence] || '미확인'}</dd></div>
    <div><dt>마감일</dt><dd>{CONFIDENCE_NAMES[discovery.deadlineConfidence] || '미확인'}</dd></div>
    <div><dt>첨부/페이지</dt><dd>{discovery.attachmentDetected ? '감지' : '미감지'} / {discovery.paginationDetected ? '감지' : '미감지'}</dd></div>
    {discovery.reasonCodes.length > 0
      && <div><dt>사유</dt><dd>{discovery.reasonCodes.join(', ')}</dd></div>}
  </dl>;
}

function CatalogRow({ item, operation, onCheck, onDiscover, onReview, onBind, onActivation }) {
  const statusClass = item.registrationStatus?.toLowerCase() || 'not_applicable';
  const checkLoading = operation?.check === 'loading';
  const discoveryLoading = operation?.discovery === 'loading';
  const workflowLoading = operation?.workflow === 'loading';
  const activationLoading = operation?.activation === 'loading';
  const canDiscover = item.sourceId != null && item.checkStatus === 'REACHABLE';
  const canReview = item.sourceId != null && item.siteIdentification === 'SUPPORTED'
    && item.registrationBindingSupported && item.checkStatus === 'REACHABLE'
    && ['PENDING_REVIEW', 'UNDER_REVIEW'].includes(item.registrationStatus);
  const canBind = item.sourceId != null && item.registrationStatus === 'APPROVED'
    && item.registrationBindingSupported && !item.sourceCode && item.matchedSourceCode;
  const canActivate = item.sourceId != null && item.registrationStatus === 'APPROVED'
    && item.registrationBindingSupported && item.sourceCode;
  return <tr>
    <td data-label="수집처 이름"><strong>{item.sourceName}</strong>
      {item.sourceCode && <small className="bid-source-code">{item.sourceCode}</small>}</td>
    <td data-label="유형"><span className={`source-type source-type-${item.sourceType.toLowerCase()}`}>
      {item.sourceType === 'FIXED' ? '기본' : '등록'}
    </span></td>
    <td data-label="사이트 URL" className="bid-source-url">{item.siteUrl}</td>
    <td data-label="검토 상태"><span className={`registration-status registration-status-${statusClass}`}>
      {reviewStatus(item)}
    </span></td>
    <td data-label="수집 방식">{METHOD_NAMES[item.collectionMethod] || '해당 없음'}</td>
    <td data-label="자동수집" className="bid-source-action-cell">
      <span className={item.executionEnabled ? 'collection-enabled' : 'collection-disabled'}>
        {item.executionEnabled ? '활성' : '비활성'}
      </span>
      {canActivate && <button type="button" disabled={activationLoading}
        onClick={() => onActivation(item)}>{activationLoading ? '처리 중…'
          : item.executionEnabled ? '비활성화' : '활성화'}</button>}
      {operation?.activation === 'error' && <small role="alert">활성 상태 변경에 실패했습니다.</small>}
    </td>
    <td data-label="최근 성공">{formatDateTime(item.lastSuccessAt)}</td>
    <td data-label="최근 실패">{formatDateTime(item.lastFailureAt)}</td>
    <td data-label="지원 식별"><span className={`site-identification site-identification-${(item.siteIdentification || item.sourceType).toLowerCase()}`}>
      {identificationLabel(item)}
    </span></td>
    <td data-label="사이트 점검" className="bid-source-action-cell">
      <span className={`source-check-status source-check-status-${(item.checkStatus || 'not_checked').toLowerCase()}`}>
        {CHECK_STATUS_NAMES[item.checkStatus] || (item.sourceId == null ? '등록 필요' : '미점검')}
      </span>
      {item.sourceId != null && <button type="button" disabled={checkLoading}
        onClick={() => onCheck(item.sourceId)}>{checkLoading ? '점검 중…' : '사이트 점검'}</button>}
      {operation?.check === 'error' && <small role="alert">점검 요청에 실패했습니다.</small>}
      {item.safeFailureCode && <small>{item.safeFailureCode}</small>}
    </td>
    <td data-label="구조 분석" className="bid-source-action-cell bid-source-discovery-cell">
      <span>{DISCOVERY_STATUS_NAMES[item.discoveryStatus] || '미분석'}</span>
      {canDiscover && <button type="button" disabled={discoveryLoading}
        onClick={() => onDiscover(item.sourceId)}>{discoveryLoading ? '분석 중…' : '구조 분석'}</button>}
      {operation?.discovery === 'error' && <small role="alert">구조 분석 요청에 실패했습니다.</small>}
      <DiscoverySummary item={item} />
    </td>
    <td data-label="연결 관리" className="bid-source-action-cell">
      {canReview && <button type="button" disabled={workflowLoading}
        onClick={() => onReview(item)}>{workflowLoading ? '처리 중…'
          : item.registrationStatus === 'PENDING_REVIEW' ? '검토 시작' : '수집 방식 승인'}</button>}
      {canBind && <button type="button" disabled={workflowLoading}
        onClick={() => onBind(item)}>{workflowLoading ? '처리 중…' : `${item.matchedSourceCode} 연결`}</button>}
      {item.siteIdentification === 'SUPPORTED' && !item.registrationBindingSupported
        && <span className="bid-source-detail-muted">URL 식별만 지원</span>}
      {item.sourceCode && item.registrationStatus === 'APPROVED'
        && <span className="collection-enabled">연결됨</span>}
      {operation?.workflow === 'error' && <small role="alert">검토 또는 연결 요청에 실패했습니다.</small>}
    </td>
  </tr>;
}

export default function BidSources() {
  const [form, setForm] = useState({ sourceName: '', siteUrl: '' });
  const [resource, setResource] = useState({ status: 'loading', data: [] });
  const [submission, setSubmission] = useState({ status: 'idle', message: '' });
  const [operations, setOperations] = useState({});
  const [reloadVersion, setReloadVersion] = useState(0);
  const submittingRef = useRef(false);
  const registrationControllerRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    setResource(current => ({ status: 'loading', data: current.data }));
    Promise.all([
      getBidSourceCatalog(controller.signal),
      getBidSourceRegistrations(controller.signal),
    ])
      .then(async ([catalog, registrations]) => {
        const merged = mergeCatalogAndRegistrations(catalog, registrations);
        const withStoredDiscovery = await Promise.all(merged.map(async item => {
          if (item.sourceId == null || !item.discoveryStatus) return item;
          try {
            return { ...item, discovery: await getBidSourceDiscovery(item.sourceId, controller.signal) };
          } catch (error) {
            if (controller.signal.aborted) throw error;
            return { ...item, discoveryLoadError: true };
          }
        }));
        if (!controller.signal.aborted) setResource({ status: 'success', data: withStoredDiscovery });
      })
      .catch(() => {
        if (!controller.signal.aborted) setResource(current => ({ status: 'error', data: current.data }));
      });
    return () => controller.abort();
  }, [reloadVersion]);

  useEffect(() => () => registrationControllerRef.current?.abort(), []);

  function setOperation(sourceId, type, status) {
    setOperations(current => ({
      ...current,
      [sourceId]: { ...current[sourceId], [type]: status },
    }));
  }

  function updateSource(sourceId, update) {
    setResource(current => ({
      ...current,
      data: current.data.map(item => item.sourceId === sourceId ? { ...item, ...update } : item),
    }));
  }

  async function checkSource(sourceId) {
    if (operations[sourceId]?.check === 'loading') return;
    setOperation(sourceId, 'check', 'loading');
    try {
      const result = await checkBidSource(sourceId);
      updateSource(sourceId, {
        checkStatus: result.checkStatus,
        detectedCollectionMethod: result.detectedCollectionMethod,
        httpStatus: result.httpStatus,
        contentType: result.contentType,
        checkedAt: result.checkedAt,
        safeFailureCode: result.safeFailureCode,
        siteIdentification: result.siteIdentification,
        matchedSourceCode: result.matchedSourceCode,
      });
      setOperation(sourceId, 'check', 'success');
    } catch {
      setOperation(sourceId, 'check', 'error');
    }
  }

  async function discoverSource(sourceId) {
    if (operations[sourceId]?.discovery === 'loading') return;
    setOperation(sourceId, 'discovery', 'loading');
    try {
      const result = await discoverBidSource(sourceId);
      updateSource(sourceId, {
        discoveryStatus: result.discoveryStatus,
        discovery: result,
        discoveryLoadError: false,
      });
      setOperation(sourceId, 'discovery', 'success');
    } catch {
      setOperation(sourceId, 'discovery', 'error');
    }
  }

  async function reviewSource(item) {
    if (operations[item.sourceId]?.workflow === 'loading') return;
    setOperation(item.sourceId, 'workflow', 'loading');
    try {
      const targetStatus = item.registrationStatus === 'PENDING_REVIEW' ? 'UNDER_REVIEW' : 'APPROVED';
      const result = await reviewBidSource(
        item.sourceId,
        targetStatus,
        item.detectedCollectionMethod || item.collectionMethod,
      );
      updateSource(item.sourceId, {
        registrationStatus: result.registrationStatus,
        collectionMethod: result.collectionMethod,
      });
      setOperation(item.sourceId, 'workflow', 'success');
    } catch {
      setOperation(item.sourceId, 'workflow', 'error');
    }
  }

  async function bindSource(item) {
    if (operations[item.sourceId]?.workflow === 'loading') return;
    setOperation(item.sourceId, 'workflow', 'loading');
    try {
      await bindBidSource(item.sourceId, item.matchedSourceCode);
      setOperation(item.sourceId, 'workflow', 'success');
      setReloadVersion(current => current + 1);
    } catch {
      setOperation(item.sourceId, 'workflow', 'error');
    }
  }

  async function changeActivation(item) {
    if (operations[item.sourceId]?.activation === 'loading') return;
    setOperation(item.sourceId, 'activation', 'loading');
    try {
      const result = await activateBidSource(item.sourceId, !item.executionEnabled);
      updateSource(item.sourceId, { executionEnabled: result.executionEnabled });
      setOperation(item.sourceId, 'activation', 'success');
    } catch {
      setOperation(item.sourceId, 'activation', 'error');
    }
  }

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
      const result = await registerBidSource({
        sourceName: form.sourceName.trim(),
        siteUrl: form.siteUrl.trim(),
      }, controller.signal);
      if (controller.signal.aborted) return;
      setForm({ sourceName: '', siteUrl: '' });
      const message = result.siteIdentification === 'SUPPORTED'
        ? `기존 지원 사이트(${result.matchedSourceCode})로 확인되었습니다. 자동 연결이나 활성화는 수행되지 않았습니다.`
        : '새 수집처로 등록되었습니다. 필요한 경우 사이트 점검을 실행해 주세요.';
      setSubmission({ status: 'success', message });
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
        <p>기본 수집처와 새로 등록한 입찰 사이트의 현황을 함께 확인합니다.</p></div>
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
        <div className="bid-source-section-heading"><h2 id="bid-source-list-title">수집처 현황</h2>
          <p>{resource.status === 'success' ? `전체 ${resource.data.length}개 수집처` : '수집처 현황을 확인합니다.'}</p></div>
        {resource.status === 'loading' && <div className="bid-source-list-state" role="status">수집처 현황을 불러오는 중입니다.</div>}
        {resource.status === 'error' && <div className="bid-source-list-state error" role="alert">수집처 현황을 불러오지 못했습니다.</div>}
        {resource.status === 'success' && resource.data.length === 0
          && <div className="bid-source-list-state">표시할 수집처가 없습니다.</div>}
        {resource.status === 'success' && resource.data.length > 0 && <div className="bid-source-table-wrap">
          <table className="bid-source-table">
            <thead><tr><th>수집처 이름</th><th>유형</th><th>사이트 URL</th><th>검토 상태</th>
              <th>수집 방식</th><th>자동수집</th><th>최근 성공</th><th>최근 실패</th><th>지원 식별</th>
              <th>사이트 점검</th><th>구조 분석</th><th>연결 관리</th></tr></thead>
            <tbody>{resource.data.map(item => <CatalogRow item={item}
              operation={operations[item.sourceId]} onCheck={checkSource} onDiscover={discoverSource}
              onReview={reviewSource} onBind={bindSource}
              onActivation={changeActivation}
              key={item.sourceCode || `registration-${item.sourceId}`} />)}</tbody>
          </table>
        </div>}
      </section>
    </div>
  </main>;
}
