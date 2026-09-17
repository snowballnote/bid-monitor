import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const state = {
    requirements: options.empty ? [] : [
      { id: 10, category: 'COMPANY_GENERAL', documentName: '회사서류', sourceReference: 'COMPANY', companyCommon: true },
      { id: 20, category: 'PERSONNEL', documentName: '인력서류', sourceReference: 'PERSON' },
      { id: 30, category: 'PERFORMANCE', documentName: '실적서류', sourceReference: 'PERF', performanceSelectionRequired: true },
      { id: 40, category: 'OTHER', documentName: '보존 기타서류', sourceReference: 'CUSTOM_7' },
    ],
    selections: options.empty ? [] : [
      { requirementId: 10, fileId: 101, uploadedFileId: 'company-snapshot', originalFilename: '회사-보존.pdf' },
      { requirementId: 40, fileId: 401, originalFilename: '기타-기존.pdf' },
    ],
    writes: [], nextId: 50, failCollect: false, failSelection: false, failRead: false,
    activeCollects: 0, maxCollects: 0, releaseCollect: null,
  };
  const masters = options.empty ? [] : [
    { id: 'common', category: 'COMPANY_COMMON', requirementCategory: 'COMPANY_GENERAL', name: '회사서류', sourceReference: 'COMPANY' },
    { id: 'other', category: 'OTHER', requirementCategory: 'OTHER', name: '추가 확인서', sourceReference: 'EXTRA' },
  ];
  const candidates = [
    { fileId: 401, originalFilename: '기타-기존.pdf', fileExt: 'pdf', fileModifiedAt: '2026-08-01T00:00:00Z', matchLevel: 'EXACT', matchReasons: ['서류명 일치'] },
    { fileId: 402, originalFilename: '아주-긴-경로를-대신하는-긴-후보-파일명-모바일에서도-확인.pdf', fileExt: 'pdf', fileModifiedAt: '2026-09-01T00:00:00Z', matchLevel: 'RECOMMENDED', matchReasons: ['유사 파일명'] },
  ];
  await page.route('**/api/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method();
    if (method !== 'GET') state.writes.push({ method, path, query: url.search, body: request.postDataJSON() });
    if (path === '/api/submission-document-masters') return route.fulfill({ json: masters });
    if (/\/submission-cases\/71$/.test(path)) return route.fulfill({ json: { id: 71, projectName: '기타 이전 프로젝트', deadline: '2026-09-30', performanceProjectId: 'perf-1' } });
    if (path.endsWith('/people')) return route.fulfill({ json: [] });
    if (path.endsWith('/entries')) return route.fulfill({ json: [{ info: { selectedDriveFileId: 'proof' } }] });
    if (path.endsWith('/requirements') && method === 'GET') {
      if (state.failRead) return route.fulfill({ status: 503, json: {} });
      return route.fulfill({ json: state.requirements });
    }
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: state.selections } });
    if (path.endsWith('/collect')) {
      state.activeCollects += 1; state.maxCollects = Math.max(state.maxCollects, state.activeCollects);
      if (options.holdCollect && !state.releaseCollect) await new Promise(resolve => { state.releaseCollect = resolve; });
      state.activeCollects -= 1;
      if (state.failCollect) return route.fulfill({ status: 400, json: { message: '체크 저장 실패' } });
      const requested = request.postDataJSON();
      state.requirements = requested.map(row => state.requirements.find(saved => saved.category === row.category && saved.documentName === row.documentName)
        || { ...row, id: state.nextId++ });
      state.selections = state.selections.filter(file => state.requirements.some(row => row.id === file.requirementId));
      return route.fulfill({ json: { missingRequirementIds: [] } });
    }
    if (path.endsWith('/candidates')) {
      if (options.candidateError) return route.fulfill({ status: 503, json: {} });
      return route.fulfill({ json: options.noCandidates ? [] : candidates });
    }
    if (path.endsWith('/selections')) {
      if (state.failSelection) return route.fulfill({ status: 400, json: { message: '선택 저장 실패' } });
      state.selections = request.postDataJSON().selections.map(choice => ({ ...choice,
        ...(state.selections.find(file => file.fileId === choice.fileId) || candidates.find(file => file.fileId === choice.fileId)) }));
      return route.fulfill({ json: state.selections });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/submissions/71');
  await expect(page.locator('#other-documents')).toBeVisible();
  return state;
}

const other = page => page.locator('#other-documents');
const savedOtherRow = page => other(page).locator('tbody tr').filter({ hasText: '보존 기타서류' });

test('other documents: lists saved and master rows, adds and checks without changing other categories', async ({ page }) => {
  const state = await setup(page);
  await expect(other(page)).toContainText('보존 기타서류');
  await expect(other(page)).toContainText('기타-기존.pdf');
  await expect(other(page).getByText('준비됨', { exact: true })).toBeVisible();
  await expect(other(page).getByRole('checkbox', { name: '추가 확인서 필요' })).not.toBeChecked();
  await other(page).getByRole('checkbox', { name: '추가 확인서 필요' }).check();
  await expect(other(page).getByRole('checkbox', { name: '추가 확인서 필요' })).toBeChecked();
  await other(page).getByLabel('기타 서류 추가').fill('현장 설명 확인서');
  await other(page).getByRole('button', { name: '추가', exact: true }).click();
  await expect(other(page).getByRole('checkbox', { name: '현장 설명 확인서 필요' })).toBeChecked();
  const collect = state.writes.filter(write => write.path.endsWith('/collect'));
  expect(collect.every(write => write.query === '?preserveExistingSelections=true')).toBe(true);
  expect(collect.at(-1).body).toEqual(expect.arrayContaining([
    expect.objectContaining({ documentName: '회사서류' }), expect.objectContaining({ documentName: '인력서류' }),
    expect.objectContaining({ documentName: '실적서류' }), expect.objectContaining({ documentName: '현장 설명 확인서', category: 'OTHER' }),
  ]));
  expect(state.selections.find(file => file.requirementId === 10)?.originalFilename).toBe('회사-보존.pdf');
});

test('other documents: uncheck confirms, removes only project link, and failure restores server state', async ({ page }) => {
  const state = await setup(page);
  page.once('dialog', dialog => dialog.dismiss());
  await other(page).getByRole('checkbox', { name: '보존 기타서류 필요' }).click();
  await expect(other(page).getByRole('checkbox', { name: '보존 기타서류 필요' })).toBeChecked();
  state.failCollect = true;
  page.once('dialog', dialog => dialog.accept());
  await other(page).getByRole('checkbox', { name: '보존 기타서류 필요' }).uncheck();
  await expect(other(page).getByRole('alert')).toContainText('체크 저장 실패');
  await expect(other(page).getByRole('checkbox', { name: '보존 기타서류 필요' })).toBeChecked();
  await expect(other(page)).toContainText('기타-기존.pdf');
  state.failCollect = false;
  page.once('dialog', dialog => dialog.accept());
  await other(page).getByRole('checkbox', { name: '보존 기타서류 필요' }).uncheck();
  await expect(other(page).getByRole('checkbox', { name: '보존 기타서류 필요' })).not.toBeChecked();
  expect(state.selections.some(file => file.requirementId === 40)).toBe(false);
  expect(state.selections.some(file => file.requirementId === 10)).toBe(true);
  expect(state.writes.some(write => write.method === 'DELETE')).toBe(false);
});

test('other documents: rapid checkbox changes serialize writes and keep the latest intent', async ({ page }) => {
  const options = { holdCollect: true };
  const state = await setup(page, options);
  const checkbox = other(page).getByRole('checkbox', { name: '추가 확인서 필요' });
  await checkbox.check(); await checkbox.uncheck(); await checkbox.check();
  await expect.poll(() => typeof state.releaseCollect).toBe('function');
  options.holdCollect = false; state.releaseCollect();
  await expect(other(page).getByRole('status')).toHaveText('저장됨');
  await expect(checkbox).toBeChecked();
  expect(state.maxCollects).toBe(1);
  expect(state.writes.filter(write => write.path.endsWith('/collect')).length).toBeLessThanOrEqual(2);
});

test('other documents: candidate order, recommendation and selection update current file and progress', async ({ page }) => {
  const state = await setup(page);
  await savedOtherRow(page).getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '보존 기타서류 파일 관리' });
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('기타-기존.pdf');
  await expect(dialog.locator('.other-candidate-list li')).toHaveCount(2);
  await expect(dialog.locator('.other-candidate-list li').first()).toContainText('정확한 일치');
  await expect(dialog.locator('.other-candidate-list li').last()).toContainText('추천');
  await expect(dialog.locator('.other-candidate-list li').first().getByRole('button')).toHaveText('선택됨');
  await dialog.locator('.other-candidate-list li').last().getByRole('button', { name: '선택', exact: true }).click();
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('아주-긴-경로를-대신하는-긴-후보');
  await expect(other(page)).toContainText('아주-긴-경로를-대신하는-긴-후보');
  await expect(page.locator('.case-progress')).toContainText('3 / 4');
  const write = state.writes.find(item => item.path.endsWith('/selections'));
  expect(write.body.selections).toEqual(expect.arrayContaining([{ requirementId: 10, fileId: 101 }, { requirementId: 40, fileId: 402 }]));
  expect(state.writes.filter(item => item.path.endsWith('/candidates'))).toHaveLength(0);
});

test('other documents: candidate empty/error and failed selection preserve file; mobile has no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  state.failSelection = true;
  await savedOtherRow(page).getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '보존 기타서류 파일 관리' });
  await dialog.locator('.other-candidate-list li').last().getByRole('button', { name: '선택', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('선택 저장 실패');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('기타-기존.pdf');
  await expect(other(page)).toContainText('기타-기존.pdf');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/other-documents-mobile.png', fullPage: true });
});

test('other documents: empty state is explicit', async ({ page }) => {
  await setup(page, { empty: true });
  await expect(other(page)).toContainText('등록된 기타 서류가 없습니다.');
});

test('other documents: candidate errors recover and no-candidate state is explicit', async ({ page }) => {
  const options = { candidateError: true };
  await setup(page, options);
  await savedOtherRow(page).getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '보존 기타서류 파일 관리' });
  await expect(dialog.getByRole('alert')).toContainText('조회 실패');
  options.candidateError = false; options.noCandidates = true;
  await dialog.getByRole('button', { name: '다시 조회' }).click();
  await expect(dialog.getByRole('status')).toHaveText('FMS 후보가 없습니다.');
});
