const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({ testDir: './src/test/playwright', testMatch: 'performance-paste.spec.js', workers: 1, reporter: 'list', use: { baseURL: 'http://performance.test', browserName: 'chromium', channel: 'msedge', headless: true } });
