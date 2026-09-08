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
    const processed = entries.filter(entry => entry.info.selectedDriveFileId != null).length;
    $('#entry-summary').textContent = entries.length
        ? entries.length + '건 중 ' + processed + '건 처리 / ' + (entries.length - processed) + '건 미처리'
        : '등록된 실적 없음';
    $('#entries-empty').hidden = entries.length !== 0;
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
    $('#entries').replaceChildren(...entries.map(renderEntry));
}
function evidencePresentation(entry, recommendedFiles = null) {
    if (entry.info.selectedDriveFileId != null) {
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
function renderEntry(entry) {
    const section = el('section'); section.className = 'performance-entry surface-card';
    const heading = el('h3', entry.info.pptNumber + '. ' + entry.info.businessName);
    const statusLine = el('div'); statusLine.className = 'entry-statuses';
    const business = el('span', '사업 상태 · ' + (entry.resolvedStatus === 'COMPLETED' ? '수행완료' : '수행중'));
    business.className = 'entry-business-status';
    const evidence = el('span');
    evidence.setAttribute('role', 'status');
    evidence.setAttribute('aria-live', 'polite');
    function renderEvidenceStatus(recommendedFiles = null) {
        const status = evidencePresentation(entry, recommendedFiles);
        evidence.className = 'entry-evidence-status requirement-state ' + status.tone;
        evidence.textContent = '증빙 상태 · ' + status.label;
        evidence.title = status.detail;
    }
    renderEvidenceStatus();
    statusLine.append(business, evidence);
    section.append(heading, statusLine);
    const form = el('form'); form.className = 'entry-form';
    const info = entry.info;
    field(form, 'pptNumber', 'PPT 번호', info.pptNumber, 'text', null, true, 100);
    field(form, 'businessName', '사업명', info.businessName, 'text', null, true, 1000);
    field(form, 'businessPeriod', '사업기간', info.businessPeriod, 'text', null, true, 500);
    field(form, 'contractAmount', '계약금액 (원문 유지)', info.contractAmount, 'text', null, true, 200);
    field(form, 'client', '발주처', info.client, 'text', null, true, 500);
    field(form, 'businessStatus', '사업 상태', info.businessStatus, 'text',
        [['', '기간으로 자동 판정'], ['COMPLETED', '수행완료'], ['IN_PROGRESS', '수행중']]);
    const driveFile = el('input'); driveFile.type = 'hidden'; driveFile.name = 'selectedDriveFileId';
    driveFile.value = info.selectedDriveFileId || ''; form.append(driveFile);
    const evidenceType = el('input'); evidenceType.type = 'hidden'; evidenceType.name = 'evidenceType';
    evidenceType.value = info.selectedDriveFileId ? info.evidenceType : ''; form.append(evidenceType);
    const typeLabel = el('p');
    function showType() {
        typeLabel.textContent = '증빙유형: ' + ({ CERTIFICATE: '실적증명서', CONTRACT: '계약서' }[evidenceType.value] || '미선택');
    }
    showType(); form.append(typeLabel);
    const kitc = field(form, 'kitcStatus', 'KITC 상태', info.kitcStatus, 'text',
        [['NEEDED', '요청 필요'], ['REQUESTED', '요청함'], ['RECEIVED', '회신받음']]);
    const requestedAt = field(form, 'requestedAt', '요청일 (직접 입력)', info.requestedAt, 'date');
    const repliedAt = field(form, 'repliedAt', '회신일 (직접 입력)', info.repliedAt, 'date');
    function showKitcDates() {
        requestedAt.parentElement.hidden = kitc.value === 'NEEDED';
        repliedAt.parentElement.hidden = kitc.value !== 'RECEIVED';
        requestedAt.required = kitc.value !== 'NEEDED';
        repliedAt.required = kitc.value === 'RECEIVED';
    }
    kitc.addEventListener('change', showKitcDates);
    showKitcDates();
    const actions = el('div'); actions.className = 'actions';
    const save = el('button', '실적·파일·KITC 저장'); save.type = 'submit'; save.className = 'ui-button ui-button-primary';
    const search = el('button', '저장된 실적으로 후보 추천'); search.type = 'button';
    const clear = el('button', '파일 연결 해제'); clear.type = 'button';
    const selected = el('p', '저장된 파일: ' + (entry.selectedFilename || '선택하지 않음'));
    const candidates = el('div'); candidates.className = 'candidates';
    clear.onclick = () => {
        driveFile.value = ''; evidenceType.value = ''; showType();
        $('#message').textContent = '연결 해제는 저장 버튼을 누르면 반영됩니다.';
    };
    const searchProjectId = entry.projectId || projectId;
    async function searchCandidates() {
        if (search.disabled) return;
        search.disabled = true;
        candidates.replaceChildren(el('p', '인덱스 후보 검색 중…'));
        evidence.className = 'entry-evidence-status requirement-state';
        evidence.textContent = '증빙 상태 · 검색 중';
        try {
            const result = await api('/' + encodeURIComponent(searchProjectId) + '/entries/' + encodeURIComponent(entry.id) + '/candidates');
            if (!Array.isArray(result.candidates)) throw new Error('FMS 후보 응답 형식을 확인하세요.');
            renderEvidenceStatus(result.candidates);
            candidates.replaceChildren(el('p', result.nextAction));
            for (const candidate of result.candidates.filter(candidate => candidate.file.driveFileId)) {
                const button = el('button', candidate.file.originalFilename + ' · ' + candidate.reason);
                button.type = 'button';
                button.onclick = () => {
                    driveFile.value = candidate.file.driveFileId || '';

                    evidenceType.value = candidate.evidenceType; showType();
                    $('#message').textContent = '후보를 지정했습니다. 저장 버튼을 눌러 최종 선택을 저장하세요.';
                };
                candidates.append(button);
            }
        } catch (error) {
            evidence.className = 'entry-evidence-status requirement-state attention';
            evidence.textContent = '증빙 상태 · 검색 오류';
            evidence.title = error.message;
            candidates.replaceChildren(el('p', error.message + ' · 후보 추천 버튼으로 재시도하세요.'));
            $('#message').textContent = error.message;
        } finally { search.disabled = false; }
    }
    search.onclick = searchCandidates;
    candidateSearches.set(section, searchCandidates);
    form.onsubmit = event => {
        event.preventDefault();
        action(save, async () => {
            const data = Object.fromEntries(new FormData(form));
            for (const key of ['businessStatus', 'selectedDriveFileId', 'evidenceType', 'requestedAt', 'repliedAt']) {
                data[key] = data[key] || null;
            }
            if (kitc.value === 'NEEDED') data.requestedAt = null;
            if (kitc.value !== 'RECEIVED') data.repliedAt = null;
            const updated = await api('/' + projectId + '/entries/' + entry.id,
                { method: 'PUT', body: JSON.stringify(data) });
            entriesById.set(updated.id, updated);
            section.replaceWith(renderEntry(updated));
            await loadProjects();
            $('#message').textContent = '저장했습니다.';
        });
    };
    actions.append(save, search, clear); form.append(actions);
    section.append(form, selected, candidates);
    return section;
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
$('#paste-table').addEventListener('paste', event => {
    const html = event.clipboardData.getData('text/html');
    if (html && /<table[\s>]/i.test(html)) {
        event.preventDefault();
        clipboardHtml = html; event.currentTarget.value = event.clipboardData.getData('text/plain');
    }
});
$('#paste-table').addEventListener('input', () => { clipboardHtml = ''; });
$('#import').onclick = () => action($('#import'), async () => {
    const result = await api('/' + projectId + '/import', {
        method: 'POST', body: JSON.stringify({ text: $('#paste-table').value, html: clipboardHtml })
    });
    appendImportedEntries(result.saved);
    $('#import-result').textContent = result.saved.length + '행 저장, ' + result.errors.length + '행 확인 필요';
    showErrors(result.errors); await loadProjects();
    $('#paste-table').value = ''; clipboardHtml = '';
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