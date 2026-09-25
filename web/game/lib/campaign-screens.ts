// 휘하(HWIHA) 화면 등록부 — 새 시대 화면의 단일 출처.
//
// 정본은 시안 아트보드 `docs/opensamguk/ui-new-screens-2026-09-18/project/<Name>.dc.html` 이고,
// 제목과 탭(`on`)은 그 시안에서 그대로 옮겼다. 라벨을 새로 만들지 않는다.
//
// 탭 여섯은 정본 설계 §4 「입력 여섯 가지」다. 시안 헤더의 탭 바가 곧 그 여섯이며, 화면이 어느
// 입력에 속하는지는 시안의 `head(title, on)` 두 번째 인자가 정한다. 시안이 탭을 지정하지 않은
// 화면(지도 레이어 등)은 맥락에서 들어가는 화면이므로 `tab: null` 로 둔다 — 없는 배정을
// 지어내면 그것이 스펙으로 굳는다.

import { resolveServerGamePath } from './serverGameUrl';

/** 정본 설계 §4 입력 여섯 가지. 시안 헤더 탭 바와 같은 순서·같은 라벨이다. */
export const CAMPAIGN_INPUT_TABS = [
    '장수 행동',
    '배치',
    '방침',
    '공사',
    '계책',
    '조정 결정',
] as const;

export type InputTab = (typeof CAMPAIGN_INPUT_TABS)[number];

export interface GameScreen {
    /** 시안 아트보드 파일 이름. 시안과 코드를 잇는 열쇠다. */
    readonly board: string;
    /** URL 조각. `/game/<서버>/hwiha/<slug>` — 기존 게임과 같은 인증 게이트·서버 선택을 쓴다. */
    readonly slug: string;
    /** 시안 제목 그대로. */
    readonly title: string;
    /** 속한 입력 탭. 시안이 지정하지 않았으면 null. */
    readonly tab: InputTab | null;
    /** 작전실 허브에서 바로 갈 수 있는 화면인지. */
    readonly onHub: boolean;
}

/** 작전실 — 허브. 다른 화면의 「← 작전실」이 여기로 돌아온다. */
export const CAMPAIGN_HUB_SLUG = 'war-room';

export const CAMPAIGN_SCREENS: readonly GameScreen[] = [
    { board: 'WarRoom', slug: 'war-room', title: '작전실', tab: null, onHub: false },
    { board: 'Command', slug: 'command', title: '이번 순에 할 일', tab: null, onHub: true },

    // 장수 행동
    { board: 'Yuedan', slug: 'yuedan', title: '월단평', tab: '장수 행동', onHub: true },
    { board: 'Reveal', slug: 'reveal', title: '조우 공개 · 격자 리플레이', tab: '장수 행동', onHub: false },

    // 배치
    { board: 'Posts', slug: 'posts', title: '배치 · 방침 · 공사', tab: '배치', onHub: true },
    { board: 'Main', slug: 'retinue', title: '휘하 편성', tab: '배치', onHub: true },
    { board: 'Supply', slug: 'supply', title: '보급망 · 창고', tab: '배치', onHub: true },

    // 방침
    { board: 'Commandery', slug: 'commandery', title: '군 내정 현황', tab: '방침', onHub: true },
    { board: 'County', slug: 'county', title: '현 내정 상세', tab: '방침', onHub: false },
    { board: 'Defense', slug: 'defense', title: '방어 대비', tab: '방침', onHub: false },
    { board: 'Plan', slug: 'plan', title: '전투 계획 봉인', tab: '방침', onHub: false },

    // 계책
    { board: 'Hand', slug: 'hand', title: '계책 덱', tab: '계책', onHub: true },

    // 조정 결정
    // 탭 첫 화면은 실제 결정이 있는 발령이다(조정은 아직 틀만 있다).
    { board: 'Orders', slug: 'orders', title: '발령 · 포상', tab: '조정 결정', onHub: true },
    { board: 'Court', slug: 'court', title: '조정 — 관직 · 외교 · 천도', tab: '조정 결정', onHub: true },
    { board: 'Unification', slug: 'unification', title: '천하 형세 — 통일 판정', tab: '조정 결정', onHub: false },

    // 맥락에서 들어가는 화면. 공성·포로는 시안이 탭을 켜 두었으므로(방침·장수 행동) 그대로 옮긴다.
    { board: 'Siege', slug: 'siege', title: '공성', tab: '방침', onHub: false },
    { board: 'Captives', slug: 'captives', title: '포로 · 등용', tab: '장수 행동', onHub: false },
    { board: 'CommandMap', slug: 'command-map', title: '옛 명령 → 새 자리', tab: null, onHub: false },
    { board: 'MapLayers', slug: 'map-layers', title: '천하 지도 — 레이어', tab: null, onHub: false },

    // 입장 흐름 — 작전실 밖이다.
    { board: 'Create', slug: 'create', title: '장수 생성', tab: null, onHub: false },
    { board: 'Join', slug: 'join', title: '난세 개막 — 서버 입장', tab: null, onHub: false },
];

/**
 * 휘하 화면 주소. 서버 식별자가 있으면 `/game/<서버>/hwiha/<slug>` 로 만든다 — 미들웨어가 그 경로를
 * `/game/hwiha/<slug>` 로 되쓰고 `sam_server` 쿠키를 심는다(기존 게임 링크와 같은 규칙).
 */
export function campaignHref(slug: string, serverId?: string): string {
    const child = `hwiha/${slug}`;
    return serverId ? resolveServerGamePath(undefined, serverId, '/game', child) : `/game/${child}`;
}

export function campaignScreenOf(slug: string): GameScreen | undefined {
    return CAMPAIGN_SCREENS.find((s) => s.slug === slug);
}

/**
 * 실제로 페이지가 있는 화면. 등록부는 시안 전체를 담지만 링크는 여기 있는 것만 건다 — 없는 화면으로
 * 가는 링크는 404 다. 페이지를 새로 만들면 여기에 더한다.
 */
export const CAMPAIGN_BUILT_SLUGS: ReadonlySet<string> = new Set([
    'war-room',
    'yuedan',
    'posts',
    'retinue',
    'supply',
    'hand',
    'orders',
    'court',
    'siege',
]);

export function isCampaignBuilt(slug: string): boolean {
    return CAMPAIGN_BUILT_SLUGS.has(slug);
}

/** 한 입력 탭에 속한 화면들 — 시안 순서를 지킨다. 아직 없는 화면도 포함한다. */
export function campaignScreensOfTab(tab: InputTab): readonly GameScreen[] {
    return CAMPAIGN_SCREENS.filter((s) => s.tab === tab);
}

/** 탭을 눌렀을 때 갈 첫 화면 — 페이지가 있는 것 가운데 첫째. 없으면 undefined. */
export function campaignTabLanding(tab: InputTab): GameScreen | undefined {
    return campaignScreensOfTab(tab).find((s) => isCampaignBuilt(s.slug));
}
