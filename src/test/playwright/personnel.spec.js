const path = require('node:path');
const { test, expect } = require('@playwright/test');
const types = [['PROFILE', '프로필'], ['QUALIFICATION', '자격사본'], ['KOSA', 'KOSA 경력증명서'], ['PIA', '개인정보 영향평가 전문인력 인증서']];
async function setup(page, { noCandidates = false, candidateError = false, saveError = false, holdSearch = false, searchError = false, searchEmpty = false } = {}) {
    const state = { people: [], writes: [], releaseSearch: null };
    const project = { id: 71, projectName: '2026년 개인정보 영향평가 용역', deadline: '2026-12-31' };
    await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), method = request.method(), pathname = url.pathname;
        if (pathname.startsWith('/api/')) {
            if (method !== 'GET') state.writes.push({ path: pathname, method, body: request.headers()['content-type']?.includes('application/json') ? request.postDataJSON() : null });
            const prefix = '/api/submission-cases/71/people';
            if (pathname.startsWith(prefix)) {
                if (pathname.endsWith('/search')) {
                    if (holdSearch) await new Promise(resolve => state.releaseSearch = resolve);
                    if (searchError) return route.fulfill({ status: 503, json: { message: '인력 파일 인덱스를 확인할 수 없습니다.' } });
                    return route.fulfill({ json: searchEmpty ? [] : [{ name: '김대원', department: '개발팀' }] });
                }
                if (method === 'POST' && pathname === prefix) {
                    const input = request.postDataJSON();
                    expect(input).toEqual({ name: '김대원', department: '개발팀' });
                    state.people.push({ id: 'p1', name: '김대원', department: '개발팀', documents: types.map(([type, label]) => ({ type, label, needed: false, filename: null, latestStatus: '파일 미등록' })) });
                }
                if (pathname.endsWith('/candidates')) return route.fulfill(candidateError ? { status: 503, json: { message: '인덱스 조회 실패 · 직접 업로드 가능' } } : { json: noCandidates ? [] : [
                    { id: 'f-new', filename: '프로필_김대원_20260723.pdf', filenameDate: '2026-07-23', recommended: true, note: '파일명 날짜 기준 최신 후보' },
                    { id: 'f-old', filename: '프로필_김대원_20260314.pdf', filenameDate: '2026-03-14', recommended: false, note: '이전 날짜 후보' }
                ] });
                const match = pathname.match(/documents\/(\w+)(?:\/(selection|upload))?$/);
                if (match && method !== 'GET') {
                    if (saveError) return route.fulfill({ status: 400, json: { message: '저장 실패' } });
                    const doc = state.people[0].documents.find(doc => doc.type === match[1]);
                    if (match[2] === 'upload') Object.assign(doc, { filename: '직접프로필.pdf', source: 'PC', latestStatus: '파일명 날짜 없음' });
                    else if (match[2] === 'selection') {
                        const id = request.postDataJSON().candidateId;
                        Object.assign(doc, { filename: id ? id === 'f-old' ? '프로필_김대원_20260314.pdf' : '프로필_김대원_20260723.pdf' : null, source: id ? 'FMS' : null, latestStatus: id === 'f-old' ? '더 최신 후보 있음' : id ? '파일명 날짜 기준 최신' : '파일 미등록' });
                    } else doc.needed = request.postDataJSON().needed;
                }
                if (method === 'DELETE') state.people = [];
                return route.fulfill({ json: state.people });
            }
            if (pathname === '/api/submission-cases/71') return route.fulfill({ json: project });
            if (pathname === '/api/submission-cases') return route.fulfill({ json: [{ ...project, prepared: 0, total: 0 }] });
            if (pathname.endsWith('/package')) return route.fulfill({ json: { selections: [] } });
            return route.fulfill({ json: [] });
        }
        return route.fulfill({ path: path.join(__dirname, '../../main/resources/static', pathname.endsWith('/') ? pathname + 'index.html' : pathname) });
    });
    await page.goto('/submissions/?caseId=71');
    await expect(page.locator('#personnel-add')).toBeEnabled();
    return state;
}
async function addPerson(page) {
    await page.locator('#personnel-add').click(); await page.getByLabel('이름 검색').fill('김대');
    await page.locator('#personnel-search-form button').click();
    await expect(page.locator('#personnel-search-results')).toContainText('개발팀');
    await page.locator('#personnel-search-results button').click();
    await expect(page.locator('#personnel-search-dialog')).not.toBeVisible();
    await page.locator('.personnel-person summary').click();
}
const profile = page => page.locator('.personnel-document').filter({ has: page.getByText('프로필', { exact: true }) });
test('personnel: named collection, explicit older selection, uncheck retention, change and clear', async ({ page }) => {
    const state = await setup(page); await addPerson(page);
    await profile(page).getByRole('checkbox').check();
    await expect(page.locator('#progress-caption')).toHaveText('0 / 1');
    await profile(page).getByRole('button', { name: '파일 선택' }).click();
    await expect(page.locator('#personnel-file-candidates .personnel-candidate')).toHaveCount(2);
    await expect(page.locator('#personnel-file-save')).toBeDisabled();
    expect(state.writes.filter(write => write.path.endsWith('/selection'))).toHaveLength(0);
    await page.locator('.personnel-candidate').last().getByRole('radio').check();
    await page.locator('#personnel-file-save').click();
    await expect(profile(page)).toContainText('20260314.pdf');
    await expect(profile(page)).toContainText('더 최신 후보 있음');
    await expect(page.locator('#progress-caption')).toHaveText('1 / 1');
    await profile(page).getByRole('checkbox').uncheck();
    await expect(page.locator('#progress-caption')).toHaveText('0 / 0');
    await profile(page).getByRole('checkbox').check();
    await expect(profile(page)).toContainText('20260314.pdf');
    await page.reload(); await page.locator('.personnel-person summary').click();
    await expect(profile(page)).toContainText('20260314.pdf');
    await profile(page).getByRole('button', { name: '파일 변경' }).click();
    await page.locator('.personnel-candidate').first().getByRole('radio').check(); await page.locator('#personnel-file-save').click();
    await expect(profile(page)).toContainText('20260723.pdf');
    await profile(page).getByRole('button', { name: '파일 변경' }).click(); await page.locator('#personnel-file-clear').click();
    await expect(profile(page)).toContainText('파일 미등록');
    expect(state.writes.every(write => write.path.includes('/people'))).toBe(true);
});
test('personnel: no candidate permits direct upload and mobile layout', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setup(page, { noCandidates: true }); await addPerson(page);
    await profile(page).getByRole('checkbox').check(); await profile(page).getByRole('button', { name: '파일 선택' }).click();
    await expect(page.locator('#personnel-file-message')).toContainText('후보가 없습니다');
    await page.locator('#personnel-upload-form input').setInputFiles({ name: '직접프로필.pdf', mimeType: 'application/pdf', buffer: Buffer.from('content') });
    await page.locator('#personnel-upload-form button').click();
    await expect(profile(page)).toContainText('직접 업로드');
    await expect(page.locator('#download-submission-files')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: 'artifacts/screenshots/personnel-mobile.png', fullPage: true });
});
test('personnel: failed save restores checkbox and reports the error', async ({ page }) => {
    await setup(page, { saveError: true }); await addPerson(page);
    await profile(page).getByRole('checkbox').click();
    await expect(page.locator('#personnel-message')).toHaveText('저장 실패');
    await expect(profile(page).getByRole('checkbox')).not.toBeChecked();
});
test('personnel: index failure keeps PC upload available', async ({ page }) => {
    await setup(page, { candidateError: true }); await addPerson(page);
    await profile(page).getByRole('checkbox').check(); await profile(page).getByRole('button', { name: '파일 선택' }).click();
    await expect(page.locator('#personnel-file-message')).toContainText('인덱스 조회 실패');
    await expect(page.locator('#personnel-upload-form input')).toBeEnabled();
    await page.screenshot({ path: 'artifacts/screenshots/personnel-files.png', fullPage: true });
});
for (const searchError of [false, true]) {
    test(`personnel: index search ${searchError ? 'failure' : 'empty'} does not add arbitrary people`, async ({ page }) => {
        const state = await setup(page, { searchError, searchEmpty: true });
        await page.locator('#personnel-add').click();
        await page.getByLabel('이름 검색').fill('김대'); await page.locator('#personnel-search-form button').click();
        await expect(page.locator('#personnel-search-message')).toContainText(searchError ? '인력 파일 인덱스를 확인할 수 없습니다' : '인력 파일 인덱스에 일치하는 이름이 없습니다');
        await expect(page.locator('#personnel-search-results button')).toHaveCount(0);
        await expect(page.locator('#personnel-manual-form')).toHaveCount(0);
        expect(state.writes).toEqual([]);
    });
}
