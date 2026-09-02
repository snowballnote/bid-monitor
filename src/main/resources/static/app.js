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
const analysisModalTitle = document.getElementById("document-analysis-title");

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

/** 목록에서 금액의 규모를 빠르게 읽을 수 있도록 억/만 단위로만 축약한다. */
function formatCompactAmount(amount) {
    if (amount === null || amount === undefined || amount === "") {
        return "-";
    }
    const numericAmount = Number(String(amount).replaceAll(",", ""));
    if (!Number.isFinite(numericAmount)) {
        return amount;
    }
    if (Math.abs(numericAmount) >= 100_000_000) {
        return `${(numericAmount / 100_000_000).toFixed(2).replace(/\.?0+$/, "")}억`;
    }
    if (Math.abs(numericAmount) >= 10_000) {
        return `${Math.round(numericAmount / 10_000).toLocaleString("ko-KR")}만`;
    }
    return numericAmount.toLocaleString("ko-KR");
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

function createCell(value, label, className = "") {
    const cell = document.createElement("td");
    cell.dataset.label = label;
    if (className) {
        cell.className = className;
    }
    cell.textContent = displayValue(value);
    return cell;
}

function createClampedCell(value, label, className) {
    const cell = document.createElement("td");
    cell.dataset.label = label;
    cell.className = className;
    appendTextElement(cell, "span", "bid-cell-clamp", displayValue(value));
    return cell;
}

/** 목록에서는 마감 날짜와 분 단위 시간을 두 줄로 분리한다. */
function createDeadlineCell(value) {
    const cell = document.createElement("td");
    cell.className = "bid-deadline-cell";
    cell.dataset.label = "입찰마감";
    const match = String(value || "").match(/^(\d{4}-\d{2}-\d{2})[T\s]+(\d{2}:\d{2})/);
    if (!match) {
        cell.textContent = displayValue(value);
        return cell;
    }
    appendTextElement(cell, "span", "bid-deadline-date", match[1]);
    appendTextElement(cell, "span", "bid-deadline-time", match[2]);
    return cell;
}

function getAwardMethodLabel(bid) {
    if (bid.awardMethodCategory === "QUALIFICATION_REVIEW") {
        return bid.awardMethodStatus === "CONFIRMED" ? "적격심사 확정" : "적격심사 추정";
    }
    if (bid.awardMethodCategory === "SMALL_AMOUNT_ESTIMATE") {
        return "소액수의견적";
    }
    if (bid.awardMethodCategory === "OTHER") {
        return "비대상";
    }
    return bid.awardMethodStatus === "UNKNOWN" ? "확인 필요" : "";
}

function createAwardMethodCell(bid) {
    const cell = document.createElement("td");
    cell.className = "bid-method-cell";
    cell.dataset.label = "낙찰방법";
    appendTextElement(cell, "span", "bid-method-name", displayValue(bid.sucsfbidMthdNm));
    const label = getAwardMethodLabel(bid);
    if (label) {
        appendTextElement(cell, "span", "bid-method-classification", label);
    }
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

/** 공백·줄바꿈만 다른 같은 문자열을 비교하기 위한 키를 만든다. 표시값은 원문을 유지한다. */
function createAnalysisComparisonKey(value) {
    return value.replace(/\s+/g, " ").trim();
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
        const uniqueValues = new Map();
        (Array.isArray(attachments) ? attachments : []).forEach((attachment) => {
            const values = attachment?.documentAnalysis?.[fieldName];
            if (!Array.isArray(values)) {
                return;
            }
            values.forEach((value) => {
                if (typeof value === "string" && value.trim() !== "") {
                    const originalValue = value.trim();
                    const comparisonKey = createAnalysisComparisonKey(originalValue);
                    if (!uniqueValues.has(comparisonKey)) {
                        uniqueValues.set(comparisonKey, originalValue);
                    }
                }
            });
        });
        result[fieldName] = [...uniqueValues.values()];
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

/** 긴 원문은 처음에는 두 줄만 보이고 같은 자리에서 전체 내용을 펼쳐볼 수 있게 한다. */
function createExpandableAnalysisItem(value) {
    const item = document.createElement("li");
    if (value.length <= 120) {
        item.classList.add("analysis-text-direct");
        item.textContent = value;
        return item;
    }

    const details = document.createElement("details");
    details.className = "analysis-text-details";
    const summary = appendTextElement(details, "summary", "analysis-text-preview", value);
    summary.title = "전체 문장 펼쳐보기";
    appendTextElement(details, "p", "analysis-text-full", value);
    item.appendChild(details);
    return item;
}

/** 카테고리별 추출 결과를 카드로 만들고, 제출서류는 저장 없는 체크리스트로 표시한다. */
function createAnalysisResultSection(title, values, options = {}) {
    const section = document.createElement("section");
    const variant = options.variant ? ` analysis-result-${options.variant}` : "";
    const compact = options.compact ? " analysis-result-compact" : "";
    section.className = `analysis-result-section${variant}${compact}`;

    const heading = document.createElement("div");
    heading.className = "analysis-card-heading";
    let headingToggle = null;
    if (options.headingToggle) {
        headingToggle = document.createElement("button");
        headingToggle.type = "button";
        headingToggle.className = "collapsed-section-heading";
        appendTextElement(headingToggle, "span", "", `${title} ${values.length}건`);
        appendTextElement(headingToggle, "span", "collapsed-section-action", "펼쳐보기");
        headingToggle.setAttribute("aria-expanded", "false");
        heading.appendChild(headingToggle);
    } else {
        appendTextElement(heading, "h4", "", title);
        appendTextElement(heading, "span", "analysis-item-count", `${values.length}건`);
    }
    section.appendChild(heading);

    if (values.length === 0) {
        appendTextElement(section, "p", "analysis-empty-text", "탐지된 내용 없음");
        return section;
    }

    const list = document.createElement(options.checklist ? "div" : "ul");
    list.className = options.checklist ? "document-checklist" : "analysis-item-list";
    const previewLimit = Number.isInteger(options.previewLimit) ? options.previewLimit : values.length;
    values.forEach((value, index) => {
        let renderedItem;
        if (!options.checklist) {
            renderedItem = createExpandableAnalysisItem(value);
            list.appendChild(renderedItem);
        } else {
            const label = document.createElement("label");
            label.className = "document-check-item";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.setAttribute("aria-label", `${index + 1}번째 제출서류 확인`);
            label.appendChild(checkbox);
            appendTextElement(label, "span", "document-check-text", value);
            list.appendChild(label);
            renderedItem = label;
        }

        if (index >= previewLimit) {
            renderedItem.classList.add("analysis-collapsed-item");
        }
    });
    section.appendChild(list);

    if (headingToggle) {
        headingToggle.addEventListener("click", () => {
            const expanded = section.classList.toggle("analysis-section-expanded");
            headingToggle.lastElementChild.textContent = expanded ? "접기" : "펼쳐보기";
            headingToggle.setAttribute("aria-expanded", String(expanded));
        });
        return section;
    }

    if (previewLimit < values.length || options.startCollapsed) {
        const toggle = document.createElement("button");
        toggle.type = "button";
        toggle.className = "analysis-section-toggle";
        const collapsedText = options.toggleText || `전체 ${values.length}건 보기`;
        const expandedText = options.expandedText || "접기";
        toggle.textContent = collapsedText;
        toggle.setAttribute("aria-expanded", "false");
        toggle.addEventListener("click", () => {
            const expanded = section.classList.toggle("analysis-section-expanded");
            toggle.textContent = expanded ? expandedText : collapsedText;
            toggle.setAttribute("aria-expanded", String(expanded));
        });
        section.appendChild(toggle);
    }
    return section;
}

function getDocumentStatusLabel(status) {
    if (status === "ANALYZED") {
        return "분석완료";
    }
    if (status === "FAILED") {
        return "분석실패 / 원문 확인 필요";
    }
    if (status === "UNKNOWN") {
        return "확인 필요";
    }
    return "미분석";
}

/** 기존 검토상태와 판정사유를 가공하지 않고 상세보기 최상단에 강조한다. */
function appendReviewPriority(parent, bid) {
    const section = document.createElement("section");
    section.className = `review-priority ${getStatusClass(bid.reviewStatus)}`;

    const heading = document.createElement("div");
    heading.className = "review-priority-heading";
    appendTextElement(heading, "span", "review-priority-label", "핵심 확인");
    const badge = appendTextElement(heading, "strong", "status", displayValue(bid.reviewStatus));
    badge.classList.add(getStatusClass(bid.reviewStatus));
    section.appendChild(heading);

    appendTextElement(section, "p", "review-priority-reason", displayValue(bid.reviewReason));
    parent.appendChild(section);
}

/** 기존 판정값과 공고 진행정보를 해석 없이 한눈에 볼 수 있는 상단 요약으로 표시한다. */
function appendPracticalSummary(parent, bid, merged) {
    const summary = document.createElement("section");
    summary.className = "practical-summary";

    const review = document.createElement("div");
    review.className = `practical-review ${getStatusClass(bid.reviewStatus)}`;
    const status = appendTextElement(review, "strong", "status", displayValue(bid.reviewStatus));
    status.classList.add(getStatusClass(bid.reviewStatus));
    appendTextElement(review, "span", "practical-review-reason", displayValue(bid.reviewReason));
    summary.appendChild(review);

    const facts = document.createElement("div");
    facts.className = "practical-fact-grid";
    const appendFact = (label, value, className = "") => {
        const fact = document.createElement("div");
        fact.className = `practical-fact ${className}`.trim();
        appendTextElement(fact, "span", "practical-fact-label", label);
        appendTextElement(fact, "strong", "practical-fact-value", value);
        facts.appendChild(fact);
        return fact;
    };

    appendFact("마감일", displayValue(bid.bidClseDt), "practical-fact-deadline");
    const methods = merged.submissionMethods.length > 0
        ? merged.submissionMethods[0]
        : "탐지된 내용 없음";
    appendFact("제출방법", methods, "practical-fact-method");

    const externalFact = appendFact(
        "외부확인",
        getExternalCheckLabel(bid.externalCheckStatus || "UNKNOWN"),
        "practical-fact-external"
    );
    if (bid.externalCheckReason) {
        const reasonDetails = document.createElement("details");
        reasonDetails.className = "practical-external-details";
        appendTextElement(reasonDetails, "summary", "practical-external-toggle", "설명 보기");
        appendTextElement(reasonDetails, "p", "practical-external-reason", bid.externalCheckReason);
        externalFact.appendChild(reasonDetails);
    }
    const urls = Array.isArray(bid.externalSiteUrls) ? [...new Set(bid.externalSiteUrls)] : [];
    const links = document.createElement("div");
    links.className = "external-link-list practical-external-links";
    urls.forEach((url, index) => {
        let label = `외부 링크 ${index + 1}`;
        try {
            label = new URL(url).hostname || label;
        } catch (error) {
            console.warn("표시할 수 없는 외부 URL입니다.", url, error);
        }
        const link = createSafeLink(url, label, "external-site-link");
        if (link) {
            links.appendChild(link);
        }
    });
    if (links.childElementCount > 0) {
        externalFact.appendChild(links);
    }

    summary.appendChild(facts);
    parent.appendChild(summary);
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

/** 첨부 한 건의 전체 추출 결과를 원문 확인용 접기 영역으로 만든다. */
function createAttachmentAnalysisBody(attachment) {
    const body = document.createElement("div");
    body.className = "attachment-analysis-body";
    const status = getDocumentAnalysisStatus(attachment);
    const analysis = attachment?.documentAnalysis;

    if (status === "FAILED") {
        appendTextElement(body, "p", "attachment-failure-text", analysis?.analysisNote || "분석에 실패했습니다.");
        return body;
    }
    if (status !== "ANALYZED") {
        appendTextElement(body, "p", "analysis-empty-text", "탐지된 내용 없음");
        return body;
    }

    const categories = [
        ["참가자격", analysis?.qualificationRequirements],
        ["제출 필요 서류", analysis?.requiredDocuments],
        ["제출방법", analysis?.submissionMethods],
        ["제출기한", analysis?.submissionDeadlines],
        ["공동수급", analysis?.jointContractRequirements]
    ];
    categories.forEach(([title, values]) => {
        const group = document.createElement("section");
        group.className = "attachment-analysis-group";
        appendTextElement(group, "h5", "", title);
        const safeValues = Array.isArray(values) ? values : [];
        if (safeValues.length === 0) {
            appendTextElement(group, "p", "analysis-empty-text", "탐지된 내용 없음");
        } else {
            const list = document.createElement("ul");
            safeValues.forEach((value) => appendTextElement(list, "li", "", value));
            group.appendChild(list);
        }
        body.appendChild(group);
    });
    return body;
}

/** 분석된 첨부와 실패·미분석 상태를 파일별로 보여주고 전체 결과를 접어 둔다. */
function appendAttachmentDetails(parent, attachments) {
    const section = document.createElement("section");
    section.className = "attachment-summary-section";
    const heading = document.createElement("div");
    heading.className = "analysis-card-heading attachment-summary-heading";
    appendTextElement(heading, "h3", "", "첨부문서");
    section.appendChild(heading);

    if (attachments.length === 0) {
        appendTextElement(section, "p", "analysis-empty-text", "등록된 첨부파일이 없습니다.");
        parent.appendChild(section);
        return;
    }

    const statuses = attachments.map(getDocumentAnalysisStatus);
    const analyzedCount = statuses.filter((status) => status === "ANALYZED").length;
    const failedCount = statuses.filter((status) => status === "FAILED").length;
    const notAnalyzedCount = statuses.length - analyzedCount - failedCount;
    const countParts = [`분석완료 ${analyzedCount}건`, `미분석 ${notAnalyzedCount}건`];
    if (failedCount > 0) {
        countParts.push(`분석실패 ${failedCount}건`);
    }
    appendTextElement(heading, "span", "attachment-count-summary", countParts.join(" / "));

    const collapsible = document.createElement("div");
    collapsible.className = "attachment-collapsible hidden";
    if (statuses.includes("FAILED")) {
        appendTextElement(collapsible, "p", "attachment-warning", "일부 첨부파일을 분석하지 못했습니다.");
    }
    if (statuses.every((status) => ["NOT_ANALYZED", "UNKNOWN"].includes(status))) {
        appendTextElement(
            collapsible,
            "p",
            "attachment-warning",
            "분석 가능한 PDF/HWPX/HWP가 없거나 아직 분석되지 않아 추출 결과를 확인할 수 없습니다."
        );
    }

    const list = document.createElement("div");
    list.className = "attachment-list";
    attachments.forEach((attachment, index) => {
        const item = document.createElement("article");
        item.className = "attachment-item";

        const fileInfo = document.createElement("div");
        fileInfo.className = "attachment-file-info";
        appendTextElement(fileInfo, "span", "attachment-name", displayValue(attachment.fileName));
        appendTextElement(fileInfo, "span", "attachment-type", displayValue(attachment.documentType));
        item.appendChild(fileInfo);

        const action = document.createElement("div");
        action.className = "attachment-actions";
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
                action.appendChild(link);
            }
        }
        item.appendChild(action);

        if (["ANALYZED", "FAILED"].includes(status)) {
            const details = document.createElement("details");
            details.className = "attachment-analysis-details";
            const summaryLabel = status === "FAILED" ? "실패내용 보기" : "분석내용 보기";
            const summary = appendTextElement(details, "summary", "attachment-analysis-toggle", summaryLabel);
            summary.setAttribute("aria-label", `${index + 1}번째 첨부 ${summaryLabel}`);
            details.appendChild(createAttachmentAnalysisBody(attachment));
            item.appendChild(details);
        } else {
            appendTextElement(item, "span", "attachment-no-analysis", "미분석");
        }
        list.appendChild(item);
    });
    collapsible.appendChild(list);
    section.appendChild(collapsible);

    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "analysis-section-toggle attachment-section-toggle";
    toggle.textContent = "첨부문서 보기";
    toggle.setAttribute("aria-expanded", "false");
    toggle.addEventListener("click", () => {
        const expanded = collapsible.classList.toggle("hidden") === false;
        toggle.textContent = expanded ? "첨부문서 접기" : "첨부문서 보기";
        toggle.setAttribute("aria-expanded", String(expanded));
    });
    section.appendChild(toggle);
    parent.appendChild(section);
}

function appendBidDetailField(parent, label, value, wide = false) {
    const field = document.createElement("div");
    field.className = `bid-detail-field${wide ? " bid-detail-field-wide" : ""}`;
    appendTextElement(field, "span", "bid-detail-label", label);
    appendTextElement(field, "span", "bid-detail-value", displayValue(value));
    parent.appendChild(field);
}

/** 목록에서 생략한 정확한 기본 정보와 판단 상태를 상세 상단에 모은다. */
function appendBidDetailOverview(parent, bid) {
    const hero = document.createElement("section");
    hero.className = "bid-detail-hero";

    const badges = document.createElement("div");
    badges.className = "bid-detail-badges";
    const reviewBadge = appendTextElement(badges, "span", "status", displayValue(bid.reviewStatus));
    reviewBadge.classList.add(getStatusClass(bid.reviewStatus));
    if ((bid.externalCheckStatus || "UNKNOWN") === "REQUIRED") {
        appendTextElement(badges, "span", "external-check-status external-check-required", "외부확인 필요");
    }
    hero.appendChild(badges);

    appendTextElement(hero, "h3", "bid-detail-title", displayValue(bid.bidNtceNm));
    appendTextElement(
        hero,
        "p",
        "bid-detail-identity",
        `${displayValue(bid.ntceInsttNm)} · 공고번호 ${displayValue(bid.bidNtceNo)}`
    );

    const facts = document.createElement("div");
    facts.className = "bid-detail-facts";
    appendBidDetailField(facts, "입찰마감", bid.bidClseDt);
    appendBidDetailField(facts, "배정예산", formatAmount(bid.asignBdgtAmt));
    hero.appendChild(facts);
    parent.appendChild(hero);
}

/** 목록에서 제거한 면허조건과 전체 판정사유를 상세의 판정 영역에 표시한다. */
function appendBidDecisionDetail(parent, bid) {
    const section = document.createElement("section");
    section.className = "bid-detail-section";
    appendTextElement(section, "h3", "bid-detail-section-title", "판정 정보");
    const grid = document.createElement("div");
    grid.className = "bid-decision-grid";
    appendBidDetailField(grid, "낙찰방법", bid.sucsfbidMthdNm);
    const awardMethodLabel = getAwardMethodLabel(bid);
    if (awardMethodLabel) {
        appendBidDetailField(grid, "적격심사 판정", awardMethodLabel);
    }
    appendBidDetailField(grid, "지역제한", bid.participationRegion);
    appendBidDetailField(grid, "면허조건", bid.licenseLimit, true);
    appendBidDetailField(grid, "판정사유", bid.reviewReason, true);
    if (bid.awardMethodReason) {
        appendBidDetailField(grid, "낙찰방법 판정 근거", bid.awardMethodReason, true);
    }
    section.appendChild(grid);
    parent.appendChild(section);
}

/** 기존 문서분석 결과는 상세 안에서 필요할 때만 펼치도록 구성한다. */
function appendDocumentAnalysisDisclosure(parent, attachments, merged) {
    const section = document.createElement("section");
    section.className = "bid-detail-section bid-document-analysis";
    if (!hasDocumentAnalysis(attachments)) {
        appendTextElement(section, "h3", "bid-detail-section-title", "문서분석");
        appendTextElement(section, "p", "analysis-empty-text", "분석 가능한 자료가 없거나 아직 분석되지 않았습니다.");
        parent.appendChild(section);
        return;
    }

    const details = document.createElement("details");
    details.className = "bid-analysis-disclosure";
    const summary = document.createElement("summary");
    appendTextElement(summary, "span", "bid-analysis-summary-title", "문서분석 보기");
    appendTextElement(summary, "span", "bid-analysis-summary-hint", "제출서류·참가자격·제출방법 확인");
    details.appendChild(summary);

    const content = document.createElement("div");
    content.className = "bid-analysis-disclosure-content";
    const priorityGrid = document.createElement("div");
    priorityGrid.className = "analysis-priority-grid analysis-priority-grid-compact";
    priorityGrid.appendChild(createAnalysisResultSection("제출기한", merged.submissionDeadlines, {variant: "deadline", compact: true}));
    priorityGrid.appendChild(createAnalysisResultSection("제출방법", merged.submissionMethods, {variant: "method", compact: true}));
    content.appendChild(priorityGrid);
    content.appendChild(createAnalysisResultSection("제출서류", merged.requiredDocuments, {
        checklist: true, variant: "documents", previewLimit: 6, toggleText: `전체 ${merged.requiredDocuments.length}건 보기`
    }));
    content.appendChild(createAnalysisResultSection("참가자격", merged.qualificationRequirements, {
        previewLimit: 4, toggleText: `전체 ${merged.qualificationRequirements.length}건 보기`
    }));
    content.appendChild(createAnalysisResultSection("공동수급 조건", merged.jointContractRequirements, {
        previewLimit: 0, startCollapsed: true, headingToggle: true
    }));
    details.appendChild(content);
    section.appendChild(details);
    parent.appendChild(section);
}

function appendBidDetailActions(parent, bid) {
    const actions = document.createElement("div");
    actions.className = "bid-detail-actions";
    const sourceLink = createSafeLink(bid.bidNtceDtlUrl, "원문 보기 ↗", "bid-source-link");
    if (sourceLink) {
        actions.appendChild(sourceLink);
    } else {
        appendTextElement(actions, "span", "analysis-empty-text", "원문 링크를 확인할 수 없습니다.");
    }
    parent.appendChild(actions);
}

/** 선택한 공고의 판단 정보와 기존 문서분석 결과를 비우고 새로 구성해 모달을 연다. */
function openDocumentAnalysisModal(bid, triggerButton) {
    lastAnalysisTrigger = triggerButton;
    analysisModalContent.replaceChildren();
    analysisModalTitle.textContent = "입찰공고 상세";

    const attachments = Array.isArray(bid.attachments) ? bid.attachments : [];
    const merged = mergeDocumentAnalysis(attachments);
    appendBidDetailOverview(analysisModalContent, bid);
    appendBidDecisionDetail(analysisModalContent, bid);
    const externalSection = document.createElement("section");
    externalSection.className = "bid-detail-section";
    appendExternalCheckDetail(externalSection, bid);
    analysisModalContent.appendChild(externalSection);
    appendAttachmentDetails(analysisModalContent, attachments);
    appendDocumentAnalysisDisclosure(analysisModalContent, attachments, merged);
    appendBidDetailActions(analysisModalContent, bid);

    analysisModal.classList.remove("hidden");
    document.body.classList.add("modal-open");
    analysisModalClose.focus();
}

/** 모달 내용과 포커스를 초기화해 다른 공고의 데이터가 남지 않게 한다. */
function closeDocumentAnalysisModal() {
    analysisModal.classList.add("hidden");
    document.body.classList.remove("modal-open");
    analysisModalContent.replaceChildren();
    analysisModalTitle.textContent = "입찰공고 상세";
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
    statusCell.className = "bid-status-cell";
    statusCell.dataset.label = "검토상태";
    const status = document.createElement("span");

    status.className = `status ${getStatusClass(bid.reviewStatus)}`;
    status.textContent = displayValue(bid.reviewStatus);
    statusCell.appendChild(status);
    row.appendChild(statusCell);

    row.appendChild(createClampedCell(bid.bidNtceNm, "공고명", "bid-title-cell"));
    row.appendChild(createClampedCell(bid.ntceInsttNm, "공고기관", "bid-institution-cell"));
    const budgetCell = createCell(formatCompactAmount(bid.asignBdgtAmt), "배정예산", "bid-budget-cell");
    budgetCell.title = formatAmount(bid.asignBdgtAmt);
    row.appendChild(budgetCell);
    row.appendChild(createDeadlineCell(bid.bidClseDt));
    row.appendChild(createAwardMethodCell(bid));
    row.appendChild(createCell(bid.participationRegion, "지역제한", "bid-region-cell"));

    const checkCell = document.createElement("td");
    checkCell.className = "bid-check-cell";
    checkCell.dataset.label = "확인사항";
    const externalStatus = bid.externalCheckStatus || "UNKNOWN";
    if (externalStatus === "REQUIRED") {
        appendTextElement(checkCell, "span", "external-check-status external-check-required", "외부확인 필요");
    } else if (externalStatus === "REFERENCE") {
        appendTextElement(checkCell, "span", "external-check-status external-check-reference", "참고사이트");
    }
    const analysisAvailable = hasDocumentAnalysis(bid.attachments);
    if (analysisAvailable) {
        appendTextElement(checkCell, "span", "bid-check-badge", "문서분석 있음");
    }
    if (checkCell.childElementCount === 0) {
        appendTextElement(checkCell, "span", "bid-check-none", "특이사항 없음");
    }
    row.appendChild(checkCell);

    const detailCell = document.createElement("td");
    detailCell.className = "bid-action-cell";
    detailCell.dataset.label = "상세";
    const detailButton = document.createElement("button");
    detailButton.type = "button";
    detailButton.className = "bid-detail-button";
    detailButton.textContent = "상세";
    detailButton.addEventListener("click", () => openDocumentAnalysisModal(bid, detailButton));
    detailCell.appendChild(detailButton);
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
