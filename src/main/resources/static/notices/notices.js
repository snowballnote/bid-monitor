"use strict";

const API_BASE_URL = "/api/external-notices";

const collectButton = document.querySelector("#collect-button");
const allFilterButton = document.querySelector("#all-filter-button");
const piaFilterButton = document.querySelector("#pia-filter-button");
const filterDescription = document.querySelector("#filter-description");
const collectionMessage = document.querySelector("#collection-message");
const loadingMessage = document.querySelector("#loading-message");
const emptyMessage = document.querySelector("#empty-message");
const errorMessage = document.querySelector("#error-message");
const noticeList = document.querySelector("#notice-list");
const noticeModal = document.querySelector("#notice-modal");
const modalCloseButton = document.querySelector("#modal-close-button");
const modalTitle = document.querySelector("#notice-detail-title");
const modalLoading = document.querySelector("#modal-loading");
const modalError = document.querySelector("#modal-error");
const noticeDetail = document.querySelector("#notice-detail");

let piaOnly = true;
let lastFocusedElement = null;

/** API가 반환한 오류 본문을 사용자에게 보여줄 수 있는 짧은 메시지로 변환한다. */
async function readErrorMessage(response, fallback) {
    try {
        const body = await response.json();
        return body.message || fallback;
    } catch (error) {
        return fallback;
    }
}

/** 외부 링크로 사용할 수 있는 http/https URL만 허용한다. */
function safeExternalUrl(value) {
    if (!value) {
        return null;
    }

    try {
        const url = new URL(value, window.location.origin);
        return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
    } catch (error) {
        return null;
    }
}

function formatDate(value) {
    return value || "게시일 미상";
}

function formatDateTime(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat("ko-KR", {
        dateStyle: "medium",
        timeStyle: "short"
    }).format(date);
}

function createTextElement(tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) {
        element.className = className;
    }
    element.textContent = text;
    return element;
}

function createBadge(piaRelated) {
    return createTextElement(
        "span",
        `badge ${piaRelated ? "badge-pia" : "badge-normal"}`,
        piaRelated ? "PIA 관련" : "일반 공지"
    );
}

function createKeywordList(keywords) {
    const list = document.createElement("div");
    list.className = "keyword-list";

    if (!Array.isArray(keywords) || keywords.length === 0) {
        list.appendChild(createTextElement("span", "detail-value", "매칭 키워드 없음"));
        return list;
    }

    keywords.forEach((keyword) => list.appendChild(createTextElement("span", "keyword", keyword)));
    return list;
}

function createNoticeItem(notice) {
    const item = document.createElement("article");
    item.className = `notice-item${notice.piaRelated ? " pia-related" : ""}`;

    const heading = document.createElement("div");
    heading.className = "notice-heading";
    const titleButton = createTextElement("button", "notice-title-button", notice.title || "제목 없음");
    titleButton.type = "button";
    titleButton.addEventListener("click", () => openNoticeDetail(notice.id, titleButton));
    heading.appendChild(titleButton);
    item.appendChild(heading);

    const badges = document.createElement("div");
    badges.className = "notice-badges";
    badges.appendChild(createBadge(Boolean(notice.piaRelated)));
    item.appendChild(badges);

    const meta = document.createElement("div");
    meta.className = "notice-meta";
    meta.appendChild(createTextElement("span", "", `게시일 ${formatDate(notice.publishedDate)}`));
    meta.appendChild(createTextElement("span", "", `최초 수집 ${formatDateTime(notice.firstSeenAt)}`));
    meta.appendChild(createTextElement("span", "", `최근 확인 ${formatDateTime(notice.lastSeenAt)}`));
    item.appendChild(meta);

    const keywords = document.createElement("div");
    keywords.className = "notice-keywords";
    keywords.appendChild(createTextElement("span", "meta-label", "매칭 키워드"));
    keywords.appendChild(createKeywordList(notice.matchedKeywords));
    item.appendChild(keywords);

    return item;
}

function showListState(state, message) {
    loadingMessage.classList.toggle("hidden", state !== "loading");
    emptyMessage.classList.toggle("hidden", state !== "empty");
    errorMessage.classList.toggle("hidden", state !== "error");
    noticeList.classList.toggle("hidden", state !== "content");
    if (state === "error") {
        errorMessage.textContent = message;
    }
}

function renderNotices(notices) {
    noticeList.replaceChildren();
    if (!Array.isArray(notices) || notices.length === 0) {
        showListState("empty");
        return;
    }

    const fragment = document.createDocumentFragment();
    notices.forEach((notice) => fragment.appendChild(createNoticeItem(notice)));
    noticeList.appendChild(fragment);
    showListState("content");
}

async function loadNotices() {
    showListState("loading");
    const url = piaOnly ? `${API_BASE_URL}?piaRelated=true` : API_BASE_URL;

    try {
        const response = await fetch(url, { headers: { Accept: "application/json" } });
        if (!response.ok) {
            throw new Error(await readErrorMessage(response, `공지 목록 조회에 실패했습니다. (${response.status})`));
        }
        renderNotices(await response.json());
    } catch (error) {
        console.error(error);
        showListState("error", error.message || "공지 목록을 불러오지 못했습니다.");
    }
}

function setFilter(nextPiaOnly) {
    if (piaOnly === nextPiaOnly) {
        return;
    }

    piaOnly = nextPiaOnly;
    piaFilterButton.classList.toggle("active", piaOnly);
    piaFilterButton.setAttribute("aria-pressed", String(piaOnly));
    allFilterButton.classList.toggle("active", !piaOnly);
    allFilterButton.setAttribute("aria-pressed", String(!piaOnly));
    filterDescription.textContent = piaOnly
        ? "PIA 관련 공지만 표시하고 있습니다."
        : "수집된 전체 공지를 표시하고 있습니다.";
    loadNotices();
}

function showCollectionMessage(message, isError = false) {
    collectionMessage.textContent = message;
    collectionMessage.classList.toggle("error", isError);
    collectionMessage.classList.remove("hidden");
}

async function collectNotices() {
    collectButton.disabled = true;
    collectButton.textContent = "수집 중...";
    collectionMessage.classList.add("hidden");

    try {
        const response = await fetch(`${API_BASE_URL}/collect`, {
            method: "POST",
            headers: { Accept: "application/json" }
        });
        if (!response.ok) {
            throw new Error(await readErrorMessage(response, `공지 수집에 실패했습니다. (${response.status})`));
        }

        const result = await response.json();
        const summary = [
            `수집 ${result.collectedCount ?? 0}건`,
            `신규 ${result.newCount ?? 0}건`,
            `변경 ${result.updatedCount ?? 0}건`,
            `동일 ${result.unchangedCount ?? 0}건`,
            `PIA 관련 ${result.piaRelatedCount ?? 0}건`
        ];
        if ((result.failedCount ?? 0) > 0) {
            summary.push(`실패 ${result.failedCount}건`);
        }
        showCollectionMessage(summary.join(" · "), (result.failedCount ?? 0) > 0);
        await loadNotices();
    } catch (error) {
        console.error(error);
        showCollectionMessage(error.message || "공지 수집 중 오류가 발생했습니다.", true);
    } finally {
        collectButton.disabled = false;
        collectButton.textContent = "공지 새로 수집";
    }
}

function createDetailSummaryItem(label, content) {
    const item = document.createElement("div");
    item.className = "detail-summary-item";
    item.appendChild(createTextElement("span", "detail-label", label));
    if (content instanceof Node) {
        item.appendChild(content);
    } else {
        item.appendChild(createTextElement("div", "detail-value", content));
    }
    return item;
}

function createDetailSection(title, content) {
    const section = document.createElement("section");
    section.className = "detail-section";
    section.appendChild(createTextElement("h3", "", title));
    section.appendChild(content);
    return section;
}

function createAttachmentList(attachments) {
    if (!Array.isArray(attachments) || attachments.length === 0) {
        return createTextElement("p", "detail-value", "첨부파일이 없습니다.");
    }

    const list = document.createElement("ul");
    list.className = "attachment-list";
    attachments.forEach((attachment) => {
        const item = document.createElement("li");
        const url = safeExternalUrl(attachment.fileUrl);
        if (url) {
            const link = createTextElement("a", "attachment-link", attachment.fileName || "첨부파일");
            link.href = url;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            item.appendChild(link);
        } else {
            item.appendChild(createTextElement("span", "detail-value", attachment.fileName || "첨부파일"));
        }
        list.appendChild(item);
    });
    return list;
}

function renderNoticeDetail(notice) {
    modalTitle.textContent = notice.title || "공지 상세";
    noticeDetail.replaceChildren();

    const summary = document.createElement("div");
    summary.className = "detail-summary";
    summary.appendChild(createDetailSummaryItem("게시일", formatDate(notice.publishedDate)));
    summary.appendChild(createDetailSummaryItem("PIA 관련 여부", createBadge(Boolean(notice.piaRelated))));
    summary.appendChild(createDetailSummaryItem("판정 사유", notice.classificationReason || "판정 사유 없음"));
    summary.appendChild(createDetailSummaryItem("매칭 키워드", createKeywordList(notice.matchedKeywords)));
    noticeDetail.appendChild(summary);

    const body = createTextElement("div", "detail-body", notice.body || "본문이 없습니다.");
    noticeDetail.appendChild(createDetailSection("본문", body));
    noticeDetail.appendChild(createDetailSection("첨부파일", createAttachmentList(notice.attachments)));

    const sourceArea = document.createElement("div");
    sourceArea.className = "source-area";
    const sourceUrl = safeExternalUrl(notice.detailUrl);
    if (sourceUrl) {
        const sourceLink = createTextElement("a", "source-link", "개인정보 포털 원문 새 탭에서 보기");
        sourceLink.href = sourceUrl;
        sourceLink.target = "_blank";
        sourceLink.rel = "noopener noreferrer";
        sourceArea.appendChild(sourceLink);
    } else {
        sourceArea.appendChild(createTextElement("span", "detail-value", "원문 링크가 없습니다."));
    }
    noticeDetail.appendChild(sourceArea);

    modalLoading.classList.add("hidden");
    modalError.classList.add("hidden");
    noticeDetail.classList.remove("hidden");
}

async function openNoticeDetail(id, triggerElement) {
    lastFocusedElement = triggerElement;
    noticeModal.classList.remove("hidden");
    document.body.classList.add("modal-open");
    modalTitle.textContent = "공지 상세";
    modalLoading.classList.remove("hidden");
    modalError.classList.add("hidden");
    noticeDetail.classList.add("hidden");
    modalCloseButton.focus();

    try {
        const response = await fetch(`${API_BASE_URL}/${encodeURIComponent(id)}`, {
            headers: { Accept: "application/json" }
        });
        if (!response.ok) {
            throw new Error(await readErrorMessage(response, `공지 상세 조회에 실패했습니다. (${response.status})`));
        }
        renderNoticeDetail(await response.json());
    } catch (error) {
        console.error(error);
        modalLoading.classList.add("hidden");
        modalError.textContent = error.message || "공지 상세를 불러오지 못했습니다.";
        modalError.classList.remove("hidden");
    }
}

function closeNoticeDetail() {
    noticeModal.classList.add("hidden");
    document.body.classList.remove("modal-open");
    if (lastFocusedElement) {
        lastFocusedElement.focus();
    }
}

collectButton.addEventListener("click", collectNotices);
allFilterButton.addEventListener("click", () => setFilter(false));
piaFilterButton.addEventListener("click", () => setFilter(true));
modalCloseButton.addEventListener("click", closeNoticeDetail);
noticeModal.addEventListener("click", (event) => {
    if (event.target === noticeModal) {
        closeNoticeDetail();
    }
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !noticeModal.classList.contains("hidden")) {
        closeNoticeDetail();
    }
});

// 첫 진입에서는 사용자가 중요 공지를 바로 확인할 수 있도록 PIA 관련 목록을 조회한다.
loadNotices();
