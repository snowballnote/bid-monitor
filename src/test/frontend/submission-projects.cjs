const {chromium,expect}=require('@playwright/test');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve('src/main/resources/static');
const server=http.createServer((req,res)=>{
 let url=new URL(req.url,'http://localhost').pathname;
 if(url==='/submissions/'||url==='/documents/')url+='index.html';
 const file=path.resolve(root,'.'+url);
 if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);res.end();return;}
 res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css'})[path.extname(file)]||'text/plain');
 res.end(fs.readFileSync(file));
});
const ordinary={id:11,category:'COMPANY_GENERAL',documentName:'사업자등록증',sourceReference:'BUSINESS_REGISTRATION',performanceSelectionRequired:false};
const perf={id:12,category:'PERFORMANCE',documentName:'실적증명서',sourceReference:'PERFORMANCE',performanceSelectionRequired:true};
const tests=[];
function test(name,run){tests.push({name,run});}
test('List first, card/list persistence, direct detail and empty project creation',async({page,f})=>{
 await page.goto('/submissions/');
 await expect(page.locator('#submission-project-list')).toBeVisible();
 await expect(page.locator('#document-picker')).toBeHidden();
 await expect(page.locator('.submission-project-card')).toHaveCount(2);
 await page.locator('#view-list').click();
 await expect(page.locator('.submission-project-table tbody tr')).toHaveCount(2);
 await page.reload();
 await expect(page.locator('#view-list')).toHaveAttribute('aria-pressed','true');
 await page.locator('#new-submission-project').click();
 await page.locator('#create-submission-project [name=projectName]').fill('새 프로젝트');
 await page.locator('#create-submission-project [name=deadline]').fill('2026-12-31');
 await page.locator('#create-submission-project button').click();
 await expect(page.locator('#case-project-name')).toHaveText('새 프로젝트');
 await expect(page).toHaveURL(/caseId=3/);
 await expect(page.locator('#requirement-list button')).toHaveCount(0);
 assert.equal(f.calls.filter(r=>r.method==='POST').length,1);
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
});
test('Storage failures default to cards',async({page,context})=>{
 await context.addInitScript(()=>{Storage.prototype.getItem=()=>{throw Error('blocked')};Storage.prototype.setItem=()=>{throw Error('blocked')};});
 await page.goto('/submissions/');
 await page.locator('#view-list').click();
 await expect(page.locator('#view-card')).toHaveAttribute('aria-pressed','true');
 await expect(page.locator('.submission-project-card')).toHaveCount(2);
});
test('Reselect updates current case and candidate selection/package still work',async({page,f})=>{
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await page.locator('#change-documents-button').click();
 await expect(page.locator('.document-option[value="사업자등록증"]')).toBeChecked();
 await page.locator('.document-option[value="재직증명서"]').check();
 await page.locator('#find-documents-button').click();
 await expect(page.locator('#requirement-list button')).toHaveCount(3);
 assert(f.calls.some(r=>r.method==='POST'&&r.url==='/api/submission-cases/1/collect'));
 assert(!f.calls.some(r=>r.method==='POST'&&r.url==='/api/submission-cases'));
 await page.locator('#requirement-list button').filter({hasText:'사업자등록증'}).click();
 await page.getByRole('button',{name:'이 파일 선택',exact:true}).click();
 await expect(page.locator('#package-list')).toContainText('증빙.pdf');
 await page.locator('#back-submission-projects').click();
 await page.locator('#submission-project-items a').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 await expect(page.locator('#requirement-list button')).toHaveCount(0);
});
test('Legacy performance link migrates once; server connection wins over localStorage and URL',async({page,context,f})=>{
 await context.addInitScript(()=>localStorage.setItem('biz-assist.performance-project.1','p1'));
 await page.goto('/submissions/?caseId=1&performanceProjectId=wrong');
 await expect.poll(()=>f.cases[1].performanceProjectId).toBe('p1');
 assert.equal(f.calls.filter(r=>r.method==='PUT'&&r.body.initializePerformanceOnly).length,1);
 await page.reload();
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 assert.equal(f.calls.filter(r=>r.method==='PUT'&&r.body.initializePerformanceOnly).length,1);
 await expect(page.locator('#performance-project-select')).toHaveCount(0);
 f.cases[1].performanceProjectId='p2';
 await page.reload();
 await expect(page.locator('#performance-manage')).toHaveAttribute('href',/project=p2/);
 await context.route('**/performances/index.html?*',route=>route.fulfill({contentType:'text/html',body:'<p>performance detail</p>'}));
 await page.locator('#requirement-list button').filter({hasText:'실적증명서'}).click();
 await expect(page).toHaveURL(/project=p2/);
 assert(!f.calls.some(r=>r.url.endsWith('/performance-project')));
});
test('Slow detail response cannot replace a newly opened project',async({page,context})=>{
 let release;const gate=new Promise(r=>release=r);
 await context.route('**/api/submission-cases/1/requirements',async route=>{await gate;await route.fulfill({json:[ordinary,perf]});});
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#back-submission-projects')).toBeVisible();
 await page.locator('#back-submission-projects').click();
 await page.locator('#submission-project-items a').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 release();
 await page.waitForTimeout(100);
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 await expect(page.locator('#requirement-list button')).toHaveCount(0);
});
test('Slow candidate responses cannot contaminate another project',async({page,context})=>{
 let release;const gate=new Promise(r=>release=r);
 await context.route('**/requirements/11/candidates',async route=>{await gate;await route.fulfill({json:[{fileId:101,originalFilename:'오래된 후보.pdf',matchLevel:'EXACT'}]});});
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await page.locator('#back-submission-projects').click();
 await page.locator('#submission-project-items a').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 release();await page.waitForTimeout(100);
 await expect(page.locator('#candidate-list')).not.toContainText('오래된 후보');
 await expect(page.locator('#requirement-list button')).toHaveCount(0);
});
test('Late file-save response cannot replace the next project package',async({page,context})=>{
 let release;const gate=new Promise(r=>release=r);
 await context.route('**/api/submission-cases/1/selections',async route=>{await gate;await route.fulfill({json:[{requirementId:11,fileId:101}]});});
 await page.goto('/submissions/?caseId=1');
 await page.locator('#requirement-list button').filter({hasText:'사업자등록증'}).click();
 await page.getByRole('button',{name:'이 파일 선택',exact:true}).click();
 await page.locator('#back-submission-projects').click();
 await page.locator('#submission-project-items a').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 release();await page.waitForTimeout(100);
 await expect(page.locator('#package-list li')).toHaveCount(0);
 await expect(page.locator('#progress-caption')).toHaveText('0개 중 0개 완료');
});
test('Invalid legacy link is not persisted and project name can be edited',async({page,context,f})=>{
 await context.addInitScript(()=>localStorage.setItem('biz-assist.performance-project.1','missing'));
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await expect(page.locator('#requirement-list')).toContainText('실적증빙 관리를 열면 프로젝트가 자동 연결됩니다.');
 assert(!f.calls.some(r=>r.method==='PUT'&&r.body.initializePerformanceOnly));
 await page.locator('#rename-submission-project [name=projectName]').fill('이름 수정');
 await page.locator('#rename-submission-project button').click();
 await expect(page.locator('#case-project-name')).toHaveText('이름 수정');
 assert.equal(f.cases[1].projectName,'이름 수정');
});
test('Entire card and row open details with summary and checklist visible together',async({page})=>{
 await page.goto('/submissions/');
 await expect(page.locator('#submission-project-items')).not.toContainText('계속 준비하기');
 await page.locator('.submission-project-card h3').first().click();
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await expect(page.locator('#case-project-deadline')).toHaveText('마감일 2026-12-31');
 await expect(page.locator('#progress-percent')).toBeVisible();
 await expect(page.locator('#document-picker')).toBeVisible();
 await expect(page.locator('#requirement-list')).toBeVisible();
 await page.locator('#back-submission-projects').click();
 await page.locator('#view-list').click();
 await page.locator('.submission-project-table tbody tr').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 await expect(page.locator('#document-picker')).toBeVisible();
});
test('Deadline and name edit, delete cancellation and confirmed local deletion',async({page,f})=>{
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await page.locator('#rename-submission-project [name=projectName]').fill('수정 프로젝트');
 await page.locator('#rename-submission-project [name=deadline]').fill('2027-01-15');
 await page.locator('#rename-submission-project button').click();
 await expect(page.locator('#case-project-name')).toHaveText('수정 프로젝트');
 await expect(page.locator('#case-project-deadline')).toHaveText('마감일 2027-01-15');
 assert.equal(f.cases[1].deadline,'2027-01-15');
 page.once('dialog',dialog=>dialog.dismiss());
 await page.locator('#delete-submission-project').click();
 assert(!f.calls.some(r=>r.method==='DELETE'));
 page.once('dialog',dialog=>dialog.accept());
 await page.locator('#delete-submission-project').click();
 await expect(page.locator('#submission-project-list')).toBeVisible();
 await expect(page.locator('.submission-project-card')).toHaveCount(1);
 assert(f.cases[2]);
});
test('Back and forward preserve submissions list/detail without duplicate history entries',async({page})=>{
 await page.goto('/submissions/');
 await expect(page.locator('.submission-project-card')).toHaveCount(2);
 const before=await page.evaluate(()=>history.length);
 await page.locator('.submission-project-card').first().click();
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 assert.equal(await page.evaluate(()=>history.length),before+1);
 await page.goBack();
 await expect(page).toHaveURL(new RegExp("/submissions/$"));
 await expect(page.locator('#submission-project-list')).toBeVisible();
 await page.goForward();
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 assert.equal(await page.evaluate(()=>history.length),before+1);
 await page.reload();
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 assert.equal(await page.evaluate(()=>history.length),before+1);
 await page.goBack();
 await expect(page.locator('#submission-project-list')).toBeVisible();
});
test('Direct case link inserts exactly one list entry while retaining the earlier external page',async({page,context})=>{
 await context.route('**/prior-page',route=>route.fulfill({contentType:'text/html',body:'<p>earlier page</p>'}));
 await page.goto('/prior-page');
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#case-project-name')).toHaveText('첫 프로젝트');
 await page.goBack();
 await expect(page.locator('#submission-project-list')).toBeVisible();
 await page.goBack();
 await expect(page).toHaveURL(/prior-page$/);
});
test('Independent document cards CRUD leaves project file links intact',async({page,f})=>{
 f.masters[0].category='COMPANY_COMMON';f.selections[1]=[{requirementId:11,fileId:101,originalFilename:'증빙.pdf'}];
 await page.goto('/submissions/?caseId=1');
 await expect(page.locator('#master-manager')).toHaveCount(0);
 await expect(page.locator('input[type=file]')).toHaveCount(0);
 await page.locator('.app-nav a[href="/documents/"]').click();
 await expect(page.locator('.app-nav a[aria-current=page]')).toHaveText('서류 관리');
 await expect(page.locator('.app-shell')).toBeVisible();
 await expect(page.locator('link[href="/common.css"]')).toHaveCount(1);
 await expect(page.locator('.document-card.surface-card').first()).toBeVisible();
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.locator('#document-create-form input').fill('추가 공통서류');await page.locator('#document-create-form button').click();
 await expect(page.locator('.document-card')).toHaveCount(2);
 const row=page.locator('.document-card').first();await row.getByRole('button',{name:'서류명 수정',exact:true}).click();
 await row.locator('form input').fill('마스터 이름 수정');await row.getByRole('button',{name:'저장',exact:true}).click();
 await expect(row.locator('h2')).toHaveText('마스터 이름 수정');
 page.once('dialog',d=>d.accept());await row.getByRole('button',{name:'삭제',exact:true}).click();
 await expect(page.locator('.document-card')).toHaveCount(1);
 await page.goto('/submissions/?caseId=1');await expect(page.locator('#package-list')).toContainText('증빙.pdf');
 await expect(page.locator('.document-option[value="사업자등록증"]')).toBeChecked();
 assert.equal(f.requirements[1][0].id,11);
});
test('Unlinked performance click connects once and opens detail directly',async({page,context,f})=>{
 await context.route('**/performances/index.html?*',route=>route.fulfill({contentType:'text/html',body:'<p>performance detail</p>'}));
 await page.goto('/submissions/?caseId=1');
 await page.locator('#requirement-list button').filter({hasText:'실적증명서'}).click();
 await expect(page).toHaveURL(/project=p1/);
 assert.equal(f.calls.filter(r=>r.url.endsWith('/performance-project')).length,1);
 await page.goto('/submissions/?caseId=1');
 await page.locator('#requirement-list button').filter({hasText:'실적증명서'}).click();
 await expect(page).toHaveURL(/project=p1/);
 assert.equal(f.calls.filter(r=>r.url.endsWith('/performance-project')).length,1);
});
test('Repeated performance click and late response cannot navigate another project',async({page,context,f})=>{
 let release; const gate=new Promise(r=>release=r);let count=0;
 await context.route('**/api/submission-cases/1/performance-project',async route=>{count++;await gate;await route.fulfill({json:{...f.cases[1],performanceProjectId:'p1'}});});
 await page.goto('/submissions/?caseId=1');
 const button=page.locator('#requirement-list button').filter({hasText:'실적증명서'});
 await button.evaluate(el=>{el.click();el.click();});
 await expect.poll(()=>count).toBe(1);
 await page.locator('#back-submission-projects').click();
 await page.locator('#submission-project-items a').nth(1).click();
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
 release();await page.waitForTimeout(150);
 await expect(page).toHaveURL(/caseId=2/);
 await expect(page.locator('#case-project-name')).toHaveText('둘째 프로젝트');
});
test('Find documents shows candidates directly with preserved requirement and selection IDs',async({page,f})=>{
 f.selections[1]=[{requirementId:11,fileId:102,originalFilename:'기존.pdf'}];
 await page.goto('/submissions/?caseId=1');
 await page.locator('#find-documents-button').click();
 await expect(page.getByRole('button',{name:'이 파일 선택',exact:true})).toBeVisible();
 assert.equal(f.requirements[1].find(r=>r.documentName==='사업자등록증').id,11);
 await expect(page.locator('#package-list')).toContainText('기존.pdf');
 await page.getByRole('button',{name:'이 파일 선택',exact:true}).click();
 await expect(page.locator('#package-list')).toContainText('증빙.pdf');
 const saved=f.calls.filter(r=>r.method==='PUT'&&r.url==='/api/submission-cases/1/selections').at(-1);
 assert.deepEqual(saved.body,{selections:[{requirementId:11,fileId:101}]});
});
test('Candidate HTTP 503 is shown and a later document click retries successfully',async({page,context})=>{
 let fail=true,count=0;
 await context.route('**/api/submission-cases/1/requirements/11/candidates',route=>{count++;return route.fulfill(fail?{status:503,json:{message:'회사 DB에 연결할 수 없습니다.'}}:{status:200,json:[{fileId:101,originalFilename:'재시도.pdf',matchLevel:'EXACT'}]});});
 await page.goto('/submissions/?caseId=1');
 await page.locator('#find-documents-button').click();
 await expect(page.locator('#candidate-error')).toBeVisible();
 await expect(page.locator('#candidate-error')).toContainText('회사 DB에 연결할 수 없습니다.');
 const before=count;fail=false;
 await page.locator('#requirement-list button').filter({hasText:'사업자등록증'}).click();
 await expect(page.getByRole('button',{name:'이 파일 선택',exact:true})).toBeVisible();
 assert(count>before);
});
test('Local file input uploads on documents screen and collect uses saved reference',async({page,f})=>{
 f.masters[0].category='COMPANY_COMMON';
 await page.goto('/documents/');
 const row=page.locator('.document-card').first();
 await row.locator('input[type=file]').setInputFiles({name:'local.pdf',mimeType:'application/pdf',buffer:Buffer.from('local bytes')});
 await expect(row).toContainText('local.pdf');
 assert(f.calls.some(r=>r.method==='POST'&&r.url.endsWith('/upload')));
 assert(!f.calls.some(r=>r.url.endsWith('/candidates')));
 await page.goto('/submissions/?caseId=1');await page.locator('#find-documents-button').click();
 await expect(page.locator('#package-list')).toContainText('local.pdf');
 await expect(page.locator('#download-submission-files')).toBeVisible();
 assert(!f.calls.some(r=>r.url==='/api/submission-cases/1/requirements/11/candidates'));
});
test('Missing current common file is reported after collect without deleting an existing project link',async({page,f})=>{
 f.masters[0].category='COMPANY_COMMON';
 f.selections[1]=[{requirementId:11,fileId:909,originalFilename:'기존 보존.pdf'}];
 await page.goto('/submissions/?caseId=1');
 await page.locator('#find-documents-button').click();
 await expect(page.locator('#requirement-list button').filter({hasText:'사업자등록증'})).toContainText('파일 미등록');
 assert.equal(f.selections[1][0].fileId,909);
 assert(!f.calls.some(r=>r.url==='/api/submission-cases/1/requirements/11/candidates'));
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({channel:'msedge',headless:true});let failures=0;
 try{for(const test of tests){
  const context=await browser.newContext({baseURL:'http://127.0.0.1:'+server.address().port});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const f={cases:{1:{id:1,projectName:'첫 프로젝트',deadline:'2026-12-31',performanceProjectId:null},2:{id:2,projectName:'둘째 프로젝트',deadline:'2026-12-31',performanceProjectId:null}},
   masters:[{id:'business',name:'사업자등록증',category:'OTHER',requirementCategory:'COMPANY_GENERAL',sourceReference:'BUSINESS_REGISTRATION'},
    {id:'employment',name:'재직증명서',category:'PERSONNEL',requirementCategory:'PERSONNEL',sourceReference:'EMPLOYMENT'},
    {id:'perf',name:'실적증명서',category:'PERFORMANCE',requirementCategory:'PERFORMANCE',sourceReference:'PERFORMANCE'}],
   requirements:{1:[ordinary,perf],2:[]},selections:{1:[],2:[]},calls:[]};
  await context.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()).pathname,method=req.method(),body=req.headers()['content-type']?.includes('application/json')?req.postDataJSON():null;
   f.calls.push({url,method,body});let result;
   if(url.startsWith('/api/submission-document-masters')) {
     const id=url.split('/')[3],tail=url.split('/')[4];
     if(tail==='candidates'){await route.fulfill({json:[{fileId:201,originalFilename:'회사공통.pdf',fileExt:'pdf'}]});return;}
     if(tail==='upload'){Object.assign(f.masters.find(m=>m.id===id),{uploadedFileId:'upload1',currentFileId:null,currentFilename:'local.pdf',sizeBytes:11,uploadedAt:'2026-09-09T01:00:00Z'});await route.fulfill({json:{id:'upload1',originalFilename:'local.pdf',sizeBytes:11}});return;}
     if(tail==='current-file'){Object.assign(f.masters.find(m=>m.id===id),{currentFileId:body.fileId,currentFilename:'회사공통.pdf'});await route.fulfill({json:{fileId:body.fileId,originalFilename:'회사공통.pdf'}});return;}
     if(method==='POST')f.masters.push({id:'new',...body,requirementCategory:body.category==='COMPANY_COMMON'?'COMPANY_GENERAL':body.category,sourceReference:'NEW'});
     if(method==='PUT')Object.assign(f.masters.find(m=>m.id===id),body);
     if(method==='DELETE')f.masters=f.masters.filter(m=>m.id!==id);
     result=f.masters;
   }
   else if(url==='/api/submission-common-documents')result=[];
   else if(url==='/api/performance-projects')result=[{id:'p1',name:'실적 1'},{id:'p2',name:'실적 2'}];
   else if(url.startsWith('/api/performance-projects/'))result=[];
   else if(url==='/api/submission-cases'){
    if(method==='POST'){f.cases[3]={id:3,...body};f.requirements[3]=[];f.selections[3]=[];result=f.cases[3];}
    else result=Object.values(f.cases).map(c=>({...c,total:f.requirements[c.id].length,prepared:f.selections[c.id].length,updatedAt:'2026-09-09T01:00:00Z'}));
   } else {
    const match=url.match(new RegExp("^/api/submission-cases/([0-9]+)(.*)$"));
    if(!match){await route.fulfill({status:404,json:{}});return;}
    const id=match[1],tail=match[2];
    if(!tail && method==='DELETE'){delete f.cases[id];delete f.requirements[id];delete f.selections[id];result={};}
    else if(!tail){
     if(method==='PUT'){
      if(!body.initializePerformanceOnly||!f.cases[id].performanceLinkInitialized){Object.assign(f.cases[id],body);if('performanceProjectId'in body)f.cases[id].performanceLinkInitialized=true;}
     }result=f.cases[id];
    }else if(tail==='/performance-project'){ f.cases[id].performanceProjectId ||= 'p1';result=f.cases[id];
    }else if(tail==='/collect'){
     f.requirements[id]=body.map((r,i)=>({...r,id:f.requirements[id].find(old=>old.documentName===r.documentName)?.id||100+i,performanceSelectionRequired:r.documentName==='실적증명서'}));
     const missing=[];
     for(const r of f.requirements[id]){const m=f.masters.find(m=>m.category==='COMPANY_COMMON'&&m.sourceReference===r.sourceReference);if(!m)continue;r.companyCommon=true;if(m.uploadedFileId||m.currentFileId)f.selections[id]=[...f.selections[id].filter(s=>s.requirementId!==r.id),{requirementId:r.id,fileId:m.currentFileId,uploadedFileId:m.uploadedFileId,originalFilename:m.currentFilename}];else missing.push(r.id);}
     result={missingRequirementIds:missing};
    }else if(tail==='/requirements'){
     if(method==='PUT')f.requirements[id]=body.map((r,i)=>({...r,id:f.requirements[id].find(old=>old.documentName===r.documentName)?.id||100+i,performanceSelectionRequired:r.documentName==='실적증명서'}));
     result=f.requirements[id];
    }else if(tail==='/package')result={selections:f.selections[id]};
    else if(tail==='/selections'){f.selections[id]=body.selections.map(r=>({...r,originalFilename:'증빙.pdf'}));result=f.selections[id];}
    else if(tail.endsWith('/candidates'))result=[{fileId:101,originalFilename:'증빙.pdf',fileExt:'pdf',matchLevel:'EXACT'}];
    else result={};
   }
   await route.fulfill({json:result});
  });
  try{await test.run({page,context,f});assert.deepEqual(errors,[]);console.log('PASS '+test.name);}
  catch(e){failures++;console.error('FAIL '+test.name+' '+e.stack);}
  finally{await context.close();}
 }}finally{await browser.close();server.close();}
 console.log('RESULT '+(tests.length-failures)+'/'+tests.length);process.exitCode=failures?1:0;
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});