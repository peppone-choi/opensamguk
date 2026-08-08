import { notFound } from 'next/navigation';

/**
 * v2 프론트 네임스페이스 게이트 (OPENSAM-35 / 0A-a).
 *
 * `/game/v2-lab/**` 아래 모든 라우트는 이 레이아웃을 통과한다 — 게이트가 페이지마다가 아니라
 * 여기 한 곳에만 있으므로, 이후 추가되는 v2 페이지는 자동으로 같은 404 게이트 뒤에 놓인다.
 *
 * 값 해석은 백엔드보다 **의도적으로 더 엄격하다**. 백엔드 `@ConditionalOnProperty(havingValue = "true")`는
 * `equalsIgnoreCase` 비교라 `TRUE`·`True`에도 열리지만(실측: `V2SandboxConfigurationTest`
 * `property value is matched case-insensitively`), 여기 프론트는 `=== 'true'` strict라 닫힌다.
 * 맞추지 않는 이유: 백엔드는 `@Profile("v2-sandbox")`가 2차 조건으로 함께 걸려 production에서 `V2_ENABLED=TRUE`
 * 오타 하나로는 열리지 않는다. 프론트에는 그 2차 조건이 없어, 느슨하게 맞추는 쪽이 실제 노출을 만든다.
 * (docs/loops/opensam-35-v2-0a-2026-08-08/s2-conditional-bean-gate.md)
 *
 * `NEXT_PUBLIC_` 접두사를 쓰지 않는다: 그 값은 클라이언트 번들에 인라인되어 브라우저에 노출되고,
 * 게이트가 서버 판정이 아니라 공개 설정이 되어버린다. 서버 전용 `process.env` 참조는
 * `lib/server-api.ts`·`middleware.ts`(SERVER_ID)와 같은 리포 기존 관례다.
 *
 * 리다이렉트·빈 페이지·"준비 중" 안내가 아니라 진짜 404 — `notFound()`가 Next의 404 응답을 낸다.
 */
export default function V2LabLayout({ children }: { children: React.ReactNode }) {
    if (process.env.V2_ENABLED !== 'true') {
        notFound();
    }
    return <>{children}</>;
}
