'use strict';
window.SubmissionPersonnel = (() => {
    const $ = selector => document.querySelector(selector);
    const node = (tag, text, css) => { const item = document.createElement(tag); if (text != null) item.textContent = text; if (css) item.className = css; return item; };
    let caseId = null, revision = 0, people = [], busy = false, active = null, searchVersion = 0, fileVersion = 0, loaded = false;
    const searchDialog = $('#personnel-search-dialog'), fileDialog = $('#personnel-file-dialog');
    const base = () => `/api/submission-cases/${encodeURIComponent(caseId)}/people`;
    async function api(url, options = {}) {
        const response = await fetch(url, { ...options, headers: options.body instanceof FormData ? { Accept: 'application/json' } : { 'Content-Type': 'application/json', Accept: 'application/json' } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(response.status === 413 ? '20MB 이하 파일을 선택하세요.' : body.message || '인력 서류를 처리하지 못했습니다.');
        return body;
    }
    function notify() { document.dispatchEvent(new Event('personnel-change')); }
    function stats() {
        const documents = people.flatMap(person => person.documents).filter(doc => doc.needed);
        return { total: documents.length, prepared: documents.filter(doc => doc.filename).length, loaded };
    }
    function button(text, action) {
        const item = node('button', text, 'ui-button ui-button-secondary'); item.type = 'button'; item.disabled = busy; item.onclick = action; return item;
    }
    function render() {
        const list = $('#personnel-list');
        const expanded = new Set([...list.querySelectorAll('details[open]')].map(item => item.dataset.id));
        list.replaceChildren();
        if (!people.length && loaded) list.append(node('p', '인력을 추가하고 필요한 서류를 체크하세요.', 'personnel-empty'));
        for (const person of people) {
            const details = node('details', null, 'personnel-person'); details.dataset.id = person.id; details.open = expanded.has(person.id);
            const head = node('summary');
            const title = node('span', person.name, 'personnel-name');
            title.append(node('small', person.department || '부서 미등록'));
            const needed = person.documents.filter(doc => doc.needed);
            head.append(title, node('span', `${needed.filter(doc => doc.filename).length} / ${needed.length}`, 'requirement-state'));
            details.append(head);
            const controls = node('div', null, 'personnel-person-actions');
            controls.append(button('프로젝트에서 제외', () => mutate(`/${encodeURIComponent(person.id)}`, { method: 'DELETE' })));
            details.append(controls);
            for (const doc of person.documents) {
                const row = node('div', null, 'personnel-document');
                const label = node('label'); const check = node('input'); check.type = 'checkbox'; check.checked = doc.needed; check.disabled = busy;
                check.onchange = () => mutate(`/${encodeURIComponent(person.id)}/documents/${doc.type}`, { method: 'PUT', body: JSON.stringify({ needed: check.checked }) });
                label.append(check, node('span', doc.label)); row.append(label);
                if (doc.needed) {
                    const file = node('div', null, 'personnel-document-file');
                    file.append(node('span', doc.filename || '파일 미등록', 'personnel-filename'),
                        node('small', doc.filename ? `${doc.source === 'PC' ? '직접 업로드 · ' : ''}${doc.filenameDate || '날짜 없음'}` : '', 'personnel-help'),
                        node('span', doc.latestStatus, 'requirement-state' + (doc.latestStatus === '파일명 날짜 기준 최신' ? ' complete' : !doc.filename || doc.latestStatus === '더 최신 후보 있음' ? ' attention' : '')),
                        button(doc.filename ? '파일 변경' : '파일 선택', () => openFile(person, doc)));
                    row.append(file);
                }
                details.append(row);
            }
            list.append(details);
        }
        $('#personnel-add').disabled = busy || !loaded;
    }
    async function mutate(suffix, options, message = $('#personnel-message'), close = null) {
        if (busy || caseId == null) return;
        const current = revision; busy = true; message.textContent = '';
        render(); setDialogBusy(true);
        try {
            const result = await api(base() + suffix, options);
            if (current !== revision) return;
            if (!Array.isArray(result)) throw new Error('인력 목록을 확인할 수 없습니다.');
            people = result; loaded = true; close?.close(); notify();
        } catch (error) { if (current === revision) message.textContent = error.message; }
        finally { if (current === revision) { busy = false; render(); setDialogBusy(false); } }
    }
    function setDialogBusy(value) {
        for (const dialog of [searchDialog, fileDialog]) {
            dialog.querySelectorAll('button,input').forEach(input => { input.disabled = value; });
        }
        if (!value) $('#personnel-file-save').disabled = !fileDialog.querySelector('input[name="personnel-candidate"]:checked');
    }
    async function load(id) {
        const current = ++revision; ++searchVersion; ++fileVersion;
        caseId = id; people = []; busy = false; active = null; loaded = false;
        searchDialog.close(); fileDialog.close(); setDialogBusy(false);
        $('#personnel-message').textContent = id == null ? '' : '인력 목록 확인 중…'; render();
        if (id == null) return;
        try {
            const result = await api(base());
            if (current !== revision) return;
            if (!Array.isArray(result)) throw new Error('인력 목록을 확인할 수 없습니다.');
            people = result; loaded = true; $('#personnel-message').textContent = '';
        } catch (error) {
            if (current !== revision) return;
            $('#personnel-message').textContent = error.message;
            const retry = button('다시 조회', () => load(id)); $('#personnel-message').append(' ', retry);
        }
        if (current === revision) { render(); notify(); }
    }
    $('#personnel-add').onclick = () => {
        $('#personnel-search-results').replaceChildren(); $('#personnel-search-message').textContent = '';
        $('#personnel-search-form').reset();
        setDialogBusy(false); searchDialog.showModal(); $('#personnel-search').focus();
    };
    document.querySelectorAll('[data-personnel-close]').forEach(item => { item.onclick = () => item.closest('dialog').close(); });
    for (const dialog of [searchDialog, fileDialog]) dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    searchDialog.addEventListener('close', () => ++searchVersion);
    fileDialog.addEventListener('close', () => { ++fileVersion; active = null; });
    $('#personnel-search-form').onsubmit = async event => {
        event.preventDefault(); const version = ++searchVersion, current = revision;
        const term = $('#personnel-search').value.trim();
        const results = $('#personnel-search-results'), message = $('#personnel-search-message');
        results.replaceChildren(); message.textContent = '검색 중…';
        try {
            const options = await api(`${base()}/search?q=${encodeURIComponent(term)}`);
            if (version !== searchVersion || current !== revision || !searchDialog.open) return;
            message.textContent = options.length ? '' : '인력 파일 인덱스에 일치하는 이름이 없습니다. 이름과 인덱스를 확인하세요.';
            for (const option of options) {
                const row = node('div', null, 'personnel-search-result');
                const text = node('span', option.name); text.append(node('small', option.department || '부서 미등록'));
                row.append(text, button('선택', () => mutate('', { method: 'POST', body: JSON.stringify({ name: option.name, department: option.department }) }, message, searchDialog))); results.append(row);
            }
        } catch (error) { if (version === searchVersion && current === revision) message.textContent = error.message; }
    };
    async function openFile(person, doc) {
        const current = revision, version = ++fileVersion;
        active = { person, doc, suffix: `/${encodeURIComponent(person.id)}/documents/${doc.type}` };
        $('#personnel-file-title').textContent = `${doc.label} · ${person.name}`;
        $('#personnel-file-candidates').replaceChildren(); $('#personnel-upload-form').reset();
        $('#personnel-file-message').textContent = 'FMS 후보 검색 중…'; setDialogBusy(false); fileDialog.showModal();
        try {
            const candidates = await api(base() + active.suffix + '/candidates');
            if (current !== revision || version !== fileVersion || !fileDialog.open) return;
            $('#personnel-file-message').textContent = candidates.length ? '파일 내용을 확인한 뒤 직접 선택하세요.' : '후보가 없습니다. PC에서 직접 업로드하세요.';
            for (const candidate of candidates) {
                const label = node('label', null, 'personnel-candidate'); const radio = node('input');
                radio.type = 'radio'; radio.name = 'personnel-candidate'; radio.value = candidate.id;
                radio.onchange = () => { $('#personnel-file-save').disabled = busy; };
                const text = node('span', candidate.filename); text.append(node('small', `${candidate.filenameDate || '파일명 날짜 없음'} · ${candidate.note}`));
                label.append(radio, text);
                if (candidate.recommended) label.append(node('span', '최신 후보', 'requirement-state complete'));
                $('#personnel-file-candidates').append(label);
            }
        } catch (error) { if (current === revision && version === fileVersion) $('#personnel-file-message').textContent = error.message; }
    }
    $('#personnel-file-save').onclick = () => {
        const selected = fileDialog.querySelector('input[name="personnel-candidate"]:checked');
        if (active && selected) mutate(active.suffix + '/selection', { method: 'PUT', body: JSON.stringify({ candidateId: selected.value }) }, $('#personnel-file-message'), fileDialog);
    };
    $('#personnel-file-clear').onclick = () => {
        if (active) mutate(active.suffix + '/selection', { method: 'PUT', body: JSON.stringify({ candidateId: null }) }, $('#personnel-file-message'), fileDialog);
    };
    $('#personnel-upload-form').onsubmit = event => {
        event.preventDefault(); const file = event.currentTarget.elements.file.files[0];
        if (!file || file.size === 0 || file.size > 20 * 1024 * 1024) { $('#personnel-file-message').textContent = '비어 있지 않은 20MB 이하 파일을 선택하세요.'; return; }
        if (active) mutate(active.suffix + '/upload', { method: 'POST', body: new FormData(event.currentTarget) }, $('#personnel-file-message'), fileDialog);
    };
    return { load, stats };
})();
