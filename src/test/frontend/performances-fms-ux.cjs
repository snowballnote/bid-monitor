const { chromium, expect } = require('@playwright/test');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve('src/main/resources/static');
const server = http.createServer((req, res) => {
    let pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/submissions/') pathname += 'index.html';
    const file = path.resolve(root, '.' + pathname);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
        res.writeHead(404); res.end(); return;
    }
    const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };
    res.setHeader('Content-Type', types[path.extname(file)] || 'text/plain');
    res.end(fs.readFileSync(file));
});
const project = { id: 'p1', name: '제안 프로젝트', deadline: '2026-12-31', daysRemaining: 20, status: 'COLLECTING' };
const ordinary = { id: 1, documentName: '사업자등록증', category: 'COMPANY_GENERAL', sourceReference: 'BUSINESS_REGISTRATION', performanceSelectionRequired: false };
const performance = { id: 2, documentName: '실적증명서', category: 'PERFORMANCE', sourceReference: 'PERFORMANCE', performanceSelectionRequired: true };
function entries(count, done) {
    return Array.from({ length: count }, (_, i) => ({
        id: 'e' + i, projectId: 'p1', resolvedStatus: 'COMPLETED', selectedFilename: i < done ? '증빙.pdf' : null,
        info: { pptNumber: String(i + 1), businessName: '실적 사업 ' + (i + 1), businessPeriod: '2024.01 ~ 2025.12',
            contractAmount: '100', client: '발주처', businessStatus: null, selectedFileId: null, selectedDriveFileId: i < done ? 'drive-7' : null,
            evidenceType: i < done ? 'CERTIFICATE' : null, kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null }
    }));
}
const tests = [];
function test(name, fn) { tests.push({ name, fn }); }
test('Unsearched cards have no numeric ID or editable evidence type', async ({ page, fixture }) => {
    fixture.entries = entries(1, 0);
    await page.goto('/performances/index.html?project=p1');
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 미검색');
    await expect(page.locator('[name=selectedFileId]')).toHaveCount(0);
    await expect(page.locator('select[name=evidenceType]')).toHaveCount(0);
    await expect(page.locator('[name=requestedAt]')).toBeHidden();
    await expect(page.locator('[name=repliedAt]')).toBeHidden();
});
test('FMS recommendation requires explicit choice and save; type is automatic', async ({ page, fixture }) => {
    fixture.entries = entries(1, 0);
    fixture.recommendations = [{file: {driveFileId: 'drive-7', originalFilename: '실적증명서.pdf'}, evidenceType: 'CERTIFICATE', reason: '일치'}];
    await page.goto('/performances/index.html?project=p1');
    await page.getByRole('button', {name: '저장된 실적으로 후보 추천'}).click();
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 후보 있음');
    await expect(page.locator('[name=selectedDriveFileId]')).toHaveValue('');
    await page.locator('.candidates button').click();
    await expect(page.locator('[name=evidenceType]')).toHaveValue('CERTIFICATE');
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 후보 있음');
    assert(!fixture.requests.some(r => r.method !== 'GET'));
    await page.locator('.entry-form button[type=submit]').click();
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 선택 완료');
    await expect(page.locator('.performance-entry')).toContainText('저장된 파일: 증빙.pdf');
    await expect(page.locator('#entry-summary')).toContainText('1건 처리');
});
test('Empty successful search means KITC; failed search shows an error', async ({ page, fixture, context }) => {
    fixture.entries = entries(1, 0);
    await page.goto('/performances/index.html?project=p1');
    await page.getByRole('button', {name: '저장된 실적으로 후보 추천'}).click();
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · KITC 요청 필요');
    await page.reload();
    await context.route('**/candidates', route => route.fulfill({status:503,json:{message:'FMS 실패'}}));
    await page.getByRole('button', {name: '저장된 실적으로 후보 추천'}).click();
    await expect(page.locator('#message')).toHaveText('FMS 실패');
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 검색 오류');
});
test('Contract selection assigns CONTRACT and failed save cannot mark selected', async ({ page, fixture }) => {
    fixture.entries = entries(1, 0);
    fixture.recommendations = [{file: {driveFileId: 'contract-7', originalFilename: '계약서.pdf'}, evidenceType: 'CONTRACT', reason: '일치'}];
    fixture.failSave = true;
    await page.goto('/performances/index.html?project=p1');
    await page.getByRole('button', {name: '저장된 실적으로 후보 추천'}).click();
    await page.locator('.candidates button').click();
    await expect(page.locator('[name=evidenceType]')).toHaveValue('CONTRACT');
    await page.locator('.entry-form button[type=submit]').click();
    await expect(page.locator('#message')).toContainText('저장 실패');
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 후보 있음');
});
test('KITC dates follow status and hidden dates are excluded from saved data', async ({ page, fixture }) => {
    fixture.entries = entries(1, 0);
    await page.goto('/performances/index.html?project=p1');
    await page.locator('[name=kitcStatus]').selectOption('REQUESTED');
    await expect(page.locator('[name=requestedAt]')).toBeVisible();
    await expect(page.locator('[name=repliedAt]')).toBeHidden();
    await page.locator('[name=requestedAt]').fill('2026-09-01');
    await page.locator('[name=kitcStatus]').selectOption('RECEIVED');
    await expect(page.locator('[name=repliedAt]')).toBeVisible();
    await page.locator('[name=repliedAt]').fill('2026-09-02');
    await page.locator('[name=kitcStatus]').selectOption('NEEDED');
    await expect(page.locator('[name=requestedAt]')).toBeHidden();
    await expect(page.locator('[name=repliedAt]')).toBeHidden();
    await page.locator('.entry-form button[type=submit]').click();
    await expect(page.locator('#message')).toHaveText('저장했습니다.');
    const saved = fixture.requests.find(r => r.method === 'PUT');
    assert.equal(saved.body.requestedAt, null);
    assert.equal(saved.body.repliedAt, null);
});
test('Saved FMS selection can be cleared and never falls back to numeric candidates', async ({ page, fixture }) => {
    fixture.entries = entries(1, 1);
    fixture.recommendations = [{file: {fileId: 7, originalFilename: 'legacy.pdf'}, evidenceType:'CERTIFICATE', reason:'legacy'}];
    await page.goto('/performances/index.html?project=p1');
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 선택 완료');
    await page.getByRole('button', {name:'파일 연결 해제', exact:true}).click();
    await expect(page.locator('[name=evidenceType]')).toHaveValue('');
    await page.locator('.entry-form button[type=submit]').click();
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · 미검색');
    await page.getByRole('button', {name:'저장된 실적으로 후보 추천'}).click();
    await expect(page.locator('.candidates button')).toHaveCount(0);
});
test('Candidate click sends exactly one GET for the clicked card even with invalid unsaved fields', async ({ page, fixture }) => {
    fixture.entries = entries(2, 0);
    fixture.recommendations = [{file: {driveFileId: 'drive-2', originalFilename: '두번째 실적증명서.pdf'}, evidenceType: 'CERTIFICATE', reason: '일치'}];
    await page.goto('/performances/index.html?project=p1');
    const card = page.locator('.performance-entry').nth(1);
    await card.locator('[name=businessName]').fill('');
    await card.locator('[name=kitcStatus]').selectOption('REQUESTED');
    const button = card.getByRole('button', {name:'저장된 실적으로 후보 추천'});
    assert(await button.evaluate(element => typeof element.onclick === 'function'));
    await expect(button).toHaveAttribute('type', 'button');
    const request = page.waitForRequest(req => new URL(req.url()).pathname === '/api/performance-projects/p1/entries/e1/candidates');
    await button.click();
    assert.equal((await request).method(), 'GET');
    await expect(card.locator('.candidates button')).toHaveText('두번째 실적증명서.pdf · 일치');
    await expect(page.locator('.performance-entry').first().locator('.candidates button')).toHaveCount(0);
    assert.equal(fixture.requests.filter(r => r.url.endsWith('/candidates')).length, 1);
    assert(!fixture.requests.some(r => r.method !== 'GET'));
    await expect(button).toBeEnabled();
});
test('Candidate click remains connected after save replaces the card and after an API error', async ({ page, fixture, context }) => {
    fixture.entries = entries(1, 0);
    await page.goto('/performances/index.html?project=p1');
    await page.locator('.entry-form button[type=submit]').click();
    await expect(page.locator('#message')).toHaveText('저장했습니다.');
    const endpoint = '**/api/performance-projects/p1/entries/e0/candidates';
    await context.route(endpoint, route => route.fulfill({status:503,json:{message:'검색 오류'}}));
    const button = page.getByRole('button', {name:'저장된 실적으로 후보 추천'});
    const failed = page.waitForRequest(req => req.url().endsWith('/entries/e0/candidates'));
    await button.click();
    assert.equal((await failed).method(), 'GET');
    await expect(page.locator('#message')).toHaveText('검색 오류');
    await expect(button).toBeEnabled();
    await context.unroute(endpoint);
    fixture.recommendations = [{file:{driveFileId:'retry',originalFilename:'재시도 계약서.pdf'},evidenceType:'CONTRACT',reason:'일치'}];
    const retried = page.waitForRequest(req => req.url().endsWith('/entries/e0/candidates'));
    await button.click();
    assert.equal((await retried).method(), 'GET');
    await expect(page.locator('.candidates button')).toHaveText('재시도 계약서.pdf · 일치');
});
test('Import automatically searches every valid row; one failure cannot block sample candidates or empty results', async ({ page, fixture, context }) => {
    fixture.entries = [];
    fixture.imported = entries(3, 0);
    fixture.imported[1].info.businessName = 'AI콜봇구축사업 개인정보 영향평가';
    fixture.imported[1].info.client = '한국장학재단';
    fixture.importErrors = [{row:4,cells:['4'],message:'사업명 확인'}];
    const sample = '154_260515_실적증명서(한국장학재단) AI콜봇 구축 사업 개인정보 영향평가.pdf';
    const calls = [];
    await context.route('**/candidates', async route => {
        const url = new URL(route.request().url()).pathname;
        calls.push(url);
        if (url.includes('/e0/')) return route.fulfill({status:503,json:{message:'실적증명서 검색 폴더를 설정하세요.'}});
        await route.fulfill({json: {candidates: url.includes('/e1/') ? [
            {file:{driveFileId:'sample',originalFilename:sample},evidenceType:'CERTIFICATE',reason:'사업명·발주처 일치'}
        ] : [], nextAction:'후보 확인'}});
    });
    await page.goto('/performances/index.html?project=p1');
    await page.locator('#paste-table').fill('표');
    await page.locator('#import').click();
    const cards = page.locator('.performance-entry');
    await expect(cards.nth(0).locator('.entry-evidence-status')).toHaveText('증빙 상태 · 검색 오류');
    await expect(cards.nth(1).locator('.candidates button')).toContainText(sample);
    await expect(cards.nth(2).locator('.entry-evidence-status')).toHaveText('증빙 상태 · KITC 요청 필요');
    assert.deepEqual(calls, [0,1,2].map(i => '/api/performance-projects/p1/entries/e' + i + '/candidates'));
    await expect(page.locator('#errors')).toContainText('사업명 확인');
    await expect(cards.nth(1).locator('[name=selectedDriveFileId]')).toHaveValue('');
    assert(!fixture.requests.some(r => r.method === 'PUT'));
});
test('Queued imported rows remain unsearched until started; malformed response is an error', async ({ page, fixture, context }) => {
    fixture.entries = [];
    fixture.imported = entries(2, 0);
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await context.route('**/candidates', async route => {
        if (route.request().url().includes('/e0/')) await gate;
        await route.fulfill({json:{}});
    });
    await page.goto('/performances/index.html?project=p1');
    await page.locator('#paste-table').fill('표');
    await page.locator('#import').click();
    const cards = page.locator('.performance-entry');
    try {
        await expect(cards.nth(0).locator('.entry-evidence-status')).toHaveText('증빙 상태 · 검색 중');
        await expect(cards.nth(1).locator('.entry-evidence-status')).toHaveText('증빙 상태 · 미검색');
    } finally { release(); }
    await expect(cards.nth(0).locator('.entry-evidence-status')).toHaveText('증빙 상태 · 검색 오류');
    await expect(cards.nth(1).locator('.entry-evidence-status')).toHaveText('증빙 상태 · 검색 오류');
});
test('Corrected import error row also starts candidate search automatically', async ({ page, fixture }) => {
    fixture.entries = [];
    fixture.imported = [];
    fixture.importErrors = [{row:1,cells:['1','사업','2024.01 ~ 2025.12','100','발주처'],message:'확인'}];
    await page.goto('/performances/index.html?project=p1');
    await page.locator('#paste-table').fill('표');
    await page.locator('#import').click();
    await expect(page.locator('#errors .error-row')).toHaveCount(1);
    fixture.imported = entries(1,0);
    fixture.importErrors = [];
    await page.locator('#errors button[type=submit]').click();
    await expect(page.locator('.entry-evidence-status')).toHaveText('증빙 상태 · KITC 요청 필요');
    assert.equal(fixture.requests.filter(r => r.url.endsWith('/e0/candidates')).length,1);
});
test('Index management displays status/count/time and refreshes explicitly without exposing locators', async ({ page, context }) => {
    await context.route('**/api/drive-index', route => route.fulfill({json:[
        {label:'검색 범위 1',state:{status:'NOT_BUILT',fileCount:0,lastSuccessAt:null}}
    ]}));
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await context.route('**/api/drive-index/refresh', async route => {
        assert.equal(route.request().method(),'POST');
        await gate;
        await route.fulfill({json:[
            {label:'검색 범위 1',state:{status:'SUCCESS',fileCount:19,lastSuccessAt:'2026-09-08T01:00:00Z'}},
            {label:'검색 범위 2',state:{status:'FAILED',fileCount:2,lastSuccessAt:null}}
        ]});
    });
    await page.goto('/performances/index.html?project=p1');
    await expect(page.locator('#drive-index-status')).toContainText('미구축');
    await page.locator('#refresh-drive-index').click();
    try {
        await expect(page.locator('#drive-index-status')).toHaveText('갱신 중…');
        await expect(page.locator('#refresh-drive-index')).toBeDisabled();
    } finally { release(); }
    await expect(page.locator('#drive-index-status')).toContainText('파일 19개');
    await expect(page.locator('#drive-index-status')).toContainText('성공');
    await expect(page.locator('#drive-index-status')).toContainText('실패');
    await expect(page.locator('#refresh-drive-index')).toBeEnabled();
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const baseURL = 'http://127.0.0.1:' + server.address().port;
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    let failures = 0;
    try {
        for (const item of tests) {
            const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1050 } });
            const fixture = { project: { ...project }, entries: entries(19, 17), failEntries: false, requests: [],
                selections: [{ requirementId: 1, fileId: 101, originalFilename: '사업자등록증.pdf' }] };
            const errors = [];
            const page = await context.newPage();
            page.on('pageerror', error => errors.push(error.message));
            await context.route('**/api/**', async route => {
                const req = route.request();
                const url = new URL(req.url()).pathname;
                const method = req.method();
                const body = req.postDataJSON();
                fixture.requests.push({ url, method, body });
                let result;
                if (url === '/api/drive-index' || url === '/api/drive-index/refresh') result = [];
                else if (url === '/api/submission-common-documents') result = [];
                else if (url === '/api/performance-projects') {
                    if (method === 'POST') Object.assign(fixture.project, body);
                    result = method === 'POST' ? fixture.project : [fixture.project];
                } else if (url === '/api/performance-projects/p1') {
                    if (method === 'PUT') Object.assign(fixture.project, body);
                    result = fixture.project;
                } else if (url === '/api/performance-projects/p1/entries') {
                    if (fixture.failEntries) { await route.fulfill({ status: 503, json: { message: 'unavailable' } }); return; }
                    result = fixture.entries;
                } else if (url === '/api/performance-projects/p1/import') {
                    const saved = fixture.imported || entries(1, 0); fixture.entries.push(...saved);
                    result = { saved, errors: fixture.importErrors || [] };
                } else if (url.startsWith('/api/performance-projects/p1/entries/') && method === 'PUT') {
                    if (fixture.failSave) { await route.fulfill({ status: 503, json: { message: '저장 실패' } }); return; }
                    const entry = fixture.entries.find(e => e.id === url.split('/').pop());
                    entry.info = { ...body }; entry.selectedFilename = (body.selectedFileId || body.selectedDriveFileId) ? '증빙.pdf' : null;
                    result = entry;
                } else if (url.startsWith('/api/performance-projects/') && url.endsWith('/candidates')) {
                    result = { candidates: fixture.recommendations || [], nextAction: '후보 확인' };
                } else if (/\/requirements$/.test(url)) result = [ordinary, performance];
                else if (/\/package$/.test(url)) result = { selections: fixture.selections };
                else if (/\/candidates$/.test(url)) result = [{ fileId: 101, originalFilename: '사업자등록증.pdf', fileExt: 'pdf', matchLevel: 'EXACT' }];
                else if (/\/selections$/.test(url)) {
                    fixture.selections = body.selections.map(e => ({ ...e, originalFilename: '사업자등록증.pdf' }));
                    result = fixture.selections;
                } else if (url.startsWith('/api/submission-cases')) result = { id: url.endsWith('/42') ? 42 : 41 };
                else { await route.fulfill({ status: 404, json: {} }); return; }
                await route.fulfill({ status: 200, json: result });
            });
            try {
                await item.fn({ page, fixture, context });
                assert.deepEqual(errors, []);
                console.log('PASS ' + item.name);
                if (item.name.startsWith('17 of')) {
                    await page.screenshot({ path: 'target/submission-performance-ui.png', fullPage: true });
                }
                if (item.name.startsWith('App Shell')) {
                    await page.goto('/performances/index.html?project=p1');
                    await expect(page.locator('#workspace')).toBeVisible();
                    await page.screenshot({ path: 'target/performance-ui-desktop.png', fullPage: false });
                }
            } catch (error) {
                failures++;
                console.error('FAIL ' + item.name + '\n' + error.stack);
                await page.screenshot({ path: 'target/performance-ui-failure-' + failures + '.png', fullPage: false });
            } finally { await context.close(); }
        }
    } finally { await browser.close(); server.close(); }
    console.log('RESULT: ' + (tests.length - failures) + '/' + tests.length + ' passed');
    process.exitCode = failures ? 1 : 0;
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });