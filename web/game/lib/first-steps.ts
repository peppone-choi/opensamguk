// 첫걸음 8단계 — 설명만(사용자 결정 D21, 2026-10-01: 연습 월드 · 진행 기록 · 첫걸음 칩 없음, ADR-LITE-049 개정).
// 단계마다 무엇을 · 어디서 · 어떻게 + 그 화면 바로가기. 「어떻게」는 지금 화면에 실제로 있는 단추 · 칸 이름만 쓴다
// (2026-10-02 main 기준 확인: 게임 입구 · 장수 생성 대기(entry) · 출사(enlist, /join) · 작전실 명령 흐름(K6 #1125 — command-flow · turn-slots · lib/command-flow) · 조정
// 받은 요청(court · requests) · DomesticPanels · BattleHub, 게이트웨이 JoinScreen). 아직 없는 것은 지어내지 않고 `pending` 에 「준비 중」으로 적는다.
// 작전실 단계의 바로가기는 명령 흐름 주소(`?do=<입력>`)로 그 명령을 바로 연다.
import { JOIN_HREF } from './gatewayLinks';

// Explanation identifiers from the C7 v5 contract; these carry no progress state.
export type FirstStepsExplanationId =
    | 'tutorial.signup' | 'tutorial.createGeneral' | 'tutorial.enlist' | 'tutorial.dispatch'
    | 'tutorial.work' | 'tutorial.employ' | 'tutorial.march' | 'tutorial.battle';

export type FirstStepGo =
    /** 게임 안 화면 — 셸 주소 조각(`/game/<서버>/` 뒤, nav31 · campaign-screens 와 같은 값). */
    | { readonly kind: 'game'; readonly slug: string; readonly query?: string; readonly label: string }
    /** 게이트웨이(게임 앱 밖) — 전체 페이지로 연다. */
    | { readonly kind: 'gateway'; readonly href: string; readonly label: string };

export interface FirstStep {
    readonly explanationId: FirstStepsExplanationId;
    readonly key: 'register' | 'create' | 'enlist' | 'dispatch' | 'work' | 'employ' | 'march' | 'battle';
    readonly order: number;
    readonly name: string;
    /** 무엇을 */
    readonly what: string;
    /** 어디서 */
    readonly where: string;
    /** 어떻게 — 세 줄 이하 */
    readonly how: readonly string[];
    /** 아직 준비 중인 부분(있을 때만) */
    readonly pending?: string;
    readonly go: FirstStepGo;
}

export const FIRST_STEPS: readonly FirstStep[] = [
    {
        key: 'register', explanationId: 'tutorial.signup', order: 1, name: '가입',
        what: '계정을 만듭니다. 이미 계정이 있으면 건너뜁니다.',
        where: '로비 › 회원 가입',
        how: ['회원 가입 화면에서 계정명 · 비밀번호 · 별명을 적습니다.', '「회원가입」을 누르면 로그인된 채 게임 로비로 갑니다.'],
        go: { kind: 'gateway', href: JOIN_HREF, label: '회원 가입 화면으로' },
    },
    {
        key: 'create', explanationId: 'tutorial.createGeneral', order: 2, name: '장수 생성',
        what: '이 서버에서 쓸 내 장수를 만들거나, 역사 인물로 시작합니다.',
        where: '게임 입구 › 이 서버에서 시작한다',
        how: [
            '로비 서버 카드의 「장수 만들기」를 누르면 게임 입구 「이 서버에서 시작한다」가 열립니다.',
            '「내 장수를 만든다」(이름 · 본관 현 · 다섯 능력 · 주의 · 개성) 또는 「역사 인물로 시작」을 고릅니다.',
        ],
        pending: '장수 만들기는 아직 서버 준비 중입니다 — 지금은 화면만 볼 수 있습니다.',
        go: { kind: 'game', slug: 'create', label: '장수 생성 화면으로' },
    },
    {
        key: 'enlist', explanationId: 'tutorial.enlist', order: 3, name: '출사',
        what: '섬길 세력이나 장수를 골라 그 밑으로 들어갑니다. 아직 소속이 없는 장수가 합니다.',
        where: '출사 — 섬길 주공을 고른다',
        how: [
            '「출사 후보 묶음」에서 세력 · 장수 · 무작위 중 하나를 고릅니다.',
            '「섬길 주공」에서 한 명을 고릅니다.',
            '「출사 예약」을 누르면 다음 개인 턴에 출사합니다.',
        ],
        go: { kind: 'game', slug: 'join', label: '출사 화면으로' },
    },
    {
        key: 'dispatch', explanationId: 'tutorial.dispatch', order: 4, name: '발령',
        what: '주공이 보낸 발령에 답합니다.',
        where: '조정 › 받은 요청',
        how: [
            '조정 화면의 「받은 요청」에서 발령 카드를 봅니다. 휴대폰에서는 「조정 결정」 목록의 「받은 요청」을 누르면 열립니다.',
            '「수락」 또는 「거절」을 누릅니다. 기한까지 답하지 않으면 자동으로 수락됩니다.',
            '거절하면 충성과 명망이 줄어듭니다.',
        ],
        go: { kind: 'game', slug: 'court?tab=orders', label: '조정 화면으로' },
    },
    {
        key: 'work', explanationId: 'tutorial.work', order: 5, name: '공사',
        what: '현에서 공사를 시작합니다.',
        where: '영지 › 배치 · 방침 · 공사',
        how: [
            '「공사」 칸에서 그 현의 줄을 찾습니다.',
            '하고 싶은 공사(수리 · 둔전 · 시장수운 등) 단추를 누르면 바로 접수됩니다.',
            '공사는 순이 바뀔 때마다 조금씩 진행되고, 그 현 창고의 자원을 씁니다.',
        ],
        go: { kind: 'game', slug: 'territory', label: '영지 화면으로' },
    },
    {
        key: 'employ', explanationId: 'tutorial.employ', order: 6, name: '등용',
        what: '인물을 찾아 내 편으로 맞이합니다.',
        where: '작전실 › 이번 순에 할 일',
        how: [
            '바로가기로 「인재탐색」을 열고 「NN순에 예약」을 누릅니다. 고를 칸은 없습니다.',
            '찾은 인물이 있으면 「← 명령 목록」에서 「등용」을 열고, 「데려올 사람」을 골라 예약합니다.',
        ],
        pending: '포로를 설득해 등용하는 일은 아직 준비 중입니다.',
        go: { kind: 'game', slug: '', query: '?do=action.search', label: '작전실 · 인재탐색으로' },
    },
    {
        key: 'march', explanationId: 'tutorial.march', order: 7, name: '행군',
        what: '부대를 이끌고 다른 곳으로 갑니다.',
        where: '작전실 › 이번 순에 할 일',
        how: [
            '바로가기로 「출병」을 엽니다.',
            '「보낼 부곡」과 「어디로」를 고르고 「NN순에 예약」을 누릅니다. 「어디로」는 「지도에서 고르기」로 지도에서 골라도 됩니다.',
            '싸우지 않고 옮기기만 하려면 「← 명령 목록」에서 「이동」을 열고 「어디로」를 골라 예약합니다.',
        ],
        go: { kind: 'game', slug: '', query: '?do=action.deploy', label: '작전실 · 출병으로' },
    },
    {
        key: 'battle', explanationId: 'tutorial.battle', order: 8, name: '전투',
        what: '내 전투와 내가 없을 때의 대비를 확인합니다.',
        where: '군단 › 전투 · 부재 대비',
        how: ['「내 전투」에서 서버 준비 상태를 확인합니다.', '「부재 대비」에서 출전 군단과 직접 맡은 현의 방침을 확인합니다.'],
        pending: '서버가 아직 전투를 열지 않아 참가 대기 · 진행 중인 전투는 볼 수 없습니다.',
        go: { kind: 'game', slug: 'corps/battle', label: '전투 · 부재 대비로' },
    },
];
