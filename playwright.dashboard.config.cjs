const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
    testDir: './src/test/playwright', testMatch: 'dashboard.spec.js', workers: 1, reporter: 'list',
    use: { baseURL: 'http://dashboard.test', browserName: 'chromium', channel: 'msedge', headless: true }
});
