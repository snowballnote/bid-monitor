import { test, expect } from '@playwright/test';

async function setup(page, options = {}) {
  const entries = [{ id: 'entry-1', projectId: 'p1', selectedFilename: '기존-실적증명서.pdf', info: {
    pptNumber: '1', businessName: '기존 구축 사업', businessPeriod: '2024.01 ~ 2025.12', contractAmount: '10억',
    client: '기존 발주기관', businessStatus: 'COMPLETED', selectedFileId: null,
    selectedDriveFileId: 'drive-1', selectedUploadedFileId: null, evidenceType: 'CERTIFICATE',
    kitcStatus: 'REQUESTED', requestedAt: '2026-08-01', repliedAt: null,
  } }];
  const projects = options.empty ? [] : [
    { id: 'p1', name: '기존 실적 프로젝트', deadline: '2026-09-18', daysRemaining: 0, status: 'READY' },
    { id: 'p2', name: '수집 프로젝트', deadline: '2026-09-15', daysRemaining: -3, status: 'COLLECTING' },
    { id: 'p3', name: '초안 프로젝트', deadline: '2026-09-23', daysRemaining: 5, status: 'DRAFT' },
  ];
  const state = { projects, entries, writes: [], failSave: false, failList: false };
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname; const method = request.method();
    if (path === '/api/drive-index') return route.fulfill({ json: [] });
    if (path === '/api/performance-projects' && method === 'GET') {
      if (state.failList) return route.fulfill({ status: 503, json: { message: '내부 목록 경로' } });
      return route.fulfill({ json: state.projects });
    }
    if (path === '/api/performance-projects' && method === 'POST') {
      const body = request.postDataJSON(); state.writes.push({ method, path, body });
      if (state.failSave) return route.fulfill({ status: 503, json: { message: 'C:\\internal\\project.sql' } });
      const saved = { id: `new-${state.projects.length}`, ...body, daysRemaining: 10, status: 'DRAFT' };
      state.projects.push(saved); return route.fulfill({ json: saved });
    }
    const entriesMatch = path.match(/^\/api\/performance-projects\/([^/]+)\/entries$/);
    if (entriesMatch && method === 'GET') return route.fulfill({ json: entriesMatch[1] === 'p1' ? state.entries : [] });
    const projectMatch = path.match(/^\/api\/performance-projects\/([^/]+)$/);
    if (projectMatch && method === 'GET') {
      const project = state.projects.find(row => row.id === projectMatch[1]);
      return project ? route.fulfill({ json: project }) : route.fulfill({ status: 404, json: { message: 'not found' } });
    }
    if (projectMatch && method === 'PUT') {
      const body = request.postDataJSON(); state.writes.push({ method, path, body });
      if (state.failSave) return route.fulfill({ status: 400, json: { message: '프로젝트명을(를) 확인하세요.' } });
      const index = state.projects.findIndex(row => row.id === projectMatch[1]);
      state.projects[index] = { ...state.projects[index], ...body };
      return route.fulfill({ json: state.projects[index] });
    }
    return route.fulfill({ status: 404, json: { message: 'unexpected request' } });
  });
  await page.goto('/react/index.html#/performances');
  return state;
}

test('performance projects: compact list keeps server D-day and status values', async ({ page }) => {
  await setup(page);
  const table = page.getByRole('table');
  await expect(table).toContainText('기존 실적 프로젝트');
  await expect(table).toContainText('D-day');
  await expect(table).toContainText('D+3');
  await expect(table).toContainText('D-5');
  await expect(table).toContainText('선택 완료');
  await expect(table).toContainText('수집중');
  await expect(table).toContainText('작성중');
  await expect(page.getByRole('link', { name: '기존 실적 프로젝트' })).toHaveAttribute('href', '#/performances/p1');
});

test('performance projects: empty, loading error and retry states', async ({ page }) => {
  const state = await setup(page, { empty: true });
  await expect(page.getByText('등록된 실적 프로젝트가 없습니다.')).toBeVisible();
  state.failList = true; await page.reload();
  await expect(page.getByRole('alert')).toContainText('데이터 조회 실패: 503');
  state.failList = false; await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.getByText('등록된 실적 프로젝트가 없습니다.')).toBeVisible();
});

test('performance projects: create validates and navigates to React detail', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('button', { name: '+ 새 프로젝트' }).click();
  const dialog = page.getByRole('dialog', { name: '실적 프로젝트 생성' });
  await dialog.getByRole('button', { name: '생성' }).click();
  await expect(dialog.getByText('프로젝트명을(를) 확인하세요.')).toBeVisible();
  await expect(dialog.getByText('마감일을 확인하세요.')).toBeVisible();
  await dialog.getByLabel('프로젝트명').fill('기존 실적 프로젝트');
  await dialog.getByLabel('마감일').fill('2026-10-10');
  await dialog.getByRole('button', { name: '생성' }).click();
  await expect(page).toHaveURL(/#\/performances\/new-3$/);
  await expect(page.getByRole('heading', { name: '기존 실적 프로젝트' })).toBeVisible();
  expect(state.writes).toEqual([{ method: 'POST', path: '/api/performance-projects', body: {
    name: '기존 실적 프로젝트', deadline: '2026-10-10',
  } }]);
});

test('performance projects: edit updates detail and preserves entry, file and submission links', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('link', { name: '기존 실적 프로젝트' }).click();
  await expect(page.locator('#performance-documents')).toContainText('기존-실적증명서.pdf');
  await page.getByRole('button', { name: '프로젝트 수정' }).click();
  const dialog = page.getByRole('dialog', { name: '실적 프로젝트 수정' });
  await dialog.getByLabel('프로젝트명').fill('수정된 실적 프로젝트');
  await dialog.getByLabel('마감일').fill('2026-10-20');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(page.getByRole('heading', { name: '수정된 실적 프로젝트' })).toBeVisible();
  await expect(page.locator('#performance-documents')).toContainText('기존-실적증명서.pdf');
  expect(state.entries[0].info.selectedDriveFileId).toBe('drive-1');
  expect(state.writes).toEqual([{ method: 'PUT', path: '/api/performance-projects/p1', body: {
    name: '수정된 실적 프로젝트', deadline: '2026-10-20',
  } }]);
  expect(state.writes.some(write => write.path.includes('/entries') || write.path.includes('/submission-cases'))).toBe(false);
  await page.getByRole('link', { name: '목록' }).click();
  await expect(page.getByRole('link', { name: '수정된 실적 프로젝트' })).toBeVisible();
});

test('performance projects: failed edits keep confirmed data and form values', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('button', { name: '수정' }).first().click();
  const dialog = page.getByRole('dialog', { name: '실적 프로젝트 수정' });
  await dialog.getByLabel('프로젝트명').fill('저장 실패 이름');
  state.failSave = true; await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('프로젝트명을(를) 확인하세요.');
  await expect(dialog.getByLabel('프로젝트명')).toHaveValue('저장 실패 이름');
  await dialog.getByRole('button', { name: '취소' }).click();
  await expect(page.getByRole('link', { name: '기존 실적 프로젝트' })).toBeVisible();
});

test('performance projects: mobile list and modal stay within viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.getByRole('button', { name: '+ 새 프로젝트' }).click();
  await expect(page.getByRole('dialog', { name: '실적 프로젝트 생성' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
