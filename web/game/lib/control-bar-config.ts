export type GateBucket =
    | 'always'
    | 'myLevel' // myLevel >= 1
    | 'myLevelAndNation' // myLevel >= 1 && nationLevel >= 1
    | 'permission2' // permission >= 2
    | 'showSecret'; // showSecret === true

export interface ControlSubItem {
    label: string; // compact label (dropdown sub-item)
    href: string;
    newTab?: boolean;
}

export interface ControlButton {
    id: number; // 1..20, spec order
    label: string; // bar label (spaced Hangul)
    compactLabel: string; // dropdown label (compact)
    href: string; // F2 App-Router destination
    bucket: GateBucket;
    newTab?: boolean;
    // highlight = btn-sammo-base2 highlight when this front-info flag is truthy
    highlightVar?: 'isTournamentApplicationOpen' | 'isBettingActive';
    // 경매장 split sub-dropdown
    split?: ControlSubItem[];
}

export const CONTROL_BUTTONS: ControlButton[] = [
    { id: 1, label: '회 의 실', compactLabel: '회의실', href: '/game/board', bucket: 'myLevel' },
    { id: 2, label: '기 밀 실', compactLabel: '기밀실', href: '/game/board?secret=1', bucket: 'permission2' },
    { id: 3, label: '부대 편성', compactLabel: '부대 편성', href: '/game/troop', bucket: 'myLevelAndNation' },
    { id: 4, label: '외 교 부', compactLabel: '외교부', href: '/game/diplomacy', bucket: 'showSecret' },
    { id: 5, label: '인 사 부', compactLabel: '인사부', href: '/game/my-boss', bucket: 'myLevel' },
    { id: 6, label: '내 무 부', compactLabel: '내무부', href: '/game/nation-finance', bucket: 'showSecret' },
    { id: 7, label: '사 령 부', compactLabel: '사령부', href: '/game/chief-center', bucket: 'showSecret' },
    { id: 8, label: 'NPC 정책', compactLabel: 'NPC 정책', href: '/game/npc-control', bucket: 'showSecret' },
    { id: 9, label: '암 행 부', compactLabel: '암행부', href: '/game/generals?secret=1', bucket: 'showSecret', newTab: true },
    {
        id: 10,
        label: '토 너 먼 트',
        compactLabel: '토너먼트',
        href: '/game/tournament',
        bucket: 'always',
        newTab: true,
        highlightVar: 'isTournamentApplicationOpen',
    },
    { id: 11, label: '세력 정보', compactLabel: '세력 정보', href: '/game/my-nation', bucket: 'myLevel' },
    { id: 12, label: '세력 도시', compactLabel: '세력도시', href: '/game/my-cities', bucket: 'myLevelAndNation' },
    { id: 13, label: '세력 장수', compactLabel: '세력 장수', href: '/game/my-generals', bucket: 'myLevel' },
    { id: 14, label: '중원 정보', compactLabel: '중원 정보', href: '/game/global-diplomacy', bucket: 'always' },
    { id: 15, label: '현재 도시', compactLabel: '현재 도시', href: '/game/city', bucket: 'always' },
    { id: 16, label: '감 찰 부', compactLabel: '감찰부', href: '/game/battle-center', bucket: 'showSecret', newTab: true },
    { id: 17, label: '유산 관리', compactLabel: '유산 관리', href: '/game/inherit', bucket: 'always' },
    { id: 18, label: '내 정보&설정', compactLabel: '내 정보&설정', href: '/game/my', bucket: 'always' },
    {
        id: 19,
        label: '경 매 장',
        compactLabel: '금/쌀 경매장',
        href: '/game/auction',
        bucket: 'always',
        newTab: true,
        split: [
            { label: '금/쌀 경매장', href: '/game/auction', newTab: true },
            { label: '유니크 경매장', href: '/game/auction?type=unique', newTab: true },
        ],
    },
    {
        id: 20,
        label: '베 팅 장',
        compactLabel: '베팅장',
        href: '/game/betting',
        bucket: 'always',
        newTab: true,
        highlightVar: 'isBettingActive',
    },
];
