import { test, expect } from '@playwright/test';

const notices = [{
  sourceCode: 'G2B', sourceNoticeId: 'g2b-1', revision: '000', bidNtceNo: '2026-001',
  bidNtceNm: '긴 공고명에도 줄바꿈이 적용되는 정보시스템 감리 용역 입찰공고', ntceInsttNm: '조달청',
  bidNtceDt: '2026-09-22T09:00:00', bidClseDt: '2026-09-30T18:00:00',
  noticeStatus: '공고', detailUrl: 'https://example.test/g2b-1',
}, {
  sourceCode: 'KOREA_EXPRESSWAY', sourceNoticeId: 'ex-1', revision: '', bidNtceNo: 'EX-2026-1',
  bidNtceNm: '고속도로 정보시스템 감리', ntceInsttNm: '한국도로공사',
  bidNtceDt: '2026-09-21T10:00:00', bidClseDt: '2026-09-29T17:00:00',
  noticeStatus: '진행중', bidNtceDtlUrl: null,
}, {
  sourceCode: 'CUSTOM_ALPHA', sourceNoticeId: 'custom-1', revision: '1', bidNtceNo: 'CUSTOM-1',
  bidNtceNm: '신규 기관 정보시스템 감리', ntceInsttNm: '신규 기관',
  bidNtceDt: '2026-09-18T08:00:00', bidClseDt: null,
  noticeStatus: null, detailUrl: 'javascript:alert(1)',
}];

function catalogItem(overrides) {
  return {
    sourceId: null, sourceCode: null, sourceName: '', siteUrl: '', sourceType: 'DISCOVERED',
    collectionMethod: null, executionEnabled: false, registrationStatus: null, checkStatus: null,
    discoveryStatus: null, reviewStatus: null, lastSuccessAt: null, lastFailureAt: null,
    safeFailureCode: null, ...overrides,
  };
}

const sourceCatalog = [
  catalogItem({ sourceId: 11, sourceCode: 'G2B', sourceName: '나라장터', siteUrl: 'https://www.g2b.go.kr/',
    sourceType: 'FIXED', collectionMethod: 'OFFICIAL_API', executionEnabled: true }),
  catalogItem({ sourceId: 12, sourceCode: 'KOREA_EXPRESSWAY', sourceName: '한국도로공사', siteUrl: 'https://ebid.ex.co.kr/',
    sourceType: 'FIXED', collectionMethod: 'PUBLIC_PAGE', executionEnabled: true }),
  catalogItem({ sourceId: 13, sourceCode: 'KOGAS', sourceName: '한국가스공사', siteUrl: 'https://bid.kogas.or.kr:9443/',
    sourceType: 'FIXED', collectionMethod: 'PUBLIC_PAGE', executionEnabled: false }),
  catalogItem({ sourceCode: 'LH', sourceName: '한국토지주택공사', siteUrl: 'https://ebid.lh.or.kr/',
    sourceType: 'FIXED', collectionMethod: 'OFFICIAL_API', executionEnabled: true }),
  catalogItem({ sourceId: 41, sourceCode: 'CUSTOM_ALPHA', sourceName: '신규사이트 A',
    siteUrl: 'https://alpha.example/bids', collectionMethod: 'PUBLIC_PAGE', executionEnabled: true,
    registrationStatus: 'APPROVED', checkStatus: 'REACHABLE' }),
  catalogItem({ sourceId: 42, sourceName: '코드 대기 사이트', siteUrl: 'https://pending.example/bids',
    collectionMethod: 'UNDETERMINED', registrationStatus: 'PENDING_REVIEW' }),
];

function liveResult(overrides = {}) {
  return {
    status: 'SUCCESS', startDate: '2026-09-01', endDate: '2026-09-22',
    sources: [
      { sourceCode: 'G2B', status: 'SUCCESS', noticeCount: 1, errorCode: null },
      { sourceCode: 'KOREA_EXPRESSWAY', status: 'SUCCESS', noticeCount: 1, errorCode: null },
      { sourceCode: 'CUSTOM_ALPHA', status: 'SUCCESS', noticeCount: 1, errorCode: null },
    ],
    notices,
    ...overrides,
  };
}

async function mockLivePage(page, handler = route => route.fulfill({ json: liveResult() })) {
  const requests = [];
  await page.route('**/api/bid-sources/catalog', route => route.fulfill({ json: sourceCatalog }));
  await page.route('**/api/live-bid-notices', async route => {
    requests.push({ method: route.request().method(), body: route.request().postDataJSON() });
    await handler(route);
  });
  return requests;
}

test('App Shell bid menu navigates to the live bids route', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ json: [] }));
  await page.goto('/react/index.html#/');
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('link', { name: '입찰공고' }).click();
  await expect(page).toHaveURL(/#\/bids$/);
  await expect(page.getByRole('heading', { name: '실시간 입찰공고' })).toBeVisible();
});

test('does not call external collection until the user starts a live query', async ({ page }) => {
  const requests = await mockLivePage(page);
  await page.goto('/react/index.html#/bids');

  await expect(page.getByText('기간을 선택하고 실시간 조회를 눌러 주세요.')).toBeVisible();
  await expect(page.locator('.live-source-summary')).toContainText('수집 대상 3곳');
  await expect(page.locator('select[name="sourceCode"] option'))
    .toHaveText(['전체 활성 수집처', '나라장터', '한국도로공사', '신규사이트 A']);
  expect(requests).toHaveLength(0);
});

test('posts the selected period once and displays live notices without a saved-list request', async ({ page }) => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const requests = await mockLivePage(page, async route => {
    await gate;
    await route.fulfill({ json: liveResult() });
  });
  const allApiPaths = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) allApiPaths.push(url.pathname);
  });
  await page.goto('/react/index.html#/bids');
  await page.getByLabel('시작일').fill('2026-09-01');
  await page.getByLabel('종료일').fill('2026-09-22');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('button', { name: '조회 중…' })).toBeDisabled();
  await expect.poll(() => requests.length).toBe(1);
  await page.getByRole('button', { name: '조회 중…' }).evaluate(button =>
    button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  expect(requests).toHaveLength(1);

  release();
  await expect(page.getByRole('region', { name: '실시간 공고 조회 결과' })).toContainText('조회된 공고 3건');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.saved-bids-table')).toContainText('2026-001');
  await expect(page.locator('.saved-bids-table')).toContainText('신규 기관');
  await expect(page.getByRole('link', { name: '원문 보기 ↗' })).toHaveCount(1);
  await expect(page.getByText('원문 링크 없음')).toHaveCount(2);
  expect(requests[0]).toEqual({ method: 'POST', body: { startDate: '2026-09-01', endDate: '2026-09-22' } });
  expect(allApiPaths).not.toContain('/api/bid-notices');
  expect(allApiPaths).not.toContain('/api/bid-collections');
});

test('uses today as the initial live query period', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-09-23T12:00:00+09:00') });
  const requests = await mockLivePage(page, route => route.fulfill({ json: liveResult({
    startDate: '2026-09-23', endDate: '2026-09-23',
  }) }));
  await page.goto('/react/index.html#/bids');
  await expect(page.getByLabel('시작일')).toHaveValue('2026-09-23');
  await expect(page.getByLabel('종료일')).toHaveValue('2026-09-23');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('region', { name: '실시간 공고 조회 결과' })).toBeVisible();
  expect(requests[0].body).toEqual({ startDate: '2026-09-23', endDate: '2026-09-23' });
});

test('shows successful notices and failed sources for a partial result', async ({ page }) => {
  await mockLivePage(page, route => route.fulfill({ json: liveResult({
    status: 'PARTIAL_SUCCESS',
    notices: [notices[0]],
    sources: [
      { sourceCode: 'G2B', status: 'SUCCESS', noticeCount: 1, errorCode: null },
      { sourceCode: 'KOREA_EXPRESSWAY', status: 'FAILED', noticeCount: 0, errorCode: 'COLLECTION_FAILED' },
    ],
  }) }));
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  const result = page.getByRole('region', { name: '실시간 공고 조회 결과' });
  await expect(result).toContainText('일부 수집처 조회 실패');
  await expect(result).toContainText('한국도로공사');
  await expect(result).toContainText('수집 실패');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
});

test('shows no-active-source and complete-failure results safely', async ({ page }) => {
  let response = liveResult({ status: 'NO_ACTIVE_SOURCES', sources: [], notices: [] });
  await mockLivePage(page, route => route.fulfill({ json: response }));
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('region', { name: '실시간 공고 조회 결과' }))
    .toContainText('수집처 관리에서 사용할 사이트를 활성화해 주세요.');

  response = liveResult({ status: 'FAILED', notices: [], sources: [
    { sourceCode: 'G2B', status: 'FAILED', noticeCount: 0, errorCode: 'COLLECTION_FAILED' },
  ] });
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('region', { name: '실시간 공고 조회 결과' })).toContainText('수집처 조회 실패');
  await expect(page.locator('body')).not.toContainText('sensitive upstream response');
});

test('rejects an invalid date range before calling the live API', async ({ page }) => {
  const requests = await mockLivePage(page);
  await page.goto('/react/index.html#/bids');
  await page.getByLabel('시작일').fill('2026-09-23');
  await page.getByLabel('종료일').fill('2026-09-22');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('alert')).toHaveText('시작일은 종료일보다 늦을 수 없습니다.');
  expect(requests).toHaveLength(0);
});

test('maps live API errors without exposing the server response', async ({ page }) => {
  await mockLivePage(page, route => route.fulfill({ status: 503,
    json: { message: 'internal-uri-and-exception-must-not-render' } }));
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.getByRole('alert')).toHaveText('실시간 공고 조회에 실패했습니다.');
  await expect(page.locator('body')).not.toContainText('internal-uri-and-exception-must-not-render');
});

test('filters and paginates the current in-memory result without another collection call', async ({ page }) => {
  const many = Array.from({ length: 21 }, (_, index) => ({
    ...notices[index % notices.length],
    sourceNoticeId: `notice-${index}`,
    bidNtceNo: `NOTICE-${index}`,
  }));
  const requests = await mockLivePage(page, route => route.fulfill({ json: liveResult({ notices: many }) }));
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(20);
  await page.getByRole('button', { name: '다음' }).click();
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
  await page.locator('select[name="sourceCode"]').selectOption('CUSTOM_ALPHA');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(7);
  await expect(page.locator('.saved-bids-summary')).toContainText('전체 7건');
  expect(requests).toHaveLength(1);
});

test('live bids mobile layout stays inside the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLivePage(page);
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '실시간 조회' }).click();
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.saved-bids-table thead')).toBeHidden();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
