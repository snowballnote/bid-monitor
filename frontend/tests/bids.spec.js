import { test, expect } from '@playwright/test';

const notices = [{
  sourceCode: 'G2B', sourceNoticeId: 'g2b-1', revision: '000', noticeNumber: '2026-001',
  title: '긴 공고명에도 줄바꿈이 적용되는 정보시스템 감리 용역 입찰공고', orderingOrganization: '조달청',
  publishedAt: '2026-09-22T09:00:00', submissionDeadlineAt: '2026-09-30T18:00:00',
  noticeStatus: '공고', detailUrl: 'https://example.test/g2b-1',
}, {
  sourceCode: 'KOREA_EXPRESSWAY', sourceNoticeId: 'ex-1', revision: '', noticeNumber: 'EX-2026-1',
  title: '고속도로 정보시스템 감리', orderingOrganization: '한국도로공사',
  publishedAt: '2026-09-21T10:00:00', submissionDeadlineAt: '2026-09-29T17:00:00',
  noticeStatus: '진행중', detailUrl: null,
}, {
  sourceCode: 'D2B', sourceNoticeId: 'd2b-1', revision: '1', noticeNumber: 'D2B-1',
  title: '국방 정보시스템 감리', orderingOrganization: '국방부',
  publishedAt: '2026-09-20T08:00:00', submissionDeadlineAt: null,
  noticeStatus: '공고', detailUrl: 'javascript:alert(1)',
}];

const sourceStatuses = [{
  sourceCode: 'G2B', lastSuccessAt: '2026-09-22T01:00:00Z', lastFailureAt: null,
  dailyLimit: null, usedCalls: 0, executionEnabled: true,
}, {
  sourceCode: 'KOREA_EXPRESSWAY', lastSuccessAt: null, lastFailureAt: '2026-09-21T01:00:00Z',
  dailyLimit: 100, usedCalls: 7, executionEnabled: true,
}, {
  sourceCode: 'D2B', lastSuccessAt: null, lastFailureAt: null,
  dailyLimit: 50, usedCalls: 3, executionEnabled: false,
}];

const successfulCollection = {
  status: 'SUCCESS', startDate: '2026-09-01', endDate: '2026-09-22', skippedSourceCodes: ['D2B'],
  sources: [{ sourceCode: 'G2B', runId: 41, status: 'SUCCESS', collectedCount: 4,
    newCount: 2, updatedCount: 1, unchangedCount: 1, failedCount: 0, errorCode: null }],
};

function pageResponse(url, items = notices) {
  const page = Number(url.searchParams.get('page') || 0);
  const size = Number(url.searchParams.get('size') || 20);
  return { items, page, size, totalCount: items.length, totalPages: items.length ? 2 : 0 };
}

async function mockBidNotices(page, handler = url => pageResponse(url)) {
  const requests = [];
  await page.route('**/api/bid-sources/status', route => route.fulfill({ json: sourceStatuses }));
  await page.route('**/api/bid-notices?*', async route => {
    const url = new URL(route.request().url());
    requests.push({ url, method: route.request().method() });
    const response = await handler(url, route);
    if (response) await route.fulfill({ status: 200, json: response });
  });
  return requests;
}

test('App Shell bid menu navigates to the saved bids route', async ({ page }) => {
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url());
    return route.fulfill({ json: url.pathname === '/api/bid-notices'
      ? { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 }
      : [] });
  });
  await page.goto('/react/index.html#/');
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('link', { name: '입찰공고' }).click();
  await expect(page).toHaveURL(/#\/bids$/);
  await expect(page.getByRole('heading', { name: '저장된 입찰공고' })).toBeVisible();
});

test('saved bids route renders stored notices, sources and safe detail links', async ({ page }) => {
  const requests = await mockBidNotices(page);
  await page.goto('/react/index.html#/bids');

  await expect(page.getByRole('heading', { name: '저장된 입찰공고' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('link', { name: '입찰공고' }))
    .toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.saved-bids-summary')).toContainText('전체 3건');
  await expect(page.locator('.saved-bids-table')).toContainText('나라장터');
  await expect(page.locator('.saved-bids-table')).toContainText('한국도로공사');
  await expect(page.locator('.saved-bids-table')).toContainText('D2B');
  await expect(page.locator('.saved-bid-title-cell').first()).toContainText('2026-001');
  await expect(page.getByRole('link', { name: '원문 보기 ↗' })).toHaveCount(1);
  await expect(page.getByRole('link', { name: '원문 보기 ↗' })).toHaveAttribute('href', 'https://example.test/g2b-1');
  await expect(page.getByText('원문 링크 없음')).toHaveCount(2);
  await expect(page.getByRole('link', { name: '실시간 분석 화면 열기 ↗' })).toHaveAttribute('href', '/bids/');
  await expect(page.getByRole('button', { name: /D2B.*활성/ })).toHaveCount(0);
  await expect(page.locator('.bid-source-status-card').filter({ hasText: 'D2B' })).toContainText('비활성');
  expect(requests.every(request => request.method === 'GET')).toBe(true);
});

test('manual refresh posts the selected period once, reports counts and reloads stored data and source status', async ({ page }) => {
  const requests = [];
  let listRequests = 0;
  let statusRequests = 0;
  let releaseCollection;
  const collectionGate = new Promise(resolve => { releaseCollection = resolve; });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    requests.push({ path: url.pathname, method: request.method(), body: request.postDataJSON?.() });
    if (url.pathname === '/api/bid-notices') {
      listRequests += 1;
      return route.fulfill({ json: pageResponse(url, [notices[0]]) });
    }
    if (url.pathname === '/api/bid-sources/status') {
      statusRequests += 1;
      return route.fulfill({ json: sourceStatuses });
    }
    if (url.pathname === '/api/bid-collections' && request.method() === 'POST') {
      await collectionGate;
      return route.fulfill({ json: successfulCollection });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
  await page.getByLabel('시작일').fill('2026-09-01');
  await page.getByLabel('종료일').fill('2026-09-22');
  await page.getByRole('button', { name: '조회' }).click();
  await expect.poll(() => listRequests).toBe(2);
  const listRequestsBeforeRefresh = listRequests;

  await page.getByRole('button', { name: '최신 공고 갱신' }).click();
  await expect(page.getByRole('button', { name: '공고 수집 중…' })).toBeDisabled();
  await expect.poll(() => requests.filter(entry => entry.path === '/api/bid-collections').length).toBe(1);
  await page.getByRole('button', { name: '공고 수집 중…' }).evaluate(button =>
    button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  expect(requests.filter(entry => entry.path === '/api/bid-collections')).toHaveLength(1);

  releaseCollection();
  await expect(page.getByRole('region', { name: '공고 갱신 결과' })).toContainText('공고 갱신 완료');
  await expect(page.getByRole('region', { name: '공고 갱신 결과' })).toContainText('신규 2건 · 변경 1건 · 동일 1건');
  await expect.poll(() => listRequests).toBe(listRequestsBeforeRefresh + 1);
  await expect.poll(() => statusRequests).toBe(2);
  const post = requests.find(entry => entry.path === '/api/bid-collections');
  expect(post.method).toBe('POST');
  expect(post.body).toEqual({ startDate: '2026-09-01', endDate: '2026-09-22' });
});

test('manual refresh uses today when the list period is unspecified', async ({ page }) => {
  let postedBody;
  await page.clock.install({ time: new Date('2026-09-23T12:00:00+09:00') });
  await page.route('**/api/**', route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/bid-notices') return route.fulfill({ json: pageResponse(url, []) });
    if (url.pathname === '/api/bid-sources/status') return route.fulfill({ json: sourceStatuses });
    if (url.pathname === '/api/bid-collections') {
      postedBody = request.postDataJSON();
      return route.fulfill({ json: { ...successfulCollection, startDate: '2026-09-23', endDate: '2026-09-23' } });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/bids');
  await page.getByRole('button', { name: '최신 공고 갱신' }).click();
  await expect(page.getByRole('region', { name: '공고 갱신 결과' })).toBeVisible();
  expect(postedBody).toEqual({ startDate: '2026-09-23', endDate: '2026-09-23' });
});

test('partial refresh shows per-source success and failure and reloads the list', async ({ page }) => {
  let listRequests = 0;
  await page.route('**/api/**', route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/bid-notices') {
      listRequests += 1;
      return route.fulfill({ json: pageResponse(url, [notices[0]]) });
    }
    if (url.pathname === '/api/bid-sources/status') return route.fulfill({ json: sourceStatuses });
    if (url.pathname === '/api/bid-collections') return route.fulfill({ json: {
      ...successfulCollection,
      status: 'PARTIAL_SUCCESS',
      sources: [successfulCollection.sources[0], { sourceCode: 'KOREA_EXPRESSWAY', runId: 42,
        status: 'FAILED', collectedCount: 0, newCount: 0, updatedCount: 0, unchangedCount: 0,
        failedCount: 1, errorCode: 'COLLECTION_FAILED' }],
    } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: '최신 공고 갱신' }).click();
  const result = page.getByRole('region', { name: '공고 갱신 결과' });
  await expect(result).toContainText('공고 갱신 부분 성공');
  await expect(result).toContainText('나라장터');
  await expect(result).toContainText('한국도로공사');
  await expect(result).toContainText('수집 실패');
  await expect.poll(() => listRequests).toBe(2);
});

test('manual refresh maps 400, 409, 429 and 503 safely and preserves the stored list', async ({ page }) => {
  let failureStatus = 400;
  let listRequests = 0;
  await page.route('**/api/**', route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/bid-notices') {
      listRequests += 1;
      return route.fulfill({ json: pageResponse(url, [notices[0]]) });
    }
    if (url.pathname === '/api/bid-sources/status') return route.fulfill({ json: sourceStatuses });
    if (url.pathname === '/api/bid-collections') return route.fulfill({ status: failureStatus,
      json: failureStatus === 503
        ? { status: 'FAILED', sources: [{ sourceCode: 'G2B', status: 'FAILED', errorCode: 'COLLECTION_FAILED' }] }
        : { message: 'internal-uri-and-exception-must-not-render' } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
  const expected = new Map([
    [400, '조회 기간 등 갱신 조건을 확인해 주세요.'],
    [409, '이미 공고 수집이 진행 중입니다.'],
    [429, '공고 수집 호출량 제한에 도달했습니다.'],
    [503, '전체 공고 수집에 실패했습니다.'],
  ]);
  for (const [status, message] of expected) {
    failureStatus = status;
    await page.getByRole('button', { name: '최신 공고 갱신' }).click();
    await expect(page.locator('.collection-message.error')).toContainText(message);
    await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);
    await expect(page.locator('body')).not.toContainText('internal-uri-and-exception-must-not-render');
  }
  await expect(page.locator('.collection-message.error')).toContainText('실패 출처: 나라장터');
  expect(listRequests).toBe(1);
});

test('source status shows activity, timestamps, quota and keeps D2B disabled', async ({ page }) => {
  await mockBidNotices(page);
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.bid-source-status-card')).toHaveCount(3);
  await expect(page.locator('.bid-source-status-card').filter({ hasText: '나라장터' })).toContainText('활성');
  await expect(page.locator('.bid-source-status-card').filter({ hasText: '한국도로공사' })).toContainText('7 / 100');
  const d2b = page.locator('.bid-source-status-card').filter({ hasText: 'D2B' });
  await expect(d2b).toContainText('비활성');
  await expect(d2b).toContainText('3 / 50');
  await expect(d2b.getByRole('button')).toHaveCount(0);
});

test('saved bids send date, source and server pagination parameters and reset page', async ({ page }) => {
  const requests = await mockBidNotices(page, url => {
    const response = pageResponse(url, [notices[0]]);
    response.totalCount = 3;
    response.totalPages = 2;
    return response;
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(1);

  await page.getByRole('button', { name: '다음' }).click();
  await expect.poll(() => requests.at(-1).url.searchParams.get('page')).toBe('1');

  await page.locator('select[name="sourceCode"]').selectOption('D2B');
  await expect.poll(() => requests.at(-1).url.searchParams.toString()).toContain('sourceCode=D2B');
  expect(requests.at(-1).url.searchParams.get('page')).toBe('0');

  await page.getByLabel('시작일').fill('2026-09-01');
  await page.getByLabel('종료일').fill('2026-09-22');
  await page.getByRole('button', { name: '조회' }).click();
  await expect.poll(() => requests.at(-1).url.searchParams.get('startDate')).toBe('2026-09-01');
  expect(requests.at(-1).url.searchParams.get('endDate')).toBe('2026-09-22');
  expect(requests.at(-1).url.searchParams.get('sourceCode')).toBe('D2B');
  expect(requests.at(-1).url.searchParams.get('page')).toBe('0');

  await page.getByLabel('페이지 크기').selectOption('100');
  await expect.poll(() => requests.at(-1).url.searchParams.get('size')).toBe('100');
  expect(requests.at(-1).url.searchParams.get('page')).toBe('0');
});

test('saved bids show the explicit empty database state without fallback calls', async ({ page }) => {
  const apiRequests = [];
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url());
    apiRequests.push(url.pathname);
    return route.fulfill({ json: url.pathname === '/api/bid-sources/status' ? []
      : { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 } });
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.getByText('아직 저장된 공고가 없습니다.')).toBeVisible();
  await expect(page.locator('.saved-bids-summary')).toContainText('전체 0건');
  expect(apiRequests.sort()).toEqual(['/api/bid-notices', '/api/bid-sources/status']);
});

test('saved bids expose loading and API error states', async ({ page }) => {
  let pendingRequest;
  await page.route('**/api/bid-sources/status', route => route.fulfill({ json: sourceStatuses }));
  await page.route('**/api/bid-notices?*', route => { pendingRequest = route; });
  await page.goto('/react/index.html#/bids');
  await expect(page.getByRole('status')).toHaveText('저장된 공고를 불러오는 중입니다.');

  await pendingRequest.abort();
  await page.unroute('**/api/bid-notices?*');
  await page.route('**/api/bid-notices?*', route => route.fulfill({ status: 503, json: {} }));
  await page.reload();
  await expect(page.getByRole('alert')).toHaveText('저장된 공고를 불러오지 못했습니다.');
});

test('saved bids mobile layout stays inside the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockBidNotices(page);
  await page.goto('/react/index.html#/bids');
  await expect(page.locator('.saved-bids-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.saved-bids-table thead')).toBeHidden();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/saved-bids-mobile.png', fullPage: true });
});
