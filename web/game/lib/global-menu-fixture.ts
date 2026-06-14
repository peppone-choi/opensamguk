// GlobalMenu.php v2 (production) default entries — spec §4 table.
// This is the typed static fixture used until the game-api GetGlobalMenu endpoint is live.
// GlobalMenu component prefers api.globalMenu(); on empty/failed payload it falls back to this,
// so swapping to the API is a pure data-source change. Korean labels + targets are VERBATIM.
//
// External .php / http targets keep newTab semantics. condHighlightVar names the global flag that
// drives the highlight; condShowVar gates visibility. (v2 truth; 게임정보 has a MenuLine.)

import type { MenuNode } from './menu-types';

export const GLOBAL_MENU_V2: MenuNode[] = [
    { type: 'item', name: '천통국 베팅', url: '/game/nation-betting', newTab: true, condHighlightVar: 'nationBetting' },
    {
        type: 'multi',
        name: '게임정보',
        subMenu: [
            { type: 'item', name: '세력일람', url: 'v_nationList.php' },
            { type: 'item', name: '장수일람', url: 'v_generalList.php' },
            { type: 'item', name: '명장일람', url: 'v_bestGeneral.php' },
            { type: 'line' },
            { type: 'item', name: '명예의전당', url: 'v_hallOfFame.php' },
            { type: 'item', name: '왕조일람', url: 'v_dynastyList.php' },
        ],
    },
    { type: 'item', name: '연감', url: 'v_history.php', newTab: true },
    {
        type: 'split',
        main: { type: 'item', name: '게시판', url: '/board/community', newTab: true },
        subMenu: [
            { type: 'item', name: '건의/제안', url: '/board/suggestion', newTab: true },
            { type: 'item', name: '팁/강좌', url: '/board/tip', newTab: true },
            { type: 'line' },
            { type: 'item', name: '패치 내역', url: '/board/patch', newTab: true },
        ],
    },
    {
        type: 'split',
        main: { type: 'item', name: '공식 오픈 톡', url: 'https://open.kakao.com/o/', newTab: true },
        subMenu: [{ type: 'item', name: '잡담 오픈 톡', url: 'https://open.kakao.com/o/', newTab: true }],
    },
    { type: 'item', name: '전투 시뮬레이터', url: 'battle_simulator.php', newTab: true },
    {
        type: 'multi',
        name: '기타 정보',
        subMenu: [
            { type: 'item', name: '접속량정보', url: 'v_trafficInfo.php' },
            { type: 'item', name: '빙의일람', url: 'v_npcList.php', condShowVar: 'npcMode' },
        ],
    },
    { type: 'item', name: '설문조사', url: 'v_vote.php', newTab: true, condHighlightVar: 'vote' },
];
