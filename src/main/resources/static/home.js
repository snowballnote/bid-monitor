"use strict";
const bidTotalCount = document.querySelector("#bid-total-count");
const bidCheckCount = document.querySelector("#bid-check-count");
const bidSummaryMessage = document.querySelector("#bid-summary-message");
const piaNoticeCount = document.querySelector("#pia-notice-count");
const noticeLastChecked = document.querySelector("#notice-last-checked");
const noticeSummaryMessage = document.querySelector("#notice-summary-message");
const recentNoticeLoading = document.querySelector("#recent-notice-loading");
const recentNoticeEmpty = document.querySelector("#recent-notice-empty");
const recentNoticeError = document.querySelector("#recent-notice-error");
const recentNoticeList = document.querySelector("#recent-notice-list");

async function fetchJson(url) {
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`데이터 조회 실패: ${response.status}`);
    return response.json();
}

function formatDateTime(value) {
    if (!value) return "확인 기록 없음";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function createTextElement(tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) element.className = className;
    element.textContent = text;
    return element;
}

function showRecentState(state) {
    recentNoticeLoading.classList.toggle("hidden", state !== "loading");
    recentNoticeEmpty.classList.toggle("hidden", state !== "empty");
    recentNoticeError.classList.toggle("hidden", state !== "error");
    recentNoticeList.classList.toggle("hidden", state !== "content");
}

function renderRecentNotices(notices) {
    recentNoticeList.replaceChildren();
    if (notices.length === 0) { showRecentState("empty"); return; }
    const fragment = document.createDocumentFragment();
    notices.slice(0, 3).forEach((notice) => {
        const item = document.createElement("article");
        item.className = "recent-notice-item";
        item.appendChild(createTextElement("time", "recent-notice-date", notice.publishedDate || "게시일 미상"));
        const content = document.createElement("div");
        content.className = "recent-notice-content";
        const title = createTextElement("a", "recent-notice-title", notice.title || "제목 없음");
        title.href = `/notices/?noticeId=${encodeURIComponent(notice.id)}`;
        content.appendChild(title);
        if (Array.isArray(notice.matchedKeywords) && notice.matchedKeywords.length > 0) {
            const keywords = document.createElement("div");
            keywords.className = "recent-keywords";
            notice.matchedKeywords.slice(0, 4).forEach((keyword) => keywords.appendChild(createTextElement("span", "recent-keyword", keyword)));
            content.appendChild(keywords);
        }
        item.appendChild(content);
        item.appendChild(createTextElement("span", "recent-notice-arrow", "›"));
        fragment.appendChild(item);
    });
    recentNoticeList.appendChild(fragment);
    showRecentState("content");
}

async function loadBidSummary() {
    try {
        const bids = await fetchJson("/api/bids/target/qualification?allowedLicenseCodes=6146,1468");
        if (!Array.isArray(bids)) throw new Error("입찰공고 응답 형식이 올바르지 않습니다.");
        bidTotalCount.textContent = `${bids.length}건`;
        bidCheckCount.textContent = `${bids.filter((bid) => bid.reviewStatus === "추가확인필요").length}건`;
        bidSummaryMessage.textContent = "";
    } catch (error) {
        console.error(error);
        bidTotalCount.textContent = "-"; bidCheckCount.textContent = "-";
        bidSummaryMessage.textContent = "입찰공고 현황을 불러오지 못했습니다.";
    }
}

async function loadNoticeSummary() {
    try {
        const notices = await fetchJson("/api/external-notices?piaRelated=true");
        if (!Array.isArray(notices)) throw new Error("외부공지 응답 형식이 올바르지 않습니다.");
        piaNoticeCount.textContent = `${notices.length}건`;
        const latestSeenAt = notices.map((notice) => notice.lastSeenAt).filter(Boolean).sort().at(-1);
        noticeLastChecked.textContent = formatDateTime(latestSeenAt);
        noticeSummaryMessage.textContent = "";
        renderRecentNotices(notices);
    } catch (error) {
        console.error(error);
        piaNoticeCount.textContent = "-"; noticeLastChecked.textContent = "-";
        noticeSummaryMessage.textContent = "외부공지 현황을 불러오지 못했습니다.";
        showRecentState("error");
    }
}
loadBidSummary();
loadNoticeSummary();
