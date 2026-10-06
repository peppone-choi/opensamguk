// 이미지 자산 CDN 베이스 — opensamguk-images(jsDelivr 미러, devsam/image의 미러). 모든 이미지 자산
// (맵·포트레이트·3D 등) 참조의 단일 출처. 배포 시 NEXT_PUBLIC_IMAGE_CDN으로 덮어쓴다.
export const IMAGE_CDN_BASE =
    process.env.NEXT_PUBLIC_IMAGE_CDN ?? 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images';

// 도시 상태/성/수도 아이콘 경로 (IMAGE_CDN_BASE 하위 game — event{state}.gif / cast_{level}.gif / event51.gif).
// 로컬 public/icons/ 대신 CDN 단일 출처를 쓴다 — /game/ 서브패스에서 절대경로 /icons/가 게이트웨이 도메인으로
// 새는 문제를 제거하고, devsam/image(=ground truth) 미러와 동일한 전체 상태셋(event0~9,31~34,41~43,51)을 보장한다.
export const ICON_CDN = `${IMAGE_CDN_BASE}/game`;

// NAV_ITEMS(옛 사이드바·하단 탭)는 ADR-LITE-049 부서 메뉴(lib/dept-menu-config.ts MOBILE_TABS)로 대체돼 제거했다.
// 아무 데서도 쓰지 않던 상수 21개(API_BASE · MAP_CDN · JOIN_STAT_* · COLOR_* 등)는 D105 1단계(2026-10-05)에서 지웠다.
// ── FE 전역 상수 (GameConst Kotlin 동기) ──────────────────────────────────────

export const STAT_UP_THRESHOLD = 30;

/**
 * 능력치 상한 — 엔진 `DomesticHelpers.MAX_LEVEL`(logic/.../domestic/DomesticHelpers.kt:47)과 같은 값.
 * `StatChange` 가 통·무·지·정·매를 0..255 로 클램프한다. 능력 막대의 분모는 반드시 이 값이어야 한다 —
 * 100 으로 잡으면 100 을 넘는 장수(시나리오 실측 최대 156)가 전부 꽉 찬 막대로 뭉개진다.
 */
export const STAT_MAX_LEVEL = 255;

// game-api AdminWriteController.SERVER_STATUSES 와 같은 집합(서버 상태 변경 허용값).
export const SERVER_STATUSES = ['OPEN', 'PRE_OPEN', 'CLOSED'] as const;
export type ServerStatus = (typeof SERVER_STATUSES)[number];
