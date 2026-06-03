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
