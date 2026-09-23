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

function pageResponse(url, items = notices) {
  const page = Number(url.searchParams.get('page') || 0);
  const size = Number(url.searchParams.get('size') || 20);
  return { items, page, size, totalCount: items.length, totalPages: items.length ? 2 : 0 };
}

async function mockBidNotices(page, handler = url => pageResponse(url)) {
  const requests = [];
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
  expect(requests.every(request => request.method === 'GET')).toBe(true);
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

  await page.getByLabel('출처').selectOption('D2B');
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
    return route.fulfill({ json: { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 } });
  });
  await page.goto('/react/index.html#/bids');
  await expect(page.getByText('아직 저장된 공고가 없습니다.')).toBeVisible();
  await expect(page.locator('.saved-bids-summary')).toContainText('전체 0건');
  expect(apiRequests).toEqual(['/api/bid-notices']);
});

test('saved bids expose loading and API error states', async ({ page }) => {
  let pendingRequest;
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
