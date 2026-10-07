// 명령 흐름(P-W02) 명령 표 — 직접 행동(GENERAL_ACTION) 43개.
//
// 정본은 입력 원장 `data/commands/input-catalog.json`이다. 서버 목록 API(`GET /api/inputs`, 계약판 K6-01)가
// 생기기 전까지 화면은 이 표로 목록을 그린다. 원장과 어긋나면 `__tests__/command-flow.catalog.test.ts`가 깨진다
// (행 수 · 전달 상태 · 표시 이름). 분류 · 한 줄 효과 · 인자 종류는 화면 사전이다(K6 설계서 §2.1).
//
// 이름: 원장 displayName을 쓴다. 2026-09-30 사용자 승인으로 바꾼 두 이름(쌀 사고팔기 · 병종 바꿔 익히기)만 예외다 —
// 원장은 C1이 고친다. 대조표는 __tests__/command-flow.catalog.test.ts(옛 이름은 쓰지 않는 말이라 화면 코드에 두지 않는다).
import { matchesQuery } from '../chosung';
import { INPUT_DELIVERY, type DeliveryState } from '../input-delivery.generated';

export type FlowCategory = '내정' | '군사' | '이동' | '인물' | '개인' | '나라' | '물자';
export const FLOW_CATEGORIES: readonly ('전체' | FlowCategory)[] = ['전체', '내정', '군사', '이동', '인물', '개인', '나라', '물자'];

/** 원장 전달 상태. PLANNED = 처리기 없음(화면은 「준비 중」). 값은 원장 스냅숏(input-delivery.generated.ts)에서 온다. */
export type { DeliveryState };

/**
 * 인자 종류 — 같은 종류는 명령을 바꿔도 이어받는다(설계서 §2.1 상태 유지 규칙).
 * province = 구역(지도 대상 고르기) · commandery = 군 · county = 현 · person = 사람 고르기 ·
 * units = 부대 고르기 · choice = 서버가 준 선택지 · resource · amount = 자원 · 수량.
 */
export type ArgKind = 'province' | 'commandery' | 'county' | 'person' | 'units' | 'choice' | 'resource' | 'amount';

export interface FlowCommand {
    readonly inputId: string;
    readonly name: string;
    readonly category: FlowCategory;
    /** 한 줄 효과 — 목록 행 둘째 줄. */
    readonly blurb: string;
    readonly args: readonly ArgKind[];
    readonly delivery: DeliveryState;
    /** 찾기 별칭 — 옛 이름 · 흔한 말. 원장에 삼모 역참조를 넣지 않으므로 화면 사전으로 둔다. */
    readonly aliases?: readonly string[];
}

const C = (
    inputId: string, name: string, category: FlowCategory, blurb: string, args: readonly ArgKind[],
    aliases?: readonly string[],
): FlowCommand => ({ inputId, name, category, blurb, args, delivery: INPUT_DELIVERY[inputId], aliases });

export const FLOW_COMMANDS: readonly FlowCommand[] = [
    // 내정 8 — 지금 서 있는 현에 한 번 크게
    C('action.farm', '농지개간', '내정', '이 현의 농지를 넓힌다', []),
    C('action.commerce', '상업투자', '내정', '이 현의 상업을 키운다', []),
    C('action.fortify', '수비강화', '내정', '성의 수비를 다진다', []),
    C('action.repairWall', '성벽보수', '내정', '성벽을 고친다', []),
    C('action.security', '치안강화', '내정', '치안을 바로잡는다', []),
    C('action.settle', '정착장려', '내정', '떠도는 백성을 이 현에 붙든다', []),
    C('action.selectResidents', '주민선정', '내정', '민심을 다독인다', []),
    C('action.tour', '순행', '내정', '관할 현을 돌아본다', []),
    // 군사 11
    C('action.conscript', '징병', '군사', '성 안의 호구로 병사를 모은다', [], ['현 소집']),
    C('action.raiseVolunteers', '모병', '군사', '내 금 · 쌀로 병사를 모은다', [], ['부곡 편성']),
    C('action.train', '훈련', '군사', '내 부대의 훈련을 올린다', []),
    C('action.boostMorale', '사기진작', '군사', '내 부대의 사기를 올린다', []),
    C('action.demobilize', '소집해제', '군사', '병사를 호구로 돌려보낸다', [], ['부대 해산']),
    C('action.muster', '집합', '군사', '흩어진 부곡을 지금 구역으로 모은다', [], ['군단 집결']),
    C('action.deploy', '출병', '군사', '부대를 이끌고 나간다', ['units', 'province']),
    C('action.scout', '첩보', '군사', '이웃 군을 살핀다', ['commandery']),
    C('action.assault', '강공', '군사', '포위 중인 성을 친다', ['county']),
    C('action.demandSurrender', '항복 권고', '군사', '포위 중인 성에 항복을 권한다', []),
    C('action.siegeRoadFort', '보루 포위', '군사', '길목의 적 보루를 에워싼다', ['choice']),
    // 이동 3
    C('action.move', '이동', '이동', '다른 구역으로 옮긴다', ['province']),
    C('action.forcedMarch', '강행', '이동', '빨리 가되 지친다', ['province']),
    C('action.return', '귀환', '이동', '귀환 성이 있는 구역으로 돌아간다', []),
    // 인물 3
    C('action.search', '인재탐색', '인물', '이 현의 재야 인물을 찾는다', [], ['탐방']),
    C('action.employ', '등용', '인물', '찾아낸 인물을 부로 들인다', ['person']),
    C('action.persuadeCaptive', '포로 설득', '인물', '잡은 포로를 설득한다', ['person']),
    // 개인 5
    C('action.travel', '견문', '개인', '견문을 넓힌다', [], ['유력']),
    C('action.selfTrain', '단련', '개인', '한 능력을 단련한다', ['choice']),
    C('action.recuperate', '요양', '개인', '부상과 피로를 회복한다', []),
    C('action.retire', '은퇴', '개인', '물러나고 부를 승계한다', ['person']),
    C('action.convertProficiency', '병종 바꿔 익히기', '개인', '부곡의 병종을 바꿔 익힌다', ['choice']),
    // 나라 8
    C('action.enlist', '출사', '나라', '섬길 주공을 정한다', ['choice'], ['임관']),
    C('action.resign', '하야', '나라', '섬기던 주공을 떠난다', []),
    C('action.rise', '거병', '나라', '무주 현에서 일어선다', []),
    C('action.independence', '독립', '나라', '섬기던 세력에서 나온다', []),
    C('action.foundState', '건국', '나라', '세력을 나라로 선포한다', []),
    C('action.abdicate', '선양', '나라', '주공 자리를 넘긴다', ['person']),
    C('action.oath', '결의', '나라', '같은 구역의 장수와 결의한다', ['person']),
    C('action.dissolve', '세력 해산', '나라', '세력을 흩는다', []),
    // 물자 5
    C('action.gift', '증여', '물자', '내 금 · 쌀을 다른 장수에게 준다', ['person', 'resource', 'amount']),
    C('action.donate', '헌납', '물자', '내 금 · 쌀을 나라에 바친다', ['resource', 'amount']),
    C('action.tradeGrain', '쌀 사고팔기', '물자', '금과 쌀을 바꾼다', ['choice']),
    C('action.tradeEquipment', '장비매매', '물자', '보물을 사고판다', ['choice']),
    C('action.transport', '물자조달', '물자', '이웃 현으로 물자를 나른다', ['choice', 'amount']),
];

const BY_ID = new Map(FLOW_COMMANDS.map(c => [c.inputId, c]));

export function flowCommand(inputId: string | null | undefined): FlowCommand | undefined {
    return inputId ? BY_ID.get(inputId) : undefined;
}

/** 분류 탭 · 찾기로 거른 목록. 찾기는 이름 · 초성 · 별칭을 본다. 순서는 표 순서 그대로. */
export function filterCommands(category: '전체' | FlowCategory, query: string): FlowCommand[] {
    const q = query.trim();
    return FLOW_COMMANDS.filter(c => (category === '전체' || c.category === category) && (q === ''
        || matchesQuery(c.name, q)
        || (c.aliases ?? []).some(a => matchesQuery(a, q))));
}

/**
 * 받은 대상(장소 · 사람)을 받는 명령을 위로 올린다 — 지도 카드 「여기로 명령」 · 인물 카드 「이 사람에게」로 열 때.
 * 다른 명령은 숨기지 않고 뒤에 둔다(설계서 §2.1).
 */
export function orderForPlace(list: readonly FlowCommand[], kind: ArgKind): FlowCommand[] {
    const takes = (c: FlowCommand) => c.args.includes(kind);
    return [...list.filter(takes), ...list.filter(c => !takes(c))];
}
