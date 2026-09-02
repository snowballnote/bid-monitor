const API_URL = "/api/notification-subscribers";

const elements = {
    totalCount: document.querySelector("#subscriber-total-count"),
    enabledCount: document.querySelector("#subscriber-enabled-count"),
    disabledCount: document.querySelector("#subscriber-disabled-count"),
    loading: document.querySelector("#subscriber-loading"),
    empty: document.querySelector("#subscriber-empty"),
    error: document.querySelector("#subscriber-error"),
    tableWrap: document.querySelector("#subscriber-table-wrap"),
    tableBody: document.querySelector("#subscriber-table-body"),
    cardList: document.querySelector("#subscriber-card-list"),
    toast: document.querySelector("#subscriber-toast"),
    modal: document.querySelector("#subscriber-modal"),
    openModal: document.querySelector("#open-subscriber-modal"),
    closeModal: document.querySelector("#close-subscriber-modal"),
    cancelModal: document.querySelector("#cancel-subscriber-modal"),
    form: document.querySelector("#subscriber-form"),
    name: document.querySelector("#subscriber-name"),
    email: document.querySelector("#subscriber-email"),
    notificationType: document.querySelector("#subscriber-notification-type"),
    formError: document.querySelector("#subscriber-form-error"),
    submit: document.querySelector("#submit-subscriber")
};

class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

async function requestJson(url, options = {}) {
    const response = await fetch(url, options);
    let body = null;
    try {
        body = await response.json();
    } catch (ignored) {
        body = null;
    }
    if (!response.ok) {
        throw new ApiError(response.status, body?.message || "요청을 처리하지 못했습니다.");
    }
    return body;
}

function setHidden(element, hidden) {
    element.classList.toggle("hidden", hidden);
}

function notificationTypeLabel(type) {
    return type === "PIA_EXTERNAL_NOTICE" ? "PIA 중요공지" : type;
}

function formatDate(value) {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "-";
    return new Intl.DateTimeFormat("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
    }).format(date);
}

function statusBadge(enabled) {
    const badge = document.createElement("span");
    badge.className = `subscriber-status ${enabled ? "enabled" : "disabled"}`;
    badge.textContent = enabled ? "활성" : "비활성";
    return badge;
}

function disableButton(subscriber) {
    if (!subscriber.enabled) return null;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ui-button ui-button-secondary subscriber-disable-button";
    button.textContent = "비활성화";
    button.addEventListener("click", () => disableSubscriber(subscriber));
    return button;
}

function appendCell(row, value) {
    const cell = document.createElement("td");
    if (value instanceof Node) cell.append(value);
    else cell.textContent = value;
    row.append(cell);
}

function renderTable(subscribers) {
    elements.tableBody.replaceChildren();
    subscribers.forEach((subscriber) => {
        const row = document.createElement("tr");
        row.dataset.subscriberId = String(subscriber.id);
        appendCell(row, subscriber.name || "-");
        appendCell(row, subscriber.email);
        appendCell(row, notificationTypeLabel(subscriber.notificationType));
        appendCell(row, statusBadge(subscriber.enabled));
        appendCell(row, formatDate(subscriber.createdAt));
        const button = disableButton(subscriber);
        appendCell(row, button || "-");
        elements.tableBody.append(row);
    });
}

function metaItem(list, label, value) {
    const term = document.createElement("dt");
    term.textContent = label;
    const detail = document.createElement("dd");
    if (value instanceof Node) detail.append(value);
    else detail.textContent = value;
    list.append(term, detail);
}

function renderCards(subscribers) {
    elements.cardList.replaceChildren();
    subscribers.forEach((subscriber) => {
        const card = document.createElement("article");
        card.className = "subscriber-card";
        card.dataset.subscriberId = String(subscriber.id);

        const head = document.createElement("div");
        head.className = "subscriber-card-head";
        const identity = document.createElement("div");
        const name = document.createElement("h3");
        name.className = "subscriber-card-name";
        name.textContent = subscriber.name || "이름 없음";
        const email = document.createElement("p");
        email.className = "subscriber-card-email";
        email.textContent = subscriber.email;
        identity.append(name, email);
        head.append(identity, statusBadge(subscriber.enabled));

        const meta = document.createElement("dl");
        meta.className = "subscriber-card-meta";
        metaItem(meta, "알림 유형", notificationTypeLabel(subscriber.notificationType));
        metaItem(meta, "등록일", formatDate(subscriber.createdAt));
        card.append(head, meta);

        const button = disableButton(subscriber);
        if (button) {
            const actions = document.createElement("div");
            actions.className = "subscriber-card-actions";
            actions.append(button);
            card.append(actions);
        }
        elements.cardList.append(card);
    });
}

function renderSubscribers(subscribers) {
    const enabledCount = subscribers.filter((subscriber) => subscriber.enabled).length;
    elements.totalCount.textContent = String(subscribers.length);
    elements.enabledCount.textContent = String(enabledCount);
    elements.disabledCount.textContent = String(subscribers.length - enabledCount);
    renderTable(subscribers);
    renderCards(subscribers);
    const empty = subscribers.length === 0;
    setHidden(elements.empty, !empty);
    setHidden(elements.tableWrap, empty);
    setHidden(elements.cardList, empty);
}

async function loadSubscribers() {
    setHidden(elements.loading, false);
    setHidden(elements.error, true);
    setHidden(elements.empty, true);
    setHidden(elements.tableWrap, true);
    setHidden(elements.cardList, true);
    try {
        const subscribers = await requestJson(API_URL);
        if (!Array.isArray(subscribers)) throw new Error("신청자 응답 형식이 올바르지 않습니다.");
        renderSubscribers(subscribers);
    } catch (error) {
        elements.error.textContent = "신청자 목록을 불러오지 못했습니다.";
        setHidden(elements.error, false);
    } finally {
        setHidden(elements.loading, true);
    }
}

let toastTimer;
function showToast(message, error = false) {
    window.clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", error);
    setHidden(elements.toast, false);
    toastTimer = window.setTimeout(() => setHidden(elements.toast, true), 4000);
}

function openModal() {
    elements.form.reset();
    elements.notificationType.value = "PIA_EXTERNAL_NOTICE";
    elements.email.removeAttribute("aria-invalid");
    setHidden(elements.formError, true);
    setHidden(elements.modal, false);
    elements.modal.setAttribute("aria-hidden", "false");
    document.body.classList.add("modal-open");
    elements.name.focus();
}

function closeModal() {
    setHidden(elements.modal, true);
    elements.modal.setAttribute("aria-hidden", "true");
    document.body.classList.remove("modal-open");
    elements.openModal.focus();
}

function registrationErrorMessage(error) {
    if (error instanceof ApiError && error.status === 409) return "이미 등록된 이메일입니다.";
    if (error instanceof ApiError && error.status === 400) return error.message || "입력값을 확인해주세요.";
    return "신청자를 등록하지 못했습니다. 잠시 후 다시 시도해주세요.";
}

async function submitSubscriber(event) {
    event.preventDefault();
    setHidden(elements.formError, true);
    elements.email.removeAttribute("aria-invalid");
    if (!elements.email.value.trim() || !elements.email.validity.valid) {
        elements.email.setAttribute("aria-invalid", "true");
        elements.formError.textContent = "올바른 이메일 주소를 입력해주세요.";
        setHidden(elements.formError, false);
        elements.email.focus();
        return;
    }

    elements.submit.disabled = true;
    try {
        await requestJson(API_URL, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                name: elements.name.value.trim(),
                email: elements.email.value.trim(),
                notificationType: elements.notificationType.value
            })
        });
        closeModal();
        showToast("신청자를 등록했습니다.");
        await loadSubscribers();
    } catch (error) {
        elements.formError.textContent = registrationErrorMessage(error);
        setHidden(elements.formError, false);
    } finally {
        elements.submit.disabled = false;
    }
}

async function disableSubscriber(subscriber) {
    const targetName = subscriber.name || subscriber.email;
    if (!window.confirm(`${targetName} 신청자를 비활성화하시겠습니까?`)) return;
    try {
        await requestJson(`${API_URL}/${encodeURIComponent(subscriber.id)}/disable`, { method: "PATCH" });
        showToast("신청자를 비활성화했습니다.");
        await loadSubscribers();
    } catch (error) {
        showToast("신청자를 비활성화하지 못했습니다. 잠시 후 다시 시도해주세요.", true);
    }
}

elements.openModal.addEventListener("click", openModal);
elements.closeModal.addEventListener("click", closeModal);
elements.cancelModal.addEventListener("click", closeModal);
elements.form.addEventListener("submit", submitSubscriber);
elements.modal.addEventListener("click", (event) => {
    if (event.target === elements.modal) closeModal();
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !elements.modal.classList.contains("hidden")) closeModal();
});

loadSubscribers();
