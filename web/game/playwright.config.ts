import { defineConfig, devices } from '@playwright/test';

// 데스크톱 · 모바일은 같은 게임이다(v3.1 디자인 시스템). 같은 흐름을 두 프로젝트로 돌린다.
// - desktop: 지금까지의 모든 spec(태그 없음)을 그대로 돈다. `@mobile-only` 만 뺀다.
// - mobile : 390 × 844 터치 기기. `@both`(두 프로필 같은 흐름) · `@mobile-only` 가 붙은 spec 만 돈다.
//   태그 없는 옛 spec 은 모바일에서 돌지 않으므로 기존 게이트(유주 증거 등)의 결과 수가 바뀌지 않는다.
// 새 화면 spec 은 `test('…', { tag: ['@both'] }, …)` 로 쓰고 e2e/support/parity.ts 의 도우미를 쓴다.
// 백엔드 없이 도는 spec(합성 자료는 page.route)은 e2e/smoke/ — CI web (game) 잡이 두 프로젝트로 돌린다.
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
    // 로컬은 영상을 끈다 — Playwright 1.52 의 ffmpeg(서명 없는 x86_64)를 macOS 26 이 죽여(exit 137 · spawn -88) 모든 시험이
    // 시작부터 실패한다. 다시 받아도 같은 파일이다(2026-10-01 확인). CI(리눅스)는 실패 영상을 남긴다.
    video: process.env.CI ? 'retain-on-failure' : 'off',
  },
  projects: [
    { name: 'desktop', grepInvert: /@mobile-only/, use: channel },
    { name: 'mobile', grep: /@both|@mobile-only/, use: { ...MOBILE_390, ...channel } },
  ],
});
