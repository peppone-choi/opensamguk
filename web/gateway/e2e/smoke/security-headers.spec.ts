// 보안 머리글 — 운영 빌드(next start) 응답에 next.config.mjs headers() 의 값이 실제로 붙는지(K3 2026-10-04, 원장 D90).
// 두 앱 next.config.mjs 의 SECURITY_HEADERS 와 같은 값이어야 한다. 화면과 무관해 데스크톱 프로젝트에서만 한 번 돈다.
import { expect, test } from '@playwright/test';

const EXPECTED: Readonly<Record<string, string>> = {
  'x-frame-options': 'DENY',
  'content-security-policy': "frame-ancestors 'none'",
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'permissions-policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
};

test('보안 머리글이 페이지 · 정적 파일 응답에 붙고 x-powered-by 는 없다', { tag: '@desktop-only' }, async ({ request }) => {
  for (const path of ['/privacy', '/favicon.ico']) {
    const res = await request.get(path);
    expect(res.status(), path).toBeLessThan(400);
    const headers = res.headers();
    for (const [key, value] of Object.entries(EXPECTED)) expect(headers[key], `${path} ${key}`).toBe(value);
    expect(headers['x-powered-by'], `${path} x-powered-by`).toBeUndefined();
  }
});
