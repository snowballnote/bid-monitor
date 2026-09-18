import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const entries = options.empty ? [] : [
    { id: 'one', projectId: 'perf-1', selectedFilename: '완료된-실적증명서.pdf', info: {
      pptNumber: '1', businessName: '공공정보시스템 구축', client: '한국기관', businessPeriod: '2024.01 ~ 2024.12',
      contractAmount: '10억', businessStatus: 'COMPLETED', selectedFileId: null,
      selectedDriveFileId: '11111111-1111-4111-8111-111111111111', selectedUploadedFileId: null,
      evidenceType: 'CERTIFICATE', kitcStatus: 'REQUESTED', requestedAt: '2026-08-01', repliedAt: null,
    } },
    { id: 'two', projectId: 'perf-1', selectedFilename: null, info: {
      pptNumber: '2', businessName: '운영 사업', client: '서울기관', businessPeriod: '2025.01 ~ 2025.12',
      contractAmount: '5억', businessStatus: 'COMPLETED', selectedFileId: null, selectedDriveFileId: null,
      selectedUploadedFileId: null, evidenceType: null, kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
    } },
    { id: 'three', projectId: 'perf-1', selectedFilename: '아주-긴-직접업로드-실적증빙-파일명-모바일-레이아웃-확인용.pdf', info: {
      pptNumber: '3', businessName: '개인정보 영향평가', client: '긴 이름의 발주기관', businessPeriod: '2026.01 ~ 2026.08',
      contractAmount: '7억', businessStatus: 'COMPLETED', selectedFileId: null, selectedDriveFileId: null,
      selectedUploadedFileId: 'upload-3', evidenceType: 'CERTIFICATE', kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
    } },
  ];
  const state = {
    requests: [], writes: [], entries, releaseEntries: null, releaseCandidate: null, releaseSelection: null,
    uploads: [], releaseUpload: null, holdUpload: false, uploadStatus: 0, uploadMessage: '',
    imports: [], failEntrySave: false, holdSelection: false, failSelection: false, selectedCandidate: { one: 0 },
  };
  const candidateIds = [
    '22222222-2222-4222-8222-222222222222', '33333333-3333-4333-8333-333333333333',
  ];
  const registeredIds = [
    '44444444-4444-4444-8444-444444444444', '55555555-5555-4555-8555-555555555555',
  ];
  function candidatesFor(entryId) {
    const entry = entries.find(row => row.id === entryId);
    const long = entryId === 'three';
    const names = long
      ? ['동일한-파일명이지만-다른-경로의-아주-긴-실적증빙-후보-파일명.pdf', '동일한-파일명이지만-다른-경로의-아주-긴-실적증빙-후보-파일명.pdf']
      : entryId === 'one' ? ['동일파일명.pdf', '동일파일명.pdf'] : ['운영사업_추천.pdf', '운영사업_이전.pdf'];
    const rows = [{
      file: { driveFileId: candidateIds[0], originalFilename: names[0], fileExt: 'pdf', size: 1200, lastModified: '2026-08-17T03:00:00Z' },
      evidenceType: 'CERTIFICATE', reason: '사업명과 발주기관이 일치하는 최신 후보',
    }, {
      file: { driveFileId: candidateIds[1], originalFilename: names[1], fileExt: 'pdf', size: 1400, lastModified: '2025-07-03T03:00:00Z' },
      evidenceType: 'CERTIFICATE', reason: '서버가 두 번째로 반환한 후보',
    }];
    const selected = state.selectedCandidate[entryId];
    if (selected != null && entry?.info.selectedDriveFileId) {
      rows[selected] = { ...rows[selected], file: { ...rows[selected].file, driveFileId: entry.info.selectedDriveFileId } };
    }
    return rows;
  }
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
    if (path === '/api/performance-projects/perf-1/import' && request.method() === 'POST') {
      const payload = request.postDataJSON(); state.imports.push(payload);
      const cells = (payload.text || '').split('\t').map(value => value.replace(/^"|"$/g, '').replaceAll('""', '"'));
      const message = !cells[0]?.trim() ? 'PPT 번호을(를) 확인하세요.'
        : entries.some(entry => entry.info.pptNumber === cells[0].trim()) ? '이미 저장된 PPT 번호입니다. 기존 실적을 수정하세요.'
          : !cells[2]?.match(/수행\s*중|진행\s*중|현재|계속|\d{4}[.\/-]\d{1,2}.*\d{4}[.\/-]\d{1,2}/)
            ? '사업기간은 시작~종료 날짜로 입력하거나 상세 수정에서 사업 상태를 지정하세요.' : '';
      if (message) return route.fulfill({ json: { saved: [], errors: [{ row: 1, cells, message }] } });
      const entry = { id: `new-${state.imports.length}`, projectId: 'perf-1', selectedFilename: null, selectedExt: null,
        resolvedStatus: /수행\s*중|진행\s*중|현재|계속/.test(cells[2]) ? 'IN_PROGRESS' : 'COMPLETED', info: {
          pptNumber: cells[0].trim(), businessName: cells[1].trim(), businessPeriod: cells[2].trim(),
          contractAmount: cells[3].trim(), client: cells[4].trim(), businessStatus: null,
          selectedFileId: null, selectedDriveFileId: null, selectedUploadedFileId: null, evidenceType: null,
          kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
        } };
      entries.push(entry); return route.fulfill({ json: { saved: [entry], errors: [] } });
    }
    const uploadMatch = path.match(/^\/api\/performance-projects\/perf-1\/entries\/([^/]+)\/upload$/);
    if (uploadMatch && request.method() === 'POST') {
      const entryId = uploadMatch[1]; const body = request.postData() || '';
      state.uploads.push({ method: request.method(), path, body });
      if (state.holdUpload) await new Promise(resolve => { state.releaseUpload = resolve; });
      if (state.uploadStatus) return route.fulfill({ status: state.uploadStatus, json: { message: state.uploadMessage } });
      const filename = body.match(/filename="([^"]+)"/)?.[1] || '직접업로드.bin';
      const evidenceType = body.match(/name="evidenceType"[\s\S]*?\r?\n\r?\n([A-Z_]+)/)?.[1] || 'CERTIFICATE';
      const index = entries.findIndex(entry => entry.id === entryId);
      delete state.selectedCandidate[entryId];
      entries[index] = {
        ...entries[index], selectedFilename: filename, selectedExt: filename.includes('.') ? filename.split('.').at(-1) : '',
        info: { ...entries[index].info, selectedFileId: null, selectedDriveFileId: null,
          selectedUploadedFileId: `upload-${state.uploads.length}`, evidenceType },
      };
      return route.fulfill({ json: entries[index] });
    }
    const entryMatch = path.match(/^\/api\/performance-projects\/perf-1\/entries\/([^/]+)$/);
    if (entryMatch && request.method() === 'PUT') {
      const entryId = entryMatch[1]; const body = request.postDataJSON();
      state.writes.push({ method: request.method(), path, body });
      if (state.holdSelection) await new Promise(resolve => { state.releaseSelection = resolve; });
      const disconnecting = body.selectedFileId == null && body.selectedDriveFileId == null
        && body.selectedUploadedFileId == null && body.evidenceType == null;
      if (state.failSelection) return route.fulfill({ status: 503, json: { message: disconnecting ? '연결 해제 실패' : '후보 연결 실패' } });
      const index = entries.findIndex(entry => entry.id === entryId);
      const current = entries[index];
      const metadataUpdate = body.selectedFileId === current.info.selectedFileId
        && body.selectedDriveFileId === current.info.selectedDriveFileId
        && body.selectedUploadedFileId === current.info.selectedUploadedFileId
        && body.evidenceType === current.info.evidenceType;
      if (metadataUpdate) {
        if (state.failEntrySave) return route.fulfill({ status: 503, json: { message: 'C:\\internal\\entry.sql' } });
        if (entries.some((entry, position) => position !== index && entry.info.pptNumber === body.pptNumber)) {
          return route.fulfill({ status: 409, json: { message: '이미 저장된 PPT 번호입니다.' } });
        }
        if (!body.businessPeriod.match(/수행\s*중|진행\s*중|현재|계속|\d{4}[.\/-]\d{1,2}.*\d{4}[.\/-]\d{1,2}/)) {
          return route.fulfill({ status: 400, json: { message: '사업기간은 시작~종료 날짜로 입력하거나 상세 수정에서 사업 상태를 지정하세요.' } });
        }
        entries[index] = { ...current, info: { ...body }, resolvedStatus: body.businessStatus
          || (/수행\s*중|진행\s*중|현재|계속/.test(body.businessPeriod) ? 'IN_PROGRESS' : 'COMPLETED') };
        return route.fulfill({ json: entries[index] });
      }
      if (disconnecting) {
        delete state.selectedCandidate[entryId];
        entries[index] = { ...entries[index], selectedFilename: null, selectedExt: null, info: { ...entries[index].info, ...body } };
        return route.fulfill({ json: entries[index] });
      }
      const rows = candidatesFor(entryId);
      const selected = rows.findIndex(candidate => candidate.file.driveFileId === body.selectedDriveFileId);
      if (selected < 0) return route.fulfill({ status: 400, json: { message: '후보를 다시 확인하세요.' } });
      state.selectedCandidate[entryId] = selected;
      entries[index] = {
        ...entries[index], selectedFilename: rows[selected].file.originalFilename, selectedExt: rows[selected].file.fileExt,
        info: { ...entries[index].info, ...body, selectedDriveFileId: registeredIds[selected] },
      };
      return route.fulfill({ json: entries[index] });
    }
    if (path.endsWith('/candidates')) {
      const entryId = path.split('/').at(-2);
      if (options.holdCandidateEntry === entryId) await new Promise(resolve => { state.releaseCandidate = resolve; });
      if (options.candidateErrorEntry === entryId) return route.fulfill({ status: 503, json: {} });
      const candidates = options.emptyCandidateEntry === entryId ? [] : candidatesFor(entryId);
      return route.fulfill({ json: { candidates, nextAction: '추천 순서로 후보를 확인하세요.' } });
    }
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

test('performance candidates: current file and server ordered recommendations are read only', async ({ page }) => {
  const state = await setup(page, { holdCandidateEntry: 'one' });
  const section = page.locator('#performance-documents');
  await expect(page.locator('.case-progress')).toContainText('2 / 3');
  const progress = await page.locator('.case-progress').textContent();
  await section.locator('tbody tr').filter({ hasText: '공공정보시스템 구축' }).getByRole('button', { name: '파일 관리' }).click();

  const dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('완료된-실적증명서.pdf');
  await expect(dialog.getByRole('status')).toHaveText('FMS 후보 조회 중…');
  await expect.poll(() => typeof state.releaseCandidate).toBe('function');
  state.releaseCandidate();
  await expect(dialog.locator('.performance-candidate-list li')).toHaveCount(2);
  await expect(dialog.locator('.performance-candidate-list li').nth(0)).toContainText('동일파일명.pdf');
  await expect(dialog.locator('.performance-candidate-list li').nth(0)).toContainText('사업명과 발주기관이 일치하는 최신 후보');
  await expect(dialog.locator('.performance-candidate-list li').nth(1)).toContainText('서버가 두 번째로 반환한 후보');
  await expect(dialog.getByText('추천', { exact: true })).toHaveCount(2);
  await expect(dialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  await expect(dialog.locator('.performance-candidate-list li').nth(1).getByRole('button')).toHaveText('선택');
  await expect(page.locator('.case-progress')).toHaveText(progress);
  expect(state.requests.filter(request => request.path.endsWith('/candidates'))).toEqual([{
    method: 'GET', path: '/api/performance-projects/perf-1/entries/one/candidates',
  }]);
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
});

test('performance candidates: empty and error states remain separated by entry', async ({ page }) => {
  const options = { emptyCandidateEntry: 'two', candidateErrorEntry: 'one' };
  const state = await setup(page, options);
  const section = page.locator('#performance-documents');

  await section.locator('tbody tr').filter({ hasText: '운영 사업' }).getByRole('button', { name: '파일 관리' }).click();
  let dialog = page.getByRole('dialog', { name: '운영 사업 파일 관리' });
  await expect(dialog.getByRole('status')).toHaveText('FMS 후보가 없습니다.');
  await dialog.getByRole('button', { name: '닫기' }).click();

  await section.locator('tbody tr').filter({ hasText: '공공정보시스템 구축' }).getByRole('button', { name: '파일 관리' }).click();
  dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  await expect(dialog.getByRole('alert')).toHaveText('FMS 후보를 조회하지 못했습니다.');
  expect(state.requests.filter(request => request.path.endsWith('/candidates')).map(request => request.path)).toEqual([
    '/api/performance-projects/perf-1/entries/two/candidates',
    '/api/performance-projects/perf-1/entries/one/candidates',
  ]);
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
});

test('performance candidates: long names fit the mobile modal without writes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '개인정보 영향평가' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '개인정보 영향평가 파일 관리' });
  await expect(dialog.locator('.performance-candidate-list li')).toHaveCount(2);
  await expect(dialog).toContainText('동일한-파일명이지만-다른-경로의-아주-긴-실적증빙');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(state.requests.every(request => request.method === 'GET')).toBe(true);
  await page.screenshot({ path: 'test-results/performance-candidates-mobile.png', fullPage: true });
});

test('performance candidate selection: recommended candidate updates current file and all performance progress once', async ({ page }) => {
  const state = await setup(page);
  const untouched = await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents();
  await expect(page.locator('.case-progress')).toContainText('2 / 3 · 67%');
  await page.locator('#performance-documents tbody tr').filter({ hasText: '운영 사업' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '운영 사업 파일 관리' });
  const candidates = dialog.locator('.performance-candidate-list li');
  await expect(candidates).toHaveCount(2);

  state.holdSelection = true;
  await candidates.nth(0).getByRole('button', { name: '선택' }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  await expect(dialog.getByRole('status')).toHaveText('FMS 후보 연결 중…');
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await candidates.nth(1).getByRole('button').evaluate(button => button.click());
  expect(state.writes).toHaveLength(1);
  state.holdSelection = false; state.releaseSelection();

  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('운영사업_추천.pdf');
  await expect(dialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  await expect(page.locator('#performance-documents tbody tr').filter({ hasText: '운영 사업' })).toContainText('준비됨');
  await expect(page.locator('#performance-documents .panel-header')).toContainText('3 / 3');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('3 / 3');
  await expect(page.locator('.case-progress')).toContainText('3 / 3 · 100%');
  expect(await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents()).toEqual(untouched);
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0]).toMatchObject({ method: 'PUT', path: '/api/performance-projects/perf-1/entries/two' });
  expect(state.writes[0].body).toMatchObject({
    selectedFileId: null, selectedDriveFileId: '22222222-2222-4222-8222-222222222222',
    selectedUploadedFileId: null, evidenceType: 'CERTIFICATE',
  });
  expect(state.requests.filter(request => request.path.endsWith('/candidates'))).toHaveLength(2);
  expect(state.requests.filter(request => request.method !== 'GET').map(request => request.method)).toEqual(['PUT']);
});

test('performance candidate selection: same-name paths replace by identifier and failure preserves the confirmed reference', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  let candidates = dialog.locator('.performance-candidate-list li');
  await expect(candidates).toHaveCount(2);
  await expect(candidates.nth(0)).toContainText('동일파일명.pdf');
  await expect(candidates.nth(1)).toContainText('동일파일명.pdf');
  await expect(candidates.nth(0).getByRole('button')).toHaveText('선택됨');

  await candidates.nth(1).getByRole('button', { name: '선택' }).click();
  candidates = dialog.locator('.performance-candidate-list li');
  await expect(candidates.nth(1).getByRole('button')).toHaveText('선택됨');
  expect(state.entries[0].info.selectedDriveFileId).toBe('55555555-5555-4555-8555-555555555555');

  state.failSelection = true;
  await candidates.nth(0).getByRole('button', { name: '선택' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('후보 연결 실패');
  await expect(candidates.nth(1).getByRole('button')).toHaveText('선택됨');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('동일파일명.pdf');
  expect(state.entries[0].info.selectedDriveFileId).toBe('55555555-5555-4555-8555-555555555555');
  expect(state.writes).toHaveLength(2);
});

test('performance disconnect: FMS connection clears linked fields once and updates all progress', async ({ page }) => {
  const state = await setup(page);
  const untouched = await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents();
  const before = { ...state.entries[0].info };
  await page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('실적증명서');
  state.holdSelection = true;
  await dialog.getByRole('button', { name: '연결 해제' }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  await expect(dialog.getByRole('status')).toHaveText('파일 연결 해제 중…');
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await dialog.getByRole('button', { name: '연결 해제' }).evaluate(button => button.click());
  expect(state.writes).toHaveLength(1);
  state.holdSelection = false; state.releaseSelection();

  await expect(dialog.getByLabel('현재 연결 파일')).toHaveText(/현재 연결 파일파일 미등록/);
  await expect(dialog.getByRole('button', { name: '연결 해제' })).toHaveCount(0);
  await expect(dialog.locator('.performance-candidate-list').getByRole('button', { name: '선택됨' })).toHaveCount(0);
  await expect(page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' })).toContainText('미준비');
  await expect(page.locator('#performance-documents .panel-header')).toContainText('1 / 3');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('1 / 3');
  await expect(page.locator('.case-progress')).toContainText('1 / 3 · 33%');
  expect(await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents()).toEqual(untouched);
  expect(state.writes[0].body).toMatchObject({
    selectedFileId: null, selectedDriveFileId: null, selectedUploadedFileId: null, evidenceType: null,
    kitcStatus: before.kitcStatus, requestedAt: before.requestedAt, repliedAt: before.repliedAt,
    businessName: before.businessName, businessPeriod: before.businessPeriod,
  });
  expect(state.requests.filter(request => request.path.endsWith('/candidates'))).toHaveLength(2);
  expect(state.requests.filter(request => ['DELETE', 'POST'].includes(request.method))).toHaveLength(0);
});

test('performance disconnect: direct upload connection uses the same unlink payload', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '개인정보 영향평가' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '개인정보 영향평가 파일 관리' });
  await dialog.getByRole('button', { name: '연결 해제' }).click();
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('파일 미등록');
  await expect(page.locator('#performance-documents tbody tr').filter({ hasText: '개인정보 영향평가' })).toContainText('미준비');
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].body).toMatchObject({
    selectedFileId: null, selectedDriveFileId: null, selectedUploadedFileId: null, evidenceType: null,
    kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
  });
});

test('performance disconnect: failure preserves current file, evidence type and selected candidate', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  await expect(dialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  state.failSelection = true;
  await dialog.getByRole('button', { name: '연결 해제' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('연결 해제 실패');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('완료된-실적증명서.pdf');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('실적증명서');
  await expect(dialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  expect(state.entries[0].info.selectedDriveFileId).toBe('11111111-1111-4111-8111-111111111111');
  expect(state.entries[0].info.evidenceType).toBe('CERTIFICATE');
});

test('performance upload: new PC file updates source, evidence type and all progress', async ({ page }) => {
  const state = await setup(page);
  const untouched = await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents();
  await page.locator('#performance-documents tbody tr').filter({ hasText: '운영 사업' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '운영 사업 파일 관리' });
  await dialog.getByLabel('증빙유형').selectOption('CONTRACT');
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: 'local-contract.pdf', mimeType: 'application/pdf', buffer: Buffer.from('contract') });
  await expect(dialog.getByText('local-contract.pdf', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();

  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('local-contract.pdf');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('PC 직접 업로드 · 계약서');
  await expect(page.locator('#performance-documents tbody tr').filter({ hasText: '운영 사업' })).toContainText('준비됨');
  await expect(page.locator('#performance-documents .panel-header')).toContainText('3 / 3');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('3 / 3');
  await expect(page.locator('.case-progress')).toContainText('3 / 3 · 100%');
  expect(await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents()).toEqual(untouched);
  expect(state.entries[1].info).toMatchObject({
    selectedFileId: null, selectedDriveFileId: null, selectedUploadedFileId: 'upload-1',
    evidenceType: 'CONTRACT', kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
  });
  expect(state.uploads).toHaveLength(1);
  expect(state.uploads[0].body).toContain('name="evidenceType"');
  expect(state.uploads[0].body).toContain('CONTRACT');
});

test('performance upload: replaces direct upload and FMS connection while blocking conflicting actions', async ({ page }) => {
  const state = await setup(page);
  const section = page.locator('#performance-documents');

  await section.locator('tbody tr').filter({ hasText: '개인정보 영향평가' }).getByRole('button', { name: '파일 관리' }).click();
  let dialog = page.getByRole('dialog', { name: '개인정보 영향평가 파일 관리' });
  const previousUploadId = state.entries[2].info.selectedUploadedFileId;
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: 'replacement.pdf', mimeType: 'application/pdf', buffer: Buffer.from('replacement') });
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('replacement.pdf');
  expect(state.entries[2].info.selectedUploadedFileId).not.toBe(previousUploadId);
  await dialog.getByRole('button', { name: '닫기' }).click();

  await section.locator('tbody tr').filter({ hasText: '공공정보시스템 구축' }).getByRole('button', { name: '파일 관리' }).click();
  dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: 'from-fms-to-pc.pdf', mimeType: 'application/pdf', buffer: Buffer.from('pc') });
  state.holdUpload = true;
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect.poll(() => state.uploads.length).toBe(2);
  await expect(dialog.getByRole('status')).toHaveText('파일 업로드 중…');
  await expect(dialog.getByRole('button', { name: '연결 해제' })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await expect(dialog.locator('.performance-candidate-list').getByRole('button', { name: '선택' }).first()).toBeDisabled();
  await dialog.locator('.performance-upload form').evaluate(form => form.requestSubmit());
  expect(state.uploads).toHaveLength(2);
  state.holdUpload = false; state.releaseUpload();

  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('from-fms-to-pc.pdf');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('PC 직접 업로드');
  await expect(dialog.locator('.performance-candidate-list').getByRole('button', { name: '선택됨' })).toHaveCount(0);
  expect(state.entries[0].info.selectedDriveFileId).toBeNull();
  expect(state.entries[0].info.selectedUploadedFileId).toBe('upload-2');
  expect(state.entries[0].info.kitcStatus).toBe('REQUESTED');
});

test('performance upload: empty and oversized files are rejected before POST', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '운영 사업' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '운영 사업 파일 관리' });
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('비어 있지 않은 20MB 이하 파일을 선택하세요.');
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: 'empty.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(0) });
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('비어 있지 않은 20MB 이하 파일을 선택하세요.');
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: 'large.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(20 * 1024 * 1024 + 1) });
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('비어 있지 않은 20MB 이하 파일을 선택하세요.');
  expect(state.uploads).toHaveLength(0);
});

test('performance upload: safe server validation is shown and internal details stay hidden without replacing FMS', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' })
    .getByRole('button', { name: '파일 관리' }).click();
  const dialog = page.getByRole('dialog', { name: '공공정보시스템 구축 파일 관리' });
  const input = dialog.getByLabel('업로드 파일');
  state.uploadStatus = 400; state.uploadMessage = '파일명을 확인하세요.';
  await input.setInputFiles({ name: 'invalid.pdf', mimeType: 'application/pdf', buffer: Buffer.from('invalid') });
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('파일명을 확인하세요.');
  await expect(dialog.getByLabel('현재 연결 파일')).toContainText('완료된-실적증명서.pdf');

  state.uploadStatus = 500; state.uploadMessage = 'C:\\secret\\performance-uploads\\uuid.bin';
  await dialog.getByRole('button', { name: '업로드', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('파일 업로드에 실패했습니다. 다시 시도해 주세요.');
  await expect(dialog).not.toContainText('C:\\secret');
  await expect(dialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  expect(state.entries[0].info.selectedDriveFileId).toBe('11111111-1111-4111-8111-111111111111');
  expect(state.entries[0].info.evidenceType).toBe('CERTIFICATE');
});

test('performance entry: required validation and single-row import add an ongoing entry with free-form amount', async ({ page }) => {
  const state = await setup(page);
  const untouched = await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents();
  await page.locator('#performance-documents').getByRole('button', { name: '실적 추가' }).click();
  const dialog = page.getByRole('dialog', { name: '실적 추가' });
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog.locator('.field-error')).toHaveCount(5);
  expect(state.imports).toHaveLength(0);

  await dialog.getByLabel('PPT 번호').fill('4');
  await dialog.getByLabel('사업명').fill('신규 운영 사업');
  await dialog.getByLabel('사업기간').fill('2026.01 ~ 수행중');
  await dialog.getByLabel('계약금액').fill('금액 협의');
  await dialog.getByLabel('발주처').fill('신규기관');
  await dialog.getByRole('button', { name: '저장' }).click();

  await expect(dialog).toHaveCount(0);
  const row = page.locator('#performance-documents tbody tr').filter({ hasText: '신규 운영 사업' });
  await expect(row).toContainText('미준비');
  await expect(page.locator('#performance-documents .panel-header')).toContainText('2 / 4');
  await expect(page.locator('.category-progress-card[aria-label="실적증빙"]')).toContainText('2 / 4');
  await expect(page.locator('.case-progress')).toContainText('2 / 4 · 50%');
  expect(await page.locator('.category-progress-card:not([aria-label="실적증빙"])').allTextContents()).toEqual(untouched);
  expect(state.entries.at(-1)).toMatchObject({ resolvedStatus: 'IN_PROGRESS', info: {
    pptNumber: '4', businessPeriod: '2026.01 ~ 수행중', contractAmount: '금액 협의', businessStatus: null,
    kitcStatus: 'NEEDED', selectedDriveFileId: null, evidenceType: null,
  } });
  expect(state.imports).toHaveLength(1);
  expect(state.imports[0].html).toBeNull();

  await row.getByRole('button', { name: '파일 관리' }).click();
  await expect(page.getByRole('dialog', { name: '신규 운영 사업 파일 관리' }).getByLabel('증빙유형').locator('option')).toHaveCount(1);
});

test('performance entry: duplicate PPT number and invalid period errors stay by their fields', async ({ page }) => {
  const state = await setup(page);
  await page.locator('#performance-documents').getByRole('button', { name: '실적 추가' }).click();
  const dialog = page.getByRole('dialog', { name: '실적 추가' });
  await dialog.getByLabel('PPT 번호').fill('1');
  await dialog.getByLabel('사업명').fill('중복 사업');
  await dialog.getByLabel('사업기간').fill('2024.01 ~ 2025.12');
  await dialog.getByLabel('계약금액').fill('100');
  await dialog.getByLabel('발주처').fill('기관');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog.locator('#performance-pptNumber-error')).toHaveText('이미 저장된 PPT 번호입니다. 기존 실적을 수정하세요.');

  await dialog.getByLabel('PPT 번호').fill('9');
  await dialog.getByLabel('사업기간').fill('날짜 오류');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog.locator('#performance-businessPeriod-error')).toContainText('사업기간은 시작~종료 날짜');
  await expect(page.locator('#performance-documents tbody tr')).toHaveCount(3);
  expect(state.imports).toHaveLength(2);
});

test('performance entry: edit preserves file, evidence, manual status and KITC data; failure leaves confirmed data', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  const before = structuredClone(state.entries[0].info);
  const row = page.locator('#performance-documents tbody tr').filter({ hasText: '공공정보시스템 구축' });
  await row.getByRole('button', { name: '수정' }).click();
  let dialog = page.getByRole('dialog', { name: '실적 수정' });
  await expect(dialog.getByLabel('PPT 번호')).toHaveValue('1');
  await dialog.getByLabel('PPT 번호').fill('01');
  await dialog.getByLabel('사업명').fill('수정된 구축 사업');
  await dialog.getByLabel('사업기간').fill('2026.01 ~ 수행중');
  await dialog.getByLabel('계약금액').fill('200억원');
  await dialog.getByLabel('발주처').fill('수정기관');
  await dialog.getByRole('button', { name: '저장' }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.locator('#performance-documents')).toContainText('수정된 구축 사업');
  expect(state.entries[0].info).toMatchObject({
    selectedFileId: before.selectedFileId, selectedDriveFileId: before.selectedDriveFileId,
    selectedUploadedFileId: before.selectedUploadedFileId, evidenceType: before.evidenceType,
    businessStatus: before.businessStatus, kitcStatus: before.kitcStatus,
    requestedAt: before.requestedAt, repliedAt: before.repliedAt,
  });
  expect(state.entries[0].resolvedStatus).toBe('COMPLETED');
  await expect(page.locator('#performance-documents .panel-header')).toContainText('2 / 3');
  await page.locator('#performance-documents tbody tr').filter({ hasText: '수정된 구축 사업' })
    .getByRole('button', { name: '파일 관리' }).click();
  let fileDialog = page.getByRole('dialog', { name: '수정된 구축 사업 파일 관리' });
  await expect(fileDialog.getByLabel('현재 연결 파일')).toContainText('완료된-실적증명서.pdf');
  await expect(fileDialog.locator('.performance-candidate-list li').nth(0).getByRole('button')).toHaveText('선택됨');
  await fileDialog.getByRole('button', { name: '닫기' }).click();

  state.failEntrySave = true;
  await page.locator('#performance-documents tbody tr').filter({ hasText: '수정된 구축 사업' })
    .getByRole('button', { name: '수정' }).click();
  dialog = page.getByRole('dialog', { name: '실적 수정' });
  await dialog.getByLabel('사업명').fill('저장되면 안 되는 이름');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('실적 저장에 실패했습니다.');
  await expect(dialog).not.toContainText('C:\\internal');
  await expect(page.locator('#performance-documents')).toContainText('수정된 구축 사업');
  await expect(page.locator('#performance-documents')).not.toContainText('저장되면 안 되는 이름');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
