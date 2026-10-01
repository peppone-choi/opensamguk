// 첫걸음 8단계 — 계약 목표 id · 순서(docs/development/fixtures/help-tutorial/tutorial-progress-start.json).
// 달성 기준(2026-09-30 사용자 승인): 첫 발령 = 수락 확정, 첫 등용 = 판정 결과 수신(성공 · 저항), 첫 행군 = 출병 · 이동,
// 첫 전투 = 전투 결과 수신. 판정은 서버가 한다 — 화면은 진척 응답만 믿는다.
//
// 화면이 정하는 것은 「어디서」(경로 · 앵커)뿐이다. 목표 문장은 튜토리얼 도움말 주제(계약판 K7-07)가 오면 그 글을 쓰고,
// 그 전에는 아래 초안을 「초안」으로 보인다.

export interface TutorialStep {
    readonly id: string;
    readonly order: number;
    readonly name: string;
    /** 목표 문장 초안(주제 글이 오면 대체). */
    readonly goalDraft: string;
    /** 어디서 — 화면 이름. */
    readonly where: string;
    /** 서버 기준 경로(v3.1 메뉴 NAV31, `/game/<서버>/` 뒤). 가입은 게이트웨이라 없다. 셸 라우팅(K3)이 앞을 붙인다. */
    readonly path: string | null;
    /** 목표 표시 대상. 입력은 data-input-id, 입력이 아닌 대상은 data-guide. */
    readonly anchors: readonly string[];
    /** 어떻게 — 세 줄 이하. */
    readonly how: readonly string[];
}

export const TUTORIAL_STEPS: readonly TutorialStep[] = [
    { id: 'tutorial.register', order: 1, name: '가입', goalDraft: '계정을 만듭니다.', where: '게이트웨이 가입', path: null, anchors: [], how: [] },
    {
        id: 'tutorial.createGeneral', order: 2, name: '장수 생성',
        goalDraft: '이름 · 본관 현 · 다섯 능력 · 주의 · 개성을 정해 내 장수를 만듭니다. 역사 인물을 골라도 됩니다.',
        where: '입장 › 장수 만들기', path: 'create', anchors: ['[data-guide="tutorial.createGeneral"]'],
        how: ['이름과 본관 현을 고릅니다.', '다섯 능력 · 주의 · 개성을 정합니다.', '「만들고 들어가기」를 누릅니다.'],
    },
    {
        id: 'tutorial.enlist', order: 3, name: '첫 출사', goalDraft: '섬길 주공을 골라 출사합니다.',
        where: '입장 › 출사', path: 'join', anchors: ['[data-input-id="action.enlist"]'],
        how: ['주공 목록을 봅니다.', '주공 카드에서 「출사」를 누릅니다.'],
    },
    {
        id: 'tutorial.dispatch', order: 4, name: '첫 발령', goalDraft: '주공이 보낸 발령에 답합니다.',
        where: '조정 › 발령 응답', path: 'court', anchors: ['[data-input-id="court.dispatchReply"]'],
        how: ['조정 › 발령 응답을 엽니다.', '발령 내용(자리 · 기한)을 봅니다.', '「수락」을 누릅니다.'],
    },
    {
        id: 'tutorial.work', order: 5, name: '첫 공사', goalDraft: '맡은 현에서 공사를 시작합니다.',
        where: '영지 › 공사', path: 'territory', anchors: ['[data-input-id="work.start"]'],
        how: ['영지 › 공사를 엽니다.', '공사를 고르고 시작합니다.'],
    },
    {
        id: 'tutorial.employ', order: 6, name: '첫 등용', goalDraft: '인재탐색으로 재야 인물을 찾고, 찾은 인물을 등용합니다.',
        where: '작전실 › 이번 순에 할 일', path: '', anchors: ['[data-input-id="action.search"]', '[data-input-id="action.employ"]'],
        how: ['이번 순에 할 일 › 인재탐색을 예약합니다.', '찾은 인물을 다음 빈 순에 등용합니다.'],
    },
    {
        id: 'tutorial.march', order: 7, name: '첫 행군', goalDraft: '지도에서 목적지를 골라 출병합니다. 이동도 됩니다.',
        where: '작전실 › 이번 순에 할 일 · 지도', path: '', anchors: ['[data-input-id="action.deploy"]', '[data-input-id="action.move"]'],
        how: ['이번 순에 할 일 › 출병(또는 이동)을 고릅니다.', '「지도에서 고르기」로 목적지를 누릅니다.', '예약합니다.'],
    },
    {
        id: 'tutorial.battle', order: 8, name: '첫 전투', goalDraft: '적과 맞붙어 전투 결과를 받습니다. 이기든 지든 됩니다.',
        where: '전투 알림 › 실시간 전투', path: 'corps/battle', anchors: ['[data-guide="tutorial.battle"]'],
        how: ['전투 알림에서 「참가」를 누릅니다.', '자리를 비우면 AI가 대신 싸웁니다.'],
    },
];

export function stepById(id: string): TutorialStep | undefined {
    return TUTORIAL_STEPS.find((s) => s.id === id);
}
