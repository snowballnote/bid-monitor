const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({ testDir: './src/test/playwright', testMatch: ['personnel.spec.js', 'submissions.spec.js'], workers: 1, reporter: 'list', use: { baseURL: 'http://submissions.test', browserName: 'chromium', channel: 'msedge', headless: true, timezoneId: 'Asia/Seoul' } });
