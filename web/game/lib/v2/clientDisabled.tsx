'use client';

// v2 클라이언트 진입점의 production 스텁 (lib/v2/buildIsolation.mjs 참조).
// V2_ENABLED !== 'true' 빌드에서 v2 컴포넌트 import가 전부 이리로 치환된다.
// 이 파일 자체에는 v2 로직이 없다 — 새어 나가도 잃을 것이 없는 것이 요점이다.

export default function V2Disabled() {
  return null;
}

export const SpaceProof3D = V2Disabled;
export const SpaceFallbackTable = V2Disabled;
