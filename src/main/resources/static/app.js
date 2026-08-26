const loadingMessage = document.getElementById("loading-message");
const emptyMessage = document.getElementById("empty-message");
const errorMessage = document.getElementById("error-message");
const tableWrapper = document.getElementById("table-wrapper");
const bidList = document.getElementById("bid-list");
const rangeSearchForm = document.getElementById("range-search-form");
const startDateInput = document.getElementById("start-date");
const endDateInput = document.getElementById("end-date");
const currentPeriod = document.getElementById("current-period");
const validationMessage = document.getElementById("validation-message");
const quickButtons = document.querySelectorAll(".quick-button");
const searchButtons = document.querySelectorAll(".quick-button, .search-button");
const licenseCodeTags = document.getElementById("license-code-tags");
const licenseCodeForm = document.getElementById("license-code-form");
const licenseCodeInput = document.getElementById("license-code-input");
const licenseCodeMessage = document.getElementById("license-code-message");
const analysisModal = document.getElementById("document-analysis-modal");
const analysisModalContent = document.getElementById("analysis-modal-content");
const analysisModalClose = document.getElementById("analysis-modal-close");

const LICENSE_STORAGE_KEY = "bidAllowedLicenseCodes";
const DEFAULT_LICENSE_CODES = ["6146", "1468"];
let allowedLicenseCodes = [];
let lastAnalysisTrigger = null;

/**
 * 저장된 허용 업종코드를 불러오며, 최초 접속이나 잘못된 저장값은 기본값으로 초기화한다.
 */
function loadAllowedLicenseCodes() {
    try {
        const storedValue = localStorage.getItem(LICENSE_STORAGE_KEY);
        if (storedValue === null) {
            allowedLicenseCodes = [...DEFAULT_LICENSE_CODES];
            saveAllowedLicenseCodes();
            return;
        }

        const parsedCodes = JSON.parse(storedValue);
        if (!Array.isArray(parsedCodes)) {
            throw new Error("허용 업종코드 저장 형식이 올바르지 않습니다.");
        }

        const validCodes = parsedCodes.filter(
            (code, index) => /^[0-9]{4}$/.test(code) && parsedCodes.indexOf(code) === index
        );
        allowedLicenseCodes = validCodes.length > 0 ? validCodes : [...DEFAULT_LICENSE_CODES];
        saveAllowedLicenseCodes();
    } catch (error) {
        console.error(error);
        allowedLicenseCodes = [...DEFAULT_LICENSE_CODES];
        saveAllowedLicenseCodes();
    }
}

/** 허용 업종코드 변경 내용을 브라우저에 즉시 저장한다. */
function saveAllowedLicenseCodes() {
    try {
        localStorage.setItem(LICENSE_STORAGE_KEY, JSON.stringify(allowedLicenseCodes));
    } catch (error) {
        console.error("허용 업종코드를 저장하지 못했습니다.", error);
    }
}

/** 코드 관리 안내 메시지를 표시하거나 초기화한다. */
function showLicenseCodeMessage(message) {
    licenseCodeMessage.textContent = message;
    licenseCodeMessage.classList.toggle("hidden", message === "");
}

/**
 * 현재 허용 업종코드 목록을 삭제 버튼이 있는 태그 형태로 다시 그린다.
 */
function renderAllowedLicenseCodes() {
    const fragment = document.createDocumentFragment();

    allowedLicenseCodes.forEach((code) => {
        const tag = document.createElement("span");
        tag.className = "license-code-tag";
        tag.append(document.createTextNode(code));

        const removeButton = document.createElement("button");
        removeButton.type = "button";
        removeButton.className = "license-code-remove";
        removeButton.dataset.code = code;
        removeButton.setAttribute("aria-label", `${code} 허용 업종코드 삭제`);
        removeButton.textContent = "×";
        tag.appendChild(removeButton);
        fragment.appendChild(tag);
    });

    licenseCodeTags.replaceChildren(fragment);
}

/** 허용 업종코드를 추가하고 저장값과 화면을 즉시 갱신한다. */
function addAllowedLicenseCode(code) {
    if (!/^[0-9]{4}$/.test(code)) {
        showLicenseCodeMessage("허용 업종코드는 숫자 4자리로 입력해 주세요.");
        return;
    }
    if (allowedLicenseCodes.includes(code)) {
        showLicenseCodeMessage("이미 등록된 허용 업종코드입니다.");
        return;
    }

    allowedLicenseCodes.push(code);
    saveAllowedLicenseCodes();
    renderAllowedLicenseCodes();
    licenseCodeInput.value = "";
    showLicenseCodeMessage("");
}

/** 최소 한 개를 유지하면서 선택한 허용 업종코드를 삭제한다. */
function removeAllowedLicenseCode(code) {
    if (allowedLicenseCodes.length === 1) {
        showLicenseCodeMessage("허용 업종코드는 최소 1개 이상 유지해야 합니다.");
        return;
    }

    allowedLicenseCodes = allowedLicenseCodes.filter((allowedCode) => allowedCode !== code);
    saveAllowedLicenseCodes();
    renderAllowedLicenseCodes();
    showLicenseCodeMessage("");
}

/**
 * 금액 문자열을 천 단위 구분 기호와 원 단위로 표시한다.
 */
function formatAmount(amount) {
    if (amount === null || amount === undefined || amount === "") {
        return "-";
    }

    const numericAmount = Number(String(amount).replaceAll(",", ""));
    return Number.isFinite(numericAmount) ? `${numericAmount.toLocaleString("ko-KR")}원` : amount;
}

/**
 * 빈 값도 화면에서 일관되게 표시하도록 처리한다.
 */
function displayValue(value) {
    return value === null || value === undefined || value === "" ? "-" : value;
}

/**
 * 검토 상태별 강조 색상 클래스를 결정한다.
 */
function getStatusClass(status) {
    if (status === "검토대상") {
        return "status-review";
    }
    if (status === "제외") {
        return "status-excluded";
    }
    return "status-check";
}

function createCell(value) {
    const cell = document.createElement("td");
    cell.textContent = displayValue(value);
    return cell;
}

/** 공고 단위 외부확인 상태를 업무 화면용 한글 문구로 변환한다. */
function getExternalCheckLabel(status) {
    if (status === "REQUIRED") {
        return "외부사이트 확인 필요";
    }
    if (status === "REFERENCE") {
        return "참고사이트";
    }
    if (status === "NOT_DETECTED") {
        return "외부참조 미탐지";
    }
    return "확인 필요";
}

/** 외부확인 상태, 판정 사유 및 안전한 HTTP(S) 외부 링크를 한 셀에 표시한다. */
function createExternalCheckCell(bid) {
    const cell = document.createElement("td");
    cell.className = "external-check-cell";

    const status = bid.externalCheckStatus || "UNKNOWN";
    const statusBadge = document.createElement("span");
    statusBadge.className = `external-check-status external-check-${status.toLowerCase().replace("_", "-")}`;
    statusBadge.textContent = getExternalCheckLabel(status);
    cell.appendChild(statusBadge);

    if (bid.externalCheckReason) {
        const reason = document.createElement("span");
        reason.className = "external-check-reason";
        reason.textContent = bid.externalCheckReason;
        cell.appendChild(reason);
    }

    if (["REQUIRED", "REFERENCE"].includes(status) && Array.isArray(bid.externalSiteUrls)) {
        const linkList = document.createElement("div");
        linkList.className = "external-link-list";

        bid.externalSiteUrls.forEach((url, index) => {
            try {
                const parsedUrl = new URL(url);
                if (!(["http:", "https:"].includes(parsedUrl.protocol))) {
                    return;
                }

                const link = document.createElement("a");
                link.href = parsedUrl.href;
                link.target = "_blank";
                link.rel = "noopener noreferrer";
                link.className = "external-site-link";
                link.textContent = parsedUrl.hostname || `외부 링크 ${index + 1}`;
                linkList.appendChild(link);
            } catch (error) {
                console.warn("표시할 수 없는 외부 URL입니다.", url, error);
            }
        });

        if (linkList.childElementCount > 0) {
            cell.appendChild(linkList);
        }
    }

    return cell;
}

/** 첨부문서 핵심정보 분석 상태를 누락값까지 고려해 반환한다. */
function getDocumentAnalysisStatus(attachment) {
    const status = attachment?.documentAnalysis?.analysisStatus;
    return typeof status === "string" && status !== "" ? status.toUpperCase() : "NOT_ANALYZED";
}

/** 상세보기로 확인할 분석 성공·실패 정보가 한 건이라도 있는지 판단한다. */
function hasDocumentAnalysis(attachments) {
    return Array.isArray(attachments)
        && attachments.some((attachment) => getDocumentAnalysisStatus(attachment) !== "NOT_ANALYZED");
}

/** 여러 첨부파일에서 같은 문구를 한 번만 보여주도록 항목별 결과를 합친다. */
function mergeDocumentAnalysis(attachments) {
    const result = {
        qualificationRequirements: [],
        requiredDocuments: [],
        submissionMethods: [],
        submissionDeadlines: [],
        jointContractRequirements: []
    };

    Object.keys(result).forEach((fieldName) => {
        const uniqueValues = new Set();
        (Array.isArray(attachments) ? attachments : []).forEach((attachment) => {
            const values = attachment?.documentAnalysis?.[fieldName];
            if (!Array.isArray(values)) {
                return;
            }
            values.forEach((value) => {
                if (typeof value === "string" && value.trim() !== "") {
                    uniqueValues.add(value.trim());
                }
            });
        });
        result[fieldName] = [...uniqueValues];
    });

    return result;
}

function appendTextElement(parent, tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) {
        element.className = className;
    }
    element.textContent = text;
    parent.appendChild(element);
    return element;
}

function createSafeLink(url, text, className) {
    try {
        const parsedUrl = new URL(url);
        if (!["http:", "https:"].includes(parsedUrl.protocol)) {
            return null;
        }

        const link = document.createElement("a");
        link.href = parsedUrl.href;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.className = className;
        link.textContent = text;
        return link;
    } catch (error) {
        console.warn("표시할 수 없는 URL입니다.", url, error);
        return null;
    }
}

function appendSummaryItem(parent, label, value, wide = false) {
    const item = document.createElement("div");
    item.className = `bid-summary-item${wide ? " bid-summary-item-wide" : ""}`;
    appendTextElement(item, "span", "summary-label", label);
    appendTextElement(item, "span", "summary-value", displayValue(value));
    parent.appendChild(item);
}

function createAnalysisResultSection(title, values) {
    const section = document.createElement("section");
    section.className = "analysis-result-section";
    appendTextElement(section, "h4", "", title);

    if (values.length === 0) {
        appendTextElement(section, "p", "analysis-empty-text", "추출된 내용이 없습니다.");
        return section;
    }

    const list = document.createElement("ul");
    values.forEach((value) => appendTextElement(list, "li", "", value));
    section.appendChild(list);
    return section;
}

function getDocumentStatusLabel(status) {
    if (status === "ANALYZED") {
        return "분석완료";
    }
    if (status === "FAILED") {
        return "분석실패";
    }
    if (status === "UNKNOWN") {
        return "확인 필요";
    }
    return "미분석";
}

/** 공고 단위 외부확인 상태와 탐지 URL을 상세 영역에 표시한다. */
function appendExternalCheckDetail(parent, bid) {
    appendTextElement(parent, "h3", "analysis-section-title", "외부사이트 확인");
    const box = document.createElement("div");
    box.className = "external-detail-box";

    const status = bid.externalCheckStatus || "UNKNOWN";
    const badge = appendTextElement(box, "span", "external-check-status", getExternalCheckLabel(status));
    badge.classList.add(`external-check-${status.toLowerCase().replaceAll("_", "-")}`);

    if (bid.externalCheckReason) {
        appendTextElement(box, "span", "external-check-reason", bid.externalCheckReason);
    }

    const urls = Array.isArray(bid.externalSiteUrls) ? [...new Set(bid.externalSiteUrls)] : [];
    const linkList = document.createElement("div");
    linkList.className = "external-link-list";
    urls.forEach((url, index) => {
        let label = `외부 링크 ${index + 1}`;
        try {
            label = new URL(url).hostname || label;
        } catch (error) {
            console.warn("표시할 수 없는 외부 URL입니다.", url, error);
        }
        const link = createSafeLink(url, label, "external-site-link");
        if (link) {
            linkList.appendChild(link);
        }
    });
    if (linkList.childElementCount > 0) {
        box.appendChild(linkList);
    }

    parent.appendChild(box);
}

/** 분석된 첨부와 실패·미분석 상태를 파일별로 보여준다. */
function appendAttachmentDetails(parent, attachments) {
    appendTextElement(parent, "h3", "analysis-section-title", "첨부파일별 분석 정보");

    if (attachments.length === 0) {
        appendTextElement(parent, "p", "analysis-empty-text", "등록된 첨부파일이 없습니다.");
        return;
    }

    const statuses = attachments.map(getDocumentAnalysisStatus);
    if (statuses.includes("FAILED")) {
        appendTextElement(parent, "p", "attachment-warning", "일부 첨부파일을 분석하지 못했습니다.");
    }
    if (statuses.every((status) => ["NOT_ANALYZED", "UNKNOWN"].includes(status))) {
        appendTextElement(
            parent,
            "p",
            "attachment-warning",
            "분석 가능한 PDF/HWPX/HWP가 없거나 아직 분석되지 않아 추출 결과를 확인할 수 없습니다."
        );
    }

    const list = document.createElement("div");
    list.className = "attachment-list";
    attachments.forEach((attachment) => {
        const item = document.createElement("div");
        item.className = "attachment-item";

        const fileInfo = document.createElement("div");
        appendTextElement(fileInfo, "span", "attachment-name", displayValue(attachment.fileName));
        const analysisNote = attachment?.documentAnalysis?.analysisNote;
        if (analysisNote) {
            appendTextElement(fileInfo, "span", "attachment-note", analysisNote);
        }
        item.appendChild(fileInfo);
        appendTextElement(item, "span", "", displayValue(attachment.documentType));

        const action = document.createElement("div");
        const status = getDocumentAnalysisStatus(attachment);
        const statusBadge = appendTextElement(
            action,
            "span",
            `document-analysis-status document-analysis-${status.toLowerCase().replaceAll("_", "-")}`,
            getDocumentStatusLabel(status)
        );
        statusBadge.title = status;

        if (attachment.fileUrl) {
            const link = createSafeLink(attachment.fileUrl, "원본 첨부 열기", "attachment-link");
            if (link) {
                action.appendChild(document.createElement("br"));
                action.appendChild(link);
            }
        }
        item.appendChild(action);
        list.appendChild(item);
    });
    parent.appendChild(list);
}

/** 선택한 공고의 첨부문서 분석 결과를 비우고 새로 구성해 모달을 연다. */
function openDocumentAnalysisModal(bid, triggerButton) {
    lastAnalysisTrigger = triggerButton;
    analysisModalContent.replaceChildren();

    appendTextElement(
        analysisModalContent,
        "p",
        "analysis-notice",
        "문서 자동추출 결과입니다. 표 구조, 문서 형식 등에 따라 일부 내용이 누락되거나 잘못 분류될 수 있으므로 최종 제출 전 원문을 확인하세요."
    );

    const summary = document.createElement("div");
    summary.className = "bid-summary-grid";
    appendSummaryItem(summary, "공고명", bid.bidNtceNm, true);
    appendSummaryItem(summary, "공고번호", bid.bidNtceNo);
    appendSummaryItem(summary, "공고기관", bid.ntceInsttNm);
    appendSummaryItem(summary, "마감일시", bid.bidClseDt);
    appendSummaryItem(summary, "검토상태", bid.reviewStatus);
    appendSummaryItem(summary, "판정사유", bid.reviewReason, true);
    analysisModalContent.appendChild(summary);

    const attachments = Array.isArray(bid.attachments) ? bid.attachments : [];
    const merged = mergeDocumentAnalysis(attachments);
    appendTextElement(analysisModalContent, "h3", "analysis-section-title", "문서 핵심정보");
    const resultGrid = document.createElement("div");
    resultGrid.className = "analysis-result-grid";
    resultGrid.appendChild(createAnalysisResultSection("참가자격", merged.qualificationRequirements));
    resultGrid.appendChild(createAnalysisResultSection("제출 필요 서류", merged.requiredDocuments));
    resultGrid.appendChild(createAnalysisResultSection("제출방법", merged.submissionMethods));
    resultGrid.appendChild(createAnalysisResultSection("제출기한", merged.submissionDeadlines));
    resultGrid.appendChild(createAnalysisResultSection("공동수급 관련 조건", merged.jointContractRequirements));
    analysisModalContent.appendChild(resultGrid);

    appendExternalCheckDetail(analysisModalContent, bid);
    appendAttachmentDetails(analysisModalContent, attachments);

    analysisModal.classList.remove("hidden");
    document.body.classList.add("modal-open");
    analysisModalClose.focus();
}

/** 모달 내용과 포커스를 초기화해 다른 공고의 데이터가 남지 않게 한다. */
function closeDocumentAnalysisModal() {
    analysisModal.classList.add("hidden");
    document.body.classList.remove("modal-open");
    analysisModalContent.replaceChildren();
    if (lastAnalysisTrigger) {
        lastAnalysisTrigger.focus();
        lastAnalysisTrigger = null;
    }
}

/**
 * API 응답의 각 공고를 안전하게 테이블 행으로 생성한다.
 */
function createBidRow(bid) {
    const row = document.createElement("tr");
    const statusCell = document.createElement("td");
    const status = document.createElement("span");

    status.className = `status ${getStatusClass(bid.reviewStatus)}`;
    status.textContent = displayValue(bid.reviewStatus);
    statusCell.appendChild(status);
    row.appendChild(statusCell);

    row.appendChild(createCell(bid.bidNtceNm));
    row.appendChild(createCell(bid.ntceInsttNm));
    row.appendChild(createCell(formatAmount(bid.asignBdgtAmt)));
    row.appendChild(createCell(bid.bidClseDt));
    row.appendChild(createCell(bid.sucsfbidMthdNm));
    row.appendChild(createCell(bid.licenseLimit));
    row.appendChild(createCell(bid.participationRegion));
    row.appendChild(createCell(bid.reviewReason));
    row.appendChild(createExternalCheckCell(bid));

    const analysisCell = document.createElement("td");
    const analysisButton = document.createElement("button");
    const analysisAvailable = hasDocumentAnalysis(bid.attachments);
    analysisButton.type = "button";
    analysisButton.className = "analysis-detail-button";
    analysisButton.disabled = !analysisAvailable;
    analysisButton.textContent = analysisAvailable ? "분석내용 보기" : "분석자료 없음";
    if (!analysisAvailable) {
        analysisButton.title = "분석 가능한 첨부가 없거나 문서 분석이 아직 완료되지 않았습니다.";
    } else {
        analysisButton.addEventListener("click", () => openDocumentAnalysisModal(bid, analysisButton));
    }
    analysisCell.appendChild(analysisButton);
    row.appendChild(analysisCell);

    const detailCell = document.createElement("td");
    if (bid.bidNtceDtlUrl) {
        const detailLink = document.createElement("a");
        detailLink.href = bid.bidNtceDtlUrl;
        detailLink.target = "_blank";
        detailLink.rel = "noopener noreferrer";
        detailLink.className = "detail-link";
        detailLink.textContent = "나라장터 보기";
        detailCell.appendChild(detailLink);
    } else {
        detailCell.textContent = "-";
    }
    row.appendChild(detailCell);

    return row;
}

/** 브라우저의 로컬 날짜를 API에서 사용하는 yyyy-MM-dd 형식으로 변환한다. */
function formatDate(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
}

/** 조회 전 메시지와 기존 결과를 초기화한다. */
function prepareForLoading() {
    loadingMessage.classList.remove("hidden");
    emptyMessage.classList.add("hidden");
    errorMessage.classList.add("hidden");
    tableWrapper.classList.add("hidden");
    bidList.replaceChildren();
    searchButtons.forEach((button) => {
        button.disabled = true;
    });
}

/** API 응답을 현재 테이블 또는 결과 없음 메시지로 표시한다. */
function renderBids(bids) {
    loadingMessage.classList.add("hidden");

    if (!Array.isArray(bids) || bids.length === 0) {
        emptyMessage.classList.remove("hidden");
        return;
    }

    const fragment = document.createDocumentFragment();
    bids.forEach((bid) => fragment.appendChild(createBidRow(bid)));
    bidList.replaceChildren(fragment);
    tableWrapper.classList.remove("hidden");
}

/** 선택한 기간을 입력창과 현재 조회 기간 표시에 함께 반영한다. */
function setSelectedPeriod(startDate, endDate) {
    startDateInput.value = startDate;
    endDateInput.value = endDate;
    currentPeriod.textContent = `조회기간: ${startDate} ~ ${endDate}`;
}

/**
 * 조회 시점의 localStorage 허용 업종코드를 백엔드 판정 파라미터에 포함한다.
 */
function createBidRequestUrl(url) {
    const requestUrl = new URL(url, window.location.origin);
    requestUrl.searchParams.set("allowedLicenseCodes", allowedLicenseCodes.join(","));
    return `${requestUrl.pathname}${requestUrl.search}`;
}

/** 공통 오류 처리를 유지하면서 전달받은 API에서 공고를 조회한다. */
async function fetchBids(url, startDate, endDate) {
    setSelectedPeriod(startDate, endDate);
    prepareForLoading();
    validationMessage.classList.add("hidden");

    try {
        // 코드 추가·삭제 후 다시 조회하면 현재 화면의 최신 허용 코드가 즉시 판정에 사용된다.
        const response = await fetch(createBidRequestUrl(url));
        if (!response.ok) {
            throw new Error(`공고 조회 실패: ${response.status}`);
        }

        const bids = await response.json();
        renderBids(bids);
    } catch (error) {
        console.error(error);
        loadingMessage.classList.add("hidden");
        errorMessage.classList.remove("hidden");
    } finally {
        searchButtons.forEach((button) => {
            button.disabled = false;
        });
    }
}

/** 기간 조회 API의 쿼리 문자열을 안전하게 생성해 조회한다. */
function loadBidsByRange(startDate, endDate) {
    const query = new URLSearchParams({ startDate, endDate });
    return fetchBids(`/api/bids/target/qualification/range?${query}`, startDate, endDate);
}

/** 오늘을 포함하도록 빠른 조회 일수만큼 시작일을 계산한다. */
function getRecentPeriod(days) {
    const endDate = new Date();
    const startDate = new Date(endDate);
    startDate.setDate(startDate.getDate() - (days - 1));
    return { startDate: formatDate(startDate), endDate: formatDate(endDate) };
}

quickButtons.forEach((button) => {
    button.addEventListener("click", () => {
        const period = getRecentPeriod(Number(button.dataset.days));
        loadBidsByRange(period.startDate, period.endDate);
    });
});

rangeSearchForm.addEventListener("submit", (event) => {
    event.preventDefault();

    const startDate = startDateInput.value;
    const endDate = endDateInput.value;
    let message = "";

    // 직접 조회 시 두 날짜의 입력 여부와 날짜 순서를 먼저 검증한다.
    if (!startDate || !endDate) {
        message = "시작일과 종료일을 모두 입력해 주세요.";
    } else if (startDate > endDate) {
        message = "시작일은 종료일보다 늦을 수 없습니다.";
    }

    if (message) {
        validationMessage.textContent = message;
        validationMessage.classList.remove("hidden");
        return;
    }

    loadBidsByRange(startDate, endDate);
});

// 입력 중 숫자가 아닌 문자를 제거하고 최대 4자리만 유지한다.
licenseCodeInput.addEventListener("input", () => {
    licenseCodeInput.value = licenseCodeInput.value.replace(/[^0-9]/g, "").slice(0, 4);
    showLicenseCodeMessage("");
});

licenseCodeForm.addEventListener("submit", (event) => {
    event.preventDefault();
    addAllowedLicenseCode(licenseCodeInput.value);
});

// 태그 영역의 삭제 버튼을 한 곳에서 처리해 다시 렌더링한 버튼에도 동작하게 한다.
licenseCodeTags.addEventListener("click", (event) => {
    const removeButton = event.target.closest(".license-code-remove");
    if (removeButton) {
        removeAllowedLicenseCode(removeButton.dataset.code);
    }
});

analysisModalClose.addEventListener("click", closeDocumentAnalysisModal);

// 배경 클릭과 Escape 키로도 닫을 수 있어 반복해서 다른 공고를 확인하기 쉽도록 한다.
analysisModal.addEventListener("click", (event) => {
    if (event.target === analysisModal) {
        closeDocumentAnalysisModal();
    }
});

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !analysisModal.classList.contains("hidden")) {
        closeDocumentAnalysisModal();
    }
});

// 화면을 열 때 저장값을 불러오고 현재 허용 코드를 태그로 표시한다.
loadAllowedLicenseCodes();
renderAllowedLicenseCodes();

// 최초 접속은 기존 API로 오늘 공고를 조회한다.
const today = formatDate(new Date());
fetchBids("/api/bids/target/qualification", today, today);
