const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
    testDir: "./src/test/playwright",
    timeout: 30_000,
    fullyParallel: false,
    workers: 1,
    reporter: [["list"], ["html", { open: "never" }]],
    use: {
        baseURL: "http://127.0.0.1:4173",
        browserName: "chromium",
        channel: "msedge",
        headless: true,
        trace: "retain-on-failure",
        screenshot: "only-on-failure"
    },
    webServer: {
        command: ".\\mvnw.cmd spring-boot:run",
        url: "http://127.0.0.1:4173/",
        timeout: 120_000,
        reuseExistingServer: false,
        env: {
            ...process.env,
            SERVER_PORT: "4173",
            G2B_SERVICE_KEY: "playwright-test-key",
            SPRING_DATASOURCE_URL: "jdbc:h2:mem:playwright;DB_CLOSE_DELAY=-1",
            EXTERNAL_NOTICE_SCHEDULER_ENABLED: "false"
        }
    }
});
