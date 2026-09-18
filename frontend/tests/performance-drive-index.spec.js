import { test, expect } from '@playwright/test';

const root = (label, status, lastSuccessAt = null, fileCount = 0) => ({ label, state: {
  status, lastAttemptAt: status === 'NOT_BUILT' ? null : '2026-09-18T04:00:00Z', lastSuccessAt,
  folderCount: fileCount ? 3 : 0, fileCount, errorCode: status === 'FAILED' ? 'REFRESH_FAILED' : null,
} });

async function setup(page, initial) {
  const state = { roots: initial, gets: 0, posts: 0, hold: false, release: null, failure: null, after: initial };
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    if (path === '/api/drive-index' && request.method() === 'GET') {
      state.gets += 1; return route.fulfill({ json: state.roots });
    }
    if (path === '/api/drive-index/refresh' && request.method() === 'POST') {
      state.posts += 1;
      if (state.hold) await new Promise(resolve => { state.release = resolve; });
      if (state.failure) return route.fulfill({ status: state.failure.status, json: { message: state.failure.message } });
      state.roots = state.after; return route.fulfill({ json: state.after });
    }
    if (path === '/api/performance-projects') return route.fulfill({ json: [] });
    return route.fulfill({ status: 404, json: { message: 'unexpected' } });
  });
  await page.goto('/react/index.html#/performances');
  await expect(page.getByRole('heading', { name: 'Drive 검색 인덱스' })).toBeVisible();
  return state;
}

test('drive index: root states, last success and file counts use server values', async ({ page }) => {
  await setup(page, [
    root('검색 범위 1', 'SUCCESS', '2026-09-18T03:20:00Z', 1234), root('검색 범위 2', 'NOT_BUILT'),
    root('검색 범위 3', 'REFRESHING', '2026-09-17T01:00:00Z', 20), root('검색 범위 4', 'FAILED', '2026-09-16T02:00:00Z', 19),
  ]);
  const table = page.getByRole('table', { name: '' }).first();
  await expect(table).toContainText('검색 범위 1'); await expect(table).toContainText('성공');
  await expect(table).toContainText('1,234개'); await expect(table).toContainText('미구축');
  await expect(table.getByRole('row').filter({ hasText: '검색 범위 1' })).toContainText('2026');
  await expect(table.getByRole('row').filter({ hasText: '검색 범위 2' })).toContainText('없음');
  await expect(table).toContainText('갱신 중'); await expect(table).toContainText('실패');
  await expect(table).toContainText('FMS 연결·설정·접근 권한·탐색 제한을 확인하세요.');
  await expect(table).not.toContainText(/\/private|credential|storage_path/i);
  await expect(page.getByRole('button', { name: 'Drive 인덱스 갱신' })).toBeDisabled();
});

test('drive index: missing roots show the existing configuration state', async ({ page }) => {
  await setup(page, []);
  await expect(page.getByText('검색 폴더 설정이 필요합니다.')).toBeVisible();
});

test('drive index: refresh is synchronous, locked once and followed by GET', async ({ page }) => {
  const before = root('검색 범위 1', 'SUCCESS', '2026-09-17T01:00:00Z', 7);
  const after = root('검색 범위 1', 'SUCCESS', '2026-09-18T05:00:00Z', 12);
  const state = await setup(page, [before]); state.after = [after]; state.hold = true;
  await page.getByRole('button', { name: 'Drive 인덱스 갱신' }).click();
  await expect(page.getByRole('button', { name: '갱신 중…' })).toBeDisabled();
  await page.getByRole('button', { name: '갱신 중…' }).click({ force: true });
  expect(state.posts).toBe(1); state.release();
  await expect(page.getByRole('cell', { name: '12개' })).toBeVisible();
  expect(state.gets).toBe(2); expect(state.posts).toBe(1);
});

test('drive index: refresh failure preserves the last successful snapshot and hides internals', async ({ page }) => {
  const before = root('검색 범위 1', 'SUCCESS', '2026-09-17T01:00:00Z', 7);
  const state = await setup(page, [before]);
  state.failure = { status: 503, message: 'storage_path=C:\\private credential=password' };
  await page.getByRole('button', { name: 'Drive 인덱스 갱신' }).click();
  await expect(page.getByRole('alert')).toHaveText('Drive index 갱신에 실패했습니다.');
  await expect(page.getByRole('alert')).not.toContainText(/private|credential|storage/i);
  await expect(page.getByRole('cell', { name: '7개' })).toBeVisible();
  expect(state.gets).toBe(1); expect(state.posts).toBe(1);
});

test('drive index: a completed refresh may report failed roots while retaining counts', async ({ page }) => {
  const before = root('검색 범위 1', 'SUCCESS', '2026-09-17T01:00:00Z', 7);
  const failed = root('검색 범위 1', 'FAILED', '2026-09-17T01:00:00Z', 7);
  const state = await setup(page, [before]); state.after = [failed];
  await page.getByRole('button', { name: 'Drive 인덱스 갱신' }).click();
  await expect(page.getByRole('cell').filter({ hasText: '실패' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '7개' })).toBeVisible();
  expect(state.gets).toBe(2);
});

test('drive index: mobile status table scrolls inside the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page, [root('검색 범위 1', 'SUCCESS', '2026-09-18T03:20:00Z', 1234)]);
  await expect(page.getByRole('button', { name: 'Drive 인덱스 갱신' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
