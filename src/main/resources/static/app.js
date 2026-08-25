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

const LICENSE_STORAGE_KEY = "bidAllowedLicenseCodes";
const DEFAULT_LICENSE_CODES = ["6146", "1468"];
let allowedLicenseCodes = [];

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

/** 공통 오류 처리를 유지하면서 전달받은 API에서 공고를 조회한다. */
async function fetchBids(url, startDate, endDate) {
    setSelectedPeriod(startDate, endDate);
    prepareForLoading();
    validationMessage.classList.add("hidden");

    try {
        const response = await fetch(url);
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

// 화면을 열 때 저장값을 불러오고 현재 허용 코드를 태그로 표시한다.
loadAllowedLicenseCodes();
renderAllowedLicenseCodes();

// 최초 접속은 기존 API로 오늘 공고를 조회한다.
const today = formatDate(new Date());
fetchBids("/api/bids/target/qualification", today, today);
