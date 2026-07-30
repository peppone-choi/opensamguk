import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: Number(process.env.E2E_TEST_TIMEOUT_MS ?? 120_000),
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: true,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: process.env.E2E_PLAYWRIGHT_JSON ?? 'test-results/results.json' }]],
  outputDir: process.env.E2E_PLAYWRIGHT_OUTPUT_DIR ?? 'test-results/playwright-output',
  use: {
    baseURL: process.env.E2E_GAME_URL ?? 'http://localhost:3001',
    browserName: 'chromium',
    ...devices['Desktop Chrome'],
    headless: true,
    ignoreHTTPSErrors: false,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
});
