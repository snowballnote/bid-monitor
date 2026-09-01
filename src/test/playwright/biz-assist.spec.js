const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");

const screenshotDirectory = path.resolve("artifacts", "screenshots");

const bidNotice = {
    bidNtceNo: "20260901001",
    bidNtceNm: "정보시스템 감리 용역",
    ntceInsttNm: "테스트기관",
    asignBdgtAmt: "120000000",
    bidClseDt: "2026-09-10 10:00",
    sucsfbidMthdNm: "적격심사",
    licenseLimit: "정보시스템 감리",
    participationRegion: "제한없음",
    reviewStatus: "추가확인필요",
    reviewReason: "추가 확인이 필요한 테스트 공고",
    externalCheckStatus: "NOT_DETECTED",
    externalCheckReason: "외부참조 미탐지",
    externalSiteUrls: [],
    attachments: [],
    bidNtceDtlUrl: "https://example.com/bids/20260901001"
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

async function mockApis(page) {
    let collectionRequestCount = 0;

    await page.route("**/api/bids/**", (route) => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([bidNotice])
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

test("홈과 세 업무 메뉴가 Biz Assist 앱 셸에서 연결된다", async ({ page }) => {
    await mockApis(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "안녕하세요." })).toBeVisible();
    await expect(page.locator(".app-nav-link")).toHaveCount(3);
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
    await expect(page.locator("#bid-list")).toContainText(bidNotice.reviewStatus);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-bids.png"), fullPage: true });

    await page.getByRole("link", { name: "홈", exact: true }).click();
    await page.getByRole("link", { name: "외부 중요공지", exact: true }).click();
    await expect(page).toHaveURL(/\/notices\/$/);
    await expect(page.getByRole("link", { name: "외부 중요공지", exact: true })).toHaveAttribute("aria-current", "page");
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
    await expect(page.locator(".app-nav-link")).toHaveCount(3);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(screenshotDirectory, "biz-assist-mobile.png"), fullPage: true });

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
