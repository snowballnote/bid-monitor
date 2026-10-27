import { useEffect, useMemo, useRef, useState } from 'react';
import { BidCollectionRequestError, collectBidNotices } from '../../api/bids';
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
  if (!(error instanceof BidCollectionRequestError)) return '실시간 공고 조회를 완료하지 못했습니다.';
  return {
    400: '조회 기간을 확인해 주세요.',
    409: '이미 공고 수집이 진행 중입니다.',
    429: '공고 수집 호출량 제한에 도달했습니다.',
    503: '실시간 공고 조회에 실패했습니다.',
  }[error.status] || '실시간 공고 조회를 완료하지 못했습니다.';
}

function CollectionResult({ result, sourceNames }) {
  const labels = {
    SUCCESS: '실시간 조회 완료',
    PARTIAL_SUCCESS: '일부 수집처 조회 실패',
    FAILED: '수집처 조회 실패',
    NO_ACTIVE_SOURCES: '활성 수집처 없음',
  };
  return <section className={`collection-result collection-result-${result.status.toLowerCase()}`}
    aria-label="실시간 공고 조회 결과">
    <div><strong>{labels[result.status]}</strong>
      <span>조회된 공고 {result.notices.length}건</span></div>
    {result.sources.length > 0 && <ul>{result.sources.map(source => <li key={source.sourceCode}>
      <strong>{sourceName(source.sourceCode, sourceNames)}</strong>
      <span>{source.status === 'FAILED' ? '수집 실패' : `${source.noticeCount || 0}건`}</span>
    </li>)}</ul>}
    {result.status === 'NO_ACTIVE_SOURCES'
      && <p>수집처 관리에서 사용할 사이트를 활성화해 주세요.</p>}
  </section>;
}

function ActiveSourceSummary({ resource }) {
  if (resource.status === 'loading') {
    return <p className="bid-source-status-message">활성 수집처를 확인하는 중입니다.</p>;
  }
  if (resource.status === 'error') {
    return <p className="bid-source-status-message error">수집처 상태를 불러오지 못했습니다.</p>;
  }
  const active = resource.data.filter(source => source.sourceId != null
    && source.executionEnabled && source.sourceCode);
  return <div className="live-source-summary">
    <strong>수집 대상 {active.length}곳</strong>
    <span>{active.length
      ? active.map(source => source.sourceName).join(', ')
      : '활성화된 수집처가 없습니다.'}</span>
  </div>;
}

function BidRow({ notice, sourceNames }) {
  const detailUrl = safeExternalUrl(notice.detailUrl || notice.bidNtceDtlUrl);
  return <tr>
    <td data-label="출처"><span className={`bid-source bid-source-${notice.sourceCode?.toLowerCase()}`}>
      {sourceName(notice.sourceCode, sourceNames)}
    </span></td>
    <td data-label="공고명" className="saved-bid-title-cell">
      <strong>{notice.bidNtceNm || '공고명 없음'}</strong>
      <small>{notice.bidNtceNo || notice.sourceNoticeId || '공고번호 없음'}</small>
    </td>
    <td data-label="발주기관">{notice.ntceInsttNm || '—'}</td>
    <td data-label="공고일"><time dateTime={notice.bidNtceDt || undefined}>
      {formatDateTime(notice.bidNtceDt)}</time></td>
    <td data-label="입찰마감"><time dateTime={notice.bidClseDt || undefined}>
      {formatDateTime(notice.bidClseDt)}</time></td>
    <td data-label="상태">{notice.noticeStatus || '미확인'}</td>
    <td data-label="원문">{detailUrl
      ? <a className="saved-bid-detail-link" href={detailUrl} target="_blank"
        rel="noreferrer noopener">원문 보기 ↗</a>
      : <span className="saved-bid-no-link">원문 링크 없음</span>}</td>
  </tr>;
}

export default function BidNotices() {
  const today = localDateString();
  const [dates, setDates] = useState({ startDate: today, endDate: today });
  const [filters, setFilters] = useState({ sourceCode: '', size: 20 });
  const [page, setPage] = useState(0);
  const [validation, setValidation] = useState('');
  const [sourceCatalog, setSourceCatalog] = useState({ status: 'loading', data: [] });
  const [collection, setCollection] = useState({ status: 'idle', result: null, message: '' });
  const collectingRef = useRef(false);
  const mountedRef = useRef(true);

  useEffect(() => () => { mountedRef.current = false; }, []);

  useEffect(() => {
    const controller = new AbortController();
    getBidSourceCatalog(controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setSourceCatalog({ status: 'success', data });
      })
      .catch(() => {
        if (!controller.signal.aborted) setSourceCatalog({ status: 'error', data: [] });
      });
    return () => controller.abort();
  }, []);

  async function collect(event) {
    event?.preventDefault();
    if (collectingRef.current) return;
    if (!dates.startDate || !dates.endDate) {
      setValidation('시작일과 종료일을 모두 입력해 주세요.');
      return;
    }
    if (dates.startDate > dates.endDate) {
      setValidation('시작일은 종료일보다 늦을 수 없습니다.');
      return;
    }
    setValidation('');
    collectingRef.current = true;
    setCollection({ status: 'loading', result: null, message: '' });
    try {
      const result = await collectBidNotices(dates);
      if (!mountedRef.current) return;
      setPage(0);
      setCollection({ status: 'success', result, message: '' });
    } catch (error) {
      if (!mountedRef.current) return;
      setCollection({ status: 'error', result: null, message: collectionErrorMessage(error) });
    } finally {
      collectingRef.current = false;
    }
  }

  const sourceNames = useMemo(() => new Map(sourceCatalog.data
    .filter(source => source.sourceCode)
    .map(source => [source.sourceCode.toUpperCase(), source.sourceName])), [sourceCatalog.data]);
  const activeSources = sourceCatalog.data.filter(source => source.sourceId != null
    && source.executionEnabled && source.sourceCode);
  const notices = collection.result?.notices || [];
  const filteredNotices = filters.sourceCode
    ? notices.filter(notice => notice.sourceCode?.toUpperCase() === filters.sourceCode.toUpperCase())
    : notices;
  const totalPages = filteredNotices.length ? Math.ceil(filteredNotices.length / filters.size) : 0;
  const visibleNotices = filteredNotices.slice(page * filters.size, (page + 1) * filters.size);

  function changeFilter(field, value) {
    setPage(0);
    setFilters(current => ({ ...current, [field]: field === 'size' ? Number(value) : value }));
  }

  return <main className="page-container saved-bids-page">
    <header className="page-heading saved-bids-heading">
      <div><span className="page-eyebrow">LIVE BIDS</span><h1>실시간 입찰공고</h1>
        <p>활성화한 수집처에서 허용 업종코드에 맞는 공고만 바로 조회합니다.</p></div>
      <div className="saved-bids-heading-actions">
        <a className="saved-bids-source-link" href="#/bid-sources">수집처 관리</a>
      </div>
    </header>

    <section className="surface-card saved-bids-panel" aria-label="실시간 입찰공고 목록">
      <form className="saved-bids-filters" onSubmit={collect}>
        <label>시작일<input type="date" name="startDate" value={dates.startDate}
          onChange={event => setDates(current => ({ ...current, startDate: event.target.value }))} /></label>
        <label>종료일<input type="date" name="endDate" value={dates.endDate}
          onChange={event => setDates(current => ({ ...current, endDate: event.target.value }))} /></label>
        <label>출처<select name="sourceCode" value={filters.sourceCode}
          onChange={event => changeFilter('sourceCode', event.target.value)}>
          <option value="">전체 활성 수집처</option>
          {activeSources.map(source => <option value={source.sourceCode} key={source.sourceCode}>
            {source.sourceName}
          </option>)}
        </select></label>
        <label>페이지 크기<select name="size" value={filters.size}
          onChange={event => changeFilter('size', event.target.value)}>
          {[10, 20, 50, 100].map(size => <option value={size} key={size}>{size}건</option>)}
        </select></label>
        <button type="submit" className="saved-bids-search-button" disabled={collection.status === 'loading'}>
          {collection.status === 'loading' ? '조회 중…' : '실시간 조회'}
        </button>
      </form>
      {validation && <p className="saved-bids-validation" role="alert">{validation}</p>}
      <ActiveSourceSummary resource={sourceCatalog} />
      {collection.status === 'loading'
        && <p className="collection-message" role="status">활성 수집처의 공고를 조회하고 있습니다.</p>}
      {collection.status === 'error'
        && <p className="collection-message error" role="alert">{collection.message}</p>}
      {collection.status === 'success'
        && <CollectionResult result={collection.result} sourceNames={sourceNames} />}

      <div className="saved-bids-summary" aria-live="polite">
        <strong>전체 {filteredNotices.length}건</strong>
        <span>현재 조회 결과 · 저장하지 않음</span>
      </div>

      {collection.status === 'idle'
        && <div className="saved-bids-state">기간을 선택하고 실시간 조회를 눌러 주세요.</div>}
      {collection.status === 'success' && filteredNotices.length === 0
        && <div className="saved-bids-state">조건에 맞는 공고가 없습니다.</div>}
      {collection.status === 'success' && visibleNotices.length > 0 && <>
        <div className="saved-bids-table-wrap">
          <table className="saved-bids-table">
            <thead><tr><th>출처</th><th>공고명 / 공고번호</th><th>발주기관</th><th>공고일</th>
              <th>입찰마감</th><th>상태</th><th>원문</th></tr></thead>
            <tbody>{visibleNotices.map((notice, index) => <BidRow notice={notice} sourceNames={sourceNames}
              key={`${notice.sourceCode}:${notice.sourceNoticeId}:${notice.revision}:${index}`} />)}</tbody>
          </table>
        </div>
        <nav className="saved-bids-pagination" aria-label="입찰공고 페이지">
          <button type="button" onClick={() => setPage(current => Math.max(0, current - 1))}
            disabled={page === 0}>이전</button>
          <span>{totalPages ? `${page + 1} / ${totalPages}` : '0 / 0'}</span>
          <button type="button" onClick={() => setPage(current => current + 1)}
            disabled={page + 1 >= totalPages}>다음</button>
        </nav>
      </>}
    </section>
  </main>;
}
