import { test, expect } from '@playwright/test';

const entry = (id, source, filename) => ({ id, projectId: 'p1', selectedFilename: filename, info: {
  pptNumber: id, businessName: `${source} 실적`, businessPeriod: '2024.01 ~ 2025.12', contractAmount: '1억', client: '기관',
  businessStatus: 'COMPLETED', selectedFileId: source === 'company' ? 7 : null,
  selectedDriveFileId: source === 'fms' ? '11111111-1111-4111-8111-111111111111' : null,
  selectedUploadedFileId: source === 'upload' ? 'upload-1' : null, evidenceType: 'CERTIFICATE',
  kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
} });

async function setup(page, entries = [entry('1', 'fms', 'FMS.pdf')]) {
  const project = { id: 'p1', name: 'ZIP 프로젝트', deadline: '2026-10-01', daysRemaining: 13, status: entries.length ? 'READY' : 'DRAFT' };
  const state = { downloads: 0, writes: [], hold: false, release: null, error: null, disposition: 'attachment; filename="performance-evidence.zip"' };
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    if (request.method() !== 'GET') state.writes.push({ method: request.method(), path });
    if (path === '/api/drive-index') return route.fulfill({ json: [] });
    if (path === '/api/performance-projects/p1') return route.fulfill({ json: project });
    if (path === '/api/performance-projects/p1/entries') return route.fulfill({ json: entries });
    if (path === '/api/performance-projects/p1/download') {
      state.downloads += 1;
      if (state.hold) await new Promise(resolve => { state.release = resolve; });
      if (state.error) return route.fulfill({ status: state.error.status, json: { message: state.error.message } });
      return route.fulfill({ status: 200, headers: { 'content-type': 'application/zip', 'content-disposition': state.disposition }, body: Buffer.from('PK') });
    }
    return route.fulfill({ status: 404, json: { message: 'unexpected' } });
  });
  await page.goto('/react/index.html#/performances/p1');
  await expect(page.locator('#performance-documents')).toBeVisible();
  return state;
}

test('performance ZIP: server Content-Disposition filename is used without changing state', async ({ page }) => {
  const state = await setup(page);
  state.disposition = 'attachment; filename="server-selected-evidence.zip"';
  await expect(page.getByText('선택 파일 1개')).toBeVisible();
  const pending = page.waitForEvent('download');
  await page.getByRole('button', { name: 'ZIP 다운로드' }).click();
  expect((await pending).suggestedFilename()).toBe('server-selected-evidence.zip');
  expect(state.downloads).toBe(1); expect(state.writes).toHaveLength(0);
  await expect(page.locator('#performance-documents')).toContainText('FMS.pdf');
});

test('performance ZIP: FMS, direct upload and company files share the existing download', async ({ page }) => {
  const state = await setup(page, [entry('1', 'fms', 'FMS.pdf'), entry('2', 'upload', '직접업로드.pdf'), entry('3', 'company', '회사파일.pdf')]);
  await expect(page.getByText('선택 파일 3개')).toBeVisible();
  const pending = page.waitForEvent('download'); await page.getByRole('button', { name: 'ZIP 다운로드' }).click(); await pending;
  expect(state.downloads).toBe(1); expect(state.writes).toHaveLength(0);
  await expect(page.locator('#performance-documents')).toContainText('직접업로드.pdf');
  await expect(page.locator('#performance-documents')).toContainText('회사파일.pdf');
});

test('performance ZIP: no selection stays disabled without a request', async ({ page }) => {
  const state = await setup(page, []);
  await expect(page.getByText('선택된 증빙파일이 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'ZIP 다운로드' })).toBeDisabled();
  expect(state.downloads).toBe(0);
});

test('performance ZIP: loading blocks duplicate requests', async ({ page }) => {
  const state = await setup(page); state.hold = true;
  const button = page.getByRole('button', { name: 'ZIP 다운로드' }); await button.click();
  await expect(page.getByRole('button', { name: 'ZIP 생성 중…' })).toBeDisabled();
  await page.getByRole('button', { name: 'ZIP 생성 중…' }).click({ force: true });
  expect(state.downloads).toBe(1); state.release();
  await expect(page.getByRole('button', { name: 'ZIP 다운로드' })).toBeEnabled();
});

test('performance ZIP: server errors stay safe and preserve selected files', async ({ page }) => {
  const state = await setup(page); state.error = { status: 503, message: 'storage_path=C:\\secret credential=password' };
  await page.getByRole('button', { name: 'ZIP 다운로드' }).click();
  await expect(page.getByRole('alert')).toHaveText('ZIP 다운로드에 실패했습니다. 다시 시도해 주세요.');
  await expect(page.getByRole('alert')).not.toContainText(/secret|credential|storage/i);
  await expect(page.locator('#performance-documents')).toContainText('FMS.pdf');
  expect(state.writes).toHaveLength(0);
  state.error = { status: 403, message: '선택한 FMS 파일의 다운로드 권한이 없습니다.' };
  await page.getByRole('button', { name: 'ZIP 다운로드' }).click();
  await expect(page.getByRole('alert')).toHaveText('선택한 FMS 파일의 다운로드 권한이 없습니다.');
});

test('performance ZIP: mobile controls remain inside the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await setup(page);
  await expect(page.getByRole('button', { name: 'ZIP 다운로드' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
