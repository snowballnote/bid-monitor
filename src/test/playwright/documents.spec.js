const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');

async function setup(page, initial = []) {
    let items = initial.map(item => ({ ...item }));
    const writes = [];
    await page.route('**/*', async route => {
        const request = route.request();
        const url = new URL(request.url());
        if (url.pathname.startsWith('/api/submission-document-masters')) {
            if (request.method() === 'GET') return route.fulfill({ json: items });
            writes.push({ method: request.method(), path: url.pathname, body: request.postData() });
            const id = url.pathname.split('/')[3];
            if (request.method() === 'POST' && !id) {
                const data = request.postDataJSON();
                if (data.name === '실패') return route.fulfill({ status: 400, json: { message: '추가 실패' } });
                items.push({ id: 'new', ...data });
            } else if (url.pathname.endsWith('/upload')) {
                Object.assign(items.find(item => item.id === id), { currentFileId: 'file', currentFilename: '등록.pdf', sizeBytes: 4, uploadedAt: '2026-09-10T00:00:00Z' });
            } else if (request.method() === 'PUT') {
                Object.assign(items.find(item => item.id === id), request.postDataJSON());
            } else if (request.method() === 'DELETE') {
                items = items.filter(item => item.id !== id);
            }
            return route.fulfill({ json: {} });
        }
        const relative = url.pathname.endsWith('/') ? url.pathname + 'index.html' : url.pathname;
        const file = path.join(__dirname, '../../main/resources/static', relative);
        if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
        return route.fulfill({ path: file });
    });
    await page.goto('/documents/');
    return writes;
}

test('documents: table, modal focus, cancel, validation, error and creation', async ({ page }) => {
    const writes = await setup(page);
    await expect(page.getByRole('columnheader')).toHaveText(['서류명', '카테고리', '현재 파일', '상태', '관리']);
    await expect(page.getByRole('cell', { name: '등록된 회사 공통서류가 없습니다.' })).toBeVisible();
    const open = page.getByRole('button', { name: '+ 서류 추가', exact: true });
    const dialog = page.getByRole('dialog', { name: '서류 추가' });
    await expect(dialog).not.toBeVisible();
    await open.click();
    await expect(dialog.getByLabel('서류명')).toBeFocused();
    await expect(dialog.getByLabel('카테고리')).toHaveValue('COMPANY_COMMON');
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(open).toBeFocused();
    await open.click();
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await open.click();
    await dialog.getByLabel('서류명').fill('   ');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    await expect(dialog.getByRole('alert')).toHaveText('서류명을 입력하세요.');
    expect(writes).toHaveLength(0);
    await dialog.getByLabel('서류명').fill('실패');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    await expect(dialog.getByRole('alert')).toHaveText('추가 실패');
    await expect(dialog.getByLabel('서류명')).toHaveValue('실패');
    await dialog.getByLabel('서류명').fill(' 사업자등록증 ');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByRole('rowheader', { name: '사업자등록증' })).toBeVisible();
    expect(JSON.parse(writes.at(-1).body)).toEqual({ name: '사업자등록증', category: 'COMPANY_COMMON' });
    await open.click();
    await expect(dialog.getByLabel('서류명')).toHaveValue('');
});

test('documents: existing upload, replacement, rename and deletion', async ({ page }) => {
    const writes = await setup(page, [{ id: 'one', name: '사업자등록증', category: 'COMPANY_COMMON' }]);
    const row = page.locator('tbody tr[data-id="one"]');
    await expect(row.getByRole('button', { name: '파일 등록' })).toBeVisible();
    await row.locator('input[type=file]').setInputFiles({ name: '빈파일.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(0) });
    await expect(page.getByRole('status')).toContainText('비어 있지 않은');
    expect(writes).toHaveLength(0);
    await row.locator('input[type=file]').setInputFiles({ name: '등록.pdf', mimeType: 'application/pdf', buffer: Buffer.from('file') });
    await expect(row.getByRole('button', { name: '파일 교체' })).toBeVisible();
    await expect(row.getByText('등록.pdf', { exact: true })).toBeVisible();
    await row.locator('input[type=file]').setInputFiles({ name: '교체.pdf', mimeType: 'application/pdf', buffer: Buffer.from('next') });
    await expect.poll(() => writes.filter(item => item.path.endsWith('/upload')).length).toBe(2);
    await row.getByRole('button', { name: '서류명 수정' }).click();
    await row.getByRole('textbox', { name: '서류명 수정' }).fill('수정 서류');
    await row.getByRole('button', { name: '저장', exact: true }).click();
    await expect(row.getByRole('rowheader')).toHaveText('수정 서류저장취소');
    expect(JSON.parse(writes.find(item => item.method === 'PUT').body)).toEqual({ name: '수정 서류', category: 'COMPANY_COMMON' });
    page.once('dialog', dialog => dialog.dismiss());
    await row.getByRole('button', { name: '삭제', exact: true }).click();
    await expect(row).toBeVisible();
    page.once('dialog', dialog => dialog.accept());
    await row.getByRole('button', { name: '삭제', exact: true }).click();
    await expect(row).toHaveCount(0);
});

test('documents: narrow viewport keeps table scroll inside list and modal on screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setup(page, [{ id: 'one', name: '긴서류명'.repeat(20), category: 'COMPANY_COMMON' }]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect(await page.locator('.documents-list').evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
    await page.getByRole('button', { name: '+ 서류 추가' }).click();
    const bounds = await page.getByRole('dialog').boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
    await expect(page.getByRole('dialog').getByRole('button', { name: '추가', exact: true })).toBeInViewport();
});
