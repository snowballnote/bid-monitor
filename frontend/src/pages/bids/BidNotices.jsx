import { useEffect, useState } from 'react';
import { getBidNotices } from '../../api/bids';
import './bids.css';

const SOURCE_NAMES = {
  G2B: '나라장터',
  KOREA_EXPRESSWAY: '한국도로공사',
  D2B: 'D2B',
};

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

function BidRow({ notice }) {
  const detailUrl = safeExternalUrl(notice.detailUrl);
  return <tr>
    <td data-label="출처"><span className={`bid-source bid-source-${notice.sourceCode?.toLowerCase()}`}>
      {SOURCE_NAMES[notice.sourceCode] || notice.sourceCode || '출처 미상'}
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
  }, [filters, page]);

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

  const result = resource.data;
  const totalPages = result?.totalPages || 0;
  return <main className="page-container saved-bids-page">
    <header className="page-heading saved-bids-heading">
      <div><span className="page-eyebrow">SAVED BIDS</span><h1>저장된 입찰공고</h1>
        <p>수집되어 저장된 공고를 출처와 기간별로 조회합니다.</p></div>
      <a className="saved-bids-live-link" href="/bids/">실시간 분석 화면 열기 ↗</a>
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
          <option value="G2B">나라장터</option>
          <option value="KOREA_EXPRESSWAY">한국도로공사</option>
          <option value="D2B">D2B</option>
        </select></label>
        <label>페이지 크기<select name="size" value={filters.size}
          onChange={event => changeFilter('size', event.target.value)}>
          {[10, 20, 50, 100].map(size => <option value={size} key={size}>{size}건</option>)}
        </select></label>
        <button type="submit" className="saved-bids-search-button">조회</button>
      </form>
      {validation && <p className="saved-bids-validation" role="alert">{validation}</p>}

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
            <tbody>{result.items.map(notice => <BidRow notice={notice}
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
