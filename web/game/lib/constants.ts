// Same-origin proxy base (see lib/api.ts). Kept for any direct fetch; all traffic flows through /api/game.
export const API_BASE = '/api/game';

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
