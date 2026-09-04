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
    commonDocumentDialog: document.querySelector("#common-document-dialog"),
    commonDocumentForm: document.querySelector("#common-document-form"),
    commonDocumentTitle: document.querySelector("#common-document-title"),
    commonDocumentHelp: document.querySelector("#common-document-help"),
    commonDocumentIssuedAt: document.querySelector("#common-document-issued-at"),
    commonDocumentExpiresAt: document.querySelector("#common-document-expires-at"),
    issuedAtField: document.querySelector("#issued-at-field"),
    expiresAtField: document.querySelector("#expires-at-field"),
    commonDocumentMessage: document.querySelector("#common-document-message"),
    commonDocumentClose: document.querySelector("#common-document-close"),
    commonDocumentCancel: document.querySelector("#common-document-cancel")
};

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
    commonDocumentEditor: null
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
        category: "OTHER", documentName, sourceReference: `CUSTOM_${index + 1}`
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

function renderProgress() {
    const total = state.requirements.length;
    const selected = state.requirements.filter((requirement) => state.selections.has(requirement.id)).length;
    const percent = total ? Math.round((selected / total) * 100) : 0;
    elements.progressPercent.textContent = `${percent}%`;
    elements.progressBar.style.width = `${percent}%`;
    elements.progressTrack.setAttribute("aria-valuenow", String(percent));
    elements.progressCaption.textContent = `${total}개 중 ${selected}개 선택`;
    elements.packageSelectionCount.textContent = `${selected} / ${total}`;
}

function requirementState(requirement) {
    if (state.selections.has(requirement.id)) return ["선택 완료", " complete"];
    if (requirement.performanceSelectionRequired) return ["실적 선택 필요", " attention"];
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
        title.textContent = requirement.documentName;
        const meta = document.createElement("span");
        meta.className = "requirement-item-meta";
        meta.textContent = categoryLabel(requirement.category);
        content.append(title, meta);
        const completion = document.createElement("span");
        const [label, className] = requirementState(requirement);
        completion.className = `requirement-state${className}`;
        completion.textContent = label;
        button.append(content, completion);
        button.addEventListener("click", () => selectRequirement(requirement));
        elements.requirementList.append(button);
    });
}

function renderPackage() {
    elements.packageList.replaceChildren();
    const selected = state.requirements.filter((requirement) => state.selections.has(requirement.id));
    setHidden(elements.packageEmpty, selected.length !== 0);
    setHidden(elements.packageList, selected.length === 0);
    selected.forEach((requirement) => {
        const item = document.createElement("li");
        const name = document.createElement("strong");
        name.textContent = requirement.documentName;
        const filename = document.createElement("span");
        filename.textContent = state.selections.get(requirement.id).originalFilename;
        item.append(name, filename);
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
        if (selected && state.commonDocuments.has(requirement.sourceReference)) {
            const manage = document.createElement("button");
            manage.type = "button";
            manage.className = "ui-button ui-button-ghost candidate-manage";
            manage.textContent = "갱신정보 관리";
            manage.addEventListener("click", () => openCommonDocumentEditor(requirement, candidate));
            actions.append(manage);
        }
        card.append(head, meta, reason, actions);
        elements.candidateList.append(card);
    });
    showCandidateState("list");
}

async function fetchCandidates(requirement) {
    state.candidateLoadingIds.add(requirement.id);
    state.candidateErrors.delete(requirement.id);
    renderRequirements();
    try {
        const candidates = await requestJson(`${API_BASE}/${state.submissionCase.id}/requirements/${requirement.id}/candidates`);
        state.candidates.set(requirement.id, Array.isArray(candidates) ? candidates : []);
    } catch (error) {
        state.candidateErrors.set(requirement.id, friendlyError(error, "candidate"));
    } finally {
        state.candidateLoadingIds.delete(requirement.id);
        renderRequirements();
    }
}

async function preloadCandidates() {
    await Promise.all(state.requirements.filter((item) => !item.performanceSelectionRequired).map(fetchCandidates));
    if (state.activeRequirementId) {
        const active = state.requirements.find((item) => item.id === state.activeRequirementId);
        if (active) selectRequirement(active);
    }
}

async function selectRequirement(requirement) {
    state.activeRequirementId = requirement.id;
    renderRequirements();
    elements.candidateTitle.textContent = requirement.documentName;
    if (requirement.performanceSelectionRequired) {
        showCandidateState("performance");
        return;
    }
    if (state.candidateErrors.has(requirement.id)) {
        elements.candidateError.textContent = state.candidateErrors.get(requirement.id);
        showCandidateState("error");
        return;
    }
    if (!state.candidates.has(requirement.id)) {
        showCandidateState("loading");
        await fetchCandidates(requirement);
    }
    if (state.candidates.has(requirement.id)) renderCandidates(requirement, state.candidates.get(requirement.id));
}

async function saveSelection(requirement, candidate) {
    const previous = new Map(state.selections);
    state.selections.set(requirement.id, candidate);
    renderRequirements();
    renderCandidates(requirement, state.candidates.get(requirement.id) || []);
    renderPackage();
    try {
        const selections = [...state.selections].map(([requirementId, file]) => ({ requirementId, fileId: file.fileId }));
        await requestJson(`${API_BASE}/${state.submissionCase.id}/selections`, {
            method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ selections })
        });
        const packageData = await requestJson(`${API_BASE}/${state.submissionCase.id}/package`);
        state.selections = new Map((packageData.selections || []).map((item) => [item.requirementId, item]));
        renderRequirements();
        renderCandidates(requirement, state.candidates.get(requirement.id) || []);
        renderPackage();
    } catch (error) {
        state.selections = previous;
        elements.candidateError.textContent = friendlyError(error, "selection");
        showCandidateState("error");
        renderRequirements();
        renderPackage();
    }
}

function openCommonDocumentEditor(requirement, candidate) {
    const managed = state.commonDocuments.get(requirement.sourceReference);
    if (!managed) return;
    state.commonDocumentEditor = { requirement, candidate };
    elements.commonDocumentTitle.textContent = managed.displayName;
    elements.commonDocumentIssuedAt.value = managed.fileId === candidate.fileId && managed.issuedAt ? managed.issuedAt : "";
    elements.commonDocumentExpiresAt.value = managed.fileId === candidate.fileId && managed.expiresAt ? managed.expiresAt : "";
    setHidden(elements.issuedAtField, managed.refreshPolicy === "NONE");
    setHidden(elements.expiresAtField, managed.refreshPolicy !== "EXPIRATION_BASED");
    elements.commonDocumentHelp.textContent = managed.refreshPolicy === "PERIODIC"
        ? `발급일로부터 ${managed.refreshIntervalMonths}개월이 지나면 갱신을 권고합니다.`
        : managed.refreshPolicy === "EXPIRATION_BASED"
            ? "만료일 30일 전부터 만료 임박 상태로 표시합니다."
            : "이 서류는 별도 유효기간을 관리하지 않습니다.";
    setHidden(elements.commonDocumentMessage, true);
    elements.commonDocumentDialog.showModal();
}

function closeCommonDocumentEditor() {
    state.commonDocumentEditor = null;
    elements.commonDocumentDialog.close();
}

async function saveCommonDocumentManagement(event) {
    event.preventDefault();
    if (!state.commonDocumentEditor) return;
    const { requirement, candidate } = state.commonDocumentEditor;
    try {
        const updated = await requestJson(`${COMMON_DOCUMENT_API}/${encodeURIComponent(requirement.sourceReference)}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                fileId: candidate.fileId,
                issuedAt: elements.commonDocumentIssuedAt.value || null,
                expiresAt: elements.commonDocumentExpiresAt.value || null
            })
        });
        state.commonDocuments.set(updated.documentType, updated);
        renderCommonDocumentStatuses();
        closeCommonDocumentEditor();
    } catch (error) {
        elements.commonDocumentMessage.textContent = friendlyError(error, "selection");
        setHidden(elements.commonDocumentMessage, false);
    }
}

async function loadWorkspace(submissionCase) {
    state.submissionCase = submissionCase;
    state.activeRequirementId = null;
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
        state.requirements = Array.isArray(requirements) ? requirements : [];
        state.selections = new Map((packageData.selections || []).map((item) => [item.requirementId, item]));
        elements.projectName.textContent = "선택한 제출서류";
        elements.projectMeta.textContent = `${state.requirements.length}개 서류의 회사 파일 후보를 확인합니다.`;
        renderRequirements();
        renderPackage();
        showCandidateState("guide");
        setHidden(elements.documentPicker, true);
        setHidden(elements.workspace, false);
        preloadCandidates();
    } catch (error) {
        elements.workspaceError.textContent = friendlyError(error, "workspace");
        setHidden(elements.workspaceError, false);
    } finally {
        setHidden(elements.workspaceLoading, true);
    }
}

async function createManualCase(event) {
    event.preventDefault();
    const requirements = selectedRequirements();
    setHidden(elements.documentMessage, true);
    if (!requirements.length) {
        elements.documentMessage.textContent = "필요한 제출서류를 하나 이상 선택해 주세요.";
        setHidden(elements.documentMessage, false);
        return;
    }
    elements.findDocumentsButton.disabled = true;
    try {
        const submissionCase = await requestJson(API_BASE, {
            method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ requirements })
        });
        window.history.replaceState({}, "", `/submissions/?caseId=${encodeURIComponent(submissionCase.id)}`);
        await loadWorkspace(submissionCase);
    } catch (error) {
        elements.documentMessage.textContent = friendlyError(error, "create");
        setHidden(elements.documentMessage, false);
    } finally {
        elements.findDocumentsButton.disabled = false;
    }
}

function resetWorkspace() {
    state.submissionCase = null;
    state.requirements = [];
    state.selections.clear();
    state.candidates.clear();
    state.candidateErrors.clear();
    state.candidateLoadingIds.clear();
    state.activeRequirementId = null;
    elements.documentForm.reset();
    state.customDocuments = [];
    renderCustomDocuments();
    updateCheckedCount();
    setHidden(elements.workspace, true);
    setHidden(elements.workspaceError, true);
    setHidden(elements.documentMessage, true);
    setHidden(elements.documentPicker, false);
    window.history.replaceState({}, "", "/submissions/");
    elements.documentOptions[0]?.focus();
}

async function restoreCaseFromUrl() {
    const caseId = new URLSearchParams(window.location.search).get("caseId");
    if (!caseId || !/^\d+$/.test(caseId)) return;
    setHidden(elements.documentPicker, true);
    setHidden(elements.workspaceLoading, false);
    try {
        await loadWorkspace(await requestJson(`${API_BASE}/${caseId}`));
    } catch (error) {
        elements.workspaceError.textContent = friendlyError(error, "workspace");
        setHidden(elements.workspaceError, false);
        setHidden(elements.documentPicker, false);
    } finally {
        setHidden(elements.workspaceLoading, true);
    }
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
elements.changeDocumentsButton.addEventListener("click", resetWorkspace);
elements.commonDocumentForm.addEventListener("submit", saveCommonDocumentManagement);
elements.commonDocumentClose.addEventListener("click", closeCommonDocumentEditor);
elements.commonDocumentCancel.addEventListener("click", closeCommonDocumentEditor);
updateCheckedCount();
loadCommonDocuments().finally(restoreCaseFromUrl);
