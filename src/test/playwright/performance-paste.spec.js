const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const html = '<table><tr><th>번호</th><th>사업명</th><th>사업기간</th><th>계약금액</th><th>발주처</th></tr><tr><td>1</td><td>통합시스템<br>구축</td><td>2024.01 ~ 2025.12</td><td>100,000,000원</td><td>테스트기관</td></tr></table>';
const text = '1\t통합시스템 구축\t2024.01 ~ 2025.12\t100,000,000원\t테스트기관';
async function setup(page, fail = false, options = {}) {
    const writes = [];
    const project = { id: 'p1', name: '실적 프로젝트', deadline: '2026-12-31', daysRemaining: 30, status: 'DRAFT' };
    await page.route('**/*', async route => {
        const request = route.request(), pathname = new URL(request.url()).pathname;
        if (pathname.startsWith('/api/')) {
            if (request.method() !== 'GET') writes.push({ path: pathname, body: pathname.endsWith('/upload') ? request.postData() : request.postDataJSON() });
            if (pathname.endsWith('/upload')) {
                if(options.uploadError) return route.fulfill({status:503,json:{message:'파일 저장에 실패했습니다.'}});
                const count=writes.filter(item=>item.path.endsWith('/upload')).length;
                return route.fulfill({json:{id:'entry1',projectId:'p1',resolvedStatus:options.resolvedStatus||'COMPLETED',selectedFilename:'직접등록'+count+'.pdf',info:{pptNumber:'1',businessName:'통합시스템 구축',businessPeriod:'2024.01 ~ 2025.12',contractAmount:'100,000,000원',client:'테스트기관',kitcStatus:'NEEDED',selectedUploadedFileId:'upload-'+count,selectedDriveFileId:null,selectedFileId:null,evidenceType:options.resolvedStatus==='IN_PROGRESS'?'CONTRACT':'CERTIFICATE'}}});
            }
            if (pathname.endsWith('/import')) {
                if (fail) return route.fulfill({ status: 400, json: { message: '저장 실패' } });
                return route.fulfill({ json: { saved: [{ id: 'entry1', projectId: 'p1', resolvedStatus: options.resolvedStatus || 'COMPLETED', info: { pptNumber: '1', businessName: '통합시스템 구축', businessPeriod: '2024.01 ~ 2025.12', contractAmount: '100,000,000원', client: '테스트기관', kitcStatus: 'NEEDED' } }], errors: [] } });
            }
            if (pathname.endsWith('/candidates')) return route.fulfill(options.candidateError ? {status:503,json:{message:options.candidateError}} : { json: { candidates: options.candidates || [], nextAction: 'KITC 요청 필요' } });
            if (request.method() === 'PUT' && pathname.endsWith('/entries/entry1')) {
                if (options.saveError) return route.fulfill({status:400,json:{message:'지정 파일 저장 실패'}});
                const info=request.postDataJSON();
                return route.fulfill({json:{id:'entry1',projectId:'p1',resolvedStatus:'COMPLETED',info,selectedFilename:info.selectedDriveFileId?'선택한증빙.pdf':null}});
            }
            if (pathname.endsWith('/download')) return route.fulfill({contentType:'application/zip',body:Buffer.from('PK')});
            if (pathname === '/api/performance-projects') return route.fulfill({ json: [project] });
            if (pathname === '/api/performance-projects/p1') return route.fulfill({ json: project });
            return route.fulfill({ json: [] });
        }
        const file = path.join(__dirname, '../../main/resources/static', pathname);
        return fs.existsSync(file) ? route.fulfill({ path: file }) : route.fulfill({ status: 404, body: '' });
    });
    await page.goto('/performances/index.html?project=p1&caseId=71');
    await expect(page.locator('#workspace')).toBeVisible();
    return writes;
}
async function paste(page, plain = '', rich = '') {
    await page.locator('#paste-table').evaluate((textarea, data) => {
        const clipboard = new DataTransfer();
        clipboard.setData('text/plain', data.plain);clipboard.setData('text/html', data.rich);
        textarea.dispatchEvent(new ClipboardEvent('paste', { clipboardData: clipboard, bubbles: true, cancelable: true }));
    }, { plain, rich });
}

test('performance paste: HTML-only PPT table remains visible with immediate preview', async ({ page }) => {
    const writes = await setup(page);
    await paste(page, '', html);
    await expect(page.locator('#paste-table')).toContainText('');
    await expect(page.locator('#paste-table')).toHaveValue(/통합시스템\n구축/);
    await expect(page.locator('#paste-preview-rows tr')).toHaveCount(1);
    await expect(page.locator('#paste-preview th')).toHaveText(['번호', '사업명', '사업기간', '계약금액', '발주처']);
    await expect(page.locator('#paste-preview-rows td')).toHaveText(['1', '통합시스템\n구축', '2024.01 ~ 2025.12', '100,000,000원', '테스트기관']);
    expect(writes).toHaveLength(0);
});

test('performance paste: plain text, quoted multiline cells and edits refresh preview', async ({ page }) => {
    await setup(page);
    const multiline = '2\t"첫째 줄\n둘째 줄"\t2025.01 ~ 수행중\t200원\t기관';
    await paste(page, multiline);
    await expect(page.locator('#paste-table')).toHaveValue(multiline);
    await expect(page.locator('#paste-preview-rows tr')).toHaveCount(1);
    await expect(page.locator('#paste-preview-rows td').nth(1)).toHaveText('첫째 줄\n둘째 줄');
    await page.locator('#paste-table').fill(text);
    await expect(page.locator('#paste-preview-rows td').nth(1)).toHaveText('통합시스템 구축');
    await page.locator('#paste-table').fill('');
    await expect(page.locator('#paste-preview')).toBeHidden();
});

test('performance paste: incomplete and merged rows show review badges', async ({ page }) => {
    await setup(page);
    await paste(page, text + '\n2\t누락 행\t2025.01 ~ 수행중\t\t기관');
    await expect(page.locator('#paste-preview-rows tr')).toHaveCount(2);
    await expect(page.locator('#paste-preview-summary')).toHaveText('2행 · 확인 필요 1행');
    await expect(page.locator('.paste-preview-error')).toContainText('확인 필요');
    await paste(page, '', '<table><tr><td rowspan="2">1</td><td colspan="4">병합된 내용</td></tr></table>');
    await expect(page.locator('#paste-table')).toHaveValue(/병합된 내용/);
    await expect(page.locator('.paste-preview-error')).toContainText('병합된 내용');
});

test('performance paste: save keeps original HTML payload and existing success flow', async ({ page }) => {
    const writes = await setup(page);
    await paste(page, text, html);
    await expect(page.locator('#paste-table')).toHaveValue(text);
    await page.getByRole('button', { name: '실적표 일괄 파싱·저장' }).click();
    await expect(page.locator('#import-result')).toHaveText('1행 저장, 0행 확인 필요');
    expect(writes[0].body).toEqual({ text, html });
    await expect(page.locator('#entries .performance-entry')).toHaveCount(1);
    await expect(page.locator('#entries')).toContainText('KITC 요청 필요');
    await expect(page.locator('#paste-table')).toHaveValue('');
    await expect(page.locator('#paste-preview')).toBeHidden();
    await expect(page.locator('#return-submissions')).toHaveAttribute('href', '/submissions/?caseId=71&performanceProjectId=p1');
});

test('performance paste: failed save retains text and preview on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const writes = await setup(page, true);
    await paste(page, '', html);
    const visibleText = await page.locator('#paste-table').inputValue();
    await page.getByRole('button', { name: '실적표 일괄 파싱·저장' }).click();
    await expect(page.locator('#message')).toHaveText('저장 실패');
    await expect(page.locator('#paste-table')).toHaveValue(visibleText);
    await expect(page.locator('#paste-preview-rows tr')).toHaveCount(1);
    expect(writes[0].body.html).toBe(html);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});


async function importOne(page) {
    await paste(page,text,html);
    await page.getByRole('button',{name:'실적표 일괄 파싱·저장'}).click();
    await expect(page.locator('#entries .performance-entry')).toHaveCount(1);
}
const fileCandidates=[{file:{driveFileId:'drive-1',originalFilename:'증빙1.pdf'},evidenceType:'CERTIFICATE',reason:'정확히 일치'},{file:{driveFileId:'drive-2',originalFilename:'증빙2.pdf'},evidenceType:'CONTRACT',reason:'사업명 일치'}];

test('performance uploads: ongoing contract uploads despite FMS error, replaces, downloads and unlinks', async ({page})=>{
    const writes=await setup(page,false,{resolvedStatus:'IN_PROGRESS',candidateError:'계약서 검색 폴더를 설정하세요.'});
    await importOne(page);
    await page.getByRole('button',{name:'1. 통합시스템 구축 파일 관리',exact:true}).click();
    const panel=page.getByRole('region',{name:'증빙 파일 관리'});
    await expect(panel.getByLabel('증빙유형')).toHaveValue('CONTRACT');
    await expect(panel.getByLabel('증빙유형').locator('option')).toHaveCount(1);
    for(let count=1;count<=2;count++) {
        await panel.getByLabel('PC 파일').setInputFiles({name:'contract.pdf',mimeType:'application/pdf',buffer:Buffer.from('contract')});
        await panel.getByRole('button',{name:'파일 직접 등록',exact:true}).click();
        await expect(page.locator('.entry-summary-row')).toContainText('직접등록'+count+'.pdf');
        await expect(page.locator('.entry-summary-row')).toContainText('준비 완료');
    }
    const uploads=writes.filter(item=>item.path.endsWith('/upload'));
    expect(uploads).toHaveLength(2);
    expect(uploads[0].body).toContain('filename="contract.pdf"');
    expect(uploads[0].body).toContain('CONTRACT');
    await expect(page.locator('#download')).toBeEnabled();
    const download=page.waitForEvent('download');await page.locator('#download').click();await download;
    await panel.getByRole('button',{name:'연결 해제',exact:true}).click();
    await panel.getByRole('button',{name:'지정 파일 저장',exact:true}).click();
    await expect.poll(()=>writes.filter(item=>item.path.endsWith('/entries/entry1')).length).toBe(1);
    expect(writes.at(-1).body).toMatchObject({selectedUploadedFileId:null,selectedDriveFileId:null,selectedFileId:null,evidenceType:null});
    await expect(page.locator('#download')).toBeDisabled();
});

test('performance uploads: FMS can replace uploaded selection and upload failure remains retryable', async ({page})=>{
    const writes=await setup(page,false,{candidates:fileCandidates});await importOne(page);
    await page.getByRole('button',{name:'1. 통합시스템 구축 파일 관리',exact:true}).click();
    const panel=page.getByRole('region',{name:'증빙 파일 관리'});
    await panel.getByLabel('PC 파일').setInputFiles({name:'local.pdf',mimeType:'application/pdf',buffer:Buffer.from('local')});
    await panel.getByRole('button',{name:'파일 직접 등록',exact:true}).click();
    await expect(page.locator('.entry-summary-row')).toContainText('직접등록1.pdf');
    await panel.getByRole('radio',{name:'증빙1.pdf · 정확히 일치'}).check();
    await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect(page.locator('.entry-summary-row')).toContainText('선택한증빙.pdf');
    expect(writes.at(-1).body).toMatchObject({selectedUploadedFileId:null,selectedDriveFileId:'drive-1'});
    await page.route('**/entries/entry1/upload',route=>route.fulfill({status:503,json:{message:'파일 저장에 실패했습니다.'}}));
    await panel.getByLabel('PC 파일').setInputFiles({name:'retry.pdf',mimeType:'application/pdf',buffer:Buffer.from('retry')});
    await panel.getByRole('button',{name:'파일 직접 등록',exact:true}).click();
    await expect(panel.locator('.file-management-message')).toHaveText('파일 저장에 실패했습니다.');
    await expect(page.locator('.entry-summary-row')).toContainText('선택한증빙.pdf');
    await expect(panel.getByRole('button',{name:'파일 직접 등록',exact:true})).toBeEnabled();
});

test('performance files: compact table expands file-only panel, saves, replaces and clears selection', async ({page})=>{
    const writes=await setup(page,false,{candidates:fileCandidates});
    await importOne(page);
    await expect(page.locator('#entries thead th')).toHaveText(['번호','사업명','사업기간','발주처','계약금액','상태','지정 파일']);
    await expect(page.locator('.entry-detail-row')).toBeHidden();
    await page.getByRole('button',{name:'1. 통합시스템 구축 파일 관리',exact:true}).click();
    const panel=page.getByRole('region',{name:'증빙 파일 관리'});
    await expect(panel).toBeVisible();
    await expect(panel.locator('input[type=text], input[type=date], textarea')).toHaveCount(0);
    await panel.getByRole('radio',{name:'증빙1.pdf · 정확히 일치'}).check();
    await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect(page.locator('.entry-summary-row')).toContainText('준비 완료');
    await expect(page.locator('.entry-summary-row')).toContainText('선택한증빙.pdf');
    const first=writes.find(item=>item.path.endsWith('/entries/entry1')).body;
    expect(first).toMatchObject({pptNumber:'1',businessName:'통합시스템 구축',businessPeriod:'2024.01 ~ 2025.12',contractAmount:'100,000,000원',client:'테스트기관',kitcStatus:'NEEDED',selectedDriveFileId:'drive-1',evidenceType:'CERTIFICATE'});
    await panel.getByRole('radio',{name:'증빙2.pdf · 사업명 일치'}).check();
    await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect.poll(()=>writes.filter(item=>item.path.endsWith('/entries/entry1')).length).toBe(2);
    await expect(panel.getByRole('radio',{name:'증빙2.pdf · 사업명 일치'})).toBeChecked();
    await panel.getByRole('button',{name:'연결 해제',exact:true}).click();
    await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect(page.locator('.entry-summary-row')).not.toContainText('준비 완료');
    expect(writes.at(-1).body.selectedDriveFileId).toBeNull();
    expect(writes.at(-1).body.selectedFileId).toBeNull();
    await expect(page.getByRole('button',{name:'준비된 지정 파일 ZIP 다운로드'})).toBeDisabled();
});

test('performance files: failed save retains choice and ZIP uses existing download flow', async ({page})=>{
    const options={candidates:fileCandidates,saveError:true};
    await setup(page,false,options);await importOne(page);
    await page.locator('.entry-summary-row td').first().click();
    const panel=page.getByRole('region',{name:'증빙 파일 관리'});
    await panel.getByRole('radio',{name:'증빙1.pdf · 정확히 일치'}).check();
    await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect(panel.getByRole('status')).toHaveText('지정 파일 저장 실패');
    await expect(panel.getByRole('radio',{name:'증빙1.pdf · 정확히 일치'})).toBeChecked();
    options.saveError=false;await panel.getByRole('button',{name:'지정 파일 저장'}).click();
    await expect(page.locator('.entry-summary-row')).toContainText('준비 완료');
    const downloadPromise=page.waitForEvent('download');
    await page.getByRole('button',{name:'준비된 지정 파일 ZIP 다운로드'}).click();
    expect((await downloadPromise).suggestedFilename()).toBe('performance-evidence.zip');
});


test('performance candidates: contract configuration error is distinct from no matches and retry clears it', async ({page})=>{
    const options={resolvedStatus:'IN_PROGRESS',candidateError:'계약서 검색 폴더를 설정하세요.',candidates:[fileCandidates[1]]};
    await setup(page,false,options);await importOne(page);
    await page.getByRole('button',{name:'1. 통합시스템 구축 파일 관리',exact:true}).click();
    const panel=page.getByRole('region',{name:'증빙 파일 관리'});
    await expect(panel.locator('.candidate-search-context')).toHaveText('수행중 · 계약서 검색');
    await expect(panel.getByRole('alert')).toContainText('계약서 검색 폴더를 설정하세요.');
    await expect(panel.getByRole('radio')).toHaveCount(0);
    await expect(panel).not.toContainText('연결할 FMS 후보 파일이 없습니다.');
    options.candidateError=null;
    await panel.getByRole('button',{name:'후보 추천',exact:true}).click();
    await expect(panel.getByRole('radio')).toHaveCount(1);
    await expect(panel).not.toContainText('계약서 검색 폴더를 설정하세요.');
    await expect(panel.getByRole('alert')).toHaveCount(0);
});
