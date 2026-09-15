import { createServer } from 'node:http';
import { test, expect } from '@playwright/test';

test('preview proxies unchanged API and legacy screen paths to Spring Boot origin', async ({ page }) => {
  const requests = [];
  const backend = createServer((request, response) => {
    requests.push({ url: request.url, method: request.method });
    if (request.url.startsWith('/api/')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end('[]');
    } else {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      response.end('<h1>기존 서류 관리</h1>');
    }
  });
  await new Promise((resolve, reject) => {
    backend.once('error', reject);
    backend.listen(18123, '127.0.0.1', resolve);
  });
  try {
    await page.goto('/react/index.html#/');
    await expect(page.locator('#submission-count')).toHaveText('0건');
    await expect(page.locator('#performance-count')).toHaveText('0건');
    await expect(page.locator('#bid-check-count')).toHaveText('0건');
    await expect(page.locator('#recent-notice-empty')).toBeVisible();
    expect(requests).toContainEqual({ method: 'GET', url: '/api/bids/target/qualification?allowedLicenseCodes=6146,1468' });
    expect(requests).toContainEqual({ method: 'GET', url: '/api/external-notices?piaRelated=true' });
    await page.getByRole('navigation').getByRole('link', { name: '서류 관리' }).click();
    await expect(page).toHaveURL(/\/documents\/$/);
    await expect(page.getByRole('heading', { name: '기존 서류 관리' })).toBeVisible();
    expect(requests).toContainEqual({ method: 'GET', url: '/documents/' });
    expect(requests.every(request => request.method === 'GET')).toBe(true);
  } finally {
    backend.closeAllConnections();
    await new Promise(resolve => backend.close(resolve));
  }
});
