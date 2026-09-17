import { test, expect } from '@playwright/test';
const types = [['PROFILE', '프로필'], ['QUALIFICATION', '자격사본'], ['KOSA', 'KOSA 경력증명서'], ['PIA', '개인정보 영향평가 전문인력 인증서']];
const person = (id, name, ready = 0) => ({ id, name, department: '감리팀', documents: types.map(([type, label], index) => ({ type, label, needed: true, filename: index < ready ? `${name}_${type}.pdf` : null, reference: index < ready ? `snapshot-${id}-${type}` : null, fmsReferenceId: index < ready ? `snapshot-${id}-${type}` : null, source: index < ready ? 'FMS' : null })) });
const panel = page => page.locator('#react-personnel');
const entry = (page, id) => panel(page).locator(`[data-person-id="${id}"]`);
async function setup(page, options = {}) {
  const state = { people: options.empty ? [] : [person('one', '최효재', 3), person('two', '김대원', 1)], writes: [], reads: [], uploads: [], uploadStatus: 0, uploadMessage: '', candidates: {}, candidateError: false, holdCandidates: false, releaseCandidates: null, fail: false, failRead: false, commitFailure: false, hold: false, release: null, searchFail: false, searchEmpty: false };
  if (!options.empty) state.people[1].documents[3].needed = false;
  const prefix = '/api/submission-cases/71/people';
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname;
    if (req.method() !== 'GET') state.writes.push({ path, method: req.method(), body: req.headers()['content-type']?.includes('application/json') ? req.postDataJSON() : null });
    else state.reads.push(path + url.search);
    if (path.startsWith(prefix)) {
      if (path.endsWith('/candidates')) {
        const parts = path.split('/'), key = parts.at(-4) + '/' + parts.at(-2);
        const response = state.candidateError ? { status: 503, json: { message: '후보 인덱스 조회 실패' } } : { json: state.candidates[key] || [] };
        if (state.holdCandidates) await new Promise(resolve => { state.releaseCandidates = resolve; });
        return route.fulfill(response);
      }
      if (path.endsWith('/search')) return route.fulfill(state.searchFail ? { status: 503, json: { message: '인력 파일 인덱스 오류' } } : { json: state.searchEmpty ? [] : [{ name: '박민수', department: '감리팀' }, { name: '최효재', department: '감리팀' }] });
      if (req.method() === 'GET') return route.fulfill(state.failRead ? { status: 500, json: { message: '인력 조회 실패' } } : { json: state.people });
      if (state.hold) await new Promise(resolve => { state.release = resolve; });
      if (state.fail && !state.commitFailure) return route.fulfill({ status: 400, json: { message: '인력 저장 실패' } });
      if (req.method() === 'POST' && path.endsWith('/upload')) {
        const content = req.postDataBuffer().toString('utf8');
        state.uploads.push({ contentType: req.headers()['content-type'], content });
        if (state.uploadStatus && !state.commitFailure) return route.fulfill({ status: state.uploadStatus, json: { message: state.uploadMessage } });
        const parts = path.split('/');
        const doc = state.people.find(person => person.id === parts.at(-4)).documents.find(doc => doc.type === parts.at(-2));
        Object.assign(doc, { filename: content.match(/filename="([^"]+)"/)[1], source: 'PC', fmsReferenceId: null, reference: 'upload-' + state.uploads.length });
      } else if (req.method() === 'POST') {
        const input = req.postDataJSON();
        if (state.people.some(row => row.name === input.name)) return route.fulfill({ status: 400, json: { message: '이미 추가한 인력입니다.' } });
        const added = person('new', input.name); added.documents.forEach(doc => { doc.needed = false; }); state.people.push(added);
      } else if (req.method() === 'DELETE') state.people = state.people.filter(row => row.id !== path.split('/').at(-1));
      else if (req.method() === 'PUT' && path.endsWith('/selection')) {
        const parts = path.split('/'), id = parts.at(-4), type = parts.at(-2);
        const doc = state.people.find(person => person.id === id).documents.find(doc => doc.type === type);
        if (req.postDataJSON().candidateId === null) {
          Object.assign(doc, { filename: null, source: null, fmsReferenceId: null, reference: null });
        } else {
          const chosen = state.candidates[`${id}/${type}`].find(row => row.id === req.postDataJSON().candidateId);
          if (!chosen) return route.fulfill({ status: 400, json: { message: '후보를 다시 확인하세요.' } });
          if (chosen.id.startsWith('index-')) chosen.id = 'ref-' + chosen.id;
          Object.assign(doc, { filename: chosen.filename, source: 'FMS', fmsReferenceId: chosen.id, reference: chosen.id });
        }
      }
      else if (req.method() === 'PUT' && /\/documents\/[^/]+$/.test(path)) {
        const parts = path.split('/');
        const document = state.people.find(person => person.id === parts.at(-3)).documents.find(doc => doc.type === parts.at(-1));
        document.needed = req.postDataJSON().needed;
      } else return route.fulfill({ status: 400 });
      return route.fulfill(state.commitFailure ? { status: 500, json: { message: '응답 실패' } } : { json: state.people });
    }
    if (req.method() !== 'GET') return route.fulfill({ status: 400 });
    if (path === '/api/submission-cases/71') return route.fulfill({ json: { id: 71, projectName: '인력 프로젝트', performanceProjectId: 'perf', deadline: '2026-12-31' } });
    if (path.endsWith('/requirements')) return route.fulfill({ json: [
      { id: 10, documentName: '회사 서류', category: 'COMPANY_GENERAL', companyCommon: true },
      { id: 20, documentName: '실적 서류', category: 'PERFORMANCE', performanceSelectionRequired: true },
    ] });
    if (path.endsWith('/package')) return route.fulfill({ json: { selections: [{ requirementId: 10, uploadedFileId: 'common-snapshot', originalFilename: '회사.pdf' }] } });
    if (path.endsWith('/entries')) return route.fulfill({ json: [{ info: { selectedUploadedFileId: 'performance-snapshot' } }] });
    return route.fulfill({ json: [] });
  });
  await page.goto('/react/index.html#/submissions/71');
  await expect(panel(page)).toBeVisible();
  return state;
}
async function search(page) {
  await panel(page).getByRole('button', { name: '+ 인력 추가' }).click();
  await page.getByLabel('이름 검색', { exact: true }).fill('박민');
  await page.getByRole('button', { name: '검색', exact: true }).click();
}
const choose = page => page.getByRole('dialog').getByRole('listitem').filter({ hasText: '박민수' }).getByRole('button');

test('personnel: saved four document states, references, person/category/project totals and read-only requests', async ({ page }) => {
  const state = await setup(page);
  await expect(entry(page, 'one').getByRole('rowheader')).toHaveText(types.map(type => type[1]));
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('준비됨');
  await expect(entry(page, 'one')).toContainText('최효재_PROFILE.pdf');
  await expect(entry(page, 'one').locator('tbody tr').last()).toContainText('미준비');
  await expect(entry(page, 'two').locator('tbody tr').last()).toContainText('미선택');
  await expect(page.getByLabel('최효재 준비율')).toHaveText('3 / 4');
  await expect(panel(page).getByRole('heading')).toContainText('4 / 7');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true })).toContainText('4 / 7');
  await expect(page.locator('.case-progress')).toContainText('6 / 9 · 67%');
  await expect(entry(page, 'one').getByRole('checkbox').first()).toBeChecked();
  await expect(entry(page, 'one').getByRole('checkbox').first()).toBeEnabled();
  await expect(page.locator('input[type=file]')).toHaveCount(0);
  expect(state.writes).toEqual([]);
  expect(state.reads.some(path => /candidates|refresh|selection/.test(path))).toBe(false);
});

test('personnel: search, immediate add, duplicate prevention and empty state', async ({ page }) => {
  const state = await setup(page, { empty: true });
  await expect(panel(page)).toContainText('선택된 인력이 없습니다.');
  await search(page);
  expect(state.reads.some(path => path.endsWith('/search?q=' + encodeURIComponent('박민')))).toBe(true);
  await choose(page).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(entry(page, 'new').getByRole('rowheader')).toHaveCount(4);
  await expect(page.getByLabel('박민수 준비율')).toHaveText('0 / 0');
  expect(state.writes).toEqual([{ path: '/api/submission-cases/71/people', method: 'POST', body: { name: '박민수', department: '감리팀' } }]);
  await search(page);
  await expect(choose(page)).toBeDisabled();
  await expect(choose(page)).toHaveText('추가됨');
  expect(state.writes).toHaveLength(1);
});

test('personnel: confirmed removal preserves other person snapshots and company/performance state', async ({ page }) => {
  const state = await setup(page);
  const preserved = structuredClone(state.people[0]);
  page.once('dialog', dialog => dialog.dismiss());
  await entry(page, 'two').getByRole('button', { name: '인력 제거' }).click();
  expect(state.writes).toHaveLength(0);
  page.once('dialog', dialog => dialog.accept());
  await entry(page, 'two').getByRole('button', { name: '인력 제거' }).click();
  await expect(entry(page, 'two')).toHaveCount(0);
  expect(state.people).toEqual([preserved]);
  await expect(panel(page).getByRole('heading')).toContainText('3 / 4');
  await expect(page.locator('.case-progress')).toContainText('5 / 6 · 83%');
  await expect(page.getByRole('region', { name: '회사 공통', exact: true })).toContainText('1 / 1');
  await expect(page.getByRole('region', { name: '실적증빙', exact: true })).toContainText('1 / 1');
  await expect(page.locator('#common-documents')).toContainText('회사.pdf');
  expect(state.writes).toEqual([{ path: '/api/submission-cases/71/people/two', method: 'DELETE', body: null }]);
});

test('personnel: failed add/remove reconciles; committed response failure and failed re-read recover', async ({ page }) => {
  const state = await setup(page);
  state.fail = true;
  await search(page); await choose(page).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('인력 저장 실패');
  await expect(entry(page, 'new')).toHaveCount(0);
  await page.getByRole('button', { name: '닫기', exact: true }).click();
  page.once('dialog', dialog => dialog.accept());
  await entry(page, 'two').getByRole('button', { name: '인력 제거' }).click();
  await expect(panel(page).getByRole('alert')).toContainText('인력 저장 실패');
  await expect(entry(page, 'two')).toBeVisible();
  state.commitFailure = true; state.failRead = true;
  page.once('dialog', dialog => dialog.accept());
  await entry(page, 'two').getByRole('button', { name: '인력 제거' }).click();
  await expect(panel(page)).toContainText('마지막으로 확인한 상태');
  await expect(panel(page).getByRole('button', { name: '+ 인력 추가' })).toBeDisabled();
  state.failRead = false;
  await page.getByRole('button', { name: '인력 다시 조회' }).click();
  await expect(entry(page, 'two')).toHaveCount(0);
  await expect(page.locator('.case-progress')).toContainText('5 / 6');
});

test('personnel: rapid add clicks serialize and file-management navigation waits for remove', async ({ page }) => {
  const state = await setup(page); state.hold = true;
  await search(page);
  await choose(page).evaluate(button => { button.click(); button.click(); });
  await expect.poll(() => !!state.release).toBe(true);
  expect(state.writes).toHaveLength(1);
  await expect(choose(page)).toBeDisabled();
  state.release();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  state.release = null;
  page.once('dialog', dialog => dialog.accept());
  await entry(page, 'two').getByRole('button', { name: '인력 제거' }).click();
  await expect.poll(() => !!state.release).toBe(true);
  await page.route('**/submissions/?caseId=71', route => route.fulfill({ body: '<main id="personnel-panel">기존 인력 관리</main>', contentType: 'text/html' }));
  const link = entry(page, 'one').getByRole('link', { name: '파일 관리' });
  await expect(link).toHaveAttribute('href', '/submissions/?caseId=71#personnel-panel');
  await link.click();
  await expect(page).toHaveURL(/#\/submissions\/71$/);
  state.release();
  await expect(page).toHaveURL(/\/submissions\/\?caseId=71#personnel-panel$/);
  expect(state.writes).toHaveLength(2);
});

test('personnel: search errors and empty results do not allow arbitrary additions', async ({ page }) => {
  const state = await setup(page); state.searchFail = true;
  await search(page);
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('인력 파일 인덱스 오류');
  state.searchFail = false; state.searchEmpty = true;
  await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('일치하는 이름이 없습니다');
  await expect(page.getByRole('dialog').getByRole('listitem')).toHaveCount(0);
  expect(state.writes).toHaveLength(0);
});

test('personnel: stale searches ignored after closing and mobile compact table/modal', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await entry(page, 'one').locator('.react-personnel-scroll').evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true);
  await panel(page).scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/personnel-mobile.png', fullPage: true });
  let release;
  await page.route('**/people/search?*', async route => { await new Promise(resolve => { release = resolve; }); await route.fulfill({ json: [{ name: '오래된 결과', department: '' }] }); });
  await search(page);
  await expect(page.getByRole('dialog').getByRole('status')).toHaveText('검색 중…');
  const bounds = await page.getByRole('dialog').boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await page.getByRole('button', { name: '닫기', exact: true }).click();
  release();
  await panel(page).getByRole('button', { name: '+ 인력 추가' }).click();
  await expect(page.getByRole('dialog').getByRole('listitem')).toHaveCount(0);
});

const neededCheckbox = (page, id, label) => entry(page, id).getByRole('checkbox', { name: new RegExp(label + ' 필요$') });

test('personnel needed: uncheck without vanilla confirmation, recheck preserves files and updates all totals', async ({ page }) => {
  const state = await setup(page);
  const original = structuredClone(state.people);
  const dialogs = [];
  page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
  const profile = neededCheckbox(page, 'one', '프로필');
  await profile.click();
  await expect(profile).not.toBeChecked();
  await expect(page.getByLabel('최효재 준비율')).toHaveText('2 / 3');
  await expect(panel(page).getByRole('heading')).toContainText('3 / 6');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true })).toContainText('3 / 6');
  await expect(page.locator('.case-progress')).toContainText('5 / 8');
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('미선택');
  await expect(entry(page, 'one')).toContainText('최효재_PROFILE.pdf');
  expect(state.people[0].documents[0]).toEqual({ ...original[0].documents[0], needed: false });
  expect(state.people[1]).toEqual(original[1]);
  expect(dialogs).toEqual([]);
  await profile.click();
  await expect(profile).toBeChecked();
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('준비됨');
  await expect(page.getByLabel('최효재 준비율')).toHaveText('3 / 4');
  await expect(panel(page).getByRole('heading')).toContainText('4 / 7');
  await expect(page.locator('.case-progress')).toContainText('6 / 9');
  expect(state.people).toEqual(original);
  await expect(page.getByRole('region', { name: '회사 공통', exact: true })).toContainText('1 / 1');
  await expect(page.getByRole('region', { name: '실적증빙', exact: true })).toContainText('1 / 1');
  await expect(page.locator('#common-documents')).toContainText('회사.pdf');
  expect(state.writes).toEqual([false, true].map(needed => ({ path: '/api/submission-cases/71/people/one/documents/PROFILE', method: 'PUT', body: { needed } })));
  expect(state.reads.some(path => /candidates|refresh|selection/.test(path))).toBe(false);
});

test('personnel needed: all four types save independently including missing files', async ({ page }) => {
  const state = await setup(page);
  for (const [type, label] of types) {
    const checkbox = neededCheckbox(page, 'one', label);
    await checkbox.click();
    await expect(checkbox).not.toBeChecked();
    await expect(checkbox).toBeEnabled();
    expect(state.writes.at(-1)).toEqual({ path: `/api/submission-cases/71/people/one/documents/${type}`, method: 'PUT', body: { needed: false } });
    await checkbox.click();
    await expect(checkbox).toBeChecked();
    await expect(checkbox).toBeEnabled();
    expect(state.writes.at(-1).body).toEqual({ needed: true });
  }
  await neededCheckbox(page, 'two', '개인정보 영향평가 전문인력 인증서').click();
  await expect(page.getByLabel('김대원 준비율')).toHaveText('1 / 4');
  await expect(entry(page, 'two').locator('tbody tr').last()).toContainText('미준비');
  await expect(panel(page).getByRole('heading')).toContainText('4 / 8');
  await expect(page.locator('.case-progress')).toContainText('6 / 10 · 60%');
});

test('personnel needed: failed save restores checks; uncertain committed save locks until re-read', async ({ page }) => {
  const state = await setup(page);
  const profile = neededCheckbox(page, 'one', '프로필');
  state.fail = true;
  await profile.click();
  await expect(panel(page).getByRole('alert')).toContainText('인력 저장 실패');
  await expect(profile).toBeChecked();
  await expect(profile).toBeEnabled();
  await expect(page.locator('.case-progress')).toContainText('6 / 9');
  expect(state.reads.filter(path => path === '/api/submission-cases/71/people')).toHaveLength(2);
  state.commitFailure = true; state.failRead = true;
  await profile.click();
  await expect(panel(page)).toContainText('마지막으로 확인한 상태');
  await expect(profile).toBeDisabled();
  await expect(profile).toBeChecked();
  state.failRead = false;
  await page.getByRole('button', { name: '인력 다시 조회' }).click();
  await expect(profile).not.toBeChecked();
  await expect(profile).toBeEnabled();
  await expect(entry(page, 'one')).toContainText('최효재_PROFILE.pdf');
  await expect(page.locator('.case-progress')).toContainText('5 / 8');
});

test('personnel needed: rapid clicks cannot duplicate or overlap saves; mobile checkbox works', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page); state.hold = true;
  const profile = neededCheckbox(page, 'one', '프로필');
  await profile.evaluate(checkbox => { checkbox.click(); checkbox.click(); checkbox.click(); });
  await expect.poll(() => !!state.release).toBe(true);
  expect(state.writes).toHaveLength(1);
  await expect(profile).toBeDisabled();
  await expect(neededCheckbox(page, 'two', '자격사본')).toBeDisabled();
  await expect(panel(page).getByRole('button', { name: '+ 인력 추가' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '새로고침', exact: true })).toBeDisabled();
  state.release();
  await expect(profile).not.toBeChecked();
  await expect(profile).toBeEnabled();
  state.hold = false;
  await profile.click();
  await expect(profile).toBeChecked();
  expect(state.writes).toHaveLength(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await panel(page).screenshot({ path: 'test-results/personnel-needed-mobile.png' });
});

const candidateButton = (page, id, label) => entry(page, id).getByRole('row').filter({ has: page.getByRole('rowheader', { name: label, exact: true }) }).getByRole('button', { name: '후보 조회' });
const candidate = (id, filename, recommended = false) => ({ id, filename, filenameDate: '2026-09-01', recommended, note: recommended ? '파일명 날짜 기준 최신 후보' : '이전 날짜 후보' });

test('personnel candidates: current file, server order and recommendations; closing does not change any saved state', async ({ page }) => {
  const state = await setup(page);
  const original = structuredClone(state.people);
  const progress = await page.locator('.case-progress').innerText();
  const common = await page.locator('#common-documents').innerText();
  const performance = await page.getByRole('region', { name: '실적증빙', exact: true }).innerText();
  state.candidates['one/PROFILE'] = [candidate('older', '이전프로필.pdf'), candidate('latest', '최신프로필.pdf', true)];
  state.candidates['one/PROFILE'][0].filenameDate = null;
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
  await expect(dialog.locator('.personnel-candidate-list li p')).toHaveText(['이전프로필.pdf', '최신프로필.pdf']);
  await expect(dialog.locator('.personnel-candidate-list li').first()).toContainText('파일명 날짜 없음');
  await expect(dialog.locator('.personnel-candidate-list li').last()).toContainText('최신 후보');
  await expect(dialog.getByRole('radio')).toHaveCount(0);
  await expect(dialog.locator('input[type=file]')).toHaveCount(1);
  await expect(dialog.locator('button')).toHaveText(['연결 해제', '선택', '선택', '업로드', '닫기']);
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(candidateButton(page, 'one', '프로필')).toBeFocused();
  expect(state.people).toEqual(original);
  expect(await page.locator('.case-progress').innerText()).toBe(progress);
  expect(await page.locator('#common-documents').innerText()).toBe(common);
  expect(await page.getByRole('region', { name: '실적증빙', exact: true }).innerText()).toBe(performance);
  expect(state.writes).toEqual([]);
  expect(state.reads.filter(path => path.endsWith('/candidates'))).toEqual(['/api/submission-cases/71/people/one/documents/PROFILE/candidates']);
  expect(state.reads.some(path => /refresh|\/selection/.test(path))).toBe(false);
});

test('personnel candidates: loading deduplicates, close aborts stale result, people and document types stay isolated', async ({ page }) => {
  const state = await setup(page);
  state.holdCandidates = true;
  state.candidates['one/PROFILE'] = [candidate('old', '늦은응답.pdf')];
  await candidateButton(page, 'one', '프로필').evaluate(button => { button.click(); button.click(); });
  await expect(page.getByRole('dialog').getByRole('status')).toHaveText('FMS 후보 조회 중…');
  await expect.poll(() => !!state.releaseCandidates).toBe(true);
  expect(state.reads.filter(path => path.endsWith('/candidates'))).toHaveLength(1);
  await page.getByRole('dialog').getByRole('button', { name: '닫기' }).click();
  state.holdCandidates = false;
  state.releaseCandidates();
  for (const [id, type, label] of [['one', 'QUALIFICATION', '자격사본'], ['two', 'KOSA', 'KOSA 경력증명서'], ['one', 'PIA', '개인정보 영향평가 전문인력 인증서']]) {
    state.candidates[`${id}/${type}`] = [candidate(type, `${id}-${type}.pdf`)];
    await candidateButton(page, id, label).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.locator('.personnel-candidate-list li p')).toHaveText([`${id}-${type}.pdf`]);
    await expect(dialog.getByText('늦은응답.pdf')).toHaveCount(0);
    if (type === 'PIA') {
      await expect(dialog.locator('.requirement-state')).toHaveCount(0);
      await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('파일 미등록');
    }
    await dialog.getByRole('button', { name: '닫기' }).click();
  }
  expect(state.writes).toEqual([]);
  expect(state.reads.filter(path => path.endsWith('/candidates'))).toHaveLength(4);
});

test('personnel candidates: empty, error retry and malformed response preserve connected file', async ({ page }) => {
  const state = await setup(page);
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('status')).toHaveText('FMS 후보가 없습니다.');
  await dialog.getByRole('button', { name: '닫기' }).click();
  state.candidateError = true;
  await candidateButton(page, 'one', '프로필').click();
  await expect(dialog.getByRole('alert')).toHaveText('후보 인덱스 조회 실패');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
  state.candidateError = false;
  state.candidates['one/PROFILE'] = [{ invalid: true }];
  await dialog.getByRole('button', { name: '다시 조회' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('FMS 후보 목록을 확인할 수 없습니다.');
  state.candidates['one/PROFILE'] = [candidate('ok', '복구.pdf')];
  await dialog.getByRole('button', { name: '다시 조회' }).click();
  await expect(dialog.locator('.personnel-candidate-list li p')).toHaveText(['복구.pdf']);
  await dialog.getByRole('button', { name: '닫기' }).click();
  await expect(page.locator('.case-progress')).toContainText('6 / 9');
  expect(state.writes).toEqual([]);
});

test('personnel candidates: long filenames and metadata wrap in mobile modal', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  const filename = '프로필_최효재_' + '긴파일명'.repeat(30) + '.pdf';
  state.candidates['one/PROFILE'] = [candidate('long', filename, true), candidate('next', '두번째.pdf')];
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(filename, { exact: true })).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  expect(await dialog.evaluate(el => parseFloat(getComputedStyle(el).paddingLeft))).toBeGreaterThanOrEqual(16);
  await dialog.getByRole('button', { name: '닫기' }).scrollIntoViewIfNeeded();
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeInViewport();
  await page.screenshot({ path: 'test-results/personnel-candidates-mobile.png', fullPage: true });
  expect(state.writes).toEqual([]);
});

const candidateRows = page => page.getByRole('dialog').locator('.personnel-candidate-list li');

test('personnel selection: reference identity distinguishes same filenames and PC uploads are never selected', async ({ page }) => {
  const state = await setup(page);
  const filename = '최효재_PROFILE.pdf';
  state.candidates['one/PROFILE'] = [candidate('snapshot-one-PROFILE', filename, true), candidate('index-other-path', filename)];
  await candidateButton(page, 'one', '프로필').click();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  await expect(candidateRows(page).last().getByRole('button')).toHaveText('선택');
  expect(state.writes).toHaveLength(0);
  await candidateRows(page).last().getByRole('button').click();
  await expect(candidateRows(page).last().getByRole('button')).toHaveText('선택됨');
  await expect(candidateRows(page).first().getByRole('button')).toBeEnabled();
  expect(state.people[0].documents[0].fmsReferenceId).toBe('ref-index-other-path');
  await candidateRows(page).first().getByRole('button').click();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  expect(state.people[0].documents[0].fmsReferenceId).toBe('snapshot-one-PROFILE');
  await page.getByRole('dialog').getByRole('button', { name: '닫기' }).click();
  Object.assign(state.people[0].documents[0], { source: 'PC', fmsReferenceId: null });
  await page.reload();
  await candidateButton(page, 'one', '프로필').click();
  await expect(candidateRows(page).getByRole('button')).toHaveText(['선택', '선택']);
  expect(state.writes).toHaveLength(2);
});

test('personnel selection: recommended and older candidates update current file and all progress without other changes', async ({ page }) => {
  const state = await setup(page);
  const otherPerson = structuredClone(state.people[1]);
  const common = await page.locator('#common-documents').innerText();
  const performance = await page.getByRole('region', { name: '실적증빙', exact: true }).innerText();
  state.people[0].documents[0].filename = null; state.people[0].documents[0].fmsReferenceId = null;
  await page.reload();
  state.candidates['one/PROFILE'] = [candidate('index-new', '새프로필.pdf', true), candidate('index-old', '이전프로필.pdf')];
  await candidateButton(page, 'one', '프로필').click();
  await expect(candidateRows(page).first()).toContainText('최신 후보');
  expect(state.writes).toEqual([]);
  await candidateRows(page).first().getByRole('button').click();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  await expect(page.getByRole('dialog').getByRole('region', { name: '현재 연결 파일' })).toContainText('새프로필.pdf');
  await expect(page.getByLabel('최효재 준비율')).toHaveText('3 / 4');
  await expect(panel(page).getByRole('heading', { name: /인력·자격/ })).toContainText('4 / 7');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true })).toContainText('4 / 7');
  await expect(page.locator('.case-progress')).toContainText('6 / 9');
  await candidateRows(page).last().getByRole('button').click();
  await expect(candidateRows(page).last().getByRole('button')).toHaveText('선택됨');
  await expect(page.getByRole('dialog').getByRole('region', { name: '현재 연결 파일' })).toContainText('이전프로필.pdf');
  await page.getByRole('dialog').getByRole('button', { name: '닫기' }).click();
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('준비됨');
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('이전프로필.pdf');
  expect(state.people[1]).toEqual(otherPerson);
  expect(await page.locator('#common-documents').innerText()).toBe(common);
  expect(await page.getByRole('region', { name: '실적증빙', exact: true }).innerText()).toBe(performance);
  expect(state.writes).toEqual(['index-new', 'index-old'].map(candidateId => ({ method: 'PUT', path: '/api/submission-cases/71/people/one/documents/PROFILE/selection', body: { candidateId } })));
  expect(state.reads.some(path => path.includes('refresh'))).toBe(false);
});

test('personnel selection: failed write preserves connection; failed recovery locks until server state is known', async ({ page }) => {
  const state = await setup(page);
  const original = structuredClone(state.people);
  state.candidates['one/PROFILE'] = [candidate('snapshot-one-PROFILE', '최효재_PROFILE.pdf'), candidate('index-new', '교체.pdf', true)];
  state.fail = true;
  await candidateButton(page, 'one', '프로필').click();
  await candidateRows(page).last().getByRole('button').click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('인력 저장 실패');
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  await expect(page.getByRole('dialog').getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
  expect(state.people).toEqual(original);
  state.commitFailure = true; state.failRead = true;
  await candidateRows(page).last().getByRole('button').click();
  await expect(page.getByRole('dialog')).toContainText('마지막으로 확인한 연결 상태');
  await expect(candidateRows(page).last().getByRole('button')).toBeDisabled();
  state.failRead = false;
  await page.getByRole('dialog').getByRole('button', { name: '인력 다시 조회' }).click();
  await expect(page.getByRole('dialog').getByRole('region', { name: '현재 연결 파일' })).toContainText('교체.pdf');
  await expect(candidateRows(page).last().getByRole('button')).toHaveText('선택됨');
});

test('personnel selection: duplicate clicks and close are locked during save, then another document stays separate on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page); state.hold = true;
  state.candidates['one/PROFILE'] = [candidate('index-new', '긴파일명'.repeat(25) + '.pdf', true)];
  state.candidates['two/KOSA'] = [candidate('kosa', '다른사람.pdf')];
  await candidateButton(page, 'one', '프로필').click();
  await candidateRows(page).first().getByRole('button').evaluate(button => { button.click(); button.click(); });
  await expect.poll(() => !!state.release).toBe(true);
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('status')).toContainText('저장 중');
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(1);
  state.release();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/personnel-selection-mobile.png', fullPage: true });
  await dialog.getByRole('button', { name: '닫기' }).click();
  await candidateButton(page, 'two', 'KOSA 경력증명서').click();
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('파일 미등록');
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택');
  expect(state.writes).toHaveLength(1);
});

test('personnel clear: FMS disconnect preserves needed, updates all totals and selected marker without file deletion or confirmation', async ({ page }) => {
  const state = await setup(page);
  const other = structuredClone(state.people[1]);
  const needed = state.people.map(person => person.documents.map(doc => doc.needed));
  const common = await page.locator('#common-documents').innerText();
  const performance = await page.getByRole('region', { name: '실적증빙', exact: true }).innerText();
  state.candidates['one/PROFILE'] = [candidate('snapshot-one-PROFILE', '최효재_PROFILE.pdf', true)];
  const dialogs = [];
  page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
  await candidateButton(page, 'one', '프로필').click();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '연결 해제', exact: true }).click();
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('파일 미등록');
  await expect(dialog.getByRole('button', { name: '연결 해제', exact: true })).toHaveCount(0);
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택');
  await expect(page.getByLabel('최효재 준비율')).toHaveText('2 / 4');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true })).toContainText('3 / 7');
  await expect(page.locator('#react-personnel-title')).toContainText('3 / 7');
  await expect(page.locator('.case-progress')).toContainText('5 / 9');
  await dialog.getByRole('button', { name: '닫기' }).click();
  await expect(entry(page, 'one').locator('tbody tr').first()).toContainText('미준비');
  await expect(neededCheckbox(page, 'one', '프로필')).toBeChecked();
  expect(state.people.map(person => person.documents.map(doc => doc.needed))).toEqual(needed);
  expect(state.people[1]).toEqual(other);
  expect(await page.locator('#common-documents').innerText()).toBe(common);
  expect(await page.getByRole('region', { name: '실적증빙', exact: true }).innerText()).toBe(performance);
  expect(dialogs).toEqual([]);
  expect(state.writes).toEqual([{ method: 'PUT', path: '/api/submission-cases/71/people/one/documents/PROFILE/selection', body: { candidateId: null } }]);
  expect(state.reads.some(path => /refresh|delete/.test(path))).toBe(false);
});

test('personnel clear: directly uploaded file can disconnect on mobile, duplicate save and closing are blocked', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page);
  Object.assign(state.people[0].documents[0], { source: 'PC', fmsReferenceId: null, needed: false });
  await page.reload();
  state.hold = true;
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('직접 업로드');
  await dialog.getByRole('button', { name: '연결 해제', exact: true }).evaluate(button => { button.click(); button.click(); });
  await expect.poll(() => !!state.release).toBe(true);
  await expect(dialog.getByRole('button', { name: '연결 해제', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(1);
  await page.screenshot({ path: 'test-results/personnel-clear-mobile.png', fullPage: true });
  state.release();
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('파일 미등록');
  expect(state.people[0].documents[0].needed).toBe(false);
  expect(state.writes).toHaveLength(1);
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await dialog.getByRole('button', { name: '닫기' }).click();
  await expect(neededCheckbox(page, 'one', '프로필')).not.toBeChecked();
});

test('personnel clear: failure preserves existing connection; uncertain state locks then reconciles', async ({ page }) => {
  const state = await setup(page);
  state.candidates['one/PROFILE'] = [candidate('snapshot-one-PROFILE', '최효재_PROFILE.pdf')];
  const original = structuredClone(state.people);
  state.fail = true;
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '연결 해제', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('인력 저장 실패');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  expect(state.people).toEqual(original);
  state.failRead = true; state.commitFailure = true;
  await dialog.getByRole('button', { name: '연결 해제', exact: true }).click();
  await expect(dialog).toContainText('마지막으로 확인한 연결 상태');
  await expect(dialog.getByRole('button', { name: '연결 해제', exact: true })).toBeDisabled();
  await expect(candidateRows(page).first().getByRole('button')).toBeDisabled();
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
  state.failRead = false;
  await dialog.getByRole('button', { name: '인력 다시 조회' }).click();
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('파일 미등록');
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택');
  await expect(candidateRows(page).first().getByRole('button')).toBeEnabled();
  expect(state.people[0].documents[0].needed).toBe(true);
});

const uploadFile = (page, name = '직접파일.pdf', size = 4) => page.getByRole('dialog').getByLabel('업로드 파일').setInputFiles({ name, mimeType: 'application/octet-stream', buffer: Buffer.alloc(size, 65) });
const submitUpload = page => page.getByRole('dialog').getByRole('button', { name: '업로드', exact: true }).click();

test('personnel upload: new upload, PC replacement, FMS replacement preserve needed and other areas', async ({ page }) => {
  const state = await setup(page);
  const other = structuredClone(state.people[1]);
  const common = await page.locator('#common-documents').innerText();
  const performance = await page.getByRole('region', { name: '실적증빙', exact: true }).innerText();
  await candidateButton(page, 'one', '개인정보 영향평가 전문인력 인증서').click();
  await uploadFile(page, '인증서.pdf');
  await expect(page.getByRole('dialog').getByRole('region', { name: 'PC에서 직접 업로드' })).toContainText('인증서.pdf');
  await submitUpload(page);
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('인증서.pdf');
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('직접 업로드');
  await expect(dialog.getByLabel('업로드 파일')).toHaveValue('');
  await expect(page.getByLabel('최효재 준비율')).toHaveText('4 / 4');
  await expect(page.locator('#react-personnel-title')).toContainText('5 / 7');
  await expect(page.getByRole('region', { name: '인력·자격', exact: true })).toContainText('5 / 7');
  await expect(page.locator('.case-progress')).toContainText('7 / 9');
  await uploadFile(page, '교체인증서.hwp'); await submitUpload(page);
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('교체인증서.hwp');
  expect(state.people[0].documents[3].fmsReferenceId).toBeNull();
  await dialog.getByRole('button', { name: '닫기' }).click();
  state.candidates['one/PROFILE'] = [candidate('snapshot-one-PROFILE', '최효재_PROFILE.pdf')];
  await candidateButton(page, 'one', '프로필').click();
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택됨');
  await uploadFile(page, '최효재_PROFILE.pdf'); await submitUpload(page);
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('직접 업로드');
  await expect(candidateRows(page).first().getByRole('button')).toHaveText('선택');
  expect(state.people[0].documents.every(doc => doc.needed)).toBe(true);
  expect(state.people[1]).toEqual(other);
  expect(state.people[0].documents[0].fmsReferenceId).toBeNull();
  expect(await page.locator('#common-documents').innerText()).toBe(common);
  expect(await page.getByRole('region', { name: '실적증빙', exact: true }).innerText()).toBe(performance);
  expect(state.writes.map(write => write.method)).toEqual(['POST', 'POST', 'POST']);
  expect(state.writes.every(write => write.path.endsWith('/upload'))).toBe(true);
  expect(state.uploads.every(upload => upload.contentType.includes('multipart/form-data; boundary=') && upload.content.includes('name="file"'))).toBe(true);
});

test('personnel upload: no file, empty file and over 20MB rejected before request', async ({ page }) => {
  const state = await setup(page);
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await submitUpload(page);
  await expect(dialog.getByRole('alert')).toContainText('비어 있지 않은 20MB 이하');
  await uploadFile(page, '빈파일.pdf', 0); await submitUpload(page);
  await expect(dialog.getByRole('alert')).toContainText('비어 있지 않은 20MB 이하');
  await uploadFile(page, '큰파일.pdf', 20 * 1024 * 1024 + 1); await submitUpload(page);
  await expect(dialog.getByRole('alert')).toContainText('비어 있지 않은 20MB 이하');
  expect(state.writes).toEqual([]);
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
});

test('personnel upload: server validation shown safely, failed upload preserves connection and failed read locks', async ({ page }) => {
  const state = await setup(page);
  const original = structuredClone(state.people);
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  await uploadFile(page);
  for (const [status, message, shown] of [[400, '파일명을 확인하세요.', '파일명을 확인하세요.'], [413, '', '20MB 이하 파일을 선택하세요.'], [500, 'C:/private/storage/secret.pdf token=secret', '파일 업로드에 실패했습니다. 다시 시도해 주세요.']]) {
    state.uploadStatus = status; state.uploadMessage = message;
    await submitUpload(page);
    await expect(dialog.getByRole('alert')).toHaveText(shown);
    await expect(dialog.getByRole('button', { name: '업로드', exact: true })).toBeEnabled();
    await expect(dialog).not.toContainText('private/storage');
    await expect(dialog).not.toContainText('token=secret');
    await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('최효재_PROFILE.pdf');
    expect(state.people).toEqual(original);
  }
  state.failRead = true;
  await submitUpload(page);
  await expect(dialog).toContainText('마지막으로 확인한 연결 상태');
  await expect(dialog.getByRole('button', { name: '업로드', exact: true })).toBeDisabled();
  await expect(dialog.getByLabel('업로드 파일')).toBeDisabled();
  state.failRead = false; state.uploadStatus = 0;
  await dialog.getByRole('button', { name: '인력 다시 조회' }).click();
  await submitUpload(page);
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('직접파일.pdf');
});

test('personnel upload: no overlapping upload/select/clear; index error still allows upload and mobile long filenames', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page); state.hold = true;
  state.people[0].documents[0].needed = false;
  await page.reload();
  state.candidates['one/PROFILE'] = [candidate('other', '다른후보.pdf')];
  await candidateButton(page, 'one', '프로필').click();
  const dialog = page.getByRole('dialog');
  const filename = '긴이름'.repeat(25) + '.pdf';
  await uploadFile(page, filename);
  await dialog.getByRole('button', { name: '업로드', exact: true }).evaluate(button => { button.click(); button.click(); });
  await expect.poll(() => !!state.release).toBe(true);
  await expect(dialog.getByRole('status')).toContainText('파일 업로드 중');
  await expect(dialog.getByLabel('업로드 파일')).toBeDisabled();
  await expect(candidateRows(page).first().getByRole('button')).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '연결 해제' })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '닫기' })).toBeDisabled();
  await page.keyboard.press('Escape'); await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(1);
  state.release(); state.hold = false;
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText(filename);
  expect(state.people[0].documents[0].needed).toBe(false);
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await dialog.getByRole('button', { name: '업로드', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/personnel-upload-mobile.png', fullPage: true });
  await dialog.getByRole('button', { name: '닫기' }).click();
  state.candidateError = true;
  await candidateButton(page, 'one', '프로필').click();
  await expect(dialog.getByRole('alert')).toContainText('후보 인덱스 조회 실패');
  await uploadFile(page, '후보없어도업로드.pdf'); await submitUpload(page);
  await expect(dialog.getByRole('region', { name: '현재 연결 파일' })).toContainText('후보없어도업로드.pdf');
});
