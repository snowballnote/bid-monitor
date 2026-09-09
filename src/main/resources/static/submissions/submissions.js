const API_BASE = "/api/submission-cases";
const COMMON_DOCUMENT_API = "/api/submission-common-documents";

const elements = {
    documentPicker: document.querySelector("#document-picker"),
    documentForm: document.querySelector("#document-form"),
    documentOptions: [...document.querySelectorAll(".document-option")],
    customDocumentName: document.querySelector("#custom-document-name"),
    addCustomDocument: document.querySelector("#add-custom-document"),
    customDocumentList: document.querySelector("#custom-document-list"),
    checkedDocumentCount: document.querySelector("#checked-document-count"),
    findDocumentsButton: document.querySelector("#find-documents-button"),
    documentMessage: document.querySelector("#document-message"),
    workspaceLoading: document.querySelector("#workspace-loading"),
    workspaceError: document.querySelector("#workspace-error"),
    workspace: document.querySelector("#submission-workspace"),
    projectName: document.querySelector("#case-project-name"),
    projectMeta: document.querySelector("#case-project-meta"),
    changeDocumentsButton: document.querySelector("#change-documents-button"),
    progressPercent: document.querySelector("#progress-percent"),
    progressBar: document.querySelector("#progress-bar"),
    progressTrack: document.querySelector(".progress-track"),
    progressCaption: document.querySelector("#progress-caption"),
    requirementCount: document.querySelector("#requirement-count"),
    requirementEmpty: document.querySelector("#requirement-empty"),
    requirementList: document.querySelector("#requirement-list"),
    candidateTitle: document.querySelector("#candidate-title"),
    candidateCount: document.querySelector("#candidate-count"),
    candidateGuide: document.querySelector("#candidate-guide"),
    candidateLoading: document.querySelector("#candidate-loading"),
    candidateEmpty: document.querySelector("#candidate-empty"),
    candidatePerformance: document.querySelector("#candidate-performance"),
    candidateError: document.querySelector("#candidate-error"),
    candidateList: document.querySelector("#candidate-list"),
    packageSelectionCount: document.querySelector("#package-selection-count"),
    packageEmpty: document.querySelector("#package-empty"),
    packageList: document.querySelector("#package-list"),
};

let workspaceRevision = 0;
const state = {
    customDocuments: [],
    submissionCase: null,
    requirements: [],
    selections: new Map(),
    activeRequirementId: null,
    candidates: new Map(),
    candidateLoadingIds: new Set(),
    candidateErrors: new Map(),
    commonDocuments: new Map(),
    commonMissing: new Set(),
    performance: { projectId: "", projects: [], total: 0, processed: 0, loading: false, error: "", revision: 0 },
};

class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

function setHidden(element, hidden) {
    element.classList.toggle("hidden", hidden);
}

async function requestJson(url, options = {}) {
    const response = await fetch(url, options);
    const text = await response.text();
    let body = null;
    if (text) {
        try { body = JSON.parse(text); } catch { body = null; }
    }
    if (!response.ok) throw new ApiError(response.status, body?.message || "요청을 처리하지 못했습니다.");
    return body;
}

function categoryLabel(category) {
    return ({ COMPANY_GENERAL: "회사 일반", FINANCIAL: "재무·경영", CERTIFICATION_LICENSE: "인증·면허",
        PERFORMANCE: "실적", PERSONNEL: "인력", SECURITY: "보안", OTHER: "기타" })[category] || "기타";
}

function formatDate(value) {
    if (!value) return "수정일 미확인";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "수정일 미확인";
    return new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
}

function friendlyError(error, action) {
    if (error instanceof ApiError && error.status === 503) return "회사 DB에 연결할 수 없습니다. 관리자에게 연결 상태를 확인해 주세요.";
    if (error instanceof ApiError && error.status === 404) return "제출서류 작업을 찾을 수 없습니다.";
    if (error instanceof ApiError && error.status === 400) return error.message;
    return action === "candidate" ? "회사 파일 후보를 불러오지 못했습니다." : "제출서류 작업을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

function normalizeName(value) {
    return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("ko-KR");
}

function selectedRequirements() {
    const checked = elements.documentOptions.filter((option) => option.checked).map((option) => ({
        category: option.dataset.category, documentName: option.value, sourceReference: option.dataset.reference
    }));
    const custom = state.customDocuments.map((documentName, index) => ({
        category: state.requirements.find(r => normalizeName(r.documentName) === normalizeName(documentName))?.category || "OTHER", documentName, sourceReference: state.requirements.find(r => normalizeName(r.documentName) === normalizeName(documentName))?.sourceReference || `CUSTOM_${index + 1}`
    }));
    return [...checked, ...custom];
}

function updateCheckedCount() {
    elements.checkedDocumentCount.textContent = String(selectedRequirements().length);
}

function commonStatusClass(status) {
    return ({ AVAILABLE: "available", REFRESH_RECOMMENDED: "refresh", EXPIRING_SOON: "expiring",
        EXPIRED: "expired", UNREGISTERED: "unregistered" })[status] || "unregistered";
}

function renderCommonDocumentStatuses() {
    elements.documentOptions.forEach((option) => {
        option.setAttribute("aria-label", option.value);
        const managed = state.commonDocuments.get(option.dataset.reference);
        const label = option.closest("label");
        let badge = label.querySelector(".document-admin-status");
        const currentMaster=commonMaster({sourceReference:option.dataset.reference,documentName:option.value,category:option.dataset.category});
        if(currentMaster){
            if(!badge){badge=document.createElement("small");label.append(badge);}
            badge.className="document-admin-status "+((currentMaster.uploadedFileId || currentMaster.currentFileId)?"available":"unregistered");
            badge.textContent=(currentMaster.uploadedFileId || currentMaster.currentFileId)?"현재 파일 등록됨":"파일 미등록";
            badge.title=currentMaster.currentFilename||"서류 관리에서 현재 파일을 등록하세요.";
            return;
        }
        if (!managed) {
            badge?.remove();
            return;
        }
        if (!badge) {
            badge = document.createElement("small");
            badge.className = "document-admin-status";
            label.append(badge);
        }
        badge.className = `document-admin-status ${commonStatusClass(managed.status)}`;
        badge.textContent = managed.statusDisplayName;
        const policy = managed.refreshPolicy === "PERIODIC"
            ? `${managed.refreshIntervalMonths}개월 주기`
            : managed.refreshPolicy === "EXPIRATION_BASED" ? "유효기간 관리" : "유효기간 없음";
        badge.title = managed.originalFilename ? `${policy} · ${managed.originalFilename}` : policy;
    });
}

async function loadCommonDocuments() {
    try {
        const documents = await requestJson(COMMON_DOCUMENT_API);
        state.commonDocuments = new Map((documents || []).map((item) => [item.documentType, item]));
        renderCommonDocumentStatuses();
    } catch {
        state.commonDocuments.clear();
    }
}

function renderCustomDocuments() {
    elements.customDocumentList.replaceChildren();
    state.customDocuments.forEach((documentName, index) => {
        const item = document.createElement("li");
        const text = document.createElement("span");
        text.textContent = documentName;
        const remove = document.createElement("button");
        remove.type = "button";
        remove.setAttribute("aria-label", `${documentName} 삭제`);
        remove.textContent = "×";
        remove.addEventListener("click", () => {
            state.customDocuments.splice(index, 1);
            renderCustomDocuments();
            updateCheckedCount();
        });
        item.append(text, remove);
        elements.customDocumentList.append(item);
    });
}

function addCustomDocument() {
    const name = elements.customDocumentName.value.trim().replace(/\s+/g, " ");
    if (!name) {
        elements.documentMessage.textContent = "추가할 서류명을 입력해 주세요.";
        setHidden(elements.documentMessage, false);
        return;
    }
    if (selectedRequirements().some((item) => normalizeName(item.documentName) === normalizeName(name))) {
        elements.documentMessage.textContent = "이미 선택하거나 추가한 서류입니다.";
        setHidden(elements.documentMessage, false);
        return;
    }
    state.customDocuments.push(name);
    elements.customDocumentName.value = "";
    setHidden(elements.documentMessage, true);
    renderCustomDocuments();
    updateCheckedCount();
    elements.customDocumentName.focus();
}

function performanceComplete() {
    const progress = state.performance;
    return !progress.loading && !progress.error && progress.total > 0 && progress.processed === progress.total;
}

function requirementComplete(requirement) {
    if (state.commonMissing.has(requirement.id)) return false;
    return requirement.performanceSelectionRequired ? performanceComplete() : state.selections.has(requirement.id);
}

function performanceCaption() {
    const progress = state.performance;
    if (progress.loading) return "실적 진행상황 조회 중";
    if (progress.error) return progress.error;
    if (!progress.projectId) return "실적증빙 관리를 열면 프로젝트가 자동 연결됩니다.";
    if (!progress.total) return "등록된 실적 없음 · 실적표를 붙여넣으세요.";
    return `${progress.total}건 중 ${progress.processed}건 처리 / ${progress.total - progress.processed}건 미처리`;
}

function performanceManageUrl() {
    const params = new URLSearchParams();
    if (state.submissionCase) params.set("caseId", state.submissionCase.id);
    if (state.performance.projectId) params.set("project", state.performance.projectId);
    return "/performances/index.html" + (params.size ? "?" + params : "");
}

function renderPerformancePanel() {
    const progress = state.performance;
    document.querySelector("#performance-progress").textContent = performanceCaption();
    document.querySelector("#performance-manage").href = performanceManageUrl();
    document.querySelector("#performance-refresh").disabled = progress.loading;
}

function renderPerformanceProgress() {
    renderPerformancePanel();
    renderRequirements();
    renderPackage();
}

function storedPerformanceProject() { return state.submissionCase?.performanceProjectId || ""; }

async function rememberPerformanceProject(id, initializeOnly = false) {
    const current = state.submissionCase;
    const revision = workspaceRevision;
    if (!current) return;
    try {
        const updated = await requestJson(API_BASE + "/" + current.id, {
            method: "PUT", headers: {"Content-Type":"application/json"},
            body: JSON.stringify({performanceProjectId:id || null, initializePerformanceOnly:initializeOnly})
        });
        if (revision !== workspaceRevision) return;
        state.submissionCase = updated;
        state.performance.projectId = updated.performanceProjectId || "";
        try { localStorage.removeItem("biz-assist.performance-project." + current.id); } catch {}
        await refreshPerformanceProgress();
    } catch (error) {
        if (revision !== workspaceRevision) return;
        state.performance.error = friendlyError(error, "selection");
        renderPerformanceProgress();
    }
}
async function migratePerformanceLink() {
    const current = state.submissionCase;
    const revision = workspaceRevision;
    if (!current || current.performanceProjectId || current.performanceLinkInitialized
            || !state.requirements.some(r => r.performanceSelectionRequired)) return;
    let legacy = "";
    try { legacy = localStorage.getItem("biz-assist.performance-project." + current.id) || ""; } catch {}
    if (!legacy) return;
    const projects = await requestJson("/api/performance-projects");
    if (revision !== workspaceRevision) return;
    if (projects.some(p => p.id === legacy)) await rememberPerformanceProject(legacy, true);
}async function refreshPerformanceProgress() {
    if (!state.submissionCase || !state.requirements.some(item => item.performanceSelectionRequired)) return;
    const progress = state.performance;
    const revision = ++progress.revision;
    progress.loading = true;
    progress.error = "";
    progress.total = 0;
    progress.processed = 0;
    renderPerformanceProgress();
    try {
        const projects = await requestJson("/api/performance-projects");
        if (state.performance !== progress || progress.revision !== revision) return;
        progress.projects = Array.isArray(projects) ? projects : [];
        if (progress.projectId) {
            if (!progress.projects.some(project => project.id === progress.projectId)) {
                throw new Error("연결한 실적 프로젝트를 찾을 수 없습니다.");
            }
            const entries = await requestJson("/api/performance-projects/" + encodeURIComponent(progress.projectId) + "/entries");
            if (state.performance !== progress || progress.revision !== revision) return;
            if (!Array.isArray(entries)) throw new Error("실적 진행상황을 확인할 수 없습니다.");
            progress.total = entries.length;
            progress.processed = entries.filter(entry => (entry.info?.selectedFileId != null || entry.info?.selectedDriveFileId != null)).length;
        }
    } catch {
        if (state.performance !== progress || progress.revision !== revision) return;
        progress.error = "실적 진행상황 확인 필요 · 프로젝트를 확인하거나 새로고침하세요.";
    } finally {
        if (state.performance === progress && progress.revision === revision) {
            progress.loading = false;
            renderPerformanceProgress();
        }
    }
}
function renderProgress() {
    const total = state.requirements.length;
    const selected = state.requirements.filter(requirementComplete).length;
    const percent = total ? Math.round((selected / total) * 100) : 0;
    elements.progressPercent.textContent = `${percent}%`;
    elements.progressBar.style.width = `${percent}%`;
    elements.progressTrack.setAttribute("aria-valuenow", String(percent));
    elements.progressCaption.textContent = `${total}개 중 ${selected}개 완료`;
    elements.packageSelectionCount.textContent = `${selected} / ${total}`;
}

function requirementState(requirement) {
    if (state.commonMissing.has(requirement.id)) return ["파일 미등록", " attention"];
    if (isCompanyCommon(requirement) && !state.selections.has(requirement.id)) return [(commonMaster(requirement)?.uploadedFileId || commonMaster(requirement)?.currentFileId) ? "모으기 필요" : "파일 미등록", " attention"];
    if (requirement.performanceSelectionRequired) {
        if (performanceComplete()) return ["완료", " complete"];
        if (state.performance.loading) return ["확인 중", ""];
        if (state.performance.error) return ["확인 필요", " attention"];
        return ["진행중", " attention"];
    }
    if (state.selections.has(requirement.id)) return ["선택 완료", " complete"];
    if (state.candidateLoadingIds.has(requirement.id)) return ["검색 중", ""];
    if (state.candidateErrors.has(requirement.id)) return ["조회 실패", " attention"];
    if (state.candidates.has(requirement.id)) {
        const count = state.candidates.get(requirement.id).length;
        return [count ? `${count}개 후보` : "후보 없음", count ? " available" : ""];
    }
    return ["검색 대기", ""];
}

function renderRequirements() {
    elements.requirementList.replaceChildren();
    elements.requirementCount.textContent = `${state.requirements.length}건`;
    setHidden(elements.requirementEmpty, state.requirements.length !== 0);
    state.requirements.forEach((requirement) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "requirement-item";
        button.classList.toggle("active", requirement.id === state.activeRequirementId);
        const content = document.createElement("span");
        const title = document.createElement("span");
        title.className = "requirement-item-title";
        title.textContent = requirement.performanceSelectionRequired ? requirement.documentName + " · 실적증빙 관리" : requirement.documentName;
        const meta = document.createElement("span");
        meta.className = "requirement-item-meta";
        meta.textContent = requirement.performanceSelectionRequired ? performanceCaption() : isCompanyCommon(requirement) ? (state.selections.get(requirement.id)?.originalFilename || "회사 공통 · " + ((commonMaster(requirement)?.uploadedFileId || commonMaster(requirement)?.currentFileId) ? "현재 파일 등록됨" : "파일 미등록")) : categoryLabel(requirement.category);
        meta.classList.toggle("performance-progress-meta", Boolean(requirement.performanceSelectionRequired));
        content.append(title, meta);
        const completion = document.createElement("span");
        const [label, className] = requirementState(requirement);
        completion.className = `requirement-state${className}`;
        completion.textContent = label;
        button.append(content, completion);
        button.addEventListener("click", () => requirement.performanceSelectionRequired ? openPerformanceProject() : selectRequirement(requirement, true));
        elements.requirementList.append(button);
    });
}

function renderPackage() {
    const download=document.querySelector("#download-submission-files");
    if(download){download.href=state.submissionCase?API_BASE+"/"+state.submissionCase.id+"/download":"#";setHidden(download,![...state.selections.values()].some(file=>file.uploadedFileId));}
    elements.packageList.replaceChildren();
    const selected = state.requirements.filter(requirement => requirement.performanceSelectionRequired || state.selections.has(requirement.id));
    setHidden(elements.packageEmpty, selected.length !== 0);
    setHidden(elements.packageList, selected.length === 0);
    selected.forEach((requirement) => {
        const item = document.createElement("li");
        const name = document.createElement("strong");
        name.textContent = requirement.documentName;
        const filename = document.createElement("span");
        filename.textContent = requirement.performanceSelectionRequired ? performanceCaption() : state.selections.get(requirement.id).originalFilename;
        item.append(name, filename);
        if (requirement.performanceSelectionRequired) {
            const manage = document.createElement("a");
            manage.className = "ui-button ui-button-secondary";
            manage.href = performanceManageUrl();
            manage.textContent = "실적증빙 관리";
            item.append(manage);
        }
        elements.packageList.append(item);
    });
    renderProgress();
}

function showCandidateState(name) {
    const states = { guide: elements.candidateGuide, loading: elements.candidateLoading, empty: elements.candidateEmpty,
        performance: elements.candidatePerformance, error: elements.candidateError, list: elements.candidateList };
    Object.entries(states).forEach(([key, element]) => setHidden(element, key !== name));
    setHidden(elements.candidateCount, name !== "list");
}

function renderCandidates(requirement, candidates) {
    elements.candidateList.replaceChildren();
    elements.candidateTitle.textContent = requirement.documentName;
    elements.candidateCount.textContent = `${candidates.length}건`;
    if (!candidates.length) {
        showCandidateState("empty");
        return;
    }
    candidates.forEach((candidate) => {
        const selected = state.selections.get(requirement.id)?.fileId === candidate.fileId;
        const card = document.createElement("article");
        card.className = `candidate-item${selected ? " selected" : ""}`;
        const head = document.createElement("div");
        head.className = "candidate-head";
        const name = document.createElement("h3");
        name.className = "candidate-name";
        name.textContent = candidate.originalFilename;
        const badge = document.createElement("span");
        badge.className = `match-badge ${candidate.matchLevel === "EXACT" ? "exact" : "recommended"}`;
        badge.textContent = candidate.matchLevel === "EXACT" ? "정확히 일치" : "추천";
        head.append(name, badge);
        const meta = document.createElement("p");
        meta.className = "candidate-meta";
        meta.textContent = `${candidate.fileExt ? candidate.fileExt.toUpperCase() : "파일"} · ${formatDate(candidate.fileModifiedAt || candidate.updatedAt)}`;
        const reason = document.createElement("p");
        reason.className = "candidate-reason";
        reason.textContent = candidate.matchReasons?.length ? candidate.matchReasons.join(" · ") : "파일명 키워드 일치";
        const actions = document.createElement("div");
        actions.className = "candidate-actions";
        const select = document.createElement("button");
        select.type = "button";
        select.className = "ui-button ui-button-secondary candidate-select";
        select.textContent = selected ? "선택됨" : "이 파일 선택";
        select.disabled = selected;
        select.addEventListener("click", () => saveSelection(requirement, candidate));
        actions.append(select);

        card.append(head, meta, reason, actions);
        elements.candidateList.append(card);
    });
    showCandidateState("list");
}

async function fetchCandidates(requirement) {
    const revision = workspaceRevision, caseId = state.submissionCase.id;
    state.candidateLoadingIds.add(requirement.id);
    state.candidateErrors.delete(requirement.id);
    renderRequirements();
    try {
        const candidates = await requestJson(`${API_BASE}/${state.submissionCase.id}/requirements/${requirement.id}/candidates`);
        if (revision !== workspaceRevision) return;
        state.candidates.set(requirement.id, Array.isArray(candidates) ? candidates : []);
    } catch (error) {
        if (revision !== workspaceRevision) return;
        state.candidateErrors.set(requirement.id, friendlyError(error, "candidate"));
    } finally {
        if (revision !== workspaceRevision) return;
        state.candidateLoadingIds.delete(requirement.id);
        renderRequirements();
    }
}

async function preloadCandidates() {
    const revision = workspaceRevision;
    await Promise.all(state.requirements.filter((item) => !item.performanceSelectionRequired && !isCompanyCommon(item)).map(fetchCandidates));
    if (revision !== workspaceRevision) return;
    if (state.activeRequirementId) {
        const active = state.requirements.find((item) => item.id === state.activeRequirementId);
        if (active) selectRequirement(active);
    }
}

async function selectRequirement(requirement, retry = false) {
    const revision = workspaceRevision;
    state.activeRequirementId = requirement.id;
    renderRequirements();
    elements.candidateTitle.textContent = requirement.documentName;
    if (requirement.performanceSelectionRequired) {
        renderPerformancePanel();
        showCandidateState("performance");
        return;
    }
    if (isCompanyCommon(requirement)) {
        elements.candidateList.replaceChildren();
        const message=document.createElement("p"), selected=state.selections.get(requirement.id);
        message.textContent=selected ? "연결된 파일: "+selected.originalFilename : (commonMaster(requirement)?.uploadedFileId || commonMaster(requirement)?.currentFileId) ? "선택한 서류 모으기를 실행하면 저장된 현재 파일이 연결됩니다." : "파일 미등록 · 서류 관리에서 현재 파일을 등록하세요.";
        elements.candidateList.append(message); showCandidateState("list"); setHidden(elements.candidateCount,true); return;
    }
    if (retry) state.candidateErrors.delete(requirement.id);
    if (state.candidateErrors.has(requirement.id)) {
        elements.candidateError.textContent = state.candidateErrors.get(requirement.id);
        showCandidateState("error");
        return;
    }
    if (!state.candidates.has(requirement.id)) {
        showCandidateState("loading");
        await fetchCandidates(requirement);
    }
    if (revision !== workspaceRevision || state.activeRequirementId !== requirement.id) return;
    if (state.candidateErrors.has(requirement.id)) {
        elements.candidateError.textContent = state.candidateErrors.get(requirement.id);
        showCandidateState("error");
    } else if (state.candidates.has(requirement.id)) renderCandidates(requirement, state.candidates.get(requirement.id));
}

async function saveSelection(requirement, candidate) {
    const revision = workspaceRevision, caseId = state.submissionCase.id;
    const previous = new Map(state.selections);
    state.selections.set(requirement.id, candidate);
    renderRequirements();
    renderCandidates(requirement, state.candidates.get(requirement.id) || []);
    renderPackage();
    try {
        const selections = [...state.selections].map(([requirementId, file]) => ({ requirementId, fileId: file.fileId }));
        await requestJson(`${API_BASE}/${caseId}/selections`, {
            method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ selections })
        });
        if (revision !== workspaceRevision) return;
        const packageData = await requestJson(`${API_BASE}/${caseId}/package`);
        if (revision !== workspaceRevision) return;
        state.selections = new Map((packageData.selections || []).map((item) => [item.requirementId, item]));
        renderRequirements();
        renderCandidates(requirement, state.candidates.get(requirement.id) || []);
        renderPackage();
    } catch (error) {
        if (revision !== workspaceRevision) return;
        state.selections = previous;
        elements.candidateError.textContent = friendlyError(error, "selection");
        showCandidateState("error");
        renderRequirements();
        renderPackage();
    }
}

async function loadWorkspace(submissionCase, openCandidates = false) {
    const revision = ++workspaceRevision;
    setHidden(document.querySelector("#submission-project-list"), true);
    setHidden(document.querySelector("#submission-detail-toolbar"), false);
    state.submissionCase = submissionCase;
    state.activeRequirementId = null;
    state.commonMissing.clear();
    state.candidates.clear();
    state.candidateErrors.clear();
    state.candidateLoadingIds.clear();
    setHidden(elements.workspaceLoading, false);
    setHidden(elements.workspaceError, true);
    setHidden(elements.workspace, true);
    try {
        const [requirements, packageData] = await Promise.all([
            requestJson(`${API_BASE}/${submissionCase.id}/requirements`), requestJson(`${API_BASE}/${submissionCase.id}/package`)
        ]);
        if (revision !== workspaceRevision) return;
        state.requirements = Array.isArray(requirements) ? requirements : [];
        state.performance = { projectId: storedPerformanceProject(), projects: [], total: 0, processed: 0, loading: false, error: "", revision: 0 };
        state.selections = new Map((packageData.selections || []).map((item) => [item.requirementId, item]));
        elements.projectName.textContent = submissionCase.projectName;
        document.querySelector("#rename-submission-project [name=projectName]").value = submissionCase.projectName;
        document.querySelector("#rename-submission-project [name=deadline]").value = submissionCase.deadline || "";
        document.querySelector("#case-project-deadline").textContent = "마감일 " + (submissionCase.deadline || "미설정");
        elements.projectMeta.textContent = `${state.requirements.length}개 서류의 회사 파일 후보를 확인합니다.`;
        renderRequirements();
        renderPackage();
        showCandidateState("guide");
        setHidden(elements.workspace, false);
        resetWorkspace();
        if (openCandidates) {
            state.activeRequirementId = state.requirements.find(item => !item.performanceSelectionRequired)?.id || null;
            if (state.activeRequirementId) {
                renderRequirements();
                showCandidateState("loading");
                elements.candidateTitle.closest(".candidate-panel").scrollIntoView({behavior:"smooth", block:"start"});
            }
        }
        preloadCandidates();
        refreshPerformanceProgress();
        migratePerformanceLink().catch(() => {});
    } catch (error) {
        if (revision !== workspaceRevision) return;
        elements.workspaceError.textContent = friendlyError(error, "workspace");
        setHidden(elements.workspaceError, false);
    } finally {
        if (revision === workspaceRevision) setHidden(elements.workspaceLoading, true);
    }
}

async function createManualCase(event) {
    event.preventDefault();
    const current = state.submissionCase, revision = workspaceRevision;
    if (!current) return;
    elements.findDocumentsButton.disabled = true;
    try {
        const collection = await requestJson(API_BASE + "/" + current.id + "/collect", {
            method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(selectedRequirements())
        });
        if (revision !== workspaceRevision) return;
        const updated = await requestJson(API_BASE + "/" + current.id);
        if (revision !== workspaceRevision) return;
        const expectedRevision=workspaceRevision+1;
        await loadWorkspace(updated, true);
        if (workspaceRevision!==expectedRevision || state.submissionCase?.id !== current.id) return;
        state.commonMissing = new Set(collection.missingRequirementIds || []);
        renderRequirements(); renderPackage();
    } catch (error) {
        if (revision !== workspaceRevision) return;
        elements.documentMessage.textContent = friendlyError(error, "create");
        setHidden(elements.documentMessage,false);
    } finally { elements.findDocumentsButton.disabled = false; }
}
function resetWorkspace() {
    if (!state.submissionCase) return;
    renderMasterOptions(state.requirements);
    state.customDocuments = [];
    renderCustomDocuments(); updateCheckedCount();
    setHidden(elements.documentPicker, false);
    document.querySelector(".performance-picker-action").href = performanceManageUrl();
}
async function restoreCaseFromUrl() {
    const id = new URLSearchParams(location.search).get("caseId");
    if (id && /^[0-9]+$/.test(id)) await openSubmissionProject(id);
    else await showSubmissionProjects();
}
elements.documentOptions.forEach((option) => option.addEventListener("change", updateCheckedCount));
elements.addCustomDocument.addEventListener("click", addCustomDocument);
elements.customDocumentName.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
        event.preventDefault();
        addCustomDocument();
    }
});
elements.documentForm.addEventListener("submit", createManualCase);
elements.changeDocumentsButton.addEventListener("click", () => elements.documentPicker.scrollIntoView({behavior:"smooth"}));
updateCheckedCount();
document.querySelector(".case-overview").after(elements.documentPicker);

initializeSubmissionHistory();
Promise.all([loadCommonDocuments(), loadDocumentMasters()]).finally(restoreCaseFromUrl);


document.querySelector("#performance-refresh").addEventListener("click", refreshPerformanceProgress);
window.addEventListener("focus", refreshPerformanceProgress);
window.addEventListener("pageshow", (event) => { if (event.persisted) refreshPerformanceProgress(); });

let projectRows = [];
let projectView = "card";
try { if (localStorage.getItem("biz-assist.submissions.view") === "list") projectView = "list"; } catch {}
function projectNode(tag, text, css) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (css) node.className = css;
    return node;
}
function renderSubmissionProjects() {
    const container = document.querySelector("#submission-project-items");
    container.replaceChildren();
    container.classList.toggle("project-cards", projectView === "card");
    document.querySelector("#view-card").setAttribute("aria-pressed", String(projectView === "card"));
    document.querySelector("#view-list").setAttribute("aria-pressed", String(projectView === "list"));
    let body = container;
    if (projectView === "list") {
        const table = projectNode("table", null, "submission-project-table");
        const head = projectNode("tr");
        ["프로젝트명","마감일","준비 현황","진행률","최근 수정일"].forEach(text => head.append(projectNode("th",text)));
        const thead = projectNode("thead"); thead.append(head); body = projectNode("tbody");
        table.append(thead,body); container.append(table);
    }
    for (const project of projectRows) {
        const count = project.prepared + " / " + project.total;
        const percent = project.total ? Math.round(project.prepared / project.total * 100) : 0;
        const deadline = project.deadline || "미설정";
        const open = event => { event.preventDefault(); openSubmissionProject(project.id); };
        if (projectView === "card") {
            const card = projectNode("a",null,"submission-project-card");
            card.href = "/submissions/?caseId=" + encodeURIComponent(project.id);
            card.onclick = event => {
                if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
                open(event);
            };
            card.append(projectNode("h3",project.projectName),projectNode("p","마감일 " + deadline),
                projectNode("p","준비 " + count),projectNode("p","진행률 " + percent + "%"),
                projectNode("p","최근 수정일 " + formatDate(project.updatedAt)));
            body.append(card);
        } else {
            const row = projectNode("tr"); row.tabIndex = 0; row.setAttribute("role","link");
            row.setAttribute("aria-label",project.projectName + " 상세");
            row.onclick = open;
            row.onkeydown = event => { if (event.key === "Enter" || event.key === " ") open(event); };
            [project.projectName,deadline,count,percent+"%",formatDate(project.updatedAt)].forEach(text => row.append(projectNode("td",text)));
            body.append(row);
        }
    }
    document.querySelector("#submission-project-message").textContent = projectRows.length ? "" : "등록된 프로젝트가 없습니다.";
}
function changeProjectView(view) {
    try { localStorage.setItem("biz-assist.submissions.view", view); projectView = view; }
    catch { projectView = "card"; }
    renderSubmissionProjects();
}
async function showSubmissionProjects() {
    const revision = ++workspaceRevision;
    state.submissionCase = null;
    state.performance = {projectId:"",projects:[],total:0,processed:0,loading:false,error:"",revision:0};
    setHidden(elements.workspace,true); setHidden(elements.documentPicker,true);
    setHidden(elements.workspaceLoading,true); setHidden(elements.workspaceError,true);
    setHidden(document.querySelector("#submission-detail-toolbar"),true);
    setHidden(document.querySelector("#submission-project-list"),false);

    try {
        const rows = await requestJson(API_BASE);
        if (revision !== workspaceRevision) return;
        projectRows = rows; renderSubmissionProjects();
    } catch (error) {
        if (revision === workspaceRevision) document.querySelector("#submission-project-message").textContent = friendlyError(error,"list");
    }
}
async function openSubmissionProject(id) {
    const revision = ++workspaceRevision;
    state.submissionCase = null;
    state.performance = {projectId:"",projects:[],total:0,processed:0,loading:false,error:"",revision:0};
    setHidden(document.querySelector("#submission-project-list"),true);
    setHidden(document.querySelector("#submission-detail-toolbar"),false);
    setHidden(elements.workspace,true); setHidden(elements.documentPicker,true);
    setHidden(elements.workspaceLoading,false);
    navigateSubmissionDetail(id);
    try {
        const project = await requestJson(API_BASE + "/" + encodeURIComponent(id));
        if (revision !== workspaceRevision) return;
        await loadWorkspace(project);
    } catch (error) {
        if (revision !== workspaceRevision) return;
        elements.workspaceError.textContent = friendlyError(error,"workspace"); setHidden(elements.workspaceError,false);
    } finally { if (revision === workspaceRevision) setHidden(elements.workspaceLoading,true); }
}
document.querySelector("#new-submission-project").onclick = () => {
    const form = document.querySelector("#create-submission-project");
    form.classList.toggle("hidden"); if (!form.classList.contains("hidden")) form.elements.projectName.focus();
};
document.querySelector("#view-card").onclick = () => changeProjectView("card");
document.querySelector("#view-list").onclick = () => changeProjectView("list");
document.querySelector("#back-submission-projects").onclick = goToSubmissionList;
document.querySelector("#create-submission-project").onsubmit = async event => {
    event.preventDefault();
    const form = event.currentTarget, button = form.querySelector("button"), revision = workspaceRevision;
    button.disabled = true;
    try {
        const project = await requestJson(API_BASE,{method:"POST",headers:{"Content-Type":"application/json"},
            body:JSON.stringify({projectName:form.elements.projectName.value,deadline:form.elements.deadline.value})});
        if (revision !== workspaceRevision) return;
        form.reset(); setHidden(form,true);
        await openSubmissionProject(project.id);
    } catch (error) {
        if (revision === workspaceRevision) document.querySelector("#submission-project-message").textContent = friendlyError(error,"create");
    } finally { button.disabled = false; }
};
document.querySelector("#rename-submission-project").onsubmit = async event => {
    event.preventDefault();
    const current = state.submissionCase, revision = workspaceRevision;
    if (!current) return;
    try {
        const updated = await requestJson(API_BASE + "/" + current.id,{method:"PUT",headers:{"Content-Type":"application/json"},
            body:JSON.stringify({projectName:event.currentTarget.elements.projectName.value,deadline:event.currentTarget.elements.deadline.value})});
        if (revision !== workspaceRevision) return;
        state.submissionCase = updated; elements.projectName.textContent = updated.projectName;
        document.querySelector("#case-project-deadline").textContent = "마감일 " + (updated.deadline || "미설정");
    } catch(error) {
        if(revision === workspaceRevision){elements.workspaceError.textContent=friendlyError(error,"update");setHidden(elements.workspaceError,false);}
    }
};
window.addEventListener("popstate", restoreCaseFromUrl);
function initializeSubmissionHistory() {
    const id = new URLSearchParams(location.search).get("caseId");
    if (id && /^[0-9]+$/.test(id)) {
        if (!history.state?.submissionDetail) {
            const detailUrl = location.pathname + location.search;
            history.replaceState({submissionList:true}, "", "/submissions/");
            history.pushState({submissionDetail:true}, "", detailUrl);
        }
    } else history.replaceState({submissionList:true}, "", "/submissions/");
}
function navigateSubmissionDetail(id) {
    const url = "/submissions/?caseId=" + encodeURIComponent(id);
    if (location.pathname + location.search === url && history.state?.submissionDetail) return;
    if (history.state?.submissionDetail) history.replaceState({submissionDetail:true}, "", url);
    else history.pushState({submissionDetail:true}, "", url);
}
function goToSubmissionList() {
    if (history.state?.submissionDetail) history.back();
    else {
        history.replaceState({submissionList:true}, "", "/submissions/");
        showSubmissionProjects();
    }
}
document.querySelector("#delete-submission-project").onclick = async () => {
    const current = state.submissionCase, revision = workspaceRevision;
    if (!current || !confirm('이 프로젝트와 선택한 제출서류 연결을 삭제할까요? 원본 파일은 삭제되지 않습니다.')) return;
    const button = document.querySelector("#delete-submission-project");
    button.disabled = true;
    try {
        await requestJson(API_BASE + "/" + current.id, {method:"DELETE"});
        if (revision === workspaceRevision) goToSubmissionList();
    } catch (error) {
        if (revision === workspaceRevision) {
            elements.workspaceError.textContent = friendlyError(error,"delete");
            setHidden(elements.workspaceError,false);
        }
    } finally { button.disabled = false; }
};
const MASTER_API = "/api/submission-document-masters";
let documentMasters = [], performanceOpening = false;
function commonMaster(requirement) {
    return documentMasters.find(item => item.category === "COMPANY_COMMON" && (item.sourceReference === requirement.sourceReference || (item.name === requirement.documentName && item.requirementCategory === requirement.category)));
}
function isCompanyCommon(requirement) { return Boolean(requirement.companyCommon || commonMaster(requirement)); }
function masterCategory(category) {
    return category === "PERFORMANCE" ? "PERFORMANCE" : category === "PERSONNEL" ? "PERSONNEL" : category === "OTHER" ? "OTHER" : "COMPANY_COMMON";
}
function renderMasterOptions(selected = []) {
    document.querySelectorAll(".master-options").forEach(group => group.replaceChildren());
    const options = documentMasters.map(item => ({...item, documentName:item.name, category:item.requirementCategory, group:item.category}));
    // Renamed/deleted master entries never remove the project's saved snapshots.
    for (const item of selected) {
        if (!options.some(o => o.category === item.category && normalizeName(o.documentName) === normalizeName(item.documentName)))
            options.push({...item, group:masterCategory(item.category)});
    }
    for (const item of options) {
        const label = document.createElement("label"), input = document.createElement("input"), text = document.createElement("span");
        input.type="checkbox"; input.className="document-option"; input.value=item.documentName;
        input.dataset.category=item.category; input.dataset.reference=item.sourceReference || "";
        input.checked=selected.some(r => r.category===item.category && normalizeName(r.documentName)===normalizeName(item.documentName));
        input.addEventListener("change",updateCheckedCount); text.textContent=item.documentName;
        label.append(input,text); document.querySelector('[data-master-category="'+item.group+'"]').append(label);
    }
    elements.documentOptions=[...document.querySelectorAll(".document-option")];
    renderCommonDocumentStatuses(); updateCheckedCount();
}
async function loadDocumentMasters() {
    try {
        const rows=await requestJson("/api/submission-document-masters");
        documentMasters=rows;
        const selected=state.submissionCase ? selectedRequirements() : [];
        state.customDocuments=[]; renderCustomDocuments();
        renderMasterOptions(selected);

    } catch { elements.documentMessage.textContent="서류 목록을 불러오지 못했습니다.";setHidden(elements.documentMessage,false); }
}
async function openPerformanceProject(event) {
    event?.preventDefault();
    const current=state.submissionCase, revision=workspaceRevision;
    if (!current || performanceOpening) return;
    performanceOpening=true;
    try {
        await migratePerformanceLink();
        if (revision!==workspaceRevision) return;
        const linked=state.submissionCase.performanceProjectId ? state.submissionCase :
            await requestJson(API_BASE+"/"+current.id+"/performance-project",{method:"POST"});
        if (revision!==workspaceRevision) return;
        state.submissionCase=linked; state.performance.projectId=linked.performanceProjectId;
        location.assign(performanceManageUrl());
    } catch(error) {
        if(revision===workspaceRevision){elements.workspaceError.textContent=friendlyError(error,"performance");setHidden(elements.workspaceError,false);}
    } finally { performanceOpening=false; }
}
document.querySelector("#performance-manage").onclick=openPerformanceProject;
document.querySelector(".performance-picker-action").onclick=openPerformanceProject;
