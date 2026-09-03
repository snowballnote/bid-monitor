const API_BASE = "/api/submission-cases";
const PROJECT_SEARCH_API = "/api/submission-projects";

const elements = {
    projectPicker: document.querySelector("#project-picker"),
    projectForm: document.querySelector("#project-form"),
    projectQuery: document.querySelector("#project-query"),
    projectSearchButton: document.querySelector("#project-search-button"),
    projectMessage: document.querySelector("#project-message"),
    selectedProject: document.querySelector("#selected-project"),
    selectedProjectName: document.querySelector("#selected-project-name"),
    selectedProjectMeta: document.querySelector("#selected-project-meta"),
    selectedProjectStatus: document.querySelector("#selected-project-status"),
    projectSearchGuide: document.querySelector("#project-search-guide"),
    projectSearchLoading: document.querySelector("#project-search-loading"),
    projectSearchEmpty: document.querySelector("#project-search-empty"),
    projectSearchError: document.querySelector("#project-search-error"),
    projectSearchResults: document.querySelector("#project-search-results"),
    projectResultCount: document.querySelector("#project-result-count"),
    projectResultTableBody: document.querySelector("#project-result-table-body"),
    projectResultCards: document.querySelector("#project-result-cards"),
    workspaceLoading: document.querySelector("#workspace-loading"),
    workspaceError: document.querySelector("#workspace-error"),
    workspace: document.querySelector("#submission-workspace"),
    projectName: document.querySelector("#case-project-name"),
    projectMeta: document.querySelector("#case-project-meta"),
    changeProjectButton: document.querySelector("#change-project-button"),
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
    candidateError: document.querySelector("#candidate-error"),
    candidateList: document.querySelector("#candidate-list"),
    packageSelectionCount: document.querySelector("#package-selection-count"),
    packageEmpty: document.querySelector("#package-empty"),
    packageList: document.querySelector("#package-list")
};

const state = {
    projects: [],
    selectedProject: null,
    creatingCase: false,
    submissionCase: null,
    requirements: [],
    selections: new Map(),
    activeRequirementId: null,
    candidates: new Map()
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
    if (!response.ok) {
        throw new ApiError(response.status, body?.message || "요청을 처리하지 못했습니다.");
    }
    return body;
}

function categoryLabel(category) {
    return ({
        COMPANY_GENERAL: "회사 일반",
        FINANCIAL: "재무·경영",
        CERTIFICATION_LICENSE: "인증·면허",
        PERFORMANCE: "실적",
        PERSONNEL: "인력",
        SECURITY: "보안",
        OTHER: "기타"
    })[category] || "기타";
}

function formatDate(value) {
    if (!value) return "수정일 미확인";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "수정일 미확인";
    return new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
}

function friendlyError(error, action) {
    if (error instanceof ApiError && error.status === 503) {
        return "회사 DB에 연결할 수 없습니다. 관리자에게 연결 상태를 확인해 주세요.";
    }
    if (error instanceof ApiError && error.status === 404) {
        return action === "project" ? "선택한 PMS 사업을 찾을 수 없습니다. 다시 검색해 주세요." : "제출서류 작업을 찾을 수 없습니다.";
    }
    if (error instanceof ApiError && error.status === 400) return error.message;
    if (action === "search") return "사업을 검색하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    return action === "candidate" ? "회사 파일 후보를 불러오지 못했습니다." : "제출서류 작업을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

function projectMeta(project) {
    return [project.organizationName, project.bidNoticeNo]
        .filter((item) => item && String(item).trim())
        .join(" · ") || "발주기관 및 공고번호 미확인";
}

function showProjectSearchState(name) {
    const states = {
        guide: elements.projectSearchGuide,
        loading: elements.projectSearchLoading,
        empty: elements.projectSearchEmpty,
        error: elements.projectSearchError,
        results: elements.projectSearchResults
    };
    Object.entries(states).forEach(([key, element]) => setHidden(element, key !== name));
}

function createProjectSelectButton(project) {
    const selected = state.selectedProject?.projectId === project.projectId;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ui-button ui-button-secondary project-select-button";
    button.textContent = selected ? "선택됨" : "선택";
    button.disabled = state.creatingCase || selected;
    button.addEventListener("click", () => selectProject(project));
    return button;
}

function renderProjectResults() {
    elements.projectResultTableBody.replaceChildren();
    elements.projectResultCards.replaceChildren();
    elements.projectResultCount.textContent = `${state.projects.length}건`;

    state.projects.forEach((project) => {
        const selected = state.selectedProject?.projectId === project.projectId;
        const row = document.createElement("tr");
        row.classList.toggle("selected", selected);
        const name = document.createElement("td");
        name.className = "project-result-name";
        name.textContent = project.projectName || "이름 없는 사업";
        const organization = document.createElement("td");
        organization.textContent = project.organizationName || "미확인";
        const noticeNumber = document.createElement("td");
        noticeNumber.textContent = project.bidNoticeNo || "미확인";
        const action = document.createElement("td");
        action.append(createProjectSelectButton(project));
        row.append(name, organization, noticeNumber, action);
        elements.projectResultTableBody.append(row);

        const card = document.createElement("article");
        card.className = `project-result-card${selected ? " selected" : ""}`;
        const title = document.createElement("h3");
        title.textContent = project.projectName || "이름 없는 사업";
        const details = document.createElement("dl");
        [["발주기관", project.organizationName || "미확인"], ["공고번호", project.bidNoticeNo || "미확인"]]
            .forEach(([label, value]) => {
                const term = document.createElement("dt");
                term.textContent = label;
                const description = document.createElement("dd");
                description.textContent = value;
                details.append(term, description);
            });
        card.append(title, details, createProjectSelectButton(project));
        elements.projectResultCards.append(card);
    });
}

async function searchProjects(event) {
    event.preventDefault();
    const query = elements.projectQuery.value.trim();
    setHidden(elements.projectMessage, true);
    setHidden(elements.selectedProject, true);
    state.selectedProject = null;
    if (!query) {
        elements.projectMessage.textContent = "사업 검색어를 입력해 주세요.";
        setHidden(elements.projectMessage, false);
        return;
    }
    elements.projectSearchButton.disabled = true;
    showProjectSearchState("loading");
    try {
        const projects = await requestJson(`${PROJECT_SEARCH_API}?query=${encodeURIComponent(query)}`);
        state.projects = Array.isArray(projects) ? projects : [];
        renderProjectResults();
        showProjectSearchState(state.projects.length ? "results" : "empty");
    } catch (error) {
        elements.projectSearchError.textContent = friendlyError(error, "search");
        showProjectSearchState("error");
    } finally {
        elements.projectSearchButton.disabled = false;
    }
}

async function selectProject(project) {
    if (state.creatingCase) return;
    state.selectedProject = project;
    state.creatingCase = true;
    elements.selectedProjectName.textContent = project.projectName || "이름 없는 사업";
    elements.selectedProjectMeta.textContent = projectMeta(project);
    elements.selectedProjectStatus.textContent = "작업 생성 중";
    elements.selectedProjectStatus.classList.remove("error");
    setHidden(elements.selectedProject, false);
    setHidden(elements.projectMessage, true);
    renderProjectResults();
    try {
        const submissionCase = await requestJson(API_BASE, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ projectId: project.projectId })
        });
        window.history.replaceState({}, "", `/submissions/?caseId=${encodeURIComponent(submissionCase.id)}`);
        await loadWorkspace(submissionCase);
    } catch (error) {
        elements.selectedProjectStatus.textContent = "작업 생성 실패";
        elements.selectedProjectStatus.classList.add("error");
        elements.projectMessage.textContent = friendlyError(error, "project");
        setHidden(elements.projectMessage, false);
    } finally {
        state.creatingCase = false;
        renderProjectResults();
    }
}

function renderCase() {
    const value = state.submissionCase;
    elements.projectName.textContent = value.projectName || "이름 없는 사업";
    const metadata = [value.projectCode, value.internalBizNo, value.bidNoticeNo]
        .filter((item) => item && String(item).trim());
    elements.projectMeta.textContent = metadata.length ? metadata.join(" · ") : "사업 식별정보 미확인";
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

function renderRequirements() {
    elements.requirementList.replaceChildren();
    elements.requirementCount.textContent = `${state.requirements.length}건`;
    setHidden(elements.requirementEmpty, state.requirements.length !== 0);
    state.requirements.forEach((requirement) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "requirement-item";
        button.classList.toggle("active", requirement.id === state.activeRequirementId);
        button.dataset.requirementId = String(requirement.id);

        const content = document.createElement("span");
        const title = document.createElement("span");
        title.className = "requirement-item-title";
        title.textContent = requirement.documentName;
        const meta = document.createElement("span");
        meta.className = "requirement-item-meta";
        meta.textContent = categoryLabel(requirement.category);
        if (requirement.required) {
            const required = document.createElement("span");
            required.className = "requirement-required";
            required.textContent = "필수";
            meta.append(" · ", required);
        }
        content.append(title, meta);

        const completion = document.createElement("span");
        const complete = state.selections.has(requirement.id);
        completion.className = `requirement-state${complete ? " complete" : ""}`;
        completion.textContent = complete ? "선택 완료" : "미선택";
        button.append(content, completion);
        button.addEventListener("click", () => selectRequirement(requirement));
        elements.requirementList.append(button);
    });
}

function renderPackage() {
    elements.packageList.replaceChildren();
    const selectedRequirements = state.requirements.filter((requirement) => state.selections.has(requirement.id));
    setHidden(elements.packageEmpty, selectedRequirements.length !== 0);
    setHidden(elements.packageList, selectedRequirements.length === 0);
    selectedRequirements.forEach((requirement) => {
        const selection = state.selections.get(requirement.id);
        const item = document.createElement("li");
        const requirementName = document.createElement("strong");
        requirementName.textContent = requirement.documentName;
        const filename = document.createElement("span");
        filename.textContent = selection.originalFilename;
        item.append(requirementName, filename);
        elements.packageList.append(item);
    });
    renderProgress();
}

function showCandidateState(name) {
    ["guide", "loading", "empty", "error", "list"].forEach((key) => {
        const element = ({
            guide: elements.candidateGuide,
            loading: elements.candidateLoading,
            empty: elements.candidateEmpty,
            error: elements.candidateError,
            list: elements.candidateList
        })[key];
        setHidden(element, key !== name);
    });
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
        card.dataset.fileId = String(candidate.fileId);

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
        reason.textContent = Array.isArray(candidate.matchReasons) && candidate.matchReasons.length
            ? candidate.matchReasons.join(" · ") : "파일명 키워드 일치";

        const actions = document.createElement("div");
        actions.className = "candidate-actions";
        const selectButton = document.createElement("button");
        selectButton.type = "button";
        selectButton.className = "ui-button ui-button-secondary candidate-select";
        selectButton.textContent = selected ? "선택됨" : "이 파일 선택";
        selectButton.disabled = selected;
        selectButton.addEventListener("click", () => saveSelection(requirement, candidate));
        actions.append(selectButton);
        card.append(head, meta, reason, actions);
        elements.candidateList.append(card);
    });
    showCandidateState("list");
}

async function selectRequirement(requirement) {
    state.activeRequirementId = requirement.id;
    renderRequirements();
    elements.candidateTitle.textContent = requirement.documentName;
    if (state.candidates.has(requirement.id)) {
        renderCandidates(requirement, state.candidates.get(requirement.id));
        return;
    }
    showCandidateState("loading");
    try {
        const candidates = await requestJson(`${API_BASE}/${state.submissionCase.id}/requirements/${requirement.id}/candidates`);
        const safeCandidates = Array.isArray(candidates) ? candidates : [];
        state.candidates.set(requirement.id, safeCandidates);
        if (state.activeRequirementId === requirement.id) renderCandidates(requirement, safeCandidates);
    } catch (error) {
        elements.candidateError.textContent = friendlyError(error, "candidate");
        showCandidateState("error");
    }
}

async function saveSelection(requirement, candidate) {
    const previous = new Map(state.selections);
    state.selections.set(requirement.id, candidate);
    renderRequirements();
    renderCandidates(requirement, state.candidates.get(requirement.id) || []);
    renderPackage();
    try {
        const choices = [...state.selections.entries()].map(([requirementId, selection]) => ({
            requirementId,
            fileId: selection.fileId
        }));
        await requestJson(`${API_BASE}/${state.submissionCase.id}/selections`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ selections: choices })
        });
        await loadPackage();
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

async function loadPackage() {
    const packageData = await requestJson(`${API_BASE}/${state.submissionCase.id}/package`);
    state.selections = new Map((packageData.selections || []).map((selection) => [selection.requirementId, selection]));
}

async function loadWorkspace(submissionCase) {
    state.submissionCase = submissionCase;
    state.activeRequirementId = null;
    state.candidates.clear();
    setHidden(elements.workspaceLoading, false);
    setHidden(elements.workspaceError, true);
    setHidden(elements.workspace, true);
    try {
        const [requirements, packageData] = await Promise.all([
            requestJson(`${API_BASE}/${submissionCase.id}/requirements`),
            requestJson(`${API_BASE}/${submissionCase.id}/package`)
        ]);
        state.requirements = Array.isArray(requirements) ? requirements : [];
        state.selections = new Map((packageData.selections || []).map((selection) => [selection.requirementId, selection]));
        renderCase();
        renderRequirements();
        renderPackage();
        elements.candidateTitle.textContent = "요구서류를 선택하세요";
        showCandidateState("guide");
        setHidden(elements.projectPicker, true);
        setHidden(elements.workspace, false);
    } catch (error) {
        elements.workspaceError.textContent = friendlyError(error, "workspace");
        setHidden(elements.workspaceError, false);
    } finally {
        setHidden(elements.workspaceLoading, true);
    }
}

function resetWorkspace() {
    state.projects = [];
    state.selectedProject = null;
    state.creatingCase = false;
    state.submissionCase = null;
    state.requirements = [];
    state.selections.clear();
    state.candidates.clear();
    state.activeRequirementId = null;
    elements.projectForm.reset();
    elements.projectResultTableBody.replaceChildren();
    elements.projectResultCards.replaceChildren();
    setHidden(elements.workspace, true);
    setHidden(elements.workspaceError, true);
    setHidden(elements.projectMessage, true);
    setHidden(elements.selectedProject, true);
    setHidden(elements.projectPicker, false);
    showProjectSearchState("guide");
    window.history.replaceState({}, "", "/submissions/");
    elements.projectQuery.focus();
}

async function restoreCaseFromUrl() {
    const caseId = new URLSearchParams(window.location.search).get("caseId");
    if (!caseId || !/^\d+$/.test(caseId)) return;
    setHidden(elements.projectPicker, true);
    setHidden(elements.workspaceLoading, false);
    try {
        const submissionCase = await requestJson(`${API_BASE}/${caseId}`);
        await loadWorkspace(submissionCase);
    } catch (error) {
        elements.workspaceError.textContent = friendlyError(error, "workspace");
        setHidden(elements.workspaceError, false);
        setHidden(elements.projectPicker, false);
    } finally {
        setHidden(elements.workspaceLoading, true);
    }
}

elements.projectForm.addEventListener("submit", searchProjects);
elements.changeProjectButton.addEventListener("click", resetWorkspace);
restoreCaseFromUrl();
