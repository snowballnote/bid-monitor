import { test, expect } from '@playwright/test';

const info = (name, overrides = {}) => ({
  pptNumber: name.at(-1), businessName: name, client: '한국기관', businessPeriod: '2024.01 ~ 2024.12',
  contractAmount: '10억', businessStatus: 'COMPLETED', selectedFileId: null, selectedDriveFileId: null,
  selectedUploadedFileId: null, evidenceType: null, kitcStatus: 'NEEDED', requestedAt: null, repliedAt: null,
  ...overrides,
});

const evidence = (evidenceId, evidenceType, filename, sourceType = 'DIRECT_UPLOAD', sourceId = evidenceId) => ({
  evidenceId, evidenceType, sourceType, filename, fileExt: filename.split('.').at(-1),
  selectedFileId: sourceType === 'COMPANY_FILE' ? Number(sourceId) : null,
  selectedDriveFileId: sourceType === 'FMS_DRIVE' ? sourceId : null,
  selectedUploadedFileId: sourceType === 'DIRECT_UPLOAD' ? sourceId : null,
  createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
});

async function setup(page, customEntries) {
  const entries = customEntries || [
    { id: 'one', projectId: 'perf-1', selectedFilename: 'legacy-projection.pdf', resolvedStatus: 'COMPLETED',
      info: info('실적 1'), evidences: [
        evidence('contract-1', 'CONTRACT', '계약서.pdf', 'COMPANY_FILE', 11),
        evidence('tax-1', 'TAX_INVOICE', '세금계산서.pdf'),
        evidence('tax-2', 'TAX_INVOICE', '세금계산서-2.pdf'),
        evidence('certificate-1', 'CERTIFICATE', '실적증명서.pdf', 'FMS_DRIVE', 'drive-certificate'),
      ] },
    { id: 'two', projectId: 'perf-1', selectedFilename: 'legacy-only.pdf', resolvedStatus: 'COMPLETED',
      info: info('실적 2', { selectedDriveFileId: 'legacy-drive', evidenceType: 'CONTRACT' }), evidences: [] },
  ];
  const state = { entries, requests: [], writes: [], nextId: 1, failUpload: false };
  const candidates = [{
    file: { driveFileId: 'candidate-drive-1', originalFilename: 'FMS-계약서.pdf', fileExt: 'pdf', size: 1200,
      lastModified: '2026-10-01T00:00:00Z' }, evidenceType: 'CONTRACT', reason: '사업명과 발주기관 일치',
  }];

  const findEntry = id => entries.find(entry => entry.id === id);
  const updateEntry = (id, updater) => {
    const index = entries.findIndex(entry => entry.id === id);
    entries[index] = updater(entries[index]); return entries[index];
  };
  const multipart = request => {
    const body = request.postData() || '';
    return {
      filename: body.match(/filename="([^"]+)"/)?.[1] || 'uploaded.bin',
      type: body.match(/name="evidenceType"[\s\S]*?\r?\n\r?\n([A-Z_]+)/)?.[1] || 'CONTRACT',
    };
  };

  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname; const method = request.method();
    state.requests.push({ method, path });
    if (path === '/api/performance-projects/perf-1' && method === 'GET') return route.fulfill({ json: {
      id: 'perf-1', name: '실적 조회 프로젝트', deadline: '2026-10-31', daysRemaining: 17, status: 'DRAFT',
    } });
    if (path === '/api/performance-projects/perf-1/entries' && method === 'GET') return route.fulfill({ json: entries });
    if (path === '/api/drive-index' && method === 'GET') return route.fulfill({ json: [] });
    const candidateMatch = path.match(/^\/api\/performance-projects\/perf-1\/entries\/([^/]+)\/candidates$/);
    if (candidateMatch) return route.fulfill({ json: { candidates, nextAction: '추천 순서로 후보를 확인하세요.' } });

    const evidenceMatch = path.match(/^\/api\/performance-projects\/perf-1\/entries\/([^/]+)\/evidences(?:\/([^/]+))?(?:\/(upload|download))?$/);
    if (evidenceMatch) {
      let [, entryId, evidenceId, action] = evidenceMatch;
      if (evidenceId === 'upload' && !action) { evidenceId = undefined; action = 'upload'; }
      state.writes.push({ method, path, body: request.postData() });
      if (action === 'download' && method === 'GET') return route.fulfill({
        body: 'evidence-bytes', contentType: 'application/octet-stream',
        headers: { 'Content-Disposition': `attachment; filename="${findEntry(entryId).evidences.find(row => row.evidenceId === evidenceId).filename}"` },
      });
      if (action === 'upload' && ['POST', 'PUT'].includes(method)) {
        if (state.failUpload) return route.fulfill({ status: 503, json: { message: 'C:\\internal\\upload.tmp' } });
        const uploaded = multipart(request);
        return route.fulfill({ json: updateEntry(entryId, entry => {
          const nextEvidence = evidence(evidenceId || `new-${state.nextId++}`, uploaded.type, uploaded.filename,
            'DIRECT_UPLOAD', `upload-${state.nextId}`);
          return { ...entry, evidences: evidenceId
            ? entry.evidences.map(row => row.evidenceId === evidenceId ? nextEvidence : row)
            : [...entry.evidences, nextEvidence] };
        }) });
      }
      if (!action && method === 'POST') {
        const body = request.postDataJSON();
        return route.fulfill({ json: updateEntry(entryId, entry => ({ ...entry, evidences: [...entry.evidences,
          evidence(`new-${state.nextId++}`, body.evidenceType, candidates[0].file.originalFilename, 'FMS_DRIVE', body.selectedDriveFileId)] })) });
      }
      if (!action && evidenceId && method === 'PUT') {
        const body = request.postDataJSON();
        return route.fulfill({ json: updateEntry(entryId, entry => ({ ...entry, evidences: entry.evidences.map(row =>
          row.evidenceId === evidenceId ? evidence(evidenceId, body.evidenceType, candidates[0].file.originalFilename,
            'FMS_DRIVE', body.selectedDriveFileId) : row) })) });
      }
      if (!action && evidenceId && method === 'DELETE') return route.fulfill({ json: updateEntry(entryId, entry => ({
        ...entry, evidences: entry.evidences.filter(row => row.evidenceId !== evidenceId),
      })) });
    }

    const entryMatch = path.match(/^\/api\/performance-projects\/perf-1\/entries\/([^/]+)$/);
    if (entryMatch && method === 'PUT') {
      const body = request.postDataJSON(); state.writes.push({ method, path, body });
      return route.fulfill({ json: updateEntry(entryMatch[1], entry => ({ ...entry, info: { ...entry.info, ...body } })) });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/react/index.html#/performances/perf-1');
  await expect(page.locator('#performance-documents')).toBeVisible();
  return state;
}

const rowFor = (page, name) => page.locator('#performance-documents tbody tr').filter({ hasText: name });

async function openEvidenceManager(page, name = '실적 1') {
  await rowFor(page, name).getByRole('button', { name: '증빙 관리' }).click();
  return page.getByRole('dialog', { name: `${name} 증빙 관리` });
}

test('evidences render every type and duplicate type while legacy projection is ignored', async ({ page }) => {
  await setup(page);
  const first = rowFor(page, '실적 1'); const second = rowFor(page, '실적 2');
  await expect(first).toContainText('4개');
  await expect(first).toContainText('계약서.pdf');
  await expect(second).toContainText('미준비');
  await expect(second).not.toContainText('legacy-only.pdf');
  const dialog = await openEvidenceManager(page);
  await expect(dialog.getByText('계약서', { exact: true })).toHaveCount(1);
  await expect(dialog.getByText('세금계산서', { exact: true })).toHaveCount(2);
  await expect(dialog.getByText('실적증명서', { exact: true })).toHaveCount(1);
  await expect(dialog.getByText('세금계산서.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('세금계산서-2.pdf', { exact: true })).toBeVisible();
});

test('empty evidences show an addable empty state even when legacy selected fields exist', async ({ page }) => {
  await setup(page);
  const dialog = await openEvidenceManager(page, '실적 2');
  await expect(dialog.getByText('증빙 없음', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '+ 증빙 추가' })).toBeEnabled();
});

test('direct upload adds a tax invoice without replacing existing contract', async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEvidenceManager(page);
  await dialog.getByRole('button', { name: '+ 증빙 추가' }).click();
  await dialog.getByLabel('증빙유형').selectOption('TAX_INVOICE');
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: '세금계산서-3.pdf', mimeType: 'application/pdf', buffer: Buffer.from('tax') });
  await dialog.getByRole('button', { name: '업로드하여 추가' }).click();
  await expect(dialog.getByText('세금계산서-3.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('계약서.pdf', { exact: true })).toBeVisible();
  expect(state.writes.at(-1)).toMatchObject({ method: 'POST', path: '/api/performance-projects/perf-1/entries/one/evidences/upload' });
});

test('FMS candidate adds another evidence through the evidence endpoint', async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEvidenceManager(page, '실적 2');
  await dialog.getByRole('button', { name: '+ 증빙 추가' }).click();
  await dialog.getByLabel('증빙유형').selectOption('CONTRACT');
  await dialog.getByRole('button', { name: '증빙 추가', exact: true }).click();
  await expect(dialog.getByText('FMS-계약서.pdf', { exact: true })).toBeVisible();
  const write = state.writes.at(-1);
  expect(write).toMatchObject({ method: 'POST', path: '/api/performance-projects/perf-1/entries/two/evidences' });
  expect(JSON.parse(write.body)).toMatchObject({ selectedDriveFileId: 'candidate-drive-1', evidenceType: 'CONTRACT' });
});

test('replace updates only the selected evidence and keeps the other evidence rows', async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEvidenceManager(page);
  const tax = dialog.locator('.performance-evidence-list > li').filter({ hasText: '세금계산서.pdf' });
  await tax.getByRole('button', { name: '교체' }).click();
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: '교체된-세금계산서.pdf', mimeType: 'application/pdf', buffer: Buffer.from('new') });
  await dialog.getByRole('button', { name: '업로드하여 교체' }).click();
  await expect(dialog.getByText('교체된-세금계산서.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('계약서.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('세금계산서-2.pdf', { exact: true })).toBeVisible();
  expect(state.writes.at(-1)).toMatchObject({ method: 'PUT', path: '/api/performance-projects/perf-1/entries/one/evidences/tax-1/upload' });
});

test('delete removes one evidence after confirmation and preserves its siblings', async ({ page }) => {
  const state = await setup(page);
  page.once('dialog', dialog => dialog.accept());
  const dialog = await openEvidenceManager(page);
  const tax = dialog.locator('.performance-evidence-list > li').filter({ hasText: '세금계산서.pdf' });
  await tax.getByRole('button', { name: '삭제' }).click();
  await expect(dialog.getByText('세금계산서.pdf', { exact: true })).toHaveCount(0);
  await expect(dialog.getByText('세금계산서-2.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('계약서.pdf', { exact: true })).toBeVisible();
  expect(state.writes.at(-1)).toMatchObject({ method: 'DELETE', path: '/api/performance-projects/perf-1/entries/one/evidences/tax-1' });
});

test('download uses the selected evidence id endpoint', async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEvidenceManager(page);
  const contract = dialog.locator('.performance-evidence-list > li').filter({ hasText: '계약서.pdf' });
  const download = page.waitForEvent('download');
  await contract.getByRole('button', { name: '보기/다운로드' }).click();
  await download;
  expect(state.requests).toContainEqual({ method: 'GET', path: '/api/performance-projects/perf-1/entries/one/evidences/contract-1/download' });
});

test('failed upload keeps every confirmed evidence visible', async ({ page }) => {
  const state = await setup(page); state.failUpload = true;
  const dialog = await openEvidenceManager(page);
  await dialog.getByRole('button', { name: '+ 증빙 추가' }).click();
  await dialog.getByLabel('업로드 파일').setInputFiles({ name: '실패.pdf', mimeType: 'application/pdf', buffer: Buffer.from('fail') });
  await dialog.getByRole('button', { name: '업로드하여 추가' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('증빙 업로드에 실패했습니다. 다시 시도해 주세요.');
  await expect(dialog.getByText('계약서.pdf', { exact: true })).toBeVisible();
  await expect(dialog.getByText('세금계산서.pdf', { exact: true })).toBeVisible();
});

test('entry metadata update neither sends nor removes evidences', async ({ page }) => {
  const state = await setup(page);
  const row = rowFor(page, '실적 1');
  await row.getByRole('button', { name: '수정' }).click();
  const dialog = page.getByRole('dialog', { name: '실적 수정' });
  await dialog.getByLabel('사업명').fill('수정된 실적 1');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(rowFor(page, '수정된 실적 1')).toContainText('4개');
  const write = state.writes.find(item => item.path === '/api/performance-projects/perf-1/entries/one');
  expect(write.body).not.toHaveProperty('evidences');
  expect(state.entries[0].evidences).toHaveLength(4);
});
