import { defineConfig, devices } from '@playwright/test';
import { join } from 'node:path';

if (process.env.GITHUB_ACTIONS !== 'true' || process.env.RUNNER_ENVIRONMENT !== 'github-hosted') {
  throw new Error('QA160 live requires the explicit GitHub-hosted backend');
}
export default defineConfig({
  testDir: './e2e',
  testMatch: 'qa160-live.spec.ts',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 700_000,
  expect: { timeout: 30_000 },
  reporter: [['dot']],
  outputDir: join(process.env.RUNNER_TEMP!, 'opensamguk-qa160-owner/custody/playwright-output'),
  use: {
    baseURL: 'http://127.0.0.1:18300',
    browserName: 'chromium',
    headless: true,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    serviceWorkers: 'block',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
});
