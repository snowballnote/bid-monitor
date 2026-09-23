import { test, expect } from '@playwright/test';

const pendingRegistration = {
  sourceId: 1,
  sourceName: '지역 공공기관 입찰정보',
  siteUrl: 'https://bids.example.go.kr/notices',
  registrationStatus: 'PENDING_REVIEW',
  collectionMethod: 'UNDETERMINED',
  executionEnabled: false,
  createdAt: '2026-09-23T01:00:00Z',
  updatedAt: '2026-09-23T01:00:00Z',
};

const reviewedRegistrations = [pendingRegistration, {
  ...pendingRegistration,
  sourceId: 2,
  sourceName: '검토 중인 입찰정보',
  siteUrl: 'https://review.example.org/bids',
  registrationStatus: 'UNDER_REVIEW',
  collectionMethod: 'PUBLIC_PAGE',
  createdAt: '2026-09-22T01:00:00Z',
  updatedAt: '2026-09-22T01:00:00Z',
}];

async function mockBidPageApis(page) {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/bid-notices') {
      return route.fulfill({ json: { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 } });
    }
    if (path === '/api/bid-sources/status' || path === '/api/bid-source-registrations') {
      return route.fulfill({ json: [] });
    }
    return route.fulfill({ status: 404, json: {} });
  });
}

test('App Shell and saved bid list navigate to the bid source route', async ({ page }) => {
  await mockBidPageApis(page);
  await page.goto('/react/index.html#/bids');

  await page.getByRole('navigation', { name: '주요 메뉴' })
    .getByRole('link', { name: '수집처 관리' }).click();
  await expect(page).toHaveURL(/#\/bid-sources$/);
  await expect(page.getByRole('heading', { name: '입찰공고 수집처 관리' })).toBeVisible();

  await page.getByRole('link', { name: '입찰공고 목록으로' }).click();
  await expect(page).toHaveURL(/#\/bids$/);
  await page.getByRole('link', { name: '수집처 등록·현황' }).click();
  await expect(page).toHaveURL(/#\/bid-sources$/);
});

test('registers only name and URL, then reloads and renders review state', async ({ page }) => {
  const requests = [];
  let listCount = 0;
  await page.route('**/api/**', route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push({ path, method: request.method(), body: request.postDataJSON?.() });
    if (path !== '/api/bid-source-registrations') return route.fulfill({ status: 404, json: {} });
    if (request.method() === 'POST') return route.fulfill({ status: 201, json: pendingRegistration });
    listCount += 1;
    return route.fulfill({ json: listCount === 1 ? [] : reviewedRegistrations });
  });

  await page.goto('/react/index.html#/bid-sources');
  await expect(page.getByText('아직 등록된 수집처가 없습니다.')).toBeVisible();
  await page.getByLabel('수집처 이름').fill('지역 공공기관 입찰정보');
  await page.getByLabel('사이트 URL').fill('https://bids.example.go.kr/notices');
  await page.getByRole('button', { name: '수집처 등록' }).click();

  await expect(page.locator('.bid-source-form-message.success')).toContainText('수집처가 등록되었습니다.');
  await expect(page.locator('.bid-source-table tbody tr')).toHaveCount(2);
  await expect(page.locator('.bid-source-table')).toContainText('검토 대기');
  await expect(page.locator('.bid-source-table')).toContainText('검토 중');
  await expect(page.locator('.bid-source-table')).toContainText('미확정');
  await expect(page.locator('.bid-source-table')).toContainText('공개 페이지');
  await expect(page.locator('.bid-source-table')).toContainText('비활성');
  await expect(page.locator('.bid-source-url a')).toHaveCount(0);
  await expect(page.getByText('등록한 사이트는 수집 가능 여부 검토 후 수집 대상으로 연결됩니다.')).toBeVisible();

  const post = requests.find(request => request.method === 'POST');
  expect(post.body).toEqual({
    sourceName: '지역 공공기관 입찰정보',
    siteUrl: 'https://bids.example.go.kr/notices',
  });
  expect(listCount).toBe(2);
  expect(requests.some(request => request.path === '/api/bid-collections')).toBe(false);
  expect(requests.some(request => request.path === '/api/bid-sources/status')).toBe(false);
});

test('shows safe validation, internal URL and duplicate URL messages', async ({ page }) => {
  let postCount = 0;
  let responseStatus = 400;
  await page.route('**/api/bid-source-registrations', route => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({ json: [] });
    postCount += 1;
    return route.fulfill({ status: responseStatus, json: { message: 'internal exception and URI' } });
  });
  await page.goto('/react/index.html#/bid-sources');

  await page.getByRole('button', { name: '수집처 등록' }).click();
  await expect(page.getByRole('alert')).toHaveText('수집처 이름을 입력해 주세요.');
  await page.getByLabel('수집처 이름').fill('잘못된 주소');
  await page.getByLabel('사이트 URL').fill('ftp://example.com/bids');
  await page.getByRole('button', { name: '수집처 등록' }).click();
  await expect(page.getByRole('alert')).toContainText('HTTP 또는 HTTPS 형식');
  expect(postCount).toBe(0);

  await page.getByLabel('사이트 URL').fill('http://127.0.0.1/bids');
  await page.getByRole('button', { name: '수집처 등록' }).click();
  await expect(page.getByRole('alert')).toContainText('공개된 HTTP 또는 HTTPS 주소');
  responseStatus = 409;
  await page.getByLabel('사이트 URL').fill('https://duplicate.example/bids');
  await page.getByRole('button', { name: '수집처 등록' }).click();
  await expect(page.getByRole('alert')).toHaveText('이미 등록된 사이트 URL입니다.');
  await expect(page.locator('body')).not.toContainText('internal exception and URI');
});

test('prevents duplicate registration posts while a request is pending', async ({ page }) => {
  let postCount = 0;
  let releasePost;
  const gate = new Promise(resolve => { releasePost = resolve; });
  await page.route('**/api/bid-source-registrations', async route => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({ json: [] });
    postCount += 1;
    await gate;
    return route.fulfill({ status: 201, json: pendingRegistration });
  });
  await page.goto('/react/index.html#/bid-sources');
  await page.getByLabel('수집처 이름').fill('중복 방지 수집처');
  await page.getByLabel('사이트 URL').fill('https://once.example/bids');
  await page.getByRole('button', { name: '수집처 등록' }).click();
  const pendingButton = page.getByRole('button', { name: '등록 중…' });
  await expect(pendingButton).toBeDisabled();
  await pendingButton.evaluate(button => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  expect(postCount).toBe(1);
  releasePost();
  await expect(page.getByRole('status')).toContainText('수집처가 등록되었습니다.');
});

test('shows loading, list error and server registration error without raw details', async ({ page }) => {
  let pendingList;
  await page.route('**/api/bid-source-registrations', route => {
    if (route.request().method() === 'GET') {
      pendingList = route;
      return;
    }
    return route.fulfill({ status: 503, json: { message: 'database path and stack trace' } });
  });
  await page.goto('/react/index.html#/bid-sources');
  await expect(page.getByRole('status')).toHaveText('등록된 수집처를 불러오는 중입니다.');
  await pendingList.fulfill({ status: 503, json: {} });
  await expect(page.getByRole('alert')).toHaveText('등록 현황을 불러오지 못했습니다.');

  await page.getByLabel('수집처 이름').fill('서버 오류 수집처');
  await page.getByLabel('사이트 URL').fill('https://failure.example/bids');
  await page.getByRole('button', { name: '수집처 등록' }).click();
  await expect(page.locator('.bid-source-form-message.error'))
    .toHaveText('수집처를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.');
  await expect(page.locator('body')).not.toContainText('database path and stack trace');
});

test('bid source management fits a 390px viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route('**/api/bid-source-registrations', route => route.fulfill({ json: reviewedRegistrations }));
  await page.goto('/react/index.html#/bid-sources');
  await expect(page.locator('.bid-source-table tbody tr')).toHaveCount(2);
  await expect(page.locator('.bid-source-table thead')).toBeHidden();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/bid-sources-mobile.png', fullPage: true });
});
