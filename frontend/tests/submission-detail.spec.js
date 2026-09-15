import { test, expect } from '@playwright/test';

async function setup(page, { empty = false, fail = '', malformed = false, hold = false } = {}) {
  const requests = [];
  const state = { fail, release: null };
  const requirements = empty ? [] : [
    { id: 81, documentName: '사업자등록증', category: 'COMPANY_GENERAL', companyCommon: true },
    { id: 82, documentName: '참여인력 확인서', category: 'PERSONNEL' },
    { id: 83, documentName: '실적증명서', category: 'PERFORMANCE', performanceSelectionRequired: true },
    { id: 84, documentName: '추가서류', category: 'OTHER' },
  ];
  await page.route('**/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push({ path, method: request.method() });
    if (hold && path === '/api/submission-cases/71') await new Promise(resolve => { state.release = resolve; });
    if (state.fail && path.endsWith(state.fail)) return route.fulfill({ status: 503, json: {} });
    if (/\/submission-cases\/\d+$/.test(path)) return route.fulfill({ json: { id: Number(path.split('/').at(-1)), projectName: `상세 프로젝트 ${path.split('/').at(-1)}`, deadline: '2026-09-20', performanceProjectId: empty ? null : 'perf-1' } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: malformed ? {} : requirements });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: empty ? [] : [
      { requirementId: 81, uploadedFileId: 'upload-1', originalFilename: '사업자등록증.pdf' },
      { requirementId: 82, fileId: 5, originalFilename: '참여인력.pdf' },
    ] } });
    if (path.endsWith('/people')) return route.fulfill({ json: empty ? [] : [{ name: '홍길동', documents: [
      { type: 'CAREER', label: '경력증명서', needed: true, filename: '경력.pdf' },
      { type: 'LICENSE', label: '자격증', needed: true, filename: null },
      { type: 'IGNORED', label: '미선택 서류', needed: false, filename: 'ignored.pdf' },
    ] }] });
    if (path.endsWith('/entries')) return route.fulfill({ json: [{ info: { selectedUploadedFileId: 'proof' } }, { info: { selectedDriveFileId: 'drive' } }] });
    if (path === '/api/submission-document-masters') return route.fulfill({ json: [] });
    if (path.endsWith('/download')) return route.fulfill({ contentType: 'application/zip', headers: { 'Content-Disposition': 'attachment; filename=submission-files.zip' }, body: Buffer.from('PK') });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.clock.install({ time: new Date('2026-09-15T12:00:00+09:00') });
  await page.goto('/react/index.html#/submissions/71');
  return { requests, state };
}

test('detail: saved state, category totals, documents, management links and ZIP', async ({ page }) => {
  const { requests } = await setup(page);
  await expect(page.locator('#case-project-name')).toHaveText('상세 프로젝트 71');
  await expect(page.locator('.case-identity')).toContainText('D-5');
  await expect(page.locator('.case-progress')).toContainText('4 / 6 · 67%');
  await expect(page.getByRole('progressbar', { name: '전체 준비율', exact: true })).toHaveAttribute('aria-valuenow', '67');
  for (const [group, text] of [['회사 공통', '1 / 1'], ['인력·자격', '2 / 3'], ['실적증빙', '1 / 1'], ['기타', '0 / 1']]) {
    await expect(page.getByRole('region', { name: group, exact: true })).toContainText(text);
  }
  await expect(page.locator('.requirement-table')).toContainText('사업자등록증.pdf');
  await expect(page.locator('.requirement-table')).toContainText('경력.pdf');
  await expect(page.locator('.requirement-table')).toContainText('파일 미등록');
  await expect(page.getByRole('link', { name: '필요 서류 관리' })).toHaveAttribute('href', '/submissions/?caseId=71#document-picker');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true }).getByRole('link')).toHaveAttribute('href', '/submissions/?caseId=71#personnel-panel');
  await expect(page.getByRole('link', { name: '전체 ZIP 다운로드' })).toHaveAttribute('href', '/api/submission-cases/71/download');
  const downloaded = page.waitForEvent('download');
  await page.getByRole('link', { name: '전체 ZIP 다운로드' }).click();
  expect((await downloaded).suggestedFilename()).toBe('submission-files.zip');
  expect(requests.every(request => request.method === 'GET')).toBe(true);
  expect(requests.some(request => /collect|candidates|selections$|performance-project$/.test(request.path))).toBe(false);
  await page.screenshot({ path: 'test-results/submission-detail-desktop.png', fullPage: true });
});

test('detail: empty state and direct refresh never create linked projects', async ({ page }) => {
  const { requests } = await setup(page, { empty: true });
  await expect(page.locator('.case-progress')).toContainText('0 / 0 · 0%');
  await expect(page.getByText('선택된 제출서류가 없습니다.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '전체 ZIP 다운로드' })).toBeDisabled();
  await page.reload();
  await expect(page.locator('#case-project-name')).toHaveText('상세 프로젝트 71');
  expect(requests.every(request => request.method === 'GET')).toBe(true);
  expect(requests.some(request => request.path.includes('performance-projects'))).toBe(false);
});

test('detail: failed auxiliary read shows error instead of incomplete totals and retry recovers', async ({ page }) => {
  const { state } = await setup(page, { fail: '/people' });
  await expect(page.getByRole('alert')).toContainText('회사 DB');
  await expect(page.getByRole('progressbar')).toHaveCount(0);
  state.fail = '';
  await page.getByRole('button', { name: '새로고침' }).click();
  await expect(page.locator('.case-progress')).toContainText('4 / 6');
});

test('detail: malformed response and invalid route ID show errors', async ({ page }) => {
  const { requests } = await setup(page, { malformed: true });
  await expect(page.getByRole('alert')).toContainText('응답을 확인할 수 없습니다');
  const before = requests.length;
  await page.goto('/react/index.html#/submissions/invalid');
  await expect(page.getByRole('alert')).toHaveText('프로젝트 번호를 확인해 주세요.');
  expect(requests).toHaveLength(before);
});

test('detail: loading and route changes do not show the previous project', async ({ page }) => {
  const { state } = await setup(page, { hold: true });
  await expect(page.getByRole('status')).toContainText('불러오는 중');
  await expect.poll(() => !!state.release).toBe(true);
  await page.goto('/react/index.html#/submissions/72');
  await expect(page.locator('#case-project-name')).toHaveText('상세 프로젝트 72');
  state.release();
  await expect(page.locator('#case-project-name')).toHaveText('상세 프로젝트 72');
});

test('detail: mobile summary and document table remain usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await expect(page.locator('#case-project-name')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole('link', { name: '필요 서류 관리' })).toBeVisible();
  await page.screenshot({ path: 'test-results/submission-detail-mobile.png', fullPage: true });
});
