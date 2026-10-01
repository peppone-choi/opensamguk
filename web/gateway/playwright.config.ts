import { defineConfig, devices } from '@playwright/test';

// 게이트웨이(로그인 · 가입 · 로비 · 계정 · 커뮤니티 · 운영 콘솔) e2e — web/game/playwright.config.ts 와 같은 두 프로젝트 규칙.
// - desktop: `@mobile-only` 만 뺀다. mobile: 390 × 844 터치 기기, `@both` · `@mobile-only` 만 돈다.
// 백엔드 없이 도는 spec(합성 자료는 page.route)은 e2e/smoke/ — CI web (gateway) 잡이 `next start` 뒤 두 프로젝트로 돌린다.
// 같은 흐름 도우미는 web/game/e2e/support/parity.ts 를 상대 경로로 가져온다(복사하지 않는다).
const MOBILE_390 = {
  browserName: 'chromium' as const,
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  userAgent: devices['Pixel 7'].userAgent,
};

// 로컬에서 번들 chromium 대신 설치된 Chrome 을 쓸 때만 E2E_BROWSER_CHANNEL=chrome. CI 는 비워 둔다(번들 chromium).
const channel = process.env.E2E_BROWSER_CHANNEL ? { channel: process.env.E2E_BROWSER_CHANNEL } : {};

export default defineConfig({
  testDir: './e2e',
  timeout: Number(process.env.E2E_TEST_TIMEOUT_MS ?? 120_000),
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: true,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list']],
  outputDir: process.env.E2E_PLAYWRIGHT_OUTPUT_DIR ?? 'test-results/playwright-output',
  use: {
    baseURL: process.env.E2E_GATEWAY_URL ?? 'http://127.0.0.1:3000',
    browserName: 'chromium',
    ...devices['Desktop Chrome'],
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    // 로컬은 영상을 끈다 — Playwright 1.52 의 ffmpeg(서명 없는 x86_64)를 macOS 26 이 죽인다. CI(리눅스)는 남긴다.
    video: process.env.CI ? 'retain-on-failure' : 'off',
  },
  projects: [
    { name: 'desktop', grepInvert: /@mobile-only/, use: channel },
    { name: 'mobile', grep: /@both|@mobile-only/, use: { ...MOBILE_390, ...channel } },
  ],
});
