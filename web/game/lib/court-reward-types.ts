// 상사 선택지 읽기(`GET /api/court/reward-options`, #789 · #892)의 응답 꼴. 검증은 lib/api/court-reward.ts 가 한다.
//
// 계약: docs/development/court-reward-options-read.md(백엔드 PR #1586). 모든 키는 null 까지 명시된다.
// 창고 금 · 차감량 · 잔액 · revision 은 큰 값도 정확한 십진 문자열로 온다 — Number 로 바꾸지 않는다.

/** 십진 정수 문자열(`0` 또는 앞자리 0 없는 양수). Number 로 바꾸지 않고 문자열 그대로 묶어 보인다. */
export type DecimalString = string;

export type RewardOptionsStatus = 'READY' | 'UNAVAILABLE' | 'WRONG_RULE_PROFILE' | 'UNSUPPORTED_WORLD_FORMAT';
export type RewardOptionsReason = 'WORLD_UNAVAILABLE' | 'WORLD_DATE_INVALID' | 'ARTIFACTS_UNAVAILABLE' | 'ROSTER_INVALID';
export type RewardQueueStatus = 'NONE' | 'QUEUED' | 'UNAVAILABLE';
export type RewardNoneReason = 'PAYER_LANDLESS' | 'LOCATION_FOREIGN' | 'LOCATION_NEUTRAL';
export type RewardFundingUnavailableReason =
    | 'RECIPIENT_MISSING' | 'LOCATION_UNKNOWN' | 'PAYER_NATION_MISSING' | 'WAREHOUSE_MALFORMED' | 'TOTAL_OVERFLOW';
/** 판정 우선순위 순서 그대로. 앞의 다섯은 효과 필드가 모두 null 이다. */
export type RewardVerdict =
    | 'INVALID_AMOUNT' | 'CARD_UNAVAILABLE' | 'NO_AMOUNT' | 'TOO_SMALL' | 'REWARD_OVER_CAP'
    | 'FUNDING_UNAVAILABLE' | 'INSUFFICIENT_STOCK' | 'COVERED_AT_SNAPSHOT';
export type RewardRejectedVerdict = Extract<RewardVerdict, 'INVALID_AMOUNT' | 'CARD_UNAVAILABLE' | 'NO_AMOUNT' | 'TOO_SMALL' | 'REWARD_OVER_CAP'>;
export type RewardUnchecked = 'QUEUE_ADMISSION' | 'REWARD_HISTORY' | 'CONCURRENT_DEBITS' | 'STATE_AFTER_SNAPSHOT';

export interface RewardSnapshot {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
}

/** 서버 규칙 값 — 화면은 이 값만 쓴다(프론트 사본 없음). */
export interface RewardRule {
    readonly moneyPerLoyalty: number;
    readonly maxLoyaltyGainPerReward: number;
    readonly minimumMoney: number;
    readonly inputMaximumMoney: number;
    readonly loyaltyCap: number;
}

/** 현재 장수의 대기 상사 진단 — 접수 승인이나 효력 보장이 아니다. */
export interface RewardQueued {
    readonly status: RewardQueueStatus;
    readonly retainerId: number | null;
    readonly money: number | null;
}

export type RewardFunding =
    | {
        readonly status: 'KNOWN'; readonly scope: 'NETWORK' | 'ISOLATED'; readonly noneReason: null;
        readonly unavailableReason: null; readonly usableMoney: DecimalString; readonly warehouseCount: number;
    }
    | {
        readonly status: 'KNOWN'; readonly scope: 'NONE'; readonly noneReason: RewardNoneReason;
        readonly unavailableReason: null; readonly usableMoney: '0'; readonly warehouseCount: 0;
    }
    | {
        readonly status: 'UNAVAILABLE'; readonly scope: null; readonly noneReason: null;
        readonly unavailableReason: RewardFundingUnavailableReason; readonly usableMoney: null; readonly warehouseCount: null;
    };

export interface RewardCard {
    /** 입력 `court.reward` 의 retainerId(카드 ID). 인물 장수 ID 가 아니다. */
    readonly retainerId: number;
    readonly recipientGeneralId: number;
    /** 받는 인물 행이 없으면 null — 다른 원천의 이름으로 채우지 않는다. */
    readonly name: string | null;
    readonly loyalty: number;
    readonly loyaltyRoom: number;
    readonly maximumMoney: number;
    readonly locationCityId: number | null;
    readonly funding: RewardFunding;
}

export interface RewardDebit {
    readonly cityId: number;
    readonly isCapital: boolean;
    readonly take: DecimalString;
    readonly balance: DecimalString;
    readonly revision: DecimalString;
}

interface RewardPreviewBase {
    readonly retainerId: number;
    readonly notChecked: readonly RewardUnchecked[];
}

export interface RewardPreviewRejected extends RewardPreviewBase {
    readonly verdict: RewardRejectedVerdict;
    readonly money: number | null;
    readonly loyaltyGain: null;
    readonly loyaltyAfter: null;
    readonly moneyWithoutGain: null;
    readonly usableMoney: null;
    readonly debitPlan: null;
}

interface RewardPreviewEffect extends RewardPreviewBase {
    readonly money: number;
    readonly loyaltyGain: number;
    readonly loyaltyAfter: number;
    readonly moneyWithoutGain: number;
}

export interface RewardPreviewFundingUnavailable extends RewardPreviewEffect {
    readonly verdict: 'FUNDING_UNAVAILABLE';
    readonly usableMoney: null;
    readonly debitPlan: null;
}

export interface RewardPreviewInsufficient extends RewardPreviewEffect {
    readonly verdict: 'INSUFFICIENT_STOCK';
    readonly usableMoney: DecimalString;
    readonly debitPlan: null;
}

export interface RewardPreviewCovered extends RewardPreviewEffect {
    readonly verdict: 'COVERED_AT_SNAPSHOT';
    readonly usableMoney: DecimalString;
    readonly debitPlan: readonly RewardDebit[];
}

export type RewardPreview = RewardPreviewRejected | RewardPreviewFundingUnavailable | RewardPreviewInsufficient | RewardPreviewCovered;

export interface RewardOptionsReady {
    readonly status: 'READY';
    readonly reason: null;
    readonly generalId: number;
    readonly snapshot: RewardSnapshot;
    readonly rule: RewardRule;
    readonly queued: RewardQueued;
    readonly cards: readonly RewardCard[];
    readonly preview: RewardPreview | null;
}

/** READY 가 아니면 snapshot · rule · queued · cards · preview 는 모두 null 이다. */
export interface RewardOptionsClosed {
    readonly status: Exclude<RewardOptionsStatus, 'READY'>;
    readonly reason: string;
    readonly generalId: number;
    readonly snapshot: null;
    readonly rule: null;
    readonly queued: null;
    readonly cards: null;
    readonly preview: null;
}

export type RewardOptions = RewardOptionsReady | RewardOptionsClosed;

/** 조회 하나 — money 는 화면이 정규화한 양의 정수만, 카드가 있을 때만 보낸다. */
export interface RewardOptionsQuery {
    readonly generalId: number;
    readonly retainerId: number | null;
    readonly money: number | null;
}

export type RewardReadFailure =
    | 'AUTH_REQUIRED' | 'BAD_REQUEST' | 'FORBIDDEN' | 'ADMISSION_NOT_PUBLIC' | 'ADMISSION_UNAVAILABLE'
    | 'HTTP' | 'NETWORK' | 'CONTRACT';

export type RewardOptionsRead =
    | { readonly ok: true; readonly options: RewardOptions }
    | { readonly ok: false; readonly failure: RewardReadFailure; readonly httpStatus: number | null };
