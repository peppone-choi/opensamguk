// Same-origin proxy base (see lib/api.ts). Kept for any direct fetch; all traffic flows through /api/game.
export const API_BASE = '/api/game';

// 이미지 자산 CDN 베이스 — opensamguk-images(jsDelivr 미러, devsam/image의 미러). 모든 이미지 자산
// (맵·포트레이트·3D 등) 참조의 단일 출처. 배포 시 NEXT_PUBLIC_IMAGE_CDN으로 덮어쓴다.
export const IMAGE_CDN_BASE =
    process.env.NEXT_PUBLIC_IMAGE_CDN ?? 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images';

// 게임 맵 타일/베이스 자산 경로 (IMAGE_CDN_BASE 하위 game/map).
export const MAP_CDN = `${IMAGE_CDN_BASE}/game/map`;

export const TYPE_LABEL: Record<string, string> = {
    buyRice: '쌀 구매',
    sellRice: '쌀 판매',
    uniqueItem: '유니크 아이템',
};

export const RES_LABEL: Record<string, string> = {
    gold: '금',
    rice: '쌀',
    inheritPoint: '유산 포인트',
};

export const DIPLOMACY_LABEL: Record<string, string> = {
    war: '전쟁',
    peace: '평화',
    neutral: '중립',
    ally: '동맹',
};

export const NAV_ITEMS = [
    { path: '/game', label: '내 정보', icon: '👤' },
    { path: '/game/generals', label: '장수', icon: '⚔️' },
    { path: '/game/city', label: '도시', icon: '🏰' },
    { path: '/game/diplomacy', label: '외교', icon: '📜' },
    { path: '/game/auction', label: '경매', icon: '🔨' },
    { path: '/game/betting', label: '내기', icon: '🎲' },
    { path: '/game/mailbox', label: '우편', icon: '✉️' },
    { path: '/game/tournament', label: '토너먼트', icon: '🏆' },
    { path: '/game/rankings', label: '랭킹', icon: '📊' },
    { path: '/game/simulator', label: '시뮬', icon: '🎮' },
];

// ── FE 전역 상수 (GameConst Kotlin 동기) ──────────────────────────────────────

/** 무기한 날짜 — legacy PHP '9999-12-31' (메시지/서신 유효기간 무기한 표시). */
export const INFINITE_DATE = '9999-12-31';

/** 토스트 자동 사라짐 시간(ms). */
export const TOAST_DURATION_MS = 3000;

/** 투표 항목 기본 색상 팔레트 — legacy css-color-names 7색 (red/orange/yellow/green/blue/navy/purple). */
export const VOTE_COLORS = [
    '#ff0000', // red
    '#ffa500', // orange
    '#ffff00', // yellow
    '#008000', // green
    '#0000ff', // blue
    '#000080', // navy
    '#800080', // purple
];

/** isBrightColor 판정 임계값 — legacy perceived-luminance (r*.299 + g*.587 + b*.114) > threshold. */
export const BRIGHT_COLOR_THRESHOLD = 140;

/** isBrightColor 대안 임계값 (history/page.tsx 등 일부 페이지에서 사용). */
export const BRIGHT_COLOR_THRESHOLD_ALT = 128;

/** 장수 등록 기본 능력치 총합. */
export const JOIN_STAT_TOTAL = 165;

/** 장수 등록 능력치 최소값. */
export const JOIN_STAT_MIN = 15;

/** 장수 등록 능력치 최대값. */
export const JOIN_STAT_MAX = 80;

/** 유산 버프 최대 레벨. */
export const INHERIT_BUFF_MAX_LEVEL = 5;

/** 유산 버프 구매 비용表 (누적 차액). */
export const INHERIT_COSTS = [0, 200, 600, 1200, 2000, 3000];

/** 댓글 최대 길이. */
export const COMMENT_MAX_LENGTH = 250;

/** 게시물 제목 최대 길이. */
export const ARTICLE_TITLE_MAX_LENGTH = 250;

/** 부대명/장수명 최대 길이. */
export const NAME_MAX_LENGTH = 18;

/** 투표 댓글 최대 길이. */
export const VOTE_COMMENT_MAX_LENGTH = 200;

/** 도시 게이지 — 민심 최대값. */
export const CITY_TRUST_MAX = 100;

/** 시세 게이지 — 기준값. */
export const CITY_TRADE_BASE = 95;

/** 시세 게이지 — 배율. */
export const CITY_TRADE_MULTIPLIER = 10;

/** SSE 엔드포인트 — 턴 완료 이벤트. */
export const SSE_TURN_ENDPOINT = '/api/game/sse/turn';

/** 토스트 z-index. */
export const TOAST_Z_INDEX = 200;

/** 기본 색상 상수. */
export const COLOR_BLACK = '#000000';
export const COLOR_WHITE = '#ffffff';
export const COLOR_DARK_GRAY = '#555555';

/** 토너먼트 매치 상태 → StatusBadge variant 매핑. */
export const TOURNAMENT_STATUS_VARIANT: Record<string, 'jade' | 'gold' | 'crimson' | 'muted'> = {
    PENDING: 'muted',
    ONGOING: 'gold',
    FINISHED: 'jade',
    CANCELLED: 'crimson',
};

/** 토너먼트 매치 상태 → 표시 한글 라벨. */
export const TOURNAMENT_STATUS_LABEL: Record<string, string> = {
    PENDING: '대기',
    ONGOING: '진행 중',
    FINISHED: '종료',
    CANCELLED: '취소',
};

/** 토너먼트 관리 기본 장수 ID (관리자 페이지 placeholder). */
export const DEFAULT_ADMIN_GENERAL_ID = 1;
