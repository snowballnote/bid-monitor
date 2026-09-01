"use strict";

const API_BASE_URL = "/api/external-notices";
const PREVIEW_MAX_CHARACTERS = 360;

const collectButton = document.querySelector("#collect-button");
const allFilterButton = document.querySelector("#all-filter-button");
const piaFilterButton = document.querySelector("#pia-filter-button");
const filterDescription = document.querySelector("#filter-description");
const noticeCount = document.querySelector("#notice-count");
const noticePiaCount = document.querySelector("#notice-pia-count");
const noticeLastChecked = document.querySelector("#notice-last-checked");
const collectionMessage = document.querySelector("#collection-message");
const loadingMessage = document.querySelector("#loading-message");
const emptyMessage = document.querySelector("#empty-message");
const errorMessage = document.querySelector("#error-message");
const noticeList = document.querySelector("#notice-list");
const noticeModal = document.querySelector("#notice-modal");
const modalCloseButton = document.querySelector("#modal-close-button");
const modalTitle = document.querySelector("#notice-detail-title");
const modalStatus = document.querySelector("#modal-status");
const modalPublicationMeta = document.querySelector("#modal-publication-meta");
const modalKeywords = document.querySelector("#modal-keywords");
const modalLoading = document.querySelector("#modal-loading");
const modalError = document.querySelector("#modal-error");
const noticeDetail = document.querySelector("#notice-detail");
const noticeScrollBody = document.querySelector("#notice-scroll-body");
const noticeActions = document.querySelector("#notice-actions");

let piaOnly = true;
let lastFocusedElement = null;
let directNoticeOpened = false;

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

    const cardTop = document.createElement("div");
    cardTop.className = "notice-card-top";
    const heading = document.createElement("div");
    heading.className = "notice-heading";
    const publishedDate = createTextElement("time", "notice-date", formatDate(notice.publishedDate));
    if (notice.publishedDate) {
        publishedDate.dateTime = notice.publishedDate;
    }
    heading.appendChild(publishedDate);
    const titleButton = createTextElement("button", "notice-title-button", notice.title || "제목 없음");
    titleButton.type = "button";
    titleButton.addEventListener("click", () => openNoticeDetail(notice.id, titleButton));
    heading.appendChild(titleButton);
    cardTop.appendChild(heading);

    const badges = document.createElement("div");
    badges.className = "notice-badges";
    badges.appendChild(createBadge(Boolean(notice.piaRelated)));
    cardTop.appendChild(badges);
    item.appendChild(cardTop);

    const keywords = document.createElement("div");
    keywords.className = "notice-keywords";
    keywords.appendChild(createTextElement("span", "meta-label", "매칭 키워드"));
    keywords.appendChild(createKeywordList(notice.matchedKeywords));
    item.appendChild(keywords);

    const meta = document.createElement("div");
    meta.className = "notice-meta";
    const firstSeen = document.createElement("span");
    firstSeen.className = "notice-meta-item";
    firstSeen.appendChild(createTextElement("strong", "", "최초 수집"));
    firstSeen.appendChild(document.createTextNode(formatDateTime(notice.firstSeenAt)));
    meta.appendChild(firstSeen);
    const lastSeen = document.createElement("span");
    lastSeen.className = "notice-meta-item";
    lastSeen.appendChild(createTextElement("strong", "", "최근 확인"));
    lastSeen.appendChild(document.createTextNode(formatDateTime(notice.lastSeenAt)));
    meta.appendChild(lastSeen);
    item.appendChild(meta);

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
    const safeNotices = Array.isArray(notices) ? notices : [];
    const piaNotices = safeNotices.filter((notice) => Boolean(notice.piaRelated));
    const lastSeenValues = safeNotices
        .map((notice) => notice.lastSeenAt)
        .filter(Boolean)
        .sort((left, right) => new Date(right) - new Date(left));
    noticePiaCount.textContent = `${piaNotices.length}건`;
    noticeLastChecked.textContent = lastSeenValues.length > 0 ? formatDateTime(lastSeenValues[0]) : "-";

    if (safeNotices.length === 0) {
        noticeCount.textContent = "0건";
        showListState("empty");
        return;
    }

    noticeCount.textContent = `${safeNotices.length}건`;
    const fragment = document.createDocumentFragment();
    safeNotices.forEach((notice) => fragment.appendChild(createNoticeItem(notice)));
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
        const directNoticeId = new URLSearchParams(window.location.search).get("noticeId");
        if (!directNoticeOpened && directNoticeId) {
            directNoticeOpened = true;
            await openNoticeDetail(directNoticeId, null);
        }
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
    collectButton.setAttribute("aria-busy", "true");
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
        collectButton.removeAttribute("aria-busy");
        collectButton.textContent = "↻ 지금 수집";
    }
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
    modalStatus.replaceChildren(createBadge(Boolean(notice.piaRelated)));
    modalPublicationMeta.textContent = `${formatDate(notice.publishedDate)} · 개인정보 포털`;
    modalKeywords.replaceChildren(createKeywordList(notice.matchedKeywords));
    noticeScrollBody.replaceChildren();
    noticeActions.replaceChildren();

    const body = typeof notice.body === "string" ? notice.body : "";
    if (body.trim().length > 0) {
        // 원문을 재작성하지 않고 앞부분만 사용하며, 나머지 노출 범위는 CSS line-clamp로 제한한다.
        const preview = document.createElement("div");
        preview.className = "notice-preview";
        preview.appendChild(createTextElement("div", "notice-preview-text", body.slice(0, PREVIEW_MAX_CHARACTERS)));
        noticeScrollBody.appendChild(createDetailSection("공지 미리보기", preview));
    }

    if (notice.classificationReason) {
        const explanation = document.createElement("details");
        explanation.className = "classification-details";
        explanation.appendChild(createTextElement("summary", "", "왜 PIA 관련인가요?"));
        explanation.appendChild(createTextElement("p", "detail-value", notice.classificationReason));
        noticeScrollBody.appendChild(explanation);
    }

    const hasAttachments = Array.isArray(notice.attachments) && notice.attachments.length > 0;
    noticeActions.classList.toggle("without-attachments", !hasAttachments);
    if (hasAttachments) {
        const attachmentPanel = document.createElement("section");
        attachmentPanel.className = "detail-link-panel";
        attachmentPanel.appendChild(createTextElement("h3", "", "첨부파일"));
        attachmentPanel.appendChild(createAttachmentList(notice.attachments));
        noticeActions.appendChild(attachmentPanel);
    }

    const sourcePanel = document.createElement("section");
    sourcePanel.className = "detail-link-panel source-panel";
    const sourceUrl = safeExternalUrl(notice.detailUrl);
    if (sourceUrl) {
        const sourceLink = createTextElement("a", "source-link", "개인정보 포털에서 전체 내용 보기 ↗");
        sourceLink.href = sourceUrl;
        sourceLink.target = "_blank";
        sourceLink.rel = "noopener noreferrer";
        sourcePanel.appendChild(sourceLink);
    } else {
        sourcePanel.appendChild(createTextElement("span", "detail-value", "원문 링크가 없습니다."));
    }
    noticeActions.appendChild(sourcePanel);

    modalLoading.classList.add("hidden");
    modalError.classList.add("hidden");
    noticeDetail.classList.remove("hidden");
}

async function openNoticeDetail(id, triggerElement) {
    lastFocusedElement = triggerElement;
    noticeModal.classList.remove("hidden");
    noticeModal.setAttribute("aria-hidden", "false");
    document.body.classList.add("modal-open");
    modalTitle.textContent = "공지 상세";
    modalStatus.replaceChildren();
    modalPublicationMeta.textContent = "개인정보 포털";
    modalKeywords.replaceChildren();
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
    noticeModal.setAttribute("aria-hidden", "true");
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
        return;
    }

    if (event.key === "Tab" && !noticeModal.classList.contains("hidden")) {
        const focusableElements = Array.from(noticeModal.querySelectorAll(
            "button:not([disabled]), a[href], input:not([disabled]), [tabindex]:not([tabindex='-1'])"
        ));
        if (focusableElements.length === 0) {
            event.preventDefault();
            return;
        }
        const firstElement = focusableElements[0];
        const lastElement = focusableElements[focusableElements.length - 1];
        if (event.shiftKey && document.activeElement === firstElement) {
            event.preventDefault();
            lastElement.focus();
        } else if (!event.shiftKey && document.activeElement === lastElement) {
            event.preventDefault();
            firstElement.focus();
        }
    }
});

// 첫 진입에서는 사용자가 중요 공지를 바로 확인할 수 있도록 PIA 관련 목록을 조회한다.
loadNotices();
