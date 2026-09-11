"use strict";
const bidCheckCount = document.querySelector("#bid-check-count");
const bidSummaryMessage = document.querySelector("#bid-check-message");
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
        bidCheckCount.textContent = `${bids.filter((bid) => bid.reviewStatus === "추가확인필요").length}건`;
        bidSummaryMessage.textContent = "추가 확인 필요 공고";
    } catch (error) {
        console.error(error);
        bidCheckCount.textContent = "-";
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

function summary(key, count, message) {
    document.querySelector(`#${key}-count`).textContent = count == null ? '—' : `${count}건`;
    document.querySelector(`#${key}-message`).textContent = message;
}

async function fetchList(url) {
    const rows = await fetchJson(url);
    if (!Array.isArray(rows)) throw new Error('목록을 확인할 수 없습니다.');
    return rows;
}

function recent(rows) {
    // Keep the existing API order when timestamps are unavailable.
    return [...rows].sort((a, b) => (Date.parse(b.updatedAt || b.createdAt) || 0)
        - (Date.parse(a.updatedAt || a.createdAt) || 0)).slice(0, 3);
}

function workState(target, message, error = false) {
    target.replaceChildren(createTextElement('p', `dashboard-state${error ? ' error' : ''}`, message));
}

function workLink(url, label) {
    const link = createTextElement('a', 'work-action', label);
    link.href = url;
    return link;
}

function deadlineBadge(deadline) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const days = deadline ? Math.round((new Date(deadline + 'T00:00:00') - today) / 86400000) : NaN;
    const label = Number.isNaN(days) ? '마감일 미설정' : days === 0 ? 'D-day' : days > 0 ? `D-${days}` : `D+${-days}`;
    return createTextElement('span', `work-badge${days <= 3 ? ' attention' : ''}`, label);
}

async function loadSubmissions() {
    const target = document.querySelector('#recent-submissions');
    try {
        const rows = await fetchList('/api/submission-cases');
        const valid = row => Number.isInteger(row.prepared) && Number.isInteger(row.total)
            && row.prepared >= 0 && row.total >= row.prepared;
        summary('submission', rows.every(valid) ? rows.filter(row => !row.total || row.prepared < row.total).length : null,
            rows.length ? '서류 준비가 남은 프로젝트' : '등록된 프로젝트 없음');
        target.replaceChildren();
        if (!rows.length) return workState(target, '등록된 서류 프로젝트가 없습니다.');
        for (const row of recent(rows)) {
            const item = createTextElement('article', 'work-item');
            const heading = createTextElement('div', 'work-heading');
            heading.append(createTextElement('h3', '', row.projectName || '사업명 미등록'), deadlineBadge(row.deadline));
            item.append(heading);
            if (valid(row)) {
                const percent = row.total ? Math.round(row.prepared / row.total * 100) : 0;
                const line = createTextElement('div', 'work-progress');
                const progress = document.createElement('progress');
                progress.max = 100; progress.value = percent;
                progress.setAttribute('aria-label', `${row.projectName || '서류'} 준비율`);
                line.append(progress, createTextElement('span', '', `${row.prepared} / ${row.total} · ${percent}%`));
                item.append(line);
            } else item.append(createTextElement('p', 'work-meta', '진행률 확인 필요'));
            const footer = createTextElement('div', 'work-footer');
            footer.append(createTextElement('span', 'work-meta', row.deadline ? `마감일 ${row.deadline}` : '마감일 미설정'),
                workLink(`/submissions/?caseId=${encodeURIComponent(row.id)}`, '계속 작업'));
            item.append(footer); target.append(item);
        }
    } catch {
        summary('submission', null, '조회 실패');
        workState(target, '서류 프로젝트를 불러오지 못했습니다.', true);
    }
}

async function loadPerformances() {
    const target = document.querySelector('#recent-performances');
    try {
        const projects = await fetchList('/api/performance-projects');
        const evidence = new Map();
        const pending = [...projects];
        // Existing entry lists contain the saved file links; limit concurrent reads.
        await Promise.all(Array.from({ length: Math.min(4, pending.length) }, async () => {
            while (pending.length) {
                const project = pending.shift();
                try {
                    const entries = await fetchList(`/api/performance-projects/${encodeURIComponent(project.id)}/entries`);
                    const prepared = entries.filter(({ info }) => info?.selectedFileId != null
                        || info?.selectedDriveFileId != null || info?.selectedUploadedFileId != null).length;
                    evidence.set(project.id, { total: entries.length, prepared });
                } catch { evidence.set(project.id, null); }
            }
        }));
        const complete = [...evidence.values()].every(Boolean);
        summary('performance', complete ? [...evidence.values()].reduce((sum, row) => sum + row.prepared, 0) : null,
            complete ? projects.length ? '증빙 파일이 연결된 실적' : '등록된 실적 없음' : '일부 증빙 조회 실패');
        target.replaceChildren();
        if (!projects.length) return workState(target, '등록된 실적 프로젝트가 없습니다.');
        for (const project of recent(projects)) {
            const row = evidence.get(project.id);
            const item = createTextElement('article', 'work-item');
            item.append(createTextElement('h3', '', project.name || '사업명 미등록'));
            const badges = createTextElement('div', 'work-badges');
            if (!row) badges.append(createTextElement('span', 'work-badge attention', '증빙 조회 실패'));
            else if (!row.total) badges.append(createTextElement('span', 'work-badge', '등록된 실적 없음'));
            else {
                if (row.prepared) badges.append(createTextElement('span', 'work-badge ready', `증빙 등록 ${row.prepared}건`));
                if (row.total > row.prepared) badges.append(createTextElement('span', 'work-badge attention', `파일 미등록 ${row.total - row.prepared}건`));
            }
            item.append(badges);
            const footer = createTextElement('div', 'work-footer');
            footer.append(createTextElement('span', 'work-meta', row ? `전체 실적 ${row.total}건` : '파일 관리에서 확인하세요'),
                workLink(`/performances/index.html?project=${encodeURIComponent(project.id)}`, '파일 관리'));
            item.append(footer); target.append(item);
        }
    } catch {
        summary('performance', null, '조회 실패');
        workState(target, '실적 프로젝트를 불러오지 못했습니다.', true);
    }
}

async function loadDocuments() {
    try {
        const rows = (await fetchList('/api/submission-document-masters')).filter(row => row.category === 'COMPANY_COMMON');
        summary('document', rows.filter(row => !row.uploadedFileId && !row.currentFileId).length,
            rows.length ? '회사 공통서류 기준' : '등록된 공통서류 없음');
    } catch { summary('document', null, '조회 실패'); }
}

loadSubmissions();
loadPerformances();
loadDocuments();
