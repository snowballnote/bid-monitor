'use strict';
const apiBase = '/api/performance-projects';
const $ = selector => document.querySelector(selector);
const statuses = { DRAFT: '작성중', COLLECTING: '수집중', READY: '선택 완료' };
let projectId = null;
let clipboardHtml = '';
const entriesById = new Map();
const candidateSearches = new WeakMap();
let candidateQueue = Promise.resolve();
const initialParams = new URLSearchParams(location.search);
const submissionCaseId = /^\d+$/.test(initialParams.get('caseId') || '') ? initialParams.get('caseId') : null;
function projectUrl(id) {
    const params = new URLSearchParams();
    if (id) params.set('project', id);
    if (submissionCaseId) params.set('caseId', submissionCaseId);
    return '/performances/index.html' + (params.size ? '?' + params : '');
}
function updateReturnLink() {
    const params = new URLSearchParams();
    if (submissionCaseId) {
        params.set('caseId', submissionCaseId);
        if (projectId) params.set('performanceProjectId', projectId);
    }
    $('#return-submissions').href = '/submissions/' + (params.size ? '?' + params : '');
}
function updateEntrySummary() {
    const entries = [...entriesById.values()];
    const processed = entries.filter(entry => (entry.info.selectedDriveFileId != null || entry.info.selectedFileId != null)).length;
    $('#entry-summary').textContent = entries.length
        ? entries.length + '건 중 ' + processed + '건 처리 / ' + (entries.length - processed) + '건 미처리'
        : '등록된 실적 없음';
    $('#entries-empty').hidden = entries.length !== 0;
    $('#entries-table-wrap').hidden = entries.length === 0;
    $('#download').disabled = processed === 0;
}
function showCreateProject(visible) {
    $('#create-project-panel').hidden = !visible;
    $('#new-project').setAttribute('aria-expanded', String(visible));
    if (visible) $('#create-project').elements.name.focus();
}
function showProjectList() {
    projectId = null;
    $('#project-list').hidden = false;
    $('#workspace').hidden = true;
    $('#project-settings').open = false;
    showCreateProject(false);
    history.replaceState(null, '', projectUrl(null));
    updateReturnLink();
    loadProjects().catch(error => { $('#message').textContent = error.message; });
}
const el = (tag, text) => { const node = document.createElement(tag); if (tag === 'button') node.className = 'ui-button ui-button-secondary'; if (text != null) node.textContent = text; return node; };

async function api(path = '', options = {}) {
    const response = await fetch(apiBase + path, {
        ...options, headers: { 'Content-Type': 'application/json', ...options.headers }
    });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || '요청을 완료하지 못했습니다.');
    }
    return response.json();
}
async function action(button, work) {
    if (button.disabled) return;
    button.disabled = true;
    $('#message').textContent = '';
    try { await work(); }
    catch (error) { $('#message').textContent = error.message; }
    finally { button.disabled = false; }
}
function field(form, name, title, value, type = 'text', choices = null, required = false, maxLength = null) {
    const label = el('label', title);
    const input = el(choices ? 'select' : 'input');
    input.name = name;
    if (choices) for (const [key, text] of choices) { const option = el('option', text); option.value = key; input.append(option); }
    else input.type = type;
    input.value = value ?? '';
    input.required = required;
    if (maxLength) input.maxLength = maxLength;
    label.append(input); form.append(label);
    return input;
}
async function loadProjects() {
    const projects = await api();
    $('#projects').replaceChildren();
    $('#projects-empty').hidden = projects.length !== 0;
    updateEntrySummary();
    for (const project of projects) {
        const row = el('tr');
        const name = el('td');
        const link = el('a', project.name); link.href = projectUrl(project.id);
        name.append(link);
        row.append(name, el('td', project.daysRemaining === 0 ? 'D-day' : project.daysRemaining > 0
            ? 'D-' + project.daysRemaining : 'D+' + -project.daysRemaining), el('td', statuses[project.status]));
        $('#projects').append(row);
    }
}
async function openProject(id) {
    const [project, entries] = await Promise.all([api('/' + encodeURIComponent(id)), api('/' + encodeURIComponent(id) + '/entries')]);
    projectId = id;
    history.replaceState(null, '', projectUrl(id));
    $('#project-list').hidden = true;
    showCreateProject(false);
    $('#project-settings').open = false;
    entriesById.clear();
    entries.forEach(entry => entriesById.set(entry.id, entry));
    updateEntrySummary();
    updateReturnLink();
    $('#project-deadline').textContent = '마감일 ' + project.deadline;
    if (submissionCaseId) {
        try { localStorage.setItem('biz-assist.performance-project.' + submissionCaseId, id); } catch { /* URL keeps the return context. */ }
    }
    $('#workspace').hidden = false;
    $('#project-title').textContent = project.name;
    $('#edit-project').elements.name.value = project.name;
    $('#edit-project').elements.deadline.value = project.deadline;
    $('#entries').querySelectorAll('tbody').forEach(body => body.remove());
    $('#entries').append(...entries.map(entry => renderEntry(entry)));
}
function evidencePresentation(entry, recommendedFiles = null) {
    if ((entry.info.selectedDriveFileId != null || entry.info.selectedFileId != null)) {
        return { label: '선택 완료', tone: 'complete', detail: '저장된 FMS 증빙파일이 연결되어 있습니다.' };
    }
    if (recommendedFiles === null) {
        return { label: '미검색', tone: '', detail: 'FMS 후보 검색을 실행하세요.' };
    }
    if (recommendedFiles.length) {
        return { label: '후보 있음', tone: 'available', detail: '후보를 선택한 뒤 저장하세요.' };
    }
    return { label: 'KITC 요청 필요', tone: 'attention', detail: 'FMS 검색 결과 연결할 증빙 후보가 없습니다.' };
}
function renderEntry(entry, expanded = false) {
    const info = entry.info;
    const body = el('tbody');body.className = 'performance-entry';body.dataset.entryId = entry.id;
    const row = el('tr');row.className = 'entry-summary-row';
    row.append(el('td', info.pptNumber), el('td', info.businessName), el('td', info.businessPeriod), el('td', info.client), el('td', info.contractAmount));
    const evidence = el('span');evidence.setAttribute('role', 'status');
    const statusCell = el('td');statusCell.append(evidence);
    const fileCell = el('td');fileCell.append(el('small', entry.selectedFilename || '—'));
    const toggle = el('button', '파일 관리');toggle.type = 'button';toggle.classList.add('entry-toggle');
    toggle.setAttribute('aria-label', info.pptNumber + '. ' + info.businessName + ' 파일 관리');
    const detail = el('tr');detail.className = 'entry-detail-row';detail.hidden = !expanded;detail.id = 'entry-detail-' + entry.id;
    toggle.setAttribute('aria-controls', detail.id);toggle.setAttribute('aria-expanded', String(expanded));
    const detailCell = el('td');detailCell.colSpan = 7;
    const panel = el('section');panel.className = 'entry-detail';panel.setAttribute('aria-label', '증빙 파일 관리');
    panel.append(el('h3', '증빙 파일 관리'));
    const current = el('p', '현재 지정 파일: ' + (entry.selectedFilename || '없음'));current.className = 'current-evidence-file';panel.append(current);
    const columns = el('div');columns.className = 'file-management-columns';
    const fms = el('section');fms.append(el('h4', 'FMS 후보 파일'));
    const candidates = el('div');candidates.className = 'candidates';fms.append(candidates);
    const upload = el('section');upload.className = 'local-file-unavailable';
    upload.append(el('h4', 'KITC 파일 등록'), el('p', '현재 PC 파일 등록은 지원되지 않습니다.'));
    upload.hidden = true;
    columns.append(fms, upload);panel.append(columns);
    const message = el('p');message.className = 'file-management-message';message.setAttribute('role', 'status');panel.append(message);
    const actions = el('div');actions.className = 'actions';
    const save = el('button', '지정 파일 저장');save.type = 'button';save.className = 'ui-button ui-button-primary';
    const search = el('button', '후보 추천');search.type = 'button';
    const clear = el('button', '연결 해제');clear.type = 'button';
    let driveFileId = info.selectedDriveFileId || null, fileId = info.selectedFileId || null, evidenceType = info.evidenceType || null;
    let recommendedFiles = null;
    function renderStatus() {
        const status = evidencePresentation(entry, recommendedFiles);
        evidence.className = 'entry-evidence-status requirement-state ' + status.tone;
        evidence.textContent = (info.selectedDriveFileId || info.selectedFileId) ? '준비 완료' : status.label;
        evidence.title = status.detail;
    }
    renderStatus();
    function renderChoices() {
        candidates.replaceChildren();
        for (const candidate of (recommendedFiles || []).filter(candidate => candidate.file.driveFileId)) {
            const label = el('label');label.className = 'file-candidate-option';
            const radio = el('input');radio.type = 'radio';radio.name = 'evidence-' + entry.id;
            radio.checked = driveFileId === candidate.file.driveFileId;
            radio.onchange = () => {
                driveFileId = candidate.file.driveFileId;fileId = null;evidenceType = candidate.evidenceType;
                message.textContent = '후보를 선택했습니다. 저장하면 지정 파일이 변경됩니다.';
            };
            label.append(radio, el('span', candidate.file.originalFilename + ' · ' + candidate.reason));candidates.append(label);
        }
        if (!candidates.children.length) candidates.append(el('p', '연결할 FMS 후보 파일이 없습니다.'));
        upload.hidden = Boolean(candidates.querySelector('input'));
    }
    const searchProjectId = entry.projectId || projectId;
    async function searchCandidates() {
        if (search.disabled) return;
        search.disabled = true;
        candidates.replaceChildren(el('p', 'FMS 후보 검색 중…'));
        evidence.textContent = (info.selectedDriveFileId || info.selectedFileId) ? '준비 완료' : '검색 중';
        try {
            const result = await api('/' + encodeURIComponent(searchProjectId) + '/entries/' + encodeURIComponent(entry.id) + '/candidates');
            if (!Array.isArray(result.candidates)) throw new Error('FMS 후보 응답 형식을 확인하세요.');
            recommendedFiles = result.candidates;renderChoices();renderStatus();
        } catch (error) {
            candidates.replaceChildren(el('p', error.message + ' · 후보 추천 버튼으로 재시도하세요.'));
            evidence.textContent = (info.selectedDriveFileId || info.selectedFileId) ? '준비 완료' : '검색 오류';
            message.textContent = error.message;
        } finally { search.disabled = false; }
    }
    search.onclick = searchCandidates;
    clear.onclick = () => {
        driveFileId = null;fileId = null;evidenceType = null;
        candidates.querySelectorAll('input').forEach(input => input.checked = false);
        message.textContent = '연결 해제는 지정 파일 저장을 누르면 반영됩니다.';
    };
    save.onclick = () => action(save, async () => {
        search.disabled = true;clear.disabled = true;candidates.querySelectorAll('input').forEach(input => input.disabled = true);
        message.textContent = '';
        try {
            const data = {...info, selectedDriveFileId:driveFileId, selectedFileId:fileId, evidenceType};
            const updated = await api('/' + encodeURIComponent(searchProjectId) + '/entries/' + encodeURIComponent(entry.id), {method:'PUT', body:JSON.stringify(data)});
            if (!body.isConnected || projectId !== searchProjectId) return;
            entriesById.set(updated.id, updated);
            const replacement = renderEntry(updated, !detail.hidden);body.replaceWith(replacement);
            updateEntrySummary();await loadProjects();
            $('#message').textContent = '지정 파일을 저장했습니다.';
        } catch (error) {message.textContent = error.message;}
        finally {search.disabled = false;clear.disabled = false;candidates.querySelectorAll('input').forEach(input => input.disabled = false);}
    });
    actions.append(save, search, clear);panel.append(actions);detailCell.append(panel);detail.append(detailCell);
    const toggleDetail = () => {
        detail.hidden = !detail.hidden;toggle.setAttribute('aria-expanded', String(!detail.hidden));
        if (!detail.hidden && recommendedFiles === null) searchCandidates();
    };
    toggle.onclick = event => {event.stopPropagation();toggleDetail();};row.onclick = toggleDetail;
    fileCell.append(toggle);row.append(statusCell, fileCell);body.append(row, detail);
    candidateSearches.set(body, searchCandidates);
    if (expanded) queueMicrotask(() => {if (body.isConnected) searchCandidates();});
    return body;
}
function appendImportedEntries(entries) {
    for (const entry of entries) {
        entriesById.set(entry.id, entry);
        const card = renderEntry(entry);
        $('#entries').append(card);
        // Serialize automatic searches so a large paste does not flood FMS.
        candidateQueue = candidateQueue.then(() => {
            if (card.isConnected) return candidateSearches.get(card)();
        });
    }
    return candidateQueue;
}
function showErrors(errors) {
    $('#errors').replaceChildren();
    for (const error of errors) {
        const box = el('div'); box.className = 'error-row';
        box.append(el('p', error.row + '행: ' + error.message), el('p', '원문: ' + error.cells.join(' / ')));
        const form = el('form'); form.className = 'fields';
        ['번호', '사업명', '사업기간', '계약금액', '발주처'].forEach((title, i) =>
            field(form, 'cell' + i, title, error.cells[i] || '', 'text', null, true));
        const retry = el('button', '이 행 수정·저장'); retry.type = 'submit'; form.append(retry);
        form.onsubmit = event => {
            event.preventDefault();
            action(retry, async () => {
                const table = el('table'); const row = el('tr');
                for (let i = 0; i < 5; i++) row.append(el('td', form.elements['cell' + i].value));
                table.append(row);
                const result = await api('/' + projectId + '/import', { method: 'POST', body: JSON.stringify({ html: table.outerHTML }) });
                if (result.errors.length) throw new Error(result.errors[0].message);
                appendImportedEntries(result.saved);
                box.remove(); await loadProjects();
            });
        };
        box.append(form); $('#errors').append(box);
    }
}
$('#create-project').onsubmit = event => {
    event.preventDefault();
    const form = event.currentTarget;
    action(form.querySelector('button'), async () => {
        const project = await api('', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(form))) });
        await loadProjects(); await openProject(project.id); form.reset();
    });
};
$('#edit-project').onsubmit = event => {
    event.preventDefault();
    const form = event.currentTarget;
    action(form.querySelector('button'), async () => {
        const updated = await api('/' + projectId, { method: 'PUT', body: JSON.stringify(Object.fromEntries(new FormData(form))) });
        $('#project-title').textContent = updated.name;
        $('#project-deadline').textContent = '마감일 ' + updated.deadline;
        $('#project-settings').open = false; await loadProjects();
        $('#message').textContent = '프로젝트를 저장했습니다.';
    });
};
function clipboardTableRows(html) {
    const template = document.createElement('template');template.innerHTML = html;
    const table = template.content.querySelector('table');
    if (!table) return [];
    return [...table.rows].map(row => ({
        cells: [...row.cells].map(cell => {
            const content = cell.cloneNode(true);
            content.querySelectorAll('script, style').forEach(node => node.remove());
            content.querySelectorAll('br').forEach(node => node.replaceWith('\n'));
            content.querySelectorAll('p, div').forEach(node => node.append('\n'));
            return content.textContent.replace(/\u00a0/g, ' ').trim();
        }),
        merged: [...row.cells].some(cell => cell.colSpan > 1 || cell.rowSpan > 1)
    }));
}
function textTableRows(text) {
    const rows = [];let cells = [], value = '', quoted = false;
    const input = text.replace(/\r\n?/g, '\n');
    for (let i = 0; i < input.length; i++) {
        const char = input[i];
        if (char === '"' && (quoted || !value)) {
            if (quoted && input[i + 1] === '"') {value += '"';i++;}
            else quoted = !quoted;
        } else if (!quoted && (char === '\t' || char === '\n')) {
            cells.push(value);value = '';
            if (char === '\n') {rows.push({cells});cells = [];}
        } else value += char;
    }
    cells.push(value);rows.push({cells, malformed:quoted});
    return rows;
}
function tableText(rows) {
    return rows.map(row => row.cells.map(value => /[\t\n"]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value).join('\t')).join('\n');
}
function renderPastePreview() {
    const rows = clipboardHtml ? clipboardTableRows(clipboardHtml) : textTableRows($('#paste-table').value);
    const body = $('#paste-preview-rows');body.replaceChildren();
    let count = 0, errors = 0;
    for (const row of rows) {
        const cells = row.cells.map(value => value.trim());
        if (cells.every(value => !value)) continue;
        const compact = cells.map(value => value.replace(/\s/g, ''));
        if (['번호', '순번', 'No', 'No.'].includes(compact[0]) && compact[1] === '사업명') continue;
        const invalid = cells.length !== 5 || cells.some(value => !value) || row.merged || row.malformed;
        count++;if (invalid) errors++;
        const item = el('tr');item.classList.toggle('paste-preview-error', Boolean(invalid));
        for (let i = 0; i < 5; i++) {
            const cell = el('td', (i === 4 ? cells.slice(i).join(' / ') : cells[i]) || '—');
            if (i === 0 && invalid) {const badge = el('span', '확인 필요');badge.className = 'count-badge';cell.append(badge);}
            item.append(cell);
        }
        body.append(item);
    }
    $('#paste-preview').hidden = count === 0;
    $('#paste-preview-summary').textContent = count + '행 · 확인 필요 ' + errors + '행';
}
$('#paste-table').addEventListener('paste', event => {
    const html = event.clipboardData.getData('text/html');
    const text = event.clipboardData.getData('text/plain');
    if (html && /<table[\s>]/i.test(html)) {
        event.preventDefault();clipboardHtml = html;
        event.currentTarget.value = text.trim() ? text : tableText(clipboardTableRows(html));
        renderPastePreview();
    } else if (text) {
        event.preventDefault();clipboardHtml = '';
        event.currentTarget.setRangeText(text, event.currentTarget.selectionStart, event.currentTarget.selectionEnd, 'end');
        renderPastePreview();
    }
});
$('#paste-table').addEventListener('input', () => { clipboardHtml = '';renderPastePreview(); });
$('#import').onclick = () => action($('#import'), async () => {
    const result = await api('/' + projectId + '/import', {
        method: 'POST', body: JSON.stringify({ text: $('#paste-table').value, html: clipboardHtml })
    });
    appendImportedEntries(result.saved);
    $('#import-result').textContent = result.saved.length + '행 저장, ' + result.errors.length + '행 확인 필요';
    showErrors(result.errors); await loadProjects();
    $('#paste-table').value = ''; clipboardHtml = '';renderPastePreview();
});
$('#download').onclick = () => action($('#download'), async () => {
    const response = await fetch(apiBase + '/' + projectId + '/download');
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || 'ZIP 다운로드에 실패했습니다.');
    }
    const url = URL.createObjectURL(await response.blob());
    const link = el('a'); link.href = url; link.download = 'performance-evidence.zip';
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('#new-project').onclick = () => showCreateProject($('#create-project-panel').hidden);
$('#cancel-create-project').onclick = () => showCreateProject(false);
$('#back-projects').onclick = showProjectList;
updateReturnLink();
(async () => {
    try {
        await loadProjects();
        const id = new URLSearchParams(location.search).get('project');
        if (id) await openProject(id);
    } catch (error) { $('#message').textContent = error.message; }
})();
async function loadDriveIndex(refresh = false) {
    const output = $('#drive-index-status');
    try {
        const response = await fetch('/api/drive-index' + (refresh ? '/refresh' : ''),
            { method: refresh ? 'POST' : 'GET' });
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.message || '인덱스 상태를 확인할 수 없습니다.');
        }
        const roots = await response.json();
        if (!Array.isArray(roots)) throw new Error('인덱스 상태 응답을 확인하세요.');
        output.replaceChildren();
        if (!roots.length) output.textContent = '검색 폴더 설정이 필요합니다.';
        const labels = { NOT_BUILT: '미구축', REFRESHING: '갱신 중', SUCCESS: '성공', FAILED: '실패' };
        for (const root of roots) {
            const state = root.state;
            output.append(el('p', root.label + ' · ' + (labels[state.status] || '확인 필요')
                + ' · 마지막 성공: ' + (state.lastSuccessAt ? new Date(state.lastSuccessAt).toLocaleString() : '없음')
                + ' · 파일 ' + state.fileCount + '개'
                + (state.status === 'FAILED' ? ' · FMS 연결·설정·접근 권한·탐색 제한을 확인한 후 다시 갱신하세요.' : '')));
        }
    } catch (error) { output.textContent = '인덱스 오류: ' + error.message; }
}
$('#refresh-drive-index').onclick = async () => {
    const button = $('#refresh-drive-index');
    if (button.disabled) return;
    button.disabled = true;
    $('#drive-index-status').textContent = '갱신 중…';
    try { await loadDriveIndex(true); }
    finally { button.disabled = false; }
};
loadDriveIndex();