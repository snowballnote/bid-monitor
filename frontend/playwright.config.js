import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  workers: 1,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium', channel: 'msedge', headless: true },
  webServer: {
    command: 'npm run preview',
    url: 'http://127.0.0.1:4173/react/index.html',
    reuseExistingServer: false,
    env: { SPRING_BOOT_URL: 'http://127.0.0.1:18123' },
  },
});
