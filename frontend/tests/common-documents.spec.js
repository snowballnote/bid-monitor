import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const state = { writes: [], active: 0, maxActive: 0, release: null, fail: false,
    requirements: [{ id: 10, category: 'COMPANY_GENERAL', documentName: '사업자등록증', sourceReference: 'BUSINESS', companyCommon: true },
      { id: 20, category: 'OTHER', documentName: '보존서류', sourceReference: 'CUSTOM' }],
    selections: [{ requirementId: 10, uploadedFileId: 'old', originalFilename: '프로젝트-기존.pdf' }],
    masters: [
      { id: 'a', category: 'COMPANY_COMMON', requirementCategory: 'COMPANY_GENERAL', name: '사업자등록증', sourceReference: 'BUSINESS', uploadedFileId: 'new-a', currentFilename: '마스터-최신.pdf' },
      { id: 'b', category: 'COMPANY_COMMON', requirementCategory: 'FINANCIAL', name: '재무제표', sourceReference: 'FINANCE', currentFileId: 200, currentFilename: '재무제표.pdf' },
      { id: 'c', category: 'COMPANY_COMMON', requirementCategory: 'CERTIFICATION_LICENSE', name: '인증서', sourceReference: 'CERT', currentFileId: null, currentFilename: null },
    ] };
  if (options.empty) { state.requirements = []; state.selections = []; state.masters = []; }
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname;
    if (req.method() !== 'GET') {
      state.writes.push({ path, query: url.search, method: req.method(), body: req.postDataJSON() });
      if (!path.endsWith('/collect')) return route.fulfill({ status: 400, json: {} });
      state.active++; state.maxActive = Math.max(state.maxActive, state.active);
      if (options.hold && !state.release) await new Promise(resolve => { state.release = resolve; });
      if (state.fail) { state.active--; return route.fulfill({ status: 400, json: { message: '체크 저장 실패' } }); }
      state.requirements = req.postDataJSON().map((row, index) => state.requirements.find(existing => existing.category === row.category && existing.documentName === row.documentName)
        || { ...row, id: 100 + index, companyCommon: true });
      state.selections = state.selections.filter(file => state.requirements.some(row => row.id === file.requirementId));
      for (const row of state.requirements) {
        if (state.selections.some(file => file.requirementId === row.id)) continue;
        const master = state.masters.find(master => master.sourceReference === row.sourceReference);
        if (master?.uploadedFileId || master?.currentFileId) state.selections.push({ requirementId: row.id, uploadedFileId: master.uploadedFileId, fileId: master.currentFileId, originalFilename: master.currentFilename });
      }
      state.active--;
      return route.fulfill({ json: { missingRequirementIds: [] } });
    }
    if (path === '/api/submission-cases/71') return route.fulfill({ json: { id: 71, projectName: '공통서류 프로젝트', deadline: '2026-10-01' } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: state.requirements });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: state.selections } });
    if (path.endsWith('/people')) return route.fulfill({ json: [] });
    if (path === '/api/submission-document-masters') return route.fulfill({ json: state.masters });
    if (path === '/api/submission-cases') return route.fulfill({ json: [] });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/submissions/71');
  await expect(page.locator('#common-documents')).toBeVisible();
  return state;
}

const checkbox = (page, name) => page.getByRole('checkbox', { name: `${name} 선택`, exact: true });
const row = (page, name) => page.locator('#common-documents tr').filter({ has: page.getByRole('rowheader', { name, exact: true }) });

test('common: checking collects current file, preserves old snapshot and other requirements, refreshes progress', async ({ page }) => {
  const state = await setup(page);
  await expect(row(page, '사업자등록증')).toContainText('프로젝트-기존.pdf');
  await expect(page.locator('.case-progress')).toContainText('1 / 2');
  await checkbox(page, '재무제표').check();
  await expect(page.locator('#common-documents [role=status]')).toHaveText('저장됨');
  await expect(row(page, '재무제표')).toContainText('준비됨');
  await expect(row(page, '재무제표')).toContainText('재무제표.pdf');
  await expect(row(page, '사업자등록증')).toContainText('프로젝트-기존.pdf');
  await expect(page.locator('.case-progress')).toContainText('2 / 3');
  await expect(page.getByRole('region', { name: '회사 공통', exact: true })).toContainText('2 / 2');
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0]).toMatchObject({ method: 'POST', path: '/api/submission-cases/71/collect', query: '?preserveExistingSelections=true' });
  expect(state.writes[0].body).toContainEqual({ category: 'OTHER', documentName: '보존서류', sourceReference: 'CUSTOM' });
});

test('common: uncheck confirmation cancellation preserves selection; accept removes only project link; recheck uses latest master', async ({ page }) => {
  const state = await setup(page);
  page.once('dialog', dialog => dialog.dismiss());
  await checkbox(page, '사업자등록증').click();
  await expect(checkbox(page, '사업자등록증')).toBeChecked();
  expect(state.writes).toHaveLength(0);
  page.once('dialog', dialog => dialog.accept());
  await checkbox(page, '사업자등록증').uncheck();
  await expect(page.locator('.case-progress')).toContainText('0 / 1');
  expect(state.selections).toHaveLength(0);
  expect(state.masters[0].uploadedFileId).toBe('new-a');
  await checkbox(page, '사업자등록증').check();
  await expect(row(page, '사업자등록증')).toContainText('마스터-최신.pdf');
  await expect(row(page, '사업자등록증')).toContainText('준비됨');
});

test('common: missing file remains unprepared and links to existing documents without uploads', async ({ page }) => {
  await setup(page);
  await checkbox(page, '인증서').check();
  await expect(page.locator('#common-documents [role=status]')).toHaveText('저장됨');
  await expect(row(page, '인증서')).toContainText('미준비');
  await expect(row(page, '인증서')).toContainText('파일 없음');
  await expect(row(page, '인증서').getByRole('link')).toHaveAttribute('href', '/documents/');
  await expect(page.locator('input[type=file]')).toHaveCount(0);
  await expect(page.locator('.case-progress')).toContainText('1 / 3');
});

test('common: rapid changes serialize and retain latest intent', async ({ page }) => {
  const state = await setup(page, { hold: true });
  await checkbox(page, '재무제표').check();
  await expect.poll(() => !!state.release).toBe(true);
  page.once('dialog', dialog => dialog.accept());
  await checkbox(page, '재무제표').uncheck();
  await checkbox(page, '인증서').check();
  state.release();
  await expect(page.locator('#common-documents [role=status]')).toHaveText('저장됨');
  expect(state.maxActive).toBe(1);
  expect(state.requirements.map(row => row.documentName)).toEqual(['사업자등록증', '보존서류', '인증서']);
  await expect(checkbox(page, '재무제표')).not.toBeChecked();
  await expect(checkbox(page, '인증서')).toBeChecked();
});

test('common: failed save reconciles with server and can retry', async ({ page }) => {
  const state = await setup(page);
  state.fail = true;
  await checkbox(page, '재무제표').check();
  await expect(page.locator('#common-documents [role=alert]')).toContainText('체크 저장 실패');
  await expect(checkbox(page, '재무제표')).not.toBeChecked();
  await expect(page.locator('.case-progress')).toContainText('1 / 2');
  state.fail = false;
  await checkbox(page, '재무제표').check();
  await expect(row(page, '재무제표')).toContainText('준비됨');
});

test('common: list navigation waits for pending save', async ({ page }) => {
  const state = await setup(page, { hold: true });
  await checkbox(page, '재무제표').check();
  await expect.poll(() => !!state.release).toBe(true);
  await page.getByRole('link', { name: '프로젝트 목록', exact: true }).click();
  await expect(page).toHaveURL(/#\/submissions\/71$/);
  state.release();
  await expect(page).toHaveURL(/#\/submissions$/);
  expect(state.selections.some(file => file.originalFilename === '재무제표.pdf')).toBe(true);
});

test('common: empty section and mobile compact table', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page, { empty: true });
  await expect(page.getByText('등록된 회사 공통서류가 없습니다.')).toBeVisible();
  await page.unrouteAll({ behavior: 'wait' });
  await page.goto('about:blank');
  await setup(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await checkbox(page, '인증서').check();
  await expect(row(page, '인증서')).toContainText('미준비');
  await page.screenshot({ path: 'test-results/common-documents-mobile.png', fullPage: true });
});
