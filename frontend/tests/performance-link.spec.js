import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const state = { project: { id: 71, projectName: '제출 작업', deadline: '2026-09-30', performanceProjectId: options.linked ? 'p1' : null,
    performanceLinkInitialized: !!options.initialized }, writes: [], fail: !!options.fail, release: null };
  await page.addInitScript(value => {
    if (value) localStorage.setItem('biz-assist.performance-project.71', value);
    localStorage.setItem('biz-assist.performance-project.72', 'other');
  }, options.legacy || '');
  await page.route('**/api/**', async route => {
    const req = route.request(); const path = new URL(req.url()).pathname;
    if (req.method() !== 'GET') {
      state.writes.push({ path, method: req.method(), body: req.postDataJSON() });
      if (options.hold) await new Promise(resolve => { state.release = resolve; });
      if (state.fail) return route.fulfill({ status: 503, json: { message: 'internal/path' } });
      state.project = { ...state.project, performanceProjectId: 'p1', performanceLinkInitialized: true };
      return route.fulfill({ json: state.project });
    }
    if (path === '/api/submission-cases/71') return route.fulfill({ json: state.project });
    if (path === '/api/submission-cases/72') return route.fulfill({ json: { ...state.project, id: 72, performanceProjectId: 'p2' } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: [{ id: 1, category: 'PERFORMANCE', documentName: '실적', performanceSelectionRequired: true }] });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: [] } });
    if (path.endsWith('/people') || path === '/api/submission-document-masters' || path === '/api/drive-index') return route.fulfill({ json: [] });
    if (path.endsWith('/entries')) return route.fulfill({ json: [{ id: 'e1', selectedFilename: '보존.pdf', info: { businessName: '기존 사업', selectedDriveFileId: 'reference', evidenceType: 'CERTIFICATE' } }] });
    if (path === '/api/performance-projects') return route.fulfill({ json: [{ id: 'p1', name: '실적 프로젝트', deadline: '2026-09-30' }] });
    if (path.startsWith('/api/performance-projects/')) return route.fulfill({ json: { id: path.split('/').at(-1), name: '실적 프로젝트', deadline: '2026-09-30', daysRemaining: 9, status: 'READY' } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/submissions/71');
  return state;
}
const connect = page => page.getByRole('button', { name: '실적 프로젝트 생성·연결' });
const legacy = page => page.evaluate(() => localStorage.getItem('biz-assist.performance-project.71'));

test('performance link: create once, preserve files and return to submission on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page, { hold: true });
  await expect(connect(page)).toBeVisible(); expect(state.writes).toEqual([]);
  await connect(page).evaluate(button => { button.click(); button.click(); });
  await expect(page.getByRole('button', { name: '연결 확인 중…' })).toBeDisabled();
  await expect.poll(() => state.writes.length).toBe(1);
  state.release();
  await expect(page).toHaveURL(/#\/performances\/p1\?caseId=71$/);
  await expect(page.getByText('보존.pdf', { exact: true })).toBeVisible();
  expect(state.writes[0]).toMatchObject({ path: '/api/submission-cases/71/performance-project', method: 'POST' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('link', { name: '원래 제출서류로 돌아가기' }).click();
  await expect(page).toHaveURL(/#\/submissions\/71$/);
  await expect(page.getByText('연결된 실적 프로젝트: p1')).toBeVisible();
  expect(state.writes).toHaveLength(1);
});

test('performance link: migrate existing project only and remove only successful case key', async ({ page }) => {
  const state = await setup(page, { legacy: 'p1' }); await connect(page).click();
  await expect(page).toHaveURL(/performances\/p1\?caseId=71$/);
  expect(state.writes).toEqual([{ path: '/api/submission-cases/71', method: 'PUT', body: { performanceProjectId: 'p1', initializePerformanceOnly: true } }]);
  expect(await legacy(page)).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('biz-assist.performance-project.72'))).toBe('other');
});

test('performance link: failed migration retains key and does not create; retry migrates', async ({ page }) => {
  const state = await setup(page, { legacy: 'p1', fail: true }); await connect(page).click();
  await expect(page.getByRole('alert')).toContainText('기존 정보는 유지');
  expect(await legacy(page)).toBe('p1'); expect(state.project.performanceProjectId).toBeNull();
  expect(state.writes.map(row => row.method)).toEqual(['PUT']);
  state.fail = false; await connect(page).click();
  await expect(page).toHaveURL(/performances\/p1\?caseId=71$/); expect(await legacy(page)).toBeNull();
});

for (const options of [{ linked: true, legacy: 'old' }, { initialized: true, legacy: 'p1' }, { legacy: 'missing' }]) {
  test('performance link: existing and ineligible migration rules ' + JSON.stringify(options), async ({ page }) => {
    const state = await setup(page, options);
    await page.getByRole('button', { name: options.linked ? '실적 프로젝트 관리' : '실적 프로젝트 생성·연결' }).click();
    await expect(page).toHaveURL(/performances\/p1\?caseId=71$/);
    expect(state.writes.map(row => row.method)).toEqual(options.linked ? [] : ['POST']);
    expect(await legacy(page)).toBe(options.legacy);
  });
}

test('performance link: delayed creation cannot navigate another case; direct routes do not inherit context', async ({ page }) => {
  const state = await setup(page, { hold: true }); await connect(page).click();
  await expect.poll(() => state.writes.length).toBe(1);
  await page.evaluate(() => { location.hash = '/submissions/72'; });
  await expect(page.getByText('연결된 실적 프로젝트: p2')).toBeVisible();
  state.release();
  await expect.poll(() => state.project.performanceProjectId).toBe('p1');
  await expect(page).toHaveURL(/submissions\/72$/);
  await page.evaluate(() => { location.hash = '/performances/p1?caseId=71'; });
  await expect(page.getByRole('link', { name: '원래 제출서류로 돌아가기' })).toBeVisible();
  await page.evaluate(() => { location.hash = '/performances/p2?caseId=71'; });
  await expect(page.getByRole('link', { name: '원래 제출서류로 돌아가기' })).toHaveCount(0);
  await page.evaluate(() => { location.hash = '/performances/p1'; });
  await expect(page.getByRole('link', { name: '목록', exact: true })).toHaveAttribute('href', '#/performances');
  await expect(page.getByRole('link', { name: '원래 제출서류로 돌아가기' })).toHaveCount(0);
});
