const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");

const screenshotDirectory = path.resolve("artifacts", "screenshots");

const bidNotice = {
    bidNtceNo: "20260901001",
    bidNtceNm: "2026년 통합정보시스템 구축 및 운영을 위한 정보시스템 감리 용역",
    ntceInsttNm: "테스트기관",
    asignBdgtAmt: "166564000",
    bidClseDt: "2026-09-10 10:00",
    sucsfbidMthdNm: "적격심사",
    awardMethodCategory: "QUALIFICATION_REVIEW",
    awardMethodStatus: "CONFIRMED",
    awardMethodReason: "낙찰방법 필드에서 \"적격심사\" 확인",
    awardMethodSource: "STRUCTURED_DETAIL",
    licenseLimit: "정보시스템 감리법인 등록 및 전자입찰 참가자격을 모두 충족한 업체",
    participationRegion: "제한없음",
    reviewStatus: "추가확인필요",
    reviewReason: "외부 제안서 제출 사이트와 첨부문서의 세부 참가자격을 함께 확인해야 합니다.",
    externalCheckStatus: "REQUIRED",
    externalCheckReason: "제안서 제출을 위해 외부사이트 확인이 필요합니다.",
    externalSiteUrls: ["https://vendor.example.com/proposal"],
    attachments: [{
        fileName: "제안요청서.pdf",
        fileUrl: "https://example.com/files/request.pdf",
        documentType: "PDF",
        documentAnalysis: {
            analysisStatus: "ANALYZED",
            qualificationRequirements: ["정보시스템 감리법인 등록"],
            requiredDocuments: ["기술제안서", "사업수행실적 증명서"],
            submissionMethods: ["외부사이트 온라인 제출"],
            submissionDeadlines: ["2026-09-10 10:00까지"],
            jointContractRequirements: []
        }
    }],
    bidNtceDtlUrl: "https://www.g2b.go.kr/link/PNPE027_01/single/?bidPbancNo=20260901001"
};

const piaNotice = {
    id: 101,
    externalId: "PRIVACY_PORTAL:BBSMSTR_000000000001:101",
    title: "2026년 개인정보 영향평가 전문교육 안내",
    publishedDate: "2026-08-28",
    piaRelated: true,
    matchedKeywords: ["개인정보 영향평가", "전문교육", "신청"],
    firstSeenAt: "2026-09-01T00:00:00Z",
    lastSeenAt: "2026-09-01T01:00:00Z",
    detailUrl: "https://example.com/notices/101"
};

const normalNotice = {
    id: 102,
    externalId: "PRIVACY_PORTAL:BBSMSTR_000000000001:102",
    title: "개인정보 포털 시스템 점검 안내",
    publishedDate: "2026-08-27",
    piaRelated: false,
    matchedKeywords: [],
    firstSeenAt: "2026-09-01T00:10:00Z",
    lastSeenAt: "2026-09-01T01:10:00Z",
    detailUrl: "https://example.com/notices/102"
};

const piaNoticeDetail = {
    ...piaNotice,
    body: "개인정보 영향평가 전문교육 신청 안내입니다.\n\n교육 일정\n- 접수: 9월 1일~9월 10일\n- 교육: 9월 20일\n\n" + "추가 교육 안내와 신청 유의사항입니다. ".repeat(30) + "FULL_BODY_END_SHOULD_NOT_RENDER",
    classificationReason: "제목에서 PIA 관련 핵심 키워드가 발견되었습니다.",
    fingerprint: "a".repeat(64),
    attachments: [{ fileName: "2026년 전문교육 안내.pdf", fileUrl: "https://example.com/files/pia-guide.pdf" }]
};

test.beforeAll(() => {
    fs.rmSync(screenshotDirectory, { recursive: true, force: true });
    fs.mkdirSync(screenshotDirectory, { recursive: true });
});

async function mockApis(page, bids = [bidNotice]) {
    let collectionRequestCount = 0;

    await page.route("**/api/bids/**", (route) => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(bids)
    }));

    await page.route("**/api/external-notices**", async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        if (request.method() === "POST" && url.pathname.endsWith("/collect")) {
            collectionRequestCount += 1;
            await new Promise((resolve) => setTimeout(resolve, 350));
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ collectedCount: 2, newCount: 1, updatedCount: 0, unchangedCount: 1, piaRelatedCount: 1, failedCount: 0 })
            });
            return;
        }
        if (url.pathname.endsWith("/101")) {
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(piaNoticeDetail) });
            return;
        }
        const notices = url.searchParams.get("piaRelated") === "true" ? [piaNotice] : [piaNotice, normalNotice];
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(notices) });
    });

    return () => collectionRequestCount;
}

test("홈과 다섯 업무 메뉴가 Biz Assist 앱 셸에서 연결된다", async ({ page }) => {
    await mockApis(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "안녕하세요." })).toBeVisible();
    await expect(page.locator(".app-nav-link")).toHaveCount(5);
    await expect(page.getByRole("link", { name: "홈", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.locator("#bid-total-count")).toHaveText("1건");
    await expect(page.locator("#bid-check-count")).toHaveText("1건");
    await expect(page.locator("#pia-notice-count")).toHaveText("1건");
    await expect(page.getByRole("link", { name: piaNotice.title })).toBeVisible();
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-home.png"), fullPage: true });

    await page.getByRole("link", { name: "입찰공고", exact: true }).click();
    await expect(page).toHaveURL(/\/bids\/$/);
    await expect(page.getByRole("link", { name: "입찰공고", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.locator("#bid-list")).toContainText(bidNotice.bidNtceNm);
    await page.getByRole("button", { name: "최근 3일" }).click();
    const bidRow = page.locator("#bid-list tr");
    await expect(bidRow).toHaveCount(1);
    await expect(bidRow).toContainText(bidNotice.reviewStatus);
    await expect(bidRow).toContainText("외부확인 필요");
    await expect(bidRow).toContainText("1.67억");
    await expect(bidRow).toContainText("적격심사 확정");
    await expect(bidRow).not.toContainText("vendor.example.com");
    await expect(bidRow).not.toContainText(bidNotice.licenseLimit);
    await expect(bidRow.locator(".bid-cell-clamp").first()).toHaveCSS("-webkit-line-clamp", "2");
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-bids.png"), fullPage: true });
    await bidRow.screenshot({ path: path.join(screenshotDirectory, "biz-assist-bids-additional-check.png") });

    await bidRow.getByRole("button", { name: "상세", exact: true }).click();
    const bidModal = page.getByRole("dialog", { name: "입찰공고 상세" });
    await expect(bidModal).toBeVisible();
    await expect(bidModal).toContainText(bidNotice.licenseLimit);
    await expect(bidModal).toContainText(bidNotice.reviewReason);
    await expect(bidModal).toContainText("vendor.example.com");
    await expect(bidModal).toContainText("166,564,000원");
    await expect(bidModal).toContainText(bidNotice.awardMethodReason);
    await expect(bidModal.getByText("문서분석 보기")).toBeVisible();
    const bidSourceLink = bidModal.getByRole("link", { name: "원문 보기 ↗" });
    await expect(bidSourceLink).toHaveAttribute("href", bidNotice.bidNtceDtlUrl);
    await expect(bidSourceLink).toHaveAttribute("target", "_blank");
    await expect(bidSourceLink).toHaveAttribute("rel", "noopener noreferrer");
    await expect(bidModal.getByRole("link", { name: /나라장터 원문 보기/ })).toHaveCount(0);
    const expandedModalStyle = await page.addStyleTag({ content: [
        "#document-analysis-modal { position: absolute !important; align-items: flex-start !important; min-height: 100vh !important; overflow: visible !important; }",
        ".analysis-modal { max-height: none !important; margin: 24px auto !important; align-self: flex-start !important; }",
        ".analysis-modal-content { max-height: none !important; overflow: visible !important; }"
    ].join("\n") });
    await page.evaluate(() => {
        window.scrollTo(0, 0);
        document.querySelector(".analysis-modal").scrollTop = 0;
        document.querySelector(".analysis-modal-content").scrollTop = 0;
    });
    await page.screenshot({
        path: path.join(screenshotDirectory, "biz-assist-bid-detail.png"),
        fullPage: true
    });
    await expandedModalStyle.evaluate((style) => style.remove());
    await page.keyboard.press("Escape");
    await expect(bidModal).toBeHidden();

    await page.getByRole("link", { name: "홈", exact: true }).click();
    await page.getByRole("link", { name: "외부 중요공지", exact: true }).click();
    await expect(page).toHaveURL(/\/notices\/$/);
    await expect(page.getByRole("link", { name: "외부 중요공지", exact: true })).toHaveAttribute("aria-current", "page");
});

test("원문 URL이 없거나 유효하지 않으면 상세 모달에 깨진 링크를 표시하지 않는다", async ({ page }) => {
    const noUrlBid = {
        ...bidNotice,
        bidNtceNo: "NO-URL-001",
        bidNtceNm: "원문 URL이 없는 감리 용역",
        bidNtceDtlUrl: ""
    };
    const invalidUrlBid = {
        ...bidNotice,
        bidNtceNo: "INVALID-URL-001",
        bidNtceNm: "잘못된 원문 URL을 가진 감리 용역",
        bidNtceDtlUrl: "javascript:alert(1)"
    };
    await mockApis(page, [noUrlBid, invalidUrlBid]);
    await page.goto("/bids/");

    for (const bid of [noUrlBid, invalidUrlBid]) {
        const row = page.locator("#bid-list tr").filter({ hasText: bid.bidNtceNm });
        await row.getByRole("button", { name: "상세", exact: true }).click();
        const modal = page.getByRole("dialog", { name: "입찰공고 상세" });
        await expect(modal.getByRole("link", { name: "원문 보기 ↗" })).toHaveCount(0);
        await expect(modal.getByText("원문 링크를 확인할 수 없습니다.")).toBeVisible();
        await modal.getByRole("button", { name: "입찰공고 상세 닫기" }).click();
    }
});

test("외부공지 필터, 상세, 홈의 직접 연결과 수동 수집이 동작한다", async ({ page }) => {
    const getCollectionRequestCount = await mockApis(page);
    await page.goto("/notices/");

    const cards = page.locator(".notice-item");
    await expect(cards).toHaveCount(1);
    await expect(page.locator("#notice-pia-count")).toHaveText("1건");
    await expect(page.locator("#notice-last-checked")).not.toHaveText("-");
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-notices.png"), fullPage: true });

    await page.getByRole("button", { name: "전체 공지" }).click();
    await expect(cards).toHaveCount(2);
    await page.getByRole("button", { name: "PIA 관련 공지만" }).click();
    await expect(cards).toHaveCount(1);

    await page.getByRole("button", { name: piaNotice.title }).click();
    const modal = page.getByRole("dialog");
    await expect(modal).toBeVisible();
    await expect(modal.locator(".detail-summary")).toHaveCount(0);
    await expect(modal.getByRole("heading", { name: piaNotice.title })).toBeVisible();
    await expect(modal.locator("#modal-publication-meta")).toHaveText(`${piaNotice.publishedDate} · 개인정보 포털`);
    await expect(modal.locator("#modal-status")).toContainText("PIA 관련");
    await expect(modal.locator("#modal-keywords")).toContainText("전문교육");
    const preview = modal.locator(".notice-preview");
    await expect(preview).toContainText("개인정보 영향평가 전문교육 신청 안내입니다.");
    await expect(modal).not.toContainText("FULL_BODY_END_SHOULD_NOT_RENDER");
    const explanation = modal.locator(".classification-details");
    await expect(explanation).not.toHaveAttribute("open", "");
    await expect(explanation.getByText("왜 PIA 관련인가요?")).toBeVisible();
    const attachmentLink = modal.getByRole("link", { name: "2026년 전문교육 안내.pdf" });
    await expect(attachmentLink).toHaveAttribute("target", "_blank");
    await expect(attachmentLink).toHaveAttribute("rel", "noopener noreferrer");
    const sourceLink = modal.getByRole("link", { name: "개인정보 포털에서 전체 내용 보기 ↗" });
    await expect(sourceLink).toHaveAttribute("target", "_blank");
    await expect(sourceLink).toHaveAttribute("rel", "noopener noreferrer");
    expect(await modal.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-notice-detail.png"), fullPage: true });
    await page.keyboard.press("Escape");
    await expect(modal).toBeHidden();

    const collectButton = page.locator("#collect-button");
    await collectButton.click();
    await expect(collectButton).toBeDisabled();
    await page.evaluate(() => document.querySelector("#collect-button").click());
    expect(getCollectionRequestCount()).toBe(1);
    await expect(page.locator("#collection-message")).toContainText("신규 1건");
    await expect(collectButton).toBeEnabled();
    expect(getCollectionRequestCount()).toBe(1);

    await page.goto("/");
    await page.getByRole("link", { name: piaNotice.title }).click();
    await expect(page).toHaveURL(/\/notices\/\?noticeId=101$/);
    await expect(page.getByRole("dialog")).toBeVisible();
});

test("390px 모바일 앱 셸에 가로 넘침이 없다", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockApis(page);
    await page.goto("/");
    await expect(page.locator(".app-nav-link")).toHaveCount(5);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-mobile.png"), fullPage: true });

    await page.getByRole("link", { name: "입찰공고", exact: true }).click();
    const mobileBidRow = page.locator("#bid-list tr");
    await expect(mobileBidRow).toHaveCount(1);
    await expect(mobileBidRow.getByRole("button", { name: "상세", exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-bids-mobile.png"), fullPage: true });

    await page.getByRole("link", { name: "외부 중요공지", exact: true }).click();
    await expect(page.locator(".notice-item")).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);

    await page.getByRole("button", { name: piaNotice.title }).click();
    const modal = page.getByRole("dialog");
    await expect(modal).toBeVisible();
    await expect(modal.getByRole("heading", { name: piaNotice.title })).toBeVisible();
    await expect(modal.locator("#modal-keywords .keyword")).toHaveCount(3);
    const mobilePreview = modal.locator(".notice-preview");
    await expect(mobilePreview).toBeVisible();
    await expect(modal).not.toContainText("FULL_BODY_END_SHOULD_NOT_RENDER");
    await expect(modal.getByRole("link", { name: "개인정보 포털에서 전체 내용 보기 ↗" })).toBeVisible();
    expect(await modal.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    expect(await mobilePreview.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-notice-detail-mobile.png"), fullPage: true });
    await page.keyboard.press("Escape");
    await expect(modal).toBeHidden();
});

async function mockSubscriberApi(page, initialSubscribers = [], duplicateEmail = null) {
    const state = {
        subscribers: initialSubscribers.map((subscriber) => ({ ...subscriber })),
        postBodies: [],
        disabledIds: []
    };

    await page.route("**/api/notification-subscribers**", async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const method = request.method();
        if (method === "GET" && url.pathname === "/api/notification-subscribers") {
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(state.subscribers) });
            return;
        }
        if (method === "POST" && url.pathname === "/api/notification-subscribers") {
            const body = request.postDataJSON();
            state.postBodies.push(body);
            if (body.email === duplicateEmail) {
                await route.fulfill({
                    status: 409,
                    contentType: "application/json",
                    body: JSON.stringify({ status: 409, error: "Conflict", message: "이미 등록된 신청자입니다." })
                });
                return;
            }
            const saved = {
                id: Math.max(0, ...state.subscribers.map((subscriber) => subscriber.id)) + 1,
                ...body,
                enabled: true,
                createdAt: "2026-09-02T07:00:00Z",
                updatedAt: "2026-09-02T07:00:00Z"
            };
            state.subscribers.push(saved);
            await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(saved) });
            return;
        }
        const disableMatch = url.pathname.match(/^\/api\/notification-subscribers\/(\d+)\/disable$/);
        if (method === "PATCH" && disableMatch) {
            const id = Number(disableMatch[1]);
            state.disabledIds.push(id);
            const subscriber = state.subscribers.find((item) => item.id === id);
            subscriber.enabled = false;
            subscriber.updatedAt = "2026-09-02T08:00:00Z";
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(subscriber) });
            return;
        }
        await route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
    });
    return state;
}

const activeSubscriber = {
    id: 1,
    email: "active@example.com",
    name: "활성 사용자",
    notificationType: "PIA_EXTERNAL_NOTICE",
    enabled: true,
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z"
};

const disabledSubscriber = {
    id: 2,
    email: "disabled@example.com",
    name: "비활성 사용자",
    notificationType: "PIA_EXTERNAL_NOTICE",
    enabled: false,
    createdAt: "2026-08-31T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z"
};

test("알림 관리에서 목록 조회, 신규 등록과 비활성화가 동작한다", async ({ page }) => {
    const state = await mockSubscriberApi(page, [activeSubscriber, disabledSubscriber]);
    page.on("dialog", (dialog) => dialog.accept());
    await page.goto("/notifications/");

    await expect(page.getByRole("heading", { name: "알림 신청 관리" })).toBeVisible();
    await expect(page.getByRole("link", { name: "알림 관리", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.locator("#subscriber-total-count")).toHaveText("2");
    await expect(page.locator("#subscriber-enabled-count")).toHaveText("1");
    await expect(page.locator("#subscriber-disabled-count")).toHaveText("1");
    await expect(page.locator("#subscriber-table-body tr")).toHaveCount(2);
    await expect(page.locator("#subscriber-table-body")).toContainText("PIA 중요공지");
    await expect(page.locator("#subscriber-table-body tr").filter({ hasText: "비활성 사용자" }).getByRole("button", { name: "비활성화" })).toHaveCount(0);

    await page.getByRole("button", { name: "+ 신청자 등록" }).click();
    const modal = page.getByRole("dialog", { name: "신청자 등록" });
    await modal.getByLabel("이름").fill("신규 사용자");
    await modal.getByLabel("이메일 필수").fill("new@example.com");
    await modal.getByRole("button", { name: "등록", exact: true }).click();

    await expect(modal).toBeHidden();
    await expect(page.locator("#subscriber-toast")).toHaveText("신청자를 등록했습니다.");
    await expect(page.locator("#subscriber-total-count")).toHaveText("3");
    await expect(page.locator("#subscriber-table-body")).toContainText("new@example.com");
    expect(state.postBodies).toEqual([{
        name: "신규 사용자",
        email: "new@example.com",
        notificationType: "PIA_EXTERNAL_NOTICE"
    }]);

    const activeRow = page.locator('#subscriber-table-body tr[data-subscriber-id="1"]');
    await activeRow.getByRole("button", { name: "비활성화" }).click();
    await expect(page.locator("#subscriber-toast")).toHaveText("신청자를 비활성화했습니다.");
    await expect(activeRow).toContainText("비활성");
    await expect(activeRow.getByRole("button", { name: "비활성화" })).toHaveCount(0);
    expect(state.disabledIds).toEqual([1]);
});

test("알림 신청 중복 등록은 409 안내를 표시한다", async ({ page }) => {
    await mockSubscriberApi(page, [activeSubscriber], "active@example.com");
    await page.goto("/notifications/");
    await page.getByRole("button", { name: "+ 신청자 등록" }).click();
    const modal = page.getByRole("dialog", { name: "신청자 등록" });
    await modal.getByLabel("이메일 필수").fill("active@example.com");
    await modal.getByRole("button", { name: "등록", exact: true }).click();

    await expect(modal).toBeVisible();
    await expect(modal.getByRole("alert")).toHaveText("이미 등록된 이메일입니다.");
});

test("알림 신청자가 없으면 빈 상태를 표시한다", async ({ page }) => {
    await mockSubscriberApi(page, []);
    await page.goto("/notifications/");

    await expect(page.getByText("등록된 알림 신청자가 없습니다.")).toBeVisible();
    await expect(page.getByText("신청자를 등록하면 PIA 중요공지 발생 시 이메일 알림을 받을 수 있습니다.")).toBeVisible();
    await expect(page.locator("#subscriber-table-wrap")).toBeHidden();
});

test("알림 관리 모바일 화면은 신청자를 카드 목록으로 표시한다", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockSubscriberApi(page, [activeSubscriber, disabledSubscriber]);
    await page.goto("/notifications/");

    await expect(page.locator(".app-nav-link")).toHaveCount(5);
    await expect(page.locator("#subscriber-table-wrap")).toBeHidden();
    await expect(page.locator(".subscriber-card")).toHaveCount(2);
    await expect(page.locator(".subscriber-card").first()).toContainText("active@example.com");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});

const submissionCase = {
    id: 71, projectId: null, projectPublicId: null, projectCode: null, internalBizNo: null,
    projectName: "직접 선택 제출서류", bidNoticeNo: null, status: "DRAFT",
    createdAt: "2026-09-03T01:00:00Z", updatedAt: "2026-09-03T01:00:00Z"
};

const submissionRequirements = [
    { id: 81, submissionCaseId: 71, category: "COMPANY_GENERAL", documentName: "사업자등록증", required: true, evidenceText: "사용자가 직접 선택한 제출서류", sourceType: "USER_SELECTED", sourceReference: "BUSINESS_REGISTRATION", performanceSelectionRequired: false },
    { id: 82, submissionCaseId: 71, category: "PERFORMANCE", documentName: "실적증명서", required: true, evidenceText: "사용자가 직접 선택한 제출서류", sourceType: "USER_SELECTED", sourceReference: "PERFORMANCE", performanceSelectionRequired: true }
];

const submissionCandidates = {
    81: [{
        fileId: 901, publicId: "10c7f0ba-caad-444c-9b33-5db57a6477d2",
        originalFilename: "사업자등록증_최신.pdf", fileExt: "pdf",
        fileModifiedAt: "2026-09-02T03:00:00Z", updatedAt: "2026-09-02T03:00:00Z",
        matchLevel: "EXACT", matchReasons: ["파일명에 요구서류명이 포함됨"]
    }, {
        fileId: 902, publicId: "338fdf7b-8b83-48b7-ab9b-4565fa2ccf9a",
        originalFilename: "회사 일반서류.pdf", fileExt: "pdf",
        fileModifiedAt: "2026-08-01T03:00:00Z", updatedAt: "2026-08-01T03:00:00Z",
        matchLevel: "RECOMMENDED", matchReasons: ["문서 유형 키워드 일치"]
    }],
    82: []
};

async function mockSubmissionApi(page, options = {}) {
    const apiState = { selections: [], putBodies: [], postBodies: [], candidateRequests: [] };
    await page.route("**/api/submission-cases**", async (route) => {
        const request = route.request();
        const pathname = new URL(request.url()).pathname;
        const method = request.method();
        if (method === "POST" && pathname === "/api/submission-cases") {
            apiState.postBodies.push(request.postDataJSON());
            await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(submissionCase) });
            return;
        }
        if (method === "GET" && pathname === "/api/submission-cases/71") {
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(submissionCase) });
            return;
        }
        if (method === "GET" && pathname === "/api/submission-cases/71/requirements") {
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(submissionRequirements) });
            return;
        }
        const candidateMatch = pathname.match(/^\/api\/submission-cases\/71\/requirements\/(\d+)\/candidates$/);
        if (method === "GET" && candidateMatch) {
            apiState.candidateRequests.push(Number(candidateMatch[1]));
            await route.fulfill(options.unavailable
                ? { status: 503, contentType: "application/json", body: JSON.stringify({ message: "unavailable" }) }
                : { status: 200, contentType: "application/json", body: JSON.stringify(options.empty ? [] : submissionCandidates[Number(candidateMatch[1])] || []) });
            return;
        }
        if (method === "PUT" && pathname === "/api/submission-cases/71/selections") {
            const body = request.postDataJSON();
            apiState.putBodies.push(body);
            apiState.selections = body.selections.map((choice, index) => ({
                id: index + 1,
                submissionCaseId: 71,
                requirementId: choice.requirementId,
                ...(submissionCandidates[choice.requirementId] || []).find((item) => item.fileId === choice.fileId),
                selectedAt: "2026-09-03T04:00:00Z"
            }));
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiState.selections) });
            return;
        }
        if (method === "GET" && pathname === "/api/submission-cases/71/package") {
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ submissionCase, requirements: submissionRequirements, selections: apiState.selections }) });
            return;
        }
        await route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
    });
    return apiState;
}

test("서류 체크 후 후보 선택과 패키지 진행률을 확인한다", async ({ page }) => {
    const apiState = await mockSubmissionApi(page);
    await page.goto("/submissions/");

    await expect(page.getByRole("link", { name: "서류 모으기", exact: true })).toHaveAttribute("aria-current", "page");
    await page.getByLabel("사업자등록증", { exact: true }).check();
    await page.getByLabel("실적증명서", { exact: true }).check();
    await expect(page.locator("#checked-document-count")).toHaveText("2");
    await page.getByRole("button", { name: "선택한 서류 찾기" }).click();
    await expect(page.getByRole("heading", { name: "선택한 제출서류" })).toBeVisible();
    await expect(page.locator("#requirement-list .requirement-item")).toHaveCount(2);
    expect(apiState.postBodies).toEqual([{ requirements: [
        { category: "COMPANY_GENERAL", documentName: "사업자등록증", sourceReference: "BUSINESS_REGISTRATION" },
        { category: "PERFORMANCE", documentName: "실적증명서", sourceReference: "PERFORMANCE" }
    ] }]);
    await expect(page.locator(".requirement-item").filter({ hasText: "사업자등록증" })).toContainText("2개 후보");
    expect(apiState.candidateRequests).toEqual([81]);
    await expect(page.locator("#progress-percent")).toHaveText("0%");

    await page.locator(".requirement-item").filter({ hasText: "사업자등록증" }).click();
    await expect(page.locator(".candidate-item")).toHaveCount(2);
    await expect(page.locator(".candidate-item").first()).toContainText("정확히 일치");
    await expect(page.locator(".candidate-item").nth(1)).toContainText("추천");
    await page.locator(".candidate-item").first().getByRole("button", { name: "이 파일 선택" }).click();
    await expect(page.locator("#progress-percent")).toHaveText("50%");
    await expect(page.locator("#package-selection-count")).toHaveText("1 / 2");
    await expect(page.locator("#package-list")).toContainText("사업자등록증_최신.pdf");
    await expect(page.locator(".requirement-item").filter({ hasText: "사업자등록증" })).toContainText("선택 완료");
    expect(apiState.putBodies).toEqual([{ selections: [{ requirementId: 81, fileId: 901 }] }]);

    await page.locator(".requirement-item").filter({ hasText: "실적증명서" }).click();
    await expect(page.getByText("실적 선택이 필요합니다.", { exact: true })).toBeVisible();
    await expect(page.locator("body")).not.toContainText("storage_path");
});

test("서류 모으기는 회사 DB 503을 사용자 안내로 표시한다", async ({ page }) => {
    await mockSubmissionApi(page, { unavailable: true });
    await page.goto("/submissions/");
    await page.getByLabel("사업자등록증", { exact: true }).check();
    await page.getByRole("button", { name: "선택한 서류 찾기" }).click();
    const requirement = page.locator(".requirement-item").filter({ hasText: "사업자등록증" });
    await expect(requirement).toContainText("조회 실패");
    await requirement.click();
    await expect(page.locator("#candidate-error")).toContainText("회사 DB에 연결할 수 없습니다");
});

test("서류 후보가 없으면 요구서류와 상세 영역에 빈 상태를 표시한다", async ({ page }) => {
    await mockSubmissionApi(page, { empty: true });
    await page.goto("/submissions/");
    await page.getByLabel("사업자등록증", { exact: true }).check();
    await page.getByRole("button", { name: "선택한 서류 찾기" }).click();
    const requirement = page.locator(".requirement-item").filter({ hasText: "사업자등록증" });
    await expect(requirement).toContainText("후보 없음");
    await requirement.click();
    await expect(page.locator("#candidate-empty")).toContainText("후보 없음");
});

test("사용자 정의 서류를 추가하고 중복 입력을 막는다", async ({ page }) => {
    await mockSubmissionApi(page);
    await page.goto("/submissions/");
    await page.getByPlaceholder("직접 입력할 서류명").fill("보안서약서");
    await page.getByRole("button", { name: "추가", exact: true }).click();
    await expect(page.locator("#custom-document-list")).toContainText("보안서약서");
    await expect(page.locator("#checked-document-count")).toHaveText("1");
    await page.getByPlaceholder("직접 입력할 서류명").fill("보안서약서");
    await page.getByRole("button", { name: "추가", exact: true }).click();
    await expect(page.locator("#document-message")).toContainText("이미 선택하거나 추가한 서류");
});

test("서류 모으기 모바일 화면은 카드 흐름과 진행률을 유지한다", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockSubmissionApi(page);
    await page.goto("/submissions/");

    await expect(page.locator(".app-nav-link")).toHaveCount(5);
    await page.getByLabel("사업자등록증", { exact: true }).check();
    await page.getByLabel("실적증명서", { exact: true }).check();
    await page.getByRole("button", { name: "선택한 서류 찾기" }).click();
    await expect(page.locator("#requirement-list .requirement-item")).toHaveCount(2);
    await expect(page.locator("#progress-percent")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});
