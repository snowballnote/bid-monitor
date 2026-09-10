const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');

async function setup(page, options = {}) {
    const state = {
        project: { id: 71, projectName: '제출 프로젝트', deadline: '2026-09-15', performanceProjectId: 'perf-1', performanceLinkInitialized: true },
        requirements: [
            { id: 81, category: 'COMPANY_GENERAL', documentName: '사업자등록증', sourceReference: 'BUSINESS', companyCommon: true },
            { id: 82, category: 'FINANCIAL', documentName: '재무제표', sourceReference: 'FINANCIAL', companyCommon: true },
            { id: 83, category: 'PERSONNEL', documentName: '참여인력', sourceReference: 'PERSONNEL' },
            { id: 84, category: 'PERFORMANCE', documentName: '실적증명서', sourceReference: 'PERFORMANCE', performanceSelectionRequired: true },
            { id: 85, category: 'OTHER', documentName: '보존된 서류', sourceReference: 'CUSTOM_7' }
        ],
        selections: [
            { requirementId: 81, fileId: 901, uploadedFileId: 'upload-1', originalFilename: '사업자등록증.pdf' },
            { requirementId: 83, fileId: 903, originalFilename: '참여인력.pdf' }
        ],
        writes: [], activeSaves: 0, maxSaves: 0, entries: [{ info: { selectedFileId: 11 } }, { info: {} }], deleted: false
    };
    if (options.empty) { state.requirements = []; state.selections = []; }
    if (options.unlinked) state.project.performanceProjectId = null;
    if (options.uploadedPerformance) state.entries = [{ info: { selectedUploadedFileId: 'local-1' } }, { info: { selectedDriveFileId: 'drive-1' } }];
    const masters = state.requirements.filter(item => item.id !== 85).map(item => ({
        id: item.id, name: item.documentName, sourceReference: item.sourceReference,
        requirementCategory: item.category, category: item.companyCommon ? 'COMPANY_COMMON' : item.category,
        currentFileId: item.companyCommon ? 'registered' : null
    }));
    masters.push({ id: 86, name: '미선택 서류', category: 'OTHER', requirementCategory: 'OTHER', sourceReference: 'EXTRA' });
    const candidates = [{ fileId: 905, originalFilename: '연결할파일.pdf', fileExt: 'pdf', matchLevel: 'EXACT', matchReasons: ['서류명 일치'] }];
    await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), method = request.method(), pathname = url.pathname;
        if (pathname.startsWith('/api/')) {
            if (method !== 'GET') state.writes.push({ method, path: pathname, body: request.postData() ? request.postDataJSON() : null, query: url.search });
            if (pathname === '/api/submission-document-masters') return route.fulfill({ json: masters });
            if (pathname === '/api/submission-common-documents') return route.fulfill({ json: [] });
            if (pathname === '/api/performance-projects') return route.fulfill({ json: [{ id: 'perf-1' }] });
            if (pathname.endsWith('/entries')) return route.fulfill({ json: state.entries });
            if (pathname === '/api/submission-cases') {
                if (method === 'POST') { Object.assign(state.project, request.postDataJSON());state.deleted = false; }
                return route.fulfill({ json: method === 'GET' ? state.deleted ? [] : [{ ...state.project, prepared: 2, total: state.requirements.length }] : state.project });
            }
            if (pathname === '/api/submission-cases/71') {
                if (method === 'PUT') {
                    if (options.editError) return route.fulfill({ status: 400, json: { message: '프로젝트 저장 실패' } });
                    Object.assign(state.project, request.postDataJSON());
                }
                if (method === 'DELETE') state.deleted = true;
                return route.fulfill({ json: state.project });
            }
            if (pathname.endsWith('/requirements')) return route.fulfill({ json: state.requirements });
            if (pathname.endsWith('/package')) return route.fulfill({ json: { selections: state.selections } });
            if (pathname.endsWith('/candidates')) return route.fulfill(options.candidateError ? { status: 503, json: {} } : { json: options.noCandidates ? [] : candidates });
            if (pathname.endsWith('/selections')) {
                if(options.holdSelection) await new Promise(resolve=>state.releaseSelection=resolve);
                state.selections = request.postDataJSON().selections.map(choice => ({ ...choice, ...(state.selections.find(item => item.fileId === choice.fileId) || candidates.find(item => item.fileId === choice.fileId)) }));
                return route.fulfill({ json: {} });
            }
            if (pathname.endsWith('/collect')) {
                state.activeSaves++;state.maxSaves=Math.max(state.maxSaves,state.activeSaves);
                if (options.holdFirstSave && !state.releaseSave) await new Promise(resolve=>state.releaseSave=resolve);
                if (options.delay) await new Promise(resolve=>setTimeout(resolve,options.delay));
                state.activeSaves--;
                if (options.saveError) return route.fulfill({status:400,json:{message:'체크 저장 실패'}});
                state.requirements = request.postDataJSON().map((item, index) => state.requirements.find(saved => saved.category === item.category && saved.documentName === item.documentName) || { ...item, id: 100 + index });
                state.selections = state.selections.filter(item => state.requirements.some(requirement => requirement.id === item.requirementId));
                if (!options.missingFile && state.requirements.some(item => item.id === 82) && !state.selections.some(item=>item.requirementId===82)) state.selections.push({ requirementId: 82, uploadedFileId: 'upload-2', originalFilename: '재무제표.pdf' });
                return route.fulfill({ json: { missingRequirementIds: [] } });
            }
            if (pathname.endsWith('/performance-project')) { state.project.performanceProjectId = 'perf-1';return route.fulfill({ json: state.project }); }
            return route.fulfill({ status: 404, json: {} });
        }
        if (pathname.startsWith('/performances/')) return route.fulfill({ contentType: 'text/html', body: '<h1>실적 관리</h1>' });
        const file = path.join(__dirname, '../../main/resources/static', pathname.endsWith('/') ? pathname + 'index.html' : pathname);
        return fs.existsSync(file) ? route.fulfill({ path: file }) : route.fulfill({ status: 404, body: '' });
    });
    await page.goto('/submissions/?caseId=71');
    await expect(page.locator('#submission-workspace')).toBeVisible();
    return state;
}
const row = (page, id) => page.locator(`[data-requirement-id="${id}"]`);

test('submissions: summary, category progress and selected-only table reuse saved data', async ({ page }, testInfo) => {
    await page.clock.install({ time: new Date('2026-09-10T12:00:00+09:00') });
    await setup(page);
    await expect(page.locator('#case-project-name')).toHaveText('제출 프로젝트');
    await expect(page.locator('#case-project-meta')).toHaveText('발주기관 미등록');
    await expect(page.locator('#case-project-dday')).toHaveText('D-5');
    await expect(page.locator('#progress-caption')).toHaveText('2 / 5');
    await expect(page.getByRole('progressbar', { name: '전체 준비율', exact: true })).toHaveAttribute('aria-valuenow', '40');
    await expect(page.locator('.category-progress-card strong')).toHaveText(['1 / 2', '1 / 1', '0 / 1', '0 / 1']);
    await expect(page.getByRole('columnheader')).toHaveText(['서류명', '구분', '상태', '연결 파일', '작업']);
    await expect(page.locator('#requirement-list tr')).toHaveCount(5);
    await expect(row(page, 81)).toContainText('준비 완료');
    await expect(row(page, 82)).toContainText('파일 미등록');
    await expect(row(page, 83)).toContainText('선택 완료');
    await expect(row(page, 84)).toContainText('1 / 2 준비');
    await expect(row(page, 84).getByRole('button', { name: '관리', exact: true })).toBeVisible();
    await expect(page.locator('.package-panel, #candidate-performance, .performance-picker-action')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'ZIP 다운로드' })).toHaveAttribute('href', '/api/submission-cases/71/download');
    const left = await page.locator('#document-picker').boundingBox(), right = await page.locator('.requirement-panel').boundingBox();
    expect(right.x).toBeGreaterThan(left.x + left.width);
    await page.screenshot({ path: testInfo.outputPath("detail-desktop.png"), fullPage: true });
});

test('submissions: checklist collapse and collect preserve requirements and selections', async ({ page }) => {
    const state = await setup(page);
    await expect(page.getByRole('checkbox', { name: '보존된 서류', exact: true })).toBeChecked();
    const group = page.locator('details').filter({ has: page.locator('summary', { hasText: '회사 공통' }) });
    await group.locator('summary').click();
    await expect(group).not.toHaveAttribute('open', '');
    await group.locator('summary').click();
    await expect(page.getByRole('checkbox', { name: '사업자등록증', exact: true })).toBeChecked();
    await page.getByRole('checkbox', { name: '미선택 서류', exact: true }).check();
    await expect(page.locator('#document-message')).toHaveText('저장됨');
    await expect(page.locator('#requirement-list tr')).toHaveCount(6);
    const collect = state.writes.find(item => item.path.endsWith('/collect'));
    expect(collect.query).toBe('?preserveExistingSelections=true');
    expect(collect.body.find(item => item.documentName === '보존된 서류')).toEqual({ category: 'OTHER', documentName: '보존된 서류', sourceReference: 'CUSTOM_7' });
    expect(state.selections.find(item => item.requirementId === 81).fileId).toBe(901);
    await expect(row(page, 82)).toContainText('재무제표.pdf');
    await expect(page.getByRole('checkbox', { name: '미선택 서류', exact: true })).toBeChecked();
    await expect(page.locator('#progress-caption')).toHaveText('3 / 6');
});

test('submissions: candidate modal keeps selection and updates progress', async ({ page }) => {
    const state = await setup(page);
    await row(page, 85).getByRole('button', { name: '파일 연결' }).click();
    const dialog = page.getByRole('dialog', { name: '보존된 서류' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: '이 파일 선택' }).click();
    await expect(row(page, 85)).toContainText('연결할파일.pdf');
    await expect(page.locator('#progress-caption')).toHaveText('3 / 5');
    expect(state.writes.find(item => item.path.endsWith('/selections')).body.selections).toContainEqual({ requirementId: 81, fileId: 901 });
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(row(page, 85).getByRole('button', { name: '파일 연결' })).toBeFocused();
});

test('submissions: project edit, list/card switch, history, create and delete', async ({ page }) => {
    const state = await setup(page);
    await page.getByRole('button', { name: '프로젝트 목록', exact: true }).click();
    await page.getByRole('button', { name: / 메뉴$/ }).click();
    await page.getByRole('menuitem', { name: '수정', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '프로젝트 수정' });
    await dialog.getByLabel('프로젝트명').fill('변경 프로젝트');
    await dialog.getByRole('button', { name: '프로젝트 저장' }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.locator('.submission-project-card')).toContainText('변경 프로젝트');
    expect(state.requirements).toHaveLength(5);
    await expect(page).toHaveURL(/\/submissions\/$/);
    await expect(page.locator('#submission-project-list')).toBeVisible();
    await page.getByRole('button', { name: '리스트형' }).click();
    await page.getByRole('link', { name: '변경 프로젝트 상세', exact: true }).click();
    await expect(page.locator('#submission-workspace')).toBeVisible();
    await page.getByRole('button', { name: '프로젝트 목록', exact: true }).click();
    await page.getByRole('button', { name: '카드형' }).click();
    await page.locator('.submission-project-card h3').click();
    await page.goBack();
    await page.getByRole('button', { name: / 메뉴$/ }).click();
    page.once('dialog', prompt => prompt.accept());
    await page.getByRole('menuitem', { name: '삭제', exact: true }).click();
    await expect(page.locator('#submission-project-message')).toContainText('등록된 프로젝트가 없습니다.');
    await page.getByRole('button', { name: '+ 새 프로젝트' }).click();
    await page.locator('#create-submission-project').getByLabel('프로젝트명').fill('새 프로젝트');
    await page.locator('#create-submission-project').getByLabel('마감일').fill('2026-09-30');
    await page.getByRole('button', { name: '프로젝트 생성', exact: true }).click();
    await expect(page.locator('#case-project-name')).toHaveText('새 프로젝트');
});

test('submissions: uploaded performance files count in preparation progress', async ({page}) => {
    await setup(page, {uploadedPerformance:true});
    await expect(row(page,84)).toContainText('2 / 2 준비');
    await expect(page.locator('#progress-caption')).toHaveText('3 / 5');
});

test('submissions: performance management links project and preserves return case', async ({ page }) => {
    const state = await setup(page, { unlinked: true });
    await row(page, 84).getByRole('button', { name: '관리', exact: true }).click();
    await expect(page).toHaveURL(/\/performances\/index.html\?caseId=71&project=perf-1/);
    expect(state.writes.some(item => item.path.endsWith('/performance-project') && item.method === 'POST')).toBe(true);
    await page.goBack();
    await expect(row(page, 84)).toContainText('1 / 2 준비');
    state.entries[1].info.selectedDriveFileId = 'drive-file';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(row(page, 84)).toContainText('2 / 2 준비');
    await expect(page.locator('#progress-caption')).toHaveText('3 / 5');
});

test('submissions: candidate and edit failures stay visible in their dialogs', async ({ page }) => {
    await setup(page, { candidateError: true, editError: true });
    await expect(row(page, 85)).toContainText('조회 실패');
    await row(page, 85).getByRole('button', { name: '파일 연결' }).click();
    await expect(page.locator('#candidate-error')).toContainText('회사 DB에 연결할 수 없습니다.');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '프로젝트 목록', exact: true }).click();
    await page.getByRole('button', { name: / 메뉴$/ }).click();
    await page.getByRole('menuitem', { name: '수정', exact: true }).click();
    await page.getByRole('button', { name: '프로젝트 저장' }).click();
    await expect(page.locator('#project-edit-message')).toHaveText('프로젝트 저장 실패');
    await expect(page.getByRole('dialog', { name: '프로젝트 수정' })).toBeVisible();
});

test('submissions: empty and mobile detail stay usable without page overflow', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setup(page, { empty: true });
    await expect(page.locator('#requirement-empty')).toBeVisible();
    await expect(page.locator('.category-progress-card strong')).toHaveText(['0 / 0', '0 / 0', '0 / 0', '0 / 0']);
    await expect(page.locator('#progress-caption')).toHaveText('0 / 0');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('button', { name: '프로젝트 목록', exact: true }).click();
    await page.getByRole('button', { name: / 메뉴$/ }).click();
    await page.getByRole('menuitem', { name: '수정', exact: true }).click();
    await expect(page.getByRole('button', { name: '프로젝트 저장' })).toBeInViewport();
});


test('submissions: linked requirement removal confirms and cancellation restores checkbox', async ({ page }) => {
    const state=await setup(page);
    const checkbox=page.getByRole('checkbox',{name:'사업자등록증',exact:true});
    page.once('dialog',dialog=>dialog.dismiss());
    await checkbox.click();
    await expect(checkbox).toBeChecked();
    expect(state.writes).toHaveLength(0);
    await expect(row(page,81)).toContainText('사업자등록증.pdf');
    page.once('dialog',dialog=>dialog.accept());
    await checkbox.uncheck();
    await expect(page.locator('#document-message')).toHaveText('저장됨');
    await expect(checkbox).not.toBeChecked();
    await expect(row(page,81)).toHaveCount(0);
    expect(state.selections.some(item=>item.requirementId===81)).toBe(false);
    expect(state.requirements.find(item=>item.id===83).documentName).toBe('참여인력');
});

test('submissions: rapid changes serialize requests and keep latest checkbox intent', async ({ page }) => {
    const state=await setup(page,{holdFirstSave:true});
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).uncheck();
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    await page.getByRole('checkbox',{name:'보존된 서류',exact:true}).uncheck();
    await expect.poll(()=>Boolean(state.releaseSave)).toBe(true);state.releaseSave();
    await expect(page.locator('#document-message')).toHaveText('저장됨');
    expect(state.maxSaves).toBe(1);
    await expect(page.getByRole('checkbox',{name:'미선택 서류',exact:true})).toBeChecked();
    await expect(page.locator('#requirement-list')).toContainText('미선택 서류');
    await expect(row(page,85)).toHaveCount(0);
    expect(state.selections.find(item=>item.requirementId===81).fileId).toBe(901);
    expect(state.writes.filter(item=>item.path.endsWith('/collect')).length).toBeLessThanOrEqual(2);
});

test('submissions: failed checkbox save restores server state and allows retry', async ({ page }) => {
    const options={saveError:true};
    const state=await setup(page,options);
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    await expect(page.locator('#document-message')).toContainText('체크 저장 실패');
    await expect(page.getByRole('checkbox',{name:'미선택 서류',exact:true})).not.toBeChecked();
    expect(state.requirements).toHaveLength(5);
    options.saveError=false;
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    await expect(page.locator('#document-message')).toHaveText('저장됨');
    await expect(page.locator('#requirement-list tr')).toHaveCount(6);
});

test('submissions: missing common file stays unregistered and navigation waits for save', async ({ page }) => {
    const state=await setup(page,{missingFile:true,delay:150});
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    await page.getByRole('button',{name:'프로젝트 목록',exact:true}).click();
    await expect(page.locator('#submission-project-list')).toBeVisible();
    expect(state.requirements.some(item=>item.documentName==='미선택 서류')).toBe(true);
    await page.locator('.submission-project-card h3').click();
    await expect(row(page,82)).toContainText('파일 미등록');
    await expect(page.getByRole('checkbox',{name:'미선택 서류',exact:true})).toBeChecked();
});

test('submissions: card and row menu keyboard/cancel actions never enter detail', async ({ page }) => {
    const state=await setup(page);
    await page.goBack();
    for (const view of ['카드형','리스트형']) {
        await page.getByRole('button',{name:view,exact:true}).click();
        const toggle=page.getByRole('button',{name:'제출 프로젝트 메뉴'});
        await toggle.click();
        const menu=page.getByRole('menu');
        await expect(menu).toBeVisible();
        const toggleBox=await toggle.boundingBox(),menuBox=await menu.boundingBox();
        expect(Math.abs(menuBox.x+menuBox.width-toggleBox.x-toggleBox.width)).toBeLessThan(2);
        expect(menuBox.y).toBeGreaterThanOrEqual(toggleBox.y+toggleBox.height);
        await page.getByRole('heading',{name:'프로젝트 목록',exact:true}).click();
        await expect(menu).not.toBeVisible();
        await expect(toggle).toHaveAttribute('aria-expanded','false');
        await expect(page).toHaveURL(/\/submissions\/$/);
        await toggle.focus();await page.keyboard.press('ArrowDown');
        await expect(page.getByRole('menuitem',{name:'수정',exact:true})).toBeFocused();
        await page.keyboard.press('Escape');
        await expect(toggle).toBeFocused();
        await toggle.click();page.once('dialog',dialog=>dialog.dismiss());
        await page.getByRole('menuitem',{name:'삭제',exact:true}).click();
        await expect(page).toHaveURL(/\/submissions\/$/);
        expect(state.deleted).toBe(false);
        await toggle.click();await page.getByRole('menuitem',{name:'수정',exact:true}).click();
        await expect(page.getByRole('dialog',{name:'프로젝트 수정'})).toBeVisible();
        await page.getByRole('button',{name:'취소',exact:true}).click();
        await expect(page).toHaveURL(/\/submissions\/$/);
    }
    await expect(page.getByRole('button',{name:'열기',exact:true})).toHaveCount(0);
});


test('submissions: checkbox save waits for an in-flight file selection', async ({ page }) => {
    const state=await setup(page,{holdSelection:true});
    await row(page,85).getByRole('button',{name:'파일 연결'}).click();
    await page.getByRole('button',{name:'이 파일 선택'}).click();
    await expect.poll(()=>Boolean(state.releaseSelection)).toBe(true);
    await page.keyboard.press('Escape');
    await page.getByRole('checkbox',{name:'미선택 서류',exact:true}).check();
    expect(state.writes.some(item=>item.path.endsWith('/collect'))).toBe(false);
    state.releaseSelection();
    await expect(page.locator('#document-message')).toHaveText('저장됨');
    await expect(row(page,85)).toContainText('연결할파일.pdf');
    expect(state.selections.find(item=>item.requirementId===85).fileId).toBe(905);
});
