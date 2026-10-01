// 첫걸음 8단계 — 설명만(사용자 결정 D21, 2026-10-01: 연습 월드 · 진행 기록 · 첫걸음 칩 없음, ADR-LITE-049 개정).
// 단계마다 무엇을 · 어디서 · 어떻게 + 그 화면 바로가기. 「어떻게」는 지금 화면에 실제로 있는 단추 · 칸 이름만 쓴다
// (2026-10-01 main 기준 확인: join/page.tsx · TurnList · CommandModal · EnlistmentForm · CourtForm · DomesticPanels · PeopleForm ·
// DeployForm · TravelForm · battle-center, 게이트웨이 JoinScreen). 아직 없는 것은 지어내지 않고 `pending` 에 「준비 중」으로 적는다.
import { JOIN_HREF } from './gatewayLinks';

export type FirstStepGo =
    /** 게임 안 화면 — 셸 주소 조각(`/game/<서버>/` 뒤, nav31 · campaign-screens 와 같은 값). */
    | { readonly kind: 'game'; readonly slug: string; readonly label: string }
    /** 게이트웨이(게임 앱 밖) — 전체 페이지로 연다. */
    | { readonly kind: 'gateway'; readonly href: string; readonly label: string };

export interface FirstStep {
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
        key: 'register', order: 1, name: '가입',
        what: '계정을 만듭니다. 이미 계정이 있으면 건너뜁니다.',
        where: '로비 › 회원 가입',
        how: ['회원 가입 화면에서 계정명 · 비밀번호 · 별명을 적습니다.', '「회원가입」을 누르면 로그인된 채 게임 로비로 갑니다.'],
        go: { kind: 'gateway', href: JOIN_HREF, label: '회원 가입 화면으로' },
    },
    {
        key: 'create', order: 2, name: '장수 생성',
        what: '이 서버에서 쓸 내 장수를 만듭니다.',
        where: '로비 › 서버 카드 「장수 만들기」 › 장수 생성',
        how: [
            '장수명과 성격을 정합니다.',
            '능력치 다섯(통솔 · 무력 · 지력 · 정치 · 매력)을 나눕니다. 「랜덤형」 같은 단추로 한 번에 채울 수도 있습니다.',
            '「장수 생성」을 누릅니다.',
        ],
        go: { kind: 'game', slug: 'join', label: '장수 생성 화면으로' },
    },
    {
        key: 'enlist', order: 3, name: '출사',
        what: '섬길 세력이나 장수를 골라 그 밑으로 들어갑니다.',
        where: '작전실 › 명령 목록',
        how: ['명령 목록의 빈 순에서 「+ 예약」을 누릅니다.', '「개인 행동」은 「출사」 그대로 두고 「출사 대상」을 고릅니다.', '「출사 예약」을 누르면 그 순에 처리됩니다.'],
        go: { kind: 'game', slug: '', label: '작전실로' },
    },
    {
        key: 'dispatch', order: 4, name: '발령',
        what: '주공이 보낸 발령에 답합니다.',
        where: '조정 › 발령 · 포상 · 조정 결정',
        how: [
            '「나에게 온 발령」 카드를 봅니다. 받은 발령이 없으면 「표시할 발령이 없습니다」가 나옵니다.',
            '「수락」 또는 「거절」을 누릅니다. 기한까지 답하지 않으면 자동으로 수락됩니다.',
            '거절하면 충성도와 명망이 줄어듭니다.',
        ],
        go: { kind: 'game', slug: 'court?tab=orders', label: '발령 화면으로' },
    },
    {
        key: 'work', order: 5, name: '공사',
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
        key: 'employ', order: 6, name: '등용',
        what: '인물을 찾아 내 편으로 맞이합니다.',
        where: '작전실 › 명령 목록',
        how: [
            '빈 순에서 「+ 예약」 → 「개인 행동」에서 「인재탐색」을 고르고 「인재탐색 예약」을 누릅니다.',
            '찾은 인물이 있으면 다음 빈 순에 「등용」을 고르고, 「대상 인물」을 정해 「등용 예약」을 누릅니다.',
        ],
        pending: '포로를 설득해 등용하는 일은 아직 준비 중입니다.',
        go: { kind: 'game', slug: '', label: '작전실로' },
    },
    {
        key: 'march', order: 7, name: '행군',
        what: '부대를 이끌고 다른 곳으로 갑니다.',
        where: '작전실 › 명령 목록',
        how: [
            '빈 순에서 「+ 예약」 → 「개인 행동」에서 「출병」을 고릅니다.',
            '「출병 부대」와 「출병 목적지」를 정하고 「출병 예약」을 누릅니다.',
            '싸우지 않고 옮기기만 하려면 「이동」을 골라 목적지를 정하고 「이동 예약」을 누릅니다.',
        ],
        go: { kind: 'game', slug: '', label: '작전실로' },
    },
    {
        key: 'battle', order: 8, name: '전투',
        what: '적과 맞붙은 전투의 결과를 봅니다.',
        where: '군단 › 전투(지금은 감찰부)',
        how: ['감찰부에서 전투 기록 · 전투 결과와 리플레이를 봅니다.'],
        pending: '실시간으로 전투에 참가하는 화면은 아직 준비 중입니다.',
        go: { kind: 'game', slug: 'battle-center', label: '감찰부로' },
    },
];
