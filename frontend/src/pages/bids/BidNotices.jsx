import { useEffect, useRef, useState } from 'react';
import { BidCollectionRequestError, collectBidNotices, getBidNotices } from '../../api/bids';
import { getBidSourceCatalog } from '../../api/bidSources';
import './bids.css';

function formatDateTime(value) {
  if (!value) return '—';
  const normalized = value.replace('T', ' ');
  return normalized.length >= 16 ? normalized.slice(0, 16) : normalized;
}

function safeExternalUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}

function localDateString(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function sourceName(sourceCode, sourceNames) {
  if (!sourceCode) return '출처 미상';
  return sourceNames.get(sourceCode.trim().toUpperCase()) || sourceCode;
}

function collectionErrorMessage(error) {
  if (!(error instanceof BidCollectionRequestError)) return '공고 갱신을 완료하지 못했습니다.';
  return {
    400: '조회 기간 등 갱신 조건을 확인해 주세요.',
    409: '이미 공고 수집이 진행 중입니다.',
    429: '공고 수집 호출량 제한에 도달했습니다.',
    503: '전체 공고 수집에 실패했습니다.',
  }[error.status] || '공고 갱신을 완료하지 못했습니다.';
}

function failedSourceMessage(error, sourceNames) {
  if (!(error instanceof BidCollectionRequestError) || !Array.isArray(error.result?.sources)) return '';
  const names = [...new Set(error.result.sources
    .filter(source => source?.status === 'FAILED')
    .map(source => sourceName(source.sourceCode, sourceNames)))];
  return names.length ? ` 실패 출처: ${names.join(', ')}` : '';
}

function CollectionResult({ result, sourceNames }) {
  const totals = result.sources.reduce((sum, source) => ({
    newCount: sum.newCount + (source.newCount || 0),
    updatedCount: sum.updatedCount + (source.updatedCount || 0),
    unchangedCount: sum.unchangedCount + (source.unchangedCount || 0),
  }), { newCount: 0, updatedCount: 0, unchangedCount: 0 });
  return <section className={`collection-result ${result.status === 'PARTIAL_SUCCESS' ? 'partial' : ''}`}
    aria-label="공고 갱신 결과">
    <div><strong>{result.status === 'SUCCESS' ? '공고 갱신 완료' : '공고 갱신 부분 성공'}</strong>
      <span>신규 {totals.newCount}건 · 변경 {totals.updatedCount}건 · 동일 {totals.unchangedCount}건</span></div>
    <ul>{result.sources.map(source => <li key={source.sourceCode}>
      <strong>{sourceName(source.sourceCode, sourceNames)}</strong>
      <span>{source.status === 'FAILED' ? '수집 실패'
        : `신규 ${source.newCount || 0} · 변경 ${source.updatedCount || 0} · 동일 ${source.unchangedCount || 0}`}</span>
    </li>)}</ul>
  </section>;
}

function SourceCatalogCards({ resource }) {
  return <section className="bid-source-statuses" aria-labelledby="bid-source-status-title">
    <div className="bid-source-status-heading"><h2 id="bid-source-status-title">수집 출처 상태</h2>
      <span>활성 출처만 수동 갱신합니다.</span></div>
    {resource.status === 'loading' && <p className="bid-source-status-message">출처 상태를 확인하는 중입니다.</p>}
    {resource.status === 'error' && <p className="bid-source-status-message error">출처 상태를 불러오지 못했습니다.</p>}
    {resource.status === 'success' && <div className="bid-source-status-grid">
      {resource.data.map(source => <article className="bid-source-status-card"
        key={source.sourceCode || `registration-${source.sourceId}`}>
        <div><strong>{source.sourceName}</strong>
          <span className={source.executionEnabled ? 'enabled' : 'disabled'}>
            {source.executionEnabled ? '활성' : '비활성'}
          </span></div>
        <p className="bid-source-status-code">{source.sourceCode || '코드 미할당'}</p>
        <dl>
          <div><dt>마지막 성공</dt><dd>{formatDateTime(source.lastSuccessAt)}</dd></div>
          <div><dt>마지막 실패</dt><dd>{formatDateTime(source.lastFailureAt)}</dd></div>
          <div><dt>오류 코드</dt><dd>{source.safeFailureCode || '—'}</dd></div>
        </dl>
      </article>)}
    </div>}
  </section>;
}

function BidRow({ notice, sourceNames }) {
  const detailUrl = safeExternalUrl(notice.detailUrl);
  return <tr>
    <td data-label="출처"><span className={`bid-source bid-source-${notice.sourceCode?.toLowerCase()}`}>
      {sourceName(notice.sourceCode, sourceNames)}
    </span></td>
    <td data-label="공고명" className="saved-bid-title-cell">
      <strong>{notice.title || '공고명 없음'}</strong>
      <small>{notice.noticeNumber || '공고번호 없음'}</small>
    </td>
    <td data-label="발주기관">{notice.orderingOrganization || '—'}</td>
    <td data-label="공고일"><time dateTime={notice.publishedAt || undefined}>{formatDateTime(notice.publishedAt)}</time></td>
    <td data-label="제출마감"><time dateTime={notice.submissionDeadlineAt || undefined}>{formatDateTime(notice.submissionDeadlineAt)}</time></td>
    <td data-label="상태">{notice.noticeStatus || '—'}</td>
    <td data-label="원문">{detailUrl
      ? <a className="saved-bid-detail-link" href={detailUrl} target="_blank" rel="noreferrer noopener">원문 보기 ↗</a>
      : <span className="saved-bid-no-link">원문 링크 없음</span>}</td>
  </tr>;
}

export default function BidNotices() {
  const [draftDates, setDraftDates] = useState({ startDate: '', endDate: '' });
  const [filters, setFilters] = useState({ startDate: '', endDate: '', sourceCode: '', size: 20 });
  const [page, setPage] = useState(0);
  const [validation, setValidation] = useState('');
  const [resource, setResource] = useState({ status: 'loading', data: null });
  const [reloadVersion, setReloadVersion] = useState(0);
  const [sourceReloadVersion, setSourceReloadVersion] = useState(0);
  const [sourceCatalog, setSourceCatalog] = useState({ status: 'loading', data: [] });
  const [collection, setCollection] = useState({ status: 'idle', result: null, message: '' });
  const refreshingRef = useRef(false);
  const mountedRef = useRef(true);

  useEffect(() => () => { mountedRef.current = false; }, []);

  useEffect(() => {
    const controller = new AbortController();
    setResource(current => ({ status: 'loading', data: current.data }));
    getBidNotices({ ...filters, page }, controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setResource({ status: 'success', data });
      })
      .catch(() => {
        if (!controller.signal.aborted) setResource({ status: 'error', data: null });
      });
    return () => controller.abort();
  }, [filters, page, reloadVersion]);

  useEffect(() => {
    const controller = new AbortController();
    setSourceCatalog(current => ({ status: 'loading', data: current.data }));
    getBidSourceCatalog(controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setSourceCatalog({ status: 'success', data });
      })
      .catch(() => {
        if (!controller.signal.aborted) setSourceCatalog(current => ({ status: 'error', data: current.data }));
      });
    return () => controller.abort();
  }, [sourceReloadVersion]);

  function submitDates(event) {
    event.preventDefault();
    if (draftDates.startDate && draftDates.endDate && draftDates.startDate > draftDates.endDate) {
      setValidation('시작일은 종료일보다 늦을 수 없습니다.');
      return;
    }
    setValidation('');
    setPage(0);
    setFilters(current => ({ ...current, ...draftDates }));
  }

  function changeFilter(field, value) {
    setPage(0);
    setFilters(current => ({ ...current, [field]: field === 'size' ? Number(value) : value }));
  }

  async function refreshNotices() {
    if (refreshingRef.current) return;
    const { startDate, endDate } = filters;
    if ((startDate && !endDate) || (!startDate && endDate)) {
      setCollection({ status: 'error', result: null, message: '갱신하려면 시작일과 종료일을 모두 입력해 주세요.' });
      return;
    }
    const period = startDate && endDate
      ? { startDate, endDate }
      : { startDate: localDateString(), endDate: localDateString() };

    refreshingRef.current = true;
    setCollection({ status: 'loading', result: null, message: '' });
    try {
      const result = await collectBidNotices(period);
      if (!mountedRef.current) return;
      setCollection({ status: 'success', result, message: '' });
      setReloadVersion(current => current + 1);
      setSourceReloadVersion(current => current + 1);
    } catch (error) {
      if (!mountedRef.current) return;
      setCollection({
        status: 'error',
        result: null,
        message: collectionErrorMessage(error) + failedSourceMessage(error, sourceNames),
      });
    } finally {
      refreshingRef.current = false;
    }
  }

  const result = resource.data;
  const totalPages = result?.totalPages || 0;
  const sourceNames = new Map(sourceCatalog.data
    .filter(source => source.sourceCode)
    .map(source => [source.sourceCode.toUpperCase(), source.sourceName]));
  const filterSources = sourceCatalog.data.filter(source => source.sourceCode);
  return <main className="page-container saved-bids-page">
    <header className="page-heading saved-bids-heading">
      <div><span className="page-eyebrow">SAVED BIDS</span><h1>저장된 입찰공고</h1>
        <p>수집되어 저장된 공고를 출처와 기간별로 조회합니다.</p></div>
      <div className="saved-bids-heading-actions">
        <button type="button" className="saved-bids-refresh-button" onClick={refreshNotices}
          disabled={collection.status === 'loading'}>
          {collection.status === 'loading' ? '공고 수집 중…' : '최신 공고 갱신'}
        </button>
        <a className="saved-bids-source-link" href="#/bid-sources">수집처 등록·현황</a>
        <a className="saved-bids-live-link" href="/bids/">실시간 분석 화면 열기 ↗</a>
      </div>
    </header>

    <section className="surface-card saved-bids-panel" aria-label="저장된 입찰공고 목록">
      <form className="saved-bids-filters" onSubmit={submitDates}>
        <label>시작일<input type="date" name="startDate" value={draftDates.startDate}
          onChange={event => setDraftDates(current => ({ ...current, startDate: event.target.value }))} /></label>
        <label>종료일<input type="date" name="endDate" value={draftDates.endDate}
          onChange={event => setDraftDates(current => ({ ...current, endDate: event.target.value }))} /></label>
        <label>출처<select name="sourceCode" value={filters.sourceCode}
          onChange={event => changeFilter('sourceCode', event.target.value)}>
          <option value="">전체 출처</option>
          {filterSources.map(source => <option value={source.sourceCode} key={source.sourceCode}>
            {source.sourceName}
          </option>)}
        </select></label>
        <label>페이지 크기<select name="size" value={filters.size}
          onChange={event => changeFilter('size', event.target.value)}>
          {[10, 20, 50, 100].map(size => <option value={size} key={size}>{size}건</option>)}
        </select></label>
        <button type="submit" className="saved-bids-search-button">조회</button>
      </form>
      {validation && <p className="saved-bids-validation" role="alert">{validation}</p>}
      {collection.status === 'loading' && <p className="collection-message" role="status">활성 출처의 공고를 수집하고 있습니다.</p>}
      {collection.status === 'error' && <p className="collection-message error" role="alert">{collection.message}</p>}
      {collection.status === 'success'
        && <CollectionResult result={collection.result} sourceNames={sourceNames} />}

      <SourceCatalogCards resource={sourceCatalog} />

      <div className="saved-bids-summary" aria-live="polite">
        <strong>{resource.status === 'success' ? `전체 ${result.totalCount}건` : '전체 —'}</strong>
        <span>저장 DB 조회 결과</span>
      </div>

      {resource.status === 'loading' && <div className="saved-bids-state" role="status">저장된 공고를 불러오는 중입니다.</div>}
      {resource.status === 'error' && <div className="saved-bids-state error" role="alert">저장된 공고를 불러오지 못했습니다.</div>}
      {resource.status === 'success' && result.items.length === 0
        && <div className="saved-bids-state">아직 저장된 공고가 없습니다.</div>}
      {resource.status === 'success' && result.items.length > 0 && <>
        <div className="saved-bids-table-wrap">
          <table className="saved-bids-table">
            <thead><tr><th>출처</th><th>공고명 / 공고번호</th><th>발주기관</th><th>공고일</th>
              <th>제출마감</th><th>상태</th><th>원문</th></tr></thead>
            <tbody>{result.items.map(notice => <BidRow notice={notice} sourceNames={sourceNames}
              key={`${notice.sourceCode}:${notice.sourceNoticeId}:${notice.revision}`} />)}</tbody>
          </table>
        </div>
        <nav className="saved-bids-pagination" aria-label="입찰공고 페이지">
          <button type="button" onClick={() => setPage(current => Math.max(0, current - 1))} disabled={page === 0}>이전</button>
          <span>{totalPages ? `${page + 1} / ${totalPages}` : '0 / 0'}</span>
          <button type="button" onClick={() => setPage(current => current + 1)} disabled={page + 1 >= totalPages}>다음</button>
        </nav>
      </>}
    </section>
  </main>;
}
