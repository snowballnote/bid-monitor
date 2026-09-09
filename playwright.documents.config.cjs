const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({ testDir: './src/test/playwright', testMatch: 'documents.spec.js', workers: 1, reporter: 'list', use: { baseURL: 'http://documents.test', browserName: 'chromium', channel: 'msedge', headless: true } });
