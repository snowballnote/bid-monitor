const loadingMessage = document.getElementById("loading-message");
const emptyMessage = document.getElementById("empty-message");
const errorMessage = document.getElementById("error-message");
const tableWrapper = document.getElementById("table-wrapper");
const bidList = document.getElementById("bid-list");

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

/**
 * 페이지 진입 시 오늘의 대상 공고와 자동 판정 결과를 조회해 화면에 표시한다.
 */
async function loadTargetBidQualifications() {
    try {
        const response = await fetch("/api/bids/target/qualification");
        if (!response.ok) {
            throw new Error(`공고 조회 실패: ${response.status}`);
        }

        const bids = await response.json();
        loadingMessage.classList.add("hidden");

        if (!Array.isArray(bids) || bids.length === 0) {
            emptyMessage.classList.remove("hidden");
            return;
        }

        const fragment = document.createDocumentFragment();
        bids.forEach((bid) => fragment.appendChild(createBidRow(bid)));
        bidList.replaceChildren(fragment);
        tableWrapper.classList.remove("hidden");
    } catch (error) {
        console.error(error);
        loadingMessage.classList.add("hidden");
        errorMessage.classList.remove("hidden");
    }
}

loadTargetBidQualifications();
