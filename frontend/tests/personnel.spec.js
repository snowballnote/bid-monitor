import { test, expect } from '@playwright/test';
const types = [['PROFILE', '프로필'], ['QUALIFICATION', '자격사본'], ['KOSA', 'KOSA 경력증명서'], ['PIA', '개인정보 영향평가 전문인력 인증서']];
const person = (id, name, ready = 0) => ({ id, name, department: '감리팀', documents: types.map(([type, label], index) => ({ type, label, needed: true, filename: index < ready ? `${name}_${type}.pdf` : null, reference: index < ready ? `snapshot-${id}-${type}` : null })) });
const panel = page => page.locator('#react-personnel');
const entry = (page, id) => panel(page).locator(`[data-person-id="${id}"]`);
async function setup(page, options = {}) {
  const state = { people: options.empty ? [] : [person('one', '최효재', 3), person('two', '김대원', 1)], writes: [], reads: [], fail: false, failRead: false, commitFailure: false, hold: false, release: null, searchFail: false, searchEmpty: false };
  if (!options.empty) state.people[1].documents[3].needed = false;
  const prefix = '/api/submission-cases/71/people';
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname;
    if (req.method() !== 'GET') state.writes.push({ path, method: req.method(), body: req.postData() ? req.postDataJSON() : null });
    else state.reads.push(path + url.search);
    if (path.startsWith(prefix)) {
      if (path.endsWith('/search')) return route.fulfill(state.searchFail ? { status: 503, json: { message: '인력 파일 인덱스 오류' } } : { json: state.searchEmpty ? [] : [{ name: '박민수', department: '감리팀' }, { name: '최효재', department: '감리팀' }] });
      if (req.method() === 'GET') return route.fulfill(state.failRead ? { status: 500, json: { message: '인력 조회 실패' } } : { json: state.people });
      if (state.hold) await new Promise(resolve => { state.release = resolve; });
      if (state.fail && !state.commitFailure) return route.fulfill({ status: 400, json: { message: '인력 저장 실패' } });
      if (req.method() === 'POST') {
        const input = req.postDataJSON();
        if (state.people.some(row => row.name === input.name)) return route.fulfill({ status: 400, json: { message: '이미 추가한 인력입니다.' } });
        const added = person('new', input.name); added.documents.forEach(doc => { doc.needed = false; }); state.people.push(added);
      } else if (req.method() === 'DELETE') state.people = state.people.filter(row => row.id !== path.split('/').at(-1));
      else return route.fulfill({ status: 400 });
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
  await expect(entry(page, 'one').getByRole('checkbox').first()).toBeDisabled();
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
