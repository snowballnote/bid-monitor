import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const state = { requests: [], releaseEntries: null };
  const entries = options.empty ? [] : [
    { id: 'one', projectId: 'perf-1', selectedFilename: '완료된-실적증명서.pdf', info: {
      businessName: '공공정보시스템 구축', client: '한국기관', businessPeriod: '2024.01 ~ 2024.12', selectedFileId: 11,
    } },
    { id: 'two', projectId: 'perf-1', selectedFilename: null, info: {
      businessName: '운영 사업', client: '서울기관', businessPeriod: '2025.01 ~ 2025.12',
      selectedFileId: null, selectedDriveFileId: null, selectedUploadedFileId: null,
    } },
    { id: 'three', projectId: 'perf-1', selectedFilename: '아주-긴-직접업로드-실적증빙-파일명-모바일-레이아웃-확인용.pdf', info: {
      businessName: '개인정보 영향평가', client: '긴 이름의 발주기관', businessPeriod: '2026.01 ~ 2026.08', selectedUploadedFileId: 'upload-3',
    } },
  ];
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    state.requests.push({ method: request.method(), path });
    if (/\/submission-cases\/71$/.test(path)) return route.fulfill({ json: {
      id: 71, projectName: '실적 조회 프로젝트', deadline: '2026-10-01', performanceProjectId: options.unlinked ? null : 'perf-1',
    } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: [{
      id: 30, category: 'PERFORMANCE', documentName: '실적증명서', sourceReference: 'PERF', performanceSelectionRequired: true,
    }] });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: [] } });
    if (path.endsWith('/people')) return route.fulfill({ json: [] });
    if (path === '/api/submission-document-masters') return route.fulfill({ json: [] });
    if (path.endsWith('/entries')) {
      if (options.hold) await new Promise(resolve => { state.releaseEntries = resolve; });
      if (options.error) return route.fulfill({ status: 503, json: {} });
      return route.fulfill({ json: entries });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/submissions/71');
  await expect(page.locator('#performance-documents')).toBeVisible();
  return state;
}

test('performance documents: connected entries show metadata, current files, states and entry-based progress', async ({ page }) => {
  const state = await setup(page);
  const section = page.locator('#performance-documents');
  await expect(section.locator('tbody tr')).toHaveCount(3);
  await expect(section).toContainText('공공정보시스템 구축');
  await expect(section).toContainText('한국기관');
  await expect(section).toContainText('2024.01 ~ 2024.12');
  await expect(section).toContainText('완료된-실적증명서.pdf');
  await expect(section.getByText('준비됨', { exact: true })).toHaveCount(2);
  await expect(section.getByText('미준비', { exact: true })).toHaveCount(1);
  await expect(section.locator('.panel-header')).toContainText('2 / 3');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('2 / 3');
  await expect(page.locator('.case-progress')).toContainText('2 / 3 · 67%');
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
  expect(state.requests.filter(request => request.path.endsWith('/entries'))).toHaveLength(1);
});

test('performance documents: loading, empty and error states stay inside the section', async ({ page }) => {
  const options = { hold: true };
  const state = await setup(page, options);
  await expect(page.locator('#performance-documents').getByRole('status')).toContainText('불러오는 중');
  options.hold = false; state.releaseEntries();
  await expect(page.locator('#performance-documents').locator('tbody tr')).toHaveCount(3);

  options.error = true;
  await page.reload();
  await expect(page.locator('#performance-documents').getByRole('alert')).toContainText('조회 실패');
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
});

test('performance documents: no entries and no linked project use distinct empty states without writes', async ({ page }) => {
  let state = await setup(page, { empty: true });
  await expect(page.locator('#performance-documents')).toContainText('등록된 실적이 없습니다.');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('0 / 0');
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);

  await page.unroute('**/api/**');
  await page.goto('about:blank');
  state = await setup(page, { unlinked: true });
  await expect(page.locator('#performance-documents')).toContainText('연결된 실적 프로젝트가 없습니다.');
  expect(state.requests.some(request => request.path.endsWith('/entries'))).toBe(false);
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
});

test('performance documents: long filenames remain usable on mobile and other sections stay unchanged', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  await expect(page.locator('#performance-documents')).toContainText('아주-긴-직접업로드-실적증빙');
  await expect(page.locator('#common-documents')).toBeVisible();
  await expect(page.locator('.react-personnel')).toBeVisible();
  await expect(page.locator('#other-documents')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
  await page.screenshot({ path: 'test-results/performance-documents-mobile.png', fullPage: true });
});
