// OPENSAM-41 — v2 클라이언트 코드의 production 번들 격리.
//
// OPENSAM-35(V2-0A)의 격리 판정은 "v2-lab은 server-only라 production 클라이언트
// 청크가 빈 스텁"이라는 전제 위에 서 있었다(__tests__/v2-lab-route.test.tsx §7.6 주석).
// G0-C의 Three.js scene은 필연적으로 'use client'다 — 그대로 두면 middleware matcher
// 밖인 `/_next/static/**`로 v2 코드가 200으로 새어 나가 그 판정이 깨진다.
//
// 그래서 전제를 복원한다: V2_ENABLED !== 'true' 빌드에서는 v2 클라이언트 진입점을
// 빈 스텁 모듈로 치환해 v2 코드가 애초에 번들 그래프에 들어가지 않게 한다.
// (middleware.ts는 다른 레인 소유라 건드리지 않는다 — 이건 빌드 측 보강이다.)
//
// 한계: webpack 빌드에만 적용된다. `next build --turbopack`을 쓰게 되면 동등한
// turbopack 규칙을 추가해야 한다 — 현재 package.json의 build는 plain `next build`다.

/** v2 클라이언트 진입점 import specifier 패턴. */
export const V2_CLIENT_REQUEST = /(^|[./])components\/v2\//;

/**
 * @param {string} request import specifier
 * @param {boolean} v2Enabled 이 빌드가 v2 canary인지
 * @returns {boolean} 스텁으로 치환해야 하는가
 */
export function shouldStubV2Client(request, v2Enabled) {
  if (v2Enabled) return false;
  return typeof request === 'string' && V2_CLIENT_REQUEST.test(request);
}

/**
 * webpack `normalModuleFactory.beforeResolve` 훅으로 치환하는 플러그인.
 * webpack을 직접 import하지 않으려고 클래스만 직접 만든다(Next가 컴파일한 webpack을
 * 끌어오면 버전 결합이 생긴다).
 * @param {string} stubPath 치환 대상 절대 경로
 * @param {boolean} v2Enabled
 */
export function createV2ClientStubPlugin(stubPath, v2Enabled) {
  return {
    apply(compiler) {
      compiler.hooks.normalModuleFactory.tap('V2ClientStub', (nmf) => {
        nmf.hooks.beforeResolve.tap('V2ClientStub', (resolveData) => {
          if (resolveData && shouldStubV2Client(resolveData.request, v2Enabled)) {
            resolveData.request = stubPath;
          }
        });
      });
    },
  };
}
