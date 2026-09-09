const API="/api/submission-document-masters";
const cards=document.querySelector("#document-cards"),message=document.querySelector("#documents-message");
async function api(path="",options={}) {
    const response=await fetch(API+path,options);const text=await response.text();let body;try{body=JSON.parse(text);}catch{}
    if(!response.ok)throw new Error(response.status===413?"20MB 이하 파일을 선택하세요.":body?.message||"서류를 처리하지 못했습니다.");
    return body;
}
function node(tag,text,css) {const n=document.createElement(tag);if(text!=null)n.textContent=text;if(css)n.className=css;return n;}
async function load() {
    try {const items=await api();cards.replaceChildren();
        for(const item of items.filter(item=>item.category==="COMPANY_COMMON"))render(item);
        if(!cards.children.length)cards.append(node("p","등록된 회사 공통서류가 없습니다."));
    }catch(error){message.textContent=error.message;}
}
function render(item) {
    const card=node("article",null,"surface-card document-card");card.dataset.id=item.id;
    card.append(node("span","회사 공통","panel-kicker"),node("h2",item.name));
    const registered=Boolean(item.uploadedFileId||item.currentFileId);
    card.append(node("p",item.currentFilename||"파일 미등록"),node("span",registered?"등록됨":"파일 미등록","count-badge"));
    if(item.sizeBytes!=null)card.append(node("small",new Intl.NumberFormat('ko-KR').format(item.sizeBytes)+" bytes · "+new Date(item.uploadedAt).toLocaleString('ko-KR')));
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
    actions.append(upload,edit,remove,input);card.append(actions,form);cards.append(card);
}
document.querySelector("#document-create-form").onsubmit=async event=>{
    event.preventDefault();const form=event.currentTarget,button=form.querySelector("button");button.disabled=true;
    try{await api("",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({name:form.elements.name.value,category:"COMPANY_COMMON"})});form.reset();await load();}catch(error){message.textContent=error.message;}finally{button.disabled=false;}
};
load();
