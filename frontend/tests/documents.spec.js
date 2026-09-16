import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const state = { items: options.empty ? [] : [{ id: 'one', name: '사업자등록증', category: 'COMPANY_COMMON', currentFileId: 42, currentFilename: '기존.pdf' }, { id: 'other', name: '제외항목', category: 'OTHER' }], writes: [], fail: 0, failRead: false, uploadCount: 0,
    selections: [{ requirementId: 10, fileId: 42, originalFilename: '프로젝트.pdf' }] };
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (req.method() !== 'GET') state.writes.push({ path, method: req.method(), body: req.postData() });
    if (path.startsWith('/api/submission-document-masters')) {
      if (req.method() === 'GET') {
        if (state.failRead) return route.fulfill({ status: 500, json: { message: '조회 실패' } });
        return route.fulfill({ json: state.items });
      }
      const id = decodeURIComponent(path.split('/')[3] || '');
      if (state.fail) return route.fulfill({ status: state.fail, json: { message: '저장 실패' } });
      if (path.endsWith('/upload')) {
        state.uploadCount++;
        Object.assign(state.items.find(item => item.id === id), { currentFileId: null, uploadedFileId: 'upload-' + state.uploadCount, currentFilename: state.uploadCount === 1 ? '등록.pdf' : '교체.pdf', sizeBytes: 4 });
      } else if (req.method() === 'POST') state.items.push({ id: 'new', ...req.postDataJSON() });
      else if (req.method() === 'PUT') Object.assign(state.items.find(item => item.id === id), req.postDataJSON());
      else if (req.method() === 'DELETE') state.items = state.items.filter(item => item.id !== id);
      return route.fulfill({ status: 204 });
    }
    if (req.method() !== 'GET') return route.fulfill({ status: 400 });
    if (path === '/api/submission-cases/71') return route.fulfill({ json: { id: 71, projectName: '기존 프로젝트', deadline: '2026-10-01' } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: [{ id: 10, category: 'COMPANY_GENERAL', documentName: '사업자등록증', companyCommon: true }] });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: state.selections } });
    return route.fulfill({ json: [] });
  });
  await page.goto('/react/index.html#/' + (options.detail ? 'submissions/71' : 'documents'));
  return state;
}
const row = page => page.locator('tr[data-id="one"]');
const upload = (page, size = 4) => row(page).locator('input[type=file]').setInputFiles({ name: '등록.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(size, 65) });

test('documents: list, category filter, empty, modal validation and creation', async ({ page }) => {
  const state = await setup(page, { empty: true });
  await expect(page.getByText('등록된 회사 공통서류가 없습니다.')).toBeVisible();
  await expect(page.getByRole('columnheader')).toHaveText(['서류명', '현재 파일', '상태', '관리']);
  const open = page.getByRole('button', { name: '+ 서류 추가' });
  await open.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByLabel('서류명')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(open).toBeFocused();
  await open.click();
  await dialog.getByLabel('서류명').fill('   ');
  await dialog.getByRole('button', { name: '추가', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('서류명을 입력하세요.');
  expect(state.writes).toHaveLength(0);
  state.fail = 400;
  await dialog.getByLabel('서류명').fill(' 새서류 ');
  await dialog.getByRole('button', { name: '추가', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('저장 실패');
  state.fail = 0;
  await dialog.getByRole('button', { name: '추가', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('rowheader', { name: '새서류' })).toBeVisible();
  expect(JSON.parse(state.writes.at(-1).body)).toEqual({ name: '새서류', category: 'COMPANY_COMMON' });
});

test('documents: rename, upload, replacement, delete confirmation and snapshot isolation', async ({ page }) => {
  const state = await setup(page, { detail: true });
  const original = structuredClone(state.selections);
  await expect(page.locator('#common-documents')).toContainText('프로젝트.pdf');
  await expect(page.locator('.requirement-table').getByRole('link', { name: '상세 관리' })).toHaveAttribute('href', '#/documents');
  await page.locator('.requirement-table').getByRole('link', { name: '상세 관리' }).click();
  await expect(page).toHaveURL(/#\/documents$/);
  await expect(row(page)).toContainText('기존.pdf');
  await expect(page.getByText('제외항목')).toHaveCount(0);
  await upload(page);
  await expect(row(page)).toContainText('등록.pdf');
  await upload(page);
  await expect(row(page)).toContainText('교체.pdf');
  await row(page).getByRole('button', { name: '서류명 수정' }).click();
  await page.getByRole('dialog').getByLabel('서류명').fill('수정 서류');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(row(page).getByRole('rowheader')).toHaveText('수정 서류');
  page.once('dialog', async dialog => { expect(dialog.message()).toBe('서류 목록에서 삭제할까요? 기존 프로젝트에 연결된 파일은 유지됩니다.'); await dialog.dismiss(); });
  await row(page).getByRole('button', { name: '삭제', exact: true }).click();
  expect(state.writes.filter(write => write.method === 'DELETE')).toHaveLength(0);
  page.once('dialog', dialog => dialog.accept());
  await row(page).getByRole('button', { name: '삭제', exact: true }).click();
  await expect(row(page)).toHaveCount(0);
  await page.goto('/react/index.html#/submissions/71');
  await expect(page.locator('#common-documents')).toContainText('프로젝트.pdf');
  await expect(page.getByRole('checkbox', { name: '사업자등록증 선택' })).toBeChecked();
  expect(state.selections).toEqual(original);
  expect(state.writes.every(write => write.path.startsWith('/api/submission-document-masters/one'))).toBe(true);
});

test('documents: unregistered file upload and failed replacement reconcile; size validation and retry', async ({ page }) => {
  const state = await setup(page);
  state.items[0].currentFileId = null; state.items[0].currentFilename = null;
  await page.reload();
  await expect(row(page).getByRole('button', { name: '파일 등록' })).toBeVisible();
  await upload(page, 0);
  await expect(page.getByRole('alert')).toContainText('비어 있지 않은');
  await upload(page, 20 * 1024 * 1024 + 1);
  await expect(page.getByRole('alert')).toContainText('20MB');
  expect(state.writes).toHaveLength(0);
  await upload(page);
  await expect(row(page)).toContainText('등록.pdf');
  state.fail = 413;
  await upload(page);
  await expect(page.getByRole('alert')).toContainText('20MB 이하 파일을 선택하세요.');
  await expect(row(page)).toContainText('등록.pdf');
  state.fail = 500; state.failRead = true;
  await upload(page);
  await expect(page.getByRole('alert')).toContainText('다시 조회');
  await expect(row(page)).toHaveCount(0);
  await expect(page.getByRole('button', { name: '+ 서류 추가' })).toBeDisabled();
  state.failRead = false; state.fail = 0;
  await page.getByRole('button', { name: '다시 조회' }).click();
  await upload(page);
  await expect(row(page)).toContainText('교체.pdf');
});

test('documents: initial loading, read failure and recovery', async ({ page }) => {
  await page.route('**/api/submission-document-masters', route => route.fulfill({ status: 500, json: { message: '조회 실패' } }));
  await page.goto('/react/index.html#/documents');
  await expect(page.getByRole('alert')).toContainText('조회 실패');
  await page.unrouteAll({ behavior: 'wait' });
  let release;
  await page.route('**/api/submission-document-masters', async route => { await new Promise(resolve => { release = resolve; }); await route.fulfill({ json: [] }); });
  await page.getByRole('button', { name: '다시 조회' }).click();
  await expect(page.getByRole('status')).toHaveText('서류를 불러오는 중…');
  release();
  await expect(page.getByText('등록된 회사 공통서류가 없습니다.')).toBeVisible();
});

test('documents: mobile table and modal stay inside viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await expect(row(page)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.locator('.documents-list').evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true);
  await page.getByRole('button', { name: '+ 서류 추가' }).click();
  const bounds = await page.getByRole('dialog').boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await expect(page.getByRole('dialog').getByRole('button', { name: '추가', exact: true })).toBeInViewport();
  await page.screenshot({ path: 'test-results/documents-mobile.png', fullPage: true });
});

test('documents: committed upload with failed response refreshes current file; rename and delete errors retain server data', async ({ page }) => {
  const state = await setup(page);
  await page.route('**/api/submission-document-masters/one/upload', async route => {
    state.items[0].currentFilename = '서버저장.pdf';
    await route.fulfill({ status: 500, json: { message: '응답 실패' } });
  });
  await upload(page);
  await expect(page.getByRole('alert')).toContainText('응답 실패');
  await expect(row(page)).toContainText('서버저장.pdf');
  state.fail = 400;
  await row(page).getByRole('button', { name: '서류명 수정' }).click();
  await page.getByRole('dialog').getByLabel('서류명').fill('실패 이름');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('저장 실패');
  await expect(row(page).getByRole('rowheader')).toHaveText('사업자등록증');
  await page.getByRole('button', { name: '취소', exact: true }).click();
  page.once('dialog', dialog => dialog.accept());
  await row(page).getByRole('button', { name: '삭제', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('저장 실패');
  await expect(row(page)).toContainText('서버저장.pdf');
});
