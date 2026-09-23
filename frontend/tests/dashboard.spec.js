
import { test, expect } from '@playwright/test';

async function setup(page, { empty = false, fail = '', malformed = false } = {}) {
    const requests = [];
    const cases = [1, 2, 3, 4].map(id => ({ id, projectName: `서류 사업 ${id}`, prepared: id === 2 ? 2 : 1,
        total: 2, deadline: '2026-09-14', updatedAt: `2026-09-0${id}T09:00:00Z` }));
    const projects = [1, 2, 3, 4].map(id => ({ id, name: `실적 사업 ${id}`, updatedAt: `2026-09-0${id}T09:00:00Z` }));
    const data = {
        '/api/submission-cases': cases,
        '/api/performance-projects': projects,
        '/api/submission-document-masters': [{ category: 'COMPANY_COMMON', currentFileId: 'file' },
            { category: 'COMPANY_COMMON', uploadedFileId: 'uploaded' }, { category: 'COMPANY_COMMON' }, { category: 'OTHER' }],
        '/api/bids/target/qualification': [{ reviewStatus: '추가확인필요' }, { reviewStatus: '검토완료' }],
        '/api/bid-notices': { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 },
        '/api/external-notices': [{ id: 9, title: '중요공지', publishedDate: '2026-09-01', lastSeenAt: '2026-09-01T09:00:00Z' }]
    };
    await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.pathname.startsWith('/api/')) {
            requests.push({ path: url.pathname, method: route.request().method() });
            if (fail && url.pathname.includes(fail)) return route.fulfill({ status: 503, json: {} });
            if (malformed) return route.fulfill({ json: {} });
            const entries = [{ info: { selectedFileId: 1 } }, { info: { selectedDriveFileId: 'drive' } },
                { info: { selectedUploadedFileId: 'upload' } }, { info: {} }];
            const body = url.pathname.endsWith('/entries') ? entries : data[url.pathname];
            if (!body) return route.fulfill({ status: 404, json: {} });
            const responseBody = empty && Array.isArray(body) ? []
                : empty && url.pathname === '/api/bid-notices'
                    ? { items: [], page: 0, size: 20, totalCount: 0, totalPages: 0 }
                    : body;
            return route.fulfill({ json: responseBody });
        }

        return route.continue();
    });
    await page.clock.install({ time: new Date('2026-09-11T12:00:00+09:00') });
    await page.goto('/react/index.html#/');
    return requests;
}

test('dashboard: counts, recent order, saved evidence types and existing links', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const requests = await setup(page);
    await expect(page.getByRole('heading', { name: 'Biz Assist', exact: true })).toBeVisible();
    await expect(page.locator('.app-nav-link')).toHaveCount(7);
    await expect(page.locator('#submission-count')).toHaveText('3건');
    await expect(page.locator('#performance-count')).toHaveText('12건');
    await expect(page.locator('#document-count')).toHaveText('1건');
    await expect(page.locator('#bid-check-count')).toHaveText('1건');
    await expect(page.locator('#bid-check-count').locator('xpath=ancestor::a')).toHaveAttribute('href', '#/bids');
    await expect(page.locator('#recent-submissions .work-item')).toHaveCount(3);
    const first = page.locator('#recent-submissions .work-item').first();
    await expect(first).toContainText('서류 사업 4');
    await expect(first).toContainText('1 / 2 · 50%');
    await expect(first).toContainText('D-3');
    await expect(first.locator('progress')).toHaveAttribute('value', '50');
    await expect(first.getByRole('link')).toHaveAttribute('href', '/submissions/?caseId=4');
    await expect(page.locator('#recent-performances .work-item')).toHaveCount(3);
    const performance = page.locator('#recent-performances .work-item').first();
    await expect(performance).toContainText('실적 사업 4');
    await expect(performance).toContainText('증빙 등록 3건');
    await expect(performance).toContainText('파일 미등록 1건');
    await expect(performance.getByRole('link')).toHaveAttribute('href', '#/performances/4');
    await expect(page.locator('#performance-count').locator('xpath=ancestor::a')).toHaveAttribute('href', '#/performances');
    await expect(page.getByRole('link', { name: '전체보기 →', exact: true }).nth(1)).toHaveAttribute('href', '#/performances');
    await expect(page.locator('.quick-grid a').filter({ hasText: '실적 붙여넣기' })).toHaveAttribute('href', '#/performances');
    await expect(page.locator('.quick-grid a')).toHaveCount(4);
    await expect(page.locator('.quick-grid a').filter({ hasText: '입찰공고 조회' })).toHaveAttribute('href', '#/bids');
    await expect(page.locator('#recent-notice-list a')).toHaveAttribute('href', '/notices/?noticeId=9');
    expect(requests.every(request => request.method === 'GET')).toBe(true);
    expect(requests.some(request => request.path === '/api/bids/target/qualification')).toBe(true);
    expect(requests.some(request => request.path === '/api/bid-notices')).toBe(false);
    expect(errors).toEqual([]);
    await page.screenshot({ path: 'test-results/dashboard-desktop.png', fullPage: true });
    await page.locator('#bid-check-count').locator('xpath=ancestor::a').click();
    await expect(page).toHaveURL(/#\/bids$/);
    await expect(page.getByRole('heading', { name: '저장된 입찰공고' })).toBeVisible();
});

test('dashboard: empty data does not create sample work', async ({ page }) => {
    await setup(page, { empty: true });
    for (const key of ['submission', 'performance', 'document', 'bid-check']) {
        await expect(page.locator(`#${key}-count`)).toHaveText('0건');
    }
    await expect(page.locator('#recent-submissions')).toHaveText('등록된 서류 프로젝트가 없습니다.');
    await expect(page.locator('#recent-performances')).toHaveText('등록된 실적 프로젝트가 없습니다.');
    await expect(page.locator('#recent-notice-empty')).toBeVisible();
    await expect(page.locator('.work-item')).toHaveCount(0);
});

test('dashboard: partial evidence failure keeps other work and avoids a misleading total', async ({ page }) => {
    await setup(page, { fail: '/performance-projects/4/entries' });
    await expect(page.locator('#performance-count')).toHaveText('—');
    await expect(page.locator('#performance-message')).toHaveText('일부 증빙 조회 실패');
    await expect(page.locator('#recent-performances .work-item').first()).toContainText('증빙 조회 실패');
    await expect(page.locator('#recent-performances .work-item').nth(1)).toContainText('증빙 등록 3건');
    await expect(page.locator('#submission-count')).toHaveText('3건');
});

test('dashboard: invalid responses show errors instead of zero counts', async ({ page }) => {
    await setup(page, { malformed: true });
    await expect(page.locator('#submission-message')).toHaveText('조회 실패');
    await expect(page.locator('#performance-message')).toHaveText('조회 실패');
    await expect(page.locator('#document-message')).toHaveText('조회 실패');
    await expect(page.locator('#recent-submissions')).toContainText('불러오지 못했습니다');
    await expect(page.locator('#recent-notice-error')).toBeVisible();
});

test('dashboard: mobile layout keeps actions inside viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setup(page);
    await expect(page.locator('#performance-count')).toHaveText('12건');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.locator('.quick-grid a').last()).toBeVisible();
    await page.screenshot({ path: 'test-results/dashboard-mobile.png', fullPage: true });
});

test('React routes survive reload and retain legacy navigation URLs', async ({ page }) => {
    await setup(page);
    await expect(page.locator('.app-nav-link.active')).toHaveText('홈');
    await expect(page.locator('.app-nav-link.active')).toHaveAttribute('aria-current', 'page');
    await page.reload();
    await expect(page.locator('#submission-count')).toHaveText('3건');
    await page.goto('/react/index.html#/unknown');
    await expect(page).toHaveURL(/index\.html#\/$/);
    await expect(page.locator('#submission-count')).toHaveText('3건');
    await expect(page.getByRole('navigation').getByRole('link', { name: '서류 관리' })).toHaveAttribute('href', '#/documents');
    await expect(page.getByRole('navigation').getByRole('link', { name: '서류 모으기' })).toHaveAttribute('href', '#/submissions');
    await expect(page.getByRole('navigation').getByRole('link', { name: '실적 관리' })).toHaveAttribute('href', '#/performances');
    await page.getByRole('navigation').getByRole('link', { name: '서류 모으기' }).click();
    await expect(page).toHaveURL(/#\/submissions$/);
    await expect(page.locator('.submission-project-card')).toHaveCount(4);
    await page.getByRole('navigation').getByRole('link', { name: '홈', exact: true }).click();
    await expect(page.locator('#submission-count')).toHaveText('3건');
    await expect(page.locator('.react-submissions')).toHaveCount(0);
});

test('React loading state does not invent zero counts', async ({ page }) => {
    await page.route('**/api/**', () => {});
    await page.goto('/react/index.html#/');
    await expect(page.locator('#submission-count')).toHaveText('—');
    await expect(page.locator('#submission-message')).toHaveText('확인 중');
    await expect(page.locator('#recent-notice-loading')).toBeVisible();
});

test('API text is rendered as text without executing HTML', async ({ page }) => {
    await setup(page);
    const title = '<img src=x onerror="window.injected=true">';
    await page.route('**/api/external-notices?*', route => route.fulfill({ json: [{ id: 9, title }] }));
    await page.reload();
    await expect(page.locator('.recent-notice-title')).toHaveText(title);
    await expect(page.locator('.recent-notice-title img')).toHaveCount(0);
    expect(await page.evaluate(() => window.injected)).toBeUndefined();
});
