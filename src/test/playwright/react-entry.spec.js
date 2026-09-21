const { test, expect } = require('@playwright/test');

test('routing: Spring root opens React, sidebar and direct hash refresh work', async ({ page }) => {
    await page.route('**/api/**', route => route.fulfill({ json: [] }));
    await page.goto('/');
    await expect(page).toHaveURL(/\/react\/index.html#\/$/);
    await expect(page.getByRole('heading', { name: 'Biz Assist', exact: true })).toBeVisible();
    const nav = page.getByRole('navigation');
    await nav.getByRole('link', { name: '실적 관리', exact: true }).click();
    await expect(page).toHaveURL(/#\/performances$/);
    await expect(page.getByRole('heading', { name: '실적 프로젝트', exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: '실적 프로젝트', exact: true })).toBeVisible();
    await nav.getByRole('link', { name: '홈', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Biz Assist', exact: true })).toBeVisible();
    await page.goto('/react/index.html#/performances');
    await expect(page.getByRole('heading', { name: '실적 프로젝트', exact: true })).toBeVisible();
    await page.goto('/react/index.html');
    await expect(page.getByRole('heading', { name: 'Biz Assist', exact: true })).toBeVisible();
});

test('routing: vanilla entry files and short routes remain served by Spring', async ({ request }) => {
    for (const [path, marker] of [
        ['/index.html', 'home.js'], ['/performances/index.html?project=old&caseId=71', 'performances.js'],
        ['/documents/', 'documents.js'], ['/submissions/', 'submissions.js'],
        ['/bids/', 'app.js'], ['/notices/', 'notices.js'], ['/notifications/', 'notifications.js'],
    ]) {
        const response = await request.get(path);
        expect(response.status(), path).toBe(200);
        expect(await response.text(), path).toContain(marker);
    }
});
