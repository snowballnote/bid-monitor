const API="/api/submission-document-masters";
const rows=document.querySelector("#document-rows"),message=document.querySelector("#documents-message");
async function api(path="",options={}) {
    const response=await fetch(API+path,options);const text=await response.text();let body;try{body=JSON.parse(text);}catch{}
    if(!response.ok)throw new Error(response.status===413?"20MB 이하 파일을 선택하세요.":body?.message||"서류를 처리하지 못했습니다.");
    return body;
}
function node(tag,text,css) {const n=document.createElement(tag);if(text!=null)n.textContent=text;if(css)n.className=css;return n;}
async function load() {
    try {const items=await api();rows.replaceChildren();
        for(const item of items.filter(item=>item.category==="COMPANY_COMMON"))render(item);
        if(!rows.children.length){const row=node("tr"),cell=node("td","등록된 회사 공통서류가 없습니다.","documents-empty");cell.colSpan=5;row.append(cell);rows.append(row);}
    }catch(error){message.textContent=error.message;}
}
function render(item) {
    const row=node("tr");row.dataset.id=item.id;
    const title=node("th",null,"document-name");title.scope="row";title.append(node("span",item.name));
    const fileCell=node("td",null,"document-file"),status=node("td"),actionCell=node("td");
    const registered=Boolean(item.uploadedFileId||item.currentFileId);
    fileCell.append(node("p",item.currentFilename||"파일 미등록"));status.append(node("span",registered?"등록됨":"파일 미등록","count-badge"));
    if(item.sizeBytes!=null)fileCell.append(node("small",new Intl.NumberFormat('ko-KR').format(item.sizeBytes)+" bytes"+(item.uploadedAt?" · "+new Date(item.uploadedAt).toLocaleString('ko-KR'):"")));
    const actions=node("div",null,"document-actions"),upload=node("button",registered?"파일 교체":"파일 등록","ui-button ui-button-primary"),input=node("input");
    input.type="file";input.hidden=true;input.setAttribute("aria-label",item.name+" 파일 선택");
    upload.onclick=()=>input.click();
    input.onchange=async()=>{
        const file=input.files[0];if(!file)return;
        if(file.size===0||file.size>20*1024*1024){message.textContent="비어 있지 않은 20MB 이하 파일을 선택하세요.";input.value="";return;}
        upload.disabled=true;const form=new FormData();form.append("file",file);
        try{await api("/"+encodeURIComponent(item.id)+"/upload",{method:"POST",body:form});message.textContent="현재 파일을 저장했습니다. 기존 프로젝트 연결은 유지됩니다.";await load();}
        catch(error){message.textContent=error.message;upload.disabled=false;input.value="";}
    };
    const edit=node("button","서류명 수정","ui-button ui-button-secondary"),remove=node("button","삭제","ui-button ui-button-secondary"),form=node("form"),name=node("input"),save=node("button","저장","ui-button ui-button-secondary"),cancel=node("button","취소","ui-button ui-button-secondary");
    form.hidden=true;name.value=item.name;name.required=true;name.maxLength=200;name.setAttribute("aria-label","서류명 수정");save.type="submit";cancel.type="button";cancel.onclick=()=>{form.hidden=true;};form.append(name,save,cancel);
    edit.onclick=()=>{form.hidden=!form.hidden;if(!form.hidden)name.focus();};
    form.onsubmit=async event=>{event.preventDefault();save.disabled=true;try{await api("/"+encodeURIComponent(item.id),{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({name:name.value,category:item.category})});await load();}catch(error){message.textContent=error.message;save.disabled=false;}};
    remove.onclick=async()=>{if(!confirm("서류 목록에서 삭제할까요? 기존 프로젝트에 연결된 파일은 유지됩니다."))return;remove.disabled=true;try{await api("/"+encodeURIComponent(item.id),{method:"DELETE"});await load();}catch(error){message.textContent=error.message;remove.disabled=false;}};
    actions.append(upload,edit,remove,input);title.append(form);actionCell.append(actions);row.append(title,node("td","회사 공통"),fileCell,status,actionCell);rows.append(row);
}
const dialog=document.querySelector("#document-create-dialog"),createForm=document.querySelector("#document-create-form"),createMessage=document.querySelector("#document-create-message"),openButton=document.querySelector("#document-create-open"),cancelButton=document.querySelector("#document-create-cancel");
let creating=false;
openButton.onclick=()=>{createForm.reset();createMessage.textContent="";dialog.showModal();};
cancelButton.onclick=()=>dialog.close();
dialog.addEventListener("cancel",event=>{if(creating)event.preventDefault();});
dialog.addEventListener("close",()=>openButton.focus());
createForm.onsubmit=async event=>{
    event.preventDefault();if(creating)return;
    const name=createForm.elements.name.value.trim();
    if(!name){createMessage.textContent="서류명을 입력하세요.";createForm.elements.name.focus();return;}
    const button=createForm.querySelector('[type="submit"]');creating=true;button.disabled=true;cancelButton.disabled=true;createMessage.textContent="";
    try{await api("",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({name,category:createForm.elements.category.value})});dialog.close();createForm.reset();message.textContent="서류를 추가했습니다.";await load();}catch(error){createMessage.textContent=error.message;}finally{creating=false;button.disabled=false;cancelButton.disabled=false;}
};
load();
