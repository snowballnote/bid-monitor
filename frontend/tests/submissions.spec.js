import { test, expect } from '@playwright/test';

async function setup(page, { empty = false, listError = false, invalid = false, writeError = false, hold = false } = {}) {
  await page.goto('about:blank');
  const state = { requests: [], listError, writeError, release: null, rows: empty ? [] : [
    { id: 71, projectName: '제출 프로젝트', deadline: '2026-09-30', prepared: 2, total: 4, orderingAgency: '발주기관', updatedAt: '2026-09-15T09:00:00+09:00' },
    { id: 72, projectName: '다음 프로젝트', deadline: null, prepared: 0, total: 0, updatedAt: null },
  ] };
  await page.route('**/api/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const method = request.method();
    state.requests.push({ pathname, method, body: request.postData() ? request.postDataJSON() : null });
    if (pathname === '/api/submission-cases' && method === 'GET') {
      if (hold) await new Promise(resolve => { state.release = resolve; });
      if (state.listError) return route.fulfill({ status: 503, json: {} });
      return route.fulfill({ json: invalid ? {} : state.rows });
    }
    if (state.writeError) return route.fulfill({ status: 400, json: { message: '프로젝트 저장 실패' } });
    if (method === 'POST' && pathname === '/api/submission-cases') {
      state.created = { id: 73, ...request.postDataJSON() };
      return route.fulfill({ json: state.created });
    }
    if (method === 'GET' && /^\/api\/submission-cases\/\d+$/.test(pathname)) return route.fulfill({ json: state.rows.find(row => String(row.id) === pathname.split('/').at(-1)) || state.created });
    if (method === 'GET' && pathname.endsWith('/package')) return route.fulfill({ json: { selections: [] } });
    if (method === 'GET' && (pathname.endsWith('/requirements') || pathname.endsWith('/people') || pathname === '/api/submission-document-masters')) return route.fulfill({ json: [] });
    if (method === 'PUT' && pathname === '/api/submission-cases/71') {
      Object.assign(state.rows[0], request.postDataJSON());
      return route.fulfill({ json: state.rows[0] });
    }
    if (method === 'DELETE' && pathname === '/api/submission-cases/71') {
      state.rows = state.rows.filter(row => row.id !== 71);
      return route.fulfill({ status: 204, body: '' });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.route('**/submissions/?caseId=*', route => route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<h1>기존 프로젝트 상세</h1>' }));
  await page.goto('/react/index.html#/submissions');
  return state;
}

test('submissions: cards and persistent list preserve all summary fields', async ({ page }) => {
  const state = await setup(page);
  await expect(page.locator('.app-nav-link.active')).toHaveText('서류 모으기');
  const card = page.getByRole('link', { name: '제출 프로젝트 상세', exact: true });
  await expect(card).toContainText('마감일 2026-09-30');
  await expect(card).toContainText('준비 2 / 4');
  await expect(card).toContainText('진행률 50%');
  await expect(card).toContainText('2026. 09. 15.');
  await expect(page.getByRole('link', { name: '다음 프로젝트 상세', exact: true })).toContainText('수정일 미확인');
  await page.screenshot({ path: 'test-results/submissions-desktop-card.png', fullPage: true });
  await page.getByRole('button', { name: '리스트형' }).click();
  await expect(page.getByRole('progressbar', { name: '제출 프로젝트 준비율' })).toHaveAttribute('aria-valuenow', '50');
  await expect(page.locator('.project-list-table')).toContainText('발주기관');
  await page.screenshot({ path: 'test-results/submissions-desktop-list.png', fullPage: true });
  await page.reload();
  await expect(page.getByRole('button', { name: '리스트형' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.project-list-table')).toBeVisible();
  expect(state.requests.every(request => request.method === 'GET' && request.pathname === '/api/submission-cases')).toBe(true);
});

test('submissions: card and row open React detail and return to list', async ({ page }) => {
  await setup(page);
  await page.getByRole('link', { name: '제출 프로젝트 상세', exact: true }).click();
  await expect(page).toHaveURL(/#\/submissions\/71$/);
  await expect(page.locator('#case-project-name')).toHaveText('제출 프로젝트');
  await page.goBack();
  await page.getByRole('button', { name: '리스트형' }).click();
  await page.getByRole('link', { name: '제출 프로젝트 상세', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#\/submissions\/71$/);
});

test('submissions: create keeps existing POST payload and opens created detail', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('button', { name: '+ 새 프로젝트' }).click();
  const form = page.locator('#create-submission-project');
  await expect(form.getByLabel('프로젝트명')).toBeFocused();
  await form.getByLabel('프로젝트명').fill('새 프로젝트');
  await form.getByLabel('마감일').fill('2026-10-01');
  await form.getByRole('button', { name: '프로젝트 생성' }).click();
  await expect(page).toHaveURL(/#\/submissions\/73$/);
  await expect(page.locator('#case-project-name')).toHaveText('새 프로젝트');
  expect(state.requests.filter(request => request.method === 'POST')).toEqual([
    { pathname: '/api/submission-cases', method: 'POST', body: { projectName: '새 프로젝트', deadline: '2026-10-01' } },
  ]);
});

test('submissions: menu keyboard, edit and delete preserve list and require confirmation', async ({ page }) => {
  const state = await setup(page);
  for (const view of ['카드형', '리스트형']) {
    await page.getByRole('button', { name: view }).click();
    const toggle = page.getByRole('button', { name: '제출 프로젝트 메뉴' });
    await toggle.focus();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: '수정' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(toggle).toBeFocused();
    await expect(page).toHaveURL(/#\/submissions$/);
    await toggle.click();
    await page.getByRole('heading', { name: '프로젝트 목록', exact: true }).click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  }
  await page.getByRole('button', { name: '제출 프로젝트 메뉴' }).click();
  await page.getByRole('menuitem', { name: '수정' }).click();
  const dialog = page.getByRole('dialog', { name: '프로젝트 수정' });
  await dialog.getByLabel('프로젝트명').fill('변경 프로젝트');
  await dialog.getByLabel('마감일').fill('2026-10-02');
  await dialog.getByRole('button', { name: '프로젝트 저장' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('link', { name: '변경 프로젝트 상세' })).toContainText('2 / 4');
  expect(state.requests.find(request => request.method === 'PUT')).toEqual({ pathname: '/api/submission-cases/71', method: 'PUT', body: { projectName: '변경 프로젝트', deadline: '2026-10-02' } });
  await page.getByRole('button', { name: '변경 프로젝트 메뉴' }).click();
  page.once('dialog', dialog => dialog.dismiss());
  await page.getByRole('menuitem', { name: '삭제' }).click();
  expect(state.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);
  await page.getByRole('button', { name: '변경 프로젝트 메뉴' }).click();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('menuitem', { name: '삭제' }).click();
  await expect(page.getByRole('link', { name: '변경 프로젝트 상세' })).toHaveCount(0);
  expect(state.requests.filter(request => request.method === 'DELETE')).toHaveLength(1);
  await expect(page).toHaveURL(/#\/submissions$/);
});

test('submissions: loading, empty, failed and invalid list responses', async ({ page }) => {
  const state = await setup(page, { hold: true, empty: true });
  await expect(page.locator('#submission-project-message')).toHaveText('프로젝트 목록을 불러오는 중입니다.');
  await expect.poll(() => !!state.release).toBe(true);
  state.release();
  await expect(page.locator('#submission-project-message')).toHaveText('등록된 프로젝트가 없습니다.');
  await page.unrouteAll({ behavior: 'wait' });
  const failed = await setup(page, { listError: true });
  await expect(page.getByRole('alert')).toContainText('회사 DB');
  failed.listError = false;
  await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.locator('.submission-project-card')).toHaveCount(2);
  await page.unrouteAll({ behavior: 'wait' });
  await setup(page, { invalid: true });
  await expect(page.getByRole('alert')).toContainText('목록을 확인할 수 없습니다');
});

test('submissions: failed writes retain inputs and project, allow retry', async ({ page }) => {
  const state = await setup(page, { writeError: true });
  await page.getByRole('button', { name: '제출 프로젝트 메뉴' }).click();
  await page.getByRole('menuitem', { name: '수정' }).click();
  const dialog = page.getByRole('dialog', { name: '프로젝트 수정' });
  await dialog.getByLabel('프로젝트명').fill('저장할 이름');
  await dialog.getByRole('button', { name: '프로젝트 저장' }).click();
  await expect(dialog.getByRole('alert')).toHaveText('프로젝트 저장 실패');
  await expect(dialog.getByLabel('프로젝트명')).toHaveValue('저장할 이름');
  await dialog.getByRole('button', { name: '취소' }).click();
  await page.getByRole('button', { name: '제출 프로젝트 메뉴' }).click();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('menuitem', { name: '삭제' }).click();
  await expect(page.locator('#submission-project-message')).toHaveText('프로젝트 저장 실패');
  await expect(page.locator('.submission-project-card')).toHaveCount(2);
  await page.getByRole('button', { name: '+ 새 프로젝트' }).click();
  const form = page.locator('#create-submission-project');
  await form.getByLabel('프로젝트명').fill('새 이름');
  await form.getByLabel('마감일').fill('2026-10-01');
  await form.getByRole('button', { name: '프로젝트 생성' }).click();
  await expect(form.getByRole('alert')).toHaveText('프로젝트 저장 실패');
  state.writeError = false;
  await form.getByRole('button', { name: '프로젝트 생성' }).click();
  await expect(page).toHaveURL(/#\/submissions\/73$/);
});

test('submissions: mobile card, list and edit dialog stay inside viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await expect(page.locator('.submission-project-card')).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/submissions-mobile-card.png', fullPage: true });
  await page.getByRole('button', { name: '리스트형' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: '제출 프로젝트 메뉴' }).click();
  await expect(page.getByRole('menuitem', { name: '수정' })).toBeInViewport();
  await page.evaluate(() => document.querySelector('#submission-project-items').dispatchEvent(new Event('scroll')));
  await expect(page.getByRole('menuitem', { name: '수정' })).toBeInViewport();
  await page.getByRole('menuitem', { name: '수정' }).click();
  await expect(page.getByRole('button', { name: '프로젝트 저장' })).toBeInViewport();
  await page.screenshot({ path: 'test-results/submissions-mobile-edit.png', fullPage: true });
});
