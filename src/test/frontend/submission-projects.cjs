const {chromium,expect}=require('@playwright/test');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve('src/main/resources/static');
const server=http.createServer((req,res)=>{
 let url=new URL(req.url,'http://localhost').pathname;
 if(url==='/submissions/')url+='index.html';
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
 await page.locator('#create-submission-project input').fill('새 프로젝트');
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
 assert(f.calls.some(r=>r.method==='PUT'&&r.url==='/api/submission-cases/1/requirements'));
 assert(!f.calls.some(r=>r.method==='POST'));
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
 await page.locator('#requirement-list button').filter({hasText:'실적증명서'}).click();
 await expect(page.locator('#performance-project-select')).toHaveValue('p1');
 await page.locator('#performance-project-select').selectOption('p2');
 await expect.poll(()=>f.cases[1].performanceProjectId).toBe('p2');
 await page.evaluate(()=>localStorage.setItem('biz-assist.performance-project.1','p1'));
 await page.reload();
 await page.locator('#requirement-list button').filter({hasText:'실적증명서'}).click();
 await expect(page.locator('#performance-project-select')).toHaveValue('p2');
 await expect(page.locator('#performance-manage')).toHaveAttribute('href',/project=p2/);
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
 await expect(page.locator('#requirement-list')).toContainText('실적 프로젝트를 선택하세요.');
 assert(!f.calls.some(r=>r.method==='PUT'&&r.body.initializePerformanceOnly));
 await page.locator('#rename-submission-project input').fill('이름 수정');
 await page.locator('#rename-submission-project button').click();
 await expect(page.locator('#case-project-name')).toHaveText('이름 수정');
 assert.equal(f.cases[1].projectName,'이름 수정');
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({channel:'msedge',headless:true});let failures=0;
 try{for(const test of tests){
  const context=await browser.newContext({baseURL:'http://127.0.0.1:'+server.address().port});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const f={cases:{1:{id:1,projectName:'첫 프로젝트',performanceProjectId:null},2:{id:2,projectName:'둘째 프로젝트',performanceProjectId:null}},
   requirements:{1:[ordinary,perf],2:[]},selections:{1:[],2:[]},calls:[]};
  await context.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()).pathname,method=req.method(),body=req.postDataJSON();
   f.calls.push({url,method,body});let result;
   if(url==='/api/submission-common-documents')result=[];
   else if(url==='/api/performance-projects')result=[{id:'p1',name:'실적 1'},{id:'p2',name:'실적 2'}];
   else if(url.startsWith('/api/performance-projects/'))result=[];
   else if(url==='/api/submission-cases'){
    if(method==='POST'){f.cases[3]={id:3,...body};f.requirements[3]=[];f.selections[3]=[];result=f.cases[3];}
    else result=Object.values(f.cases).map(c=>({...c,total:f.requirements[c.id].length,prepared:f.selections[c.id].length,updatedAt:'2026-09-09T01:00:00Z'}));
   } else {
    const match=url.match(new RegExp("^/api/submission-cases/([0-9]+)(.*)$"));
    if(!match){await route.fulfill({status:404,json:{}});return;}
    const id=match[1],tail=match[2];
    if(!tail){
     if(method==='PUT'){
      if(!body.initializePerformanceOnly||!f.cases[id].performanceLinkInitialized){Object.assign(f.cases[id],body);if('performanceProjectId'in body)f.cases[id].performanceLinkInitialized=true;}
     }result=f.cases[id];
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