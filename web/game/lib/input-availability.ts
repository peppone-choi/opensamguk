// 입력 가능 여부 어댑터 — 화면의 입력 단추(K3 InputAction)가 받는 한 행을 만든다. K4 · K5 · K6 · K7이 같이 쓴다.
//
// 정본 순서(K0 결정 2026-09-30):
//  (a) 서버 K6-01(`GET /api/inputs/availability`) 한 행 — 생기면 부른 쪽이 ctx.server로 넘긴다(그대로 돌려준다).
//  (b) 그 전엔 원장 전달 상태 스냅숏(input-delivery.generated.ts)으로 PLANNED를 「준비 중」(NOT_DELIVERED)으로 가린다.
//  (c) 입력별 *-options 응답의 available · code · reason을 BLOCKED로 합친다.
// 원장에 없는 입력은 null — 화면은 그 조작을 그리지 않는다. 사유를 지어내지 않는다(없으면 InputAction이 「사유를 받지 못했습니다」).
import { INPUT_DELIVERY, type DeliveryState } from './input-delivery.generated';

/**
 * K3 공용 부품 InputAvailability(web/shared/src/parts/types.ts — 계약판 K6-01 한 행)와 같은 모양.
 * K3 부품이 main에 들어오면 `import type { InputAvailability } from '@opensamguk/ui'`로 바꾼다.
 */
export interface InputAvailability {
    readonly inputId: string;
    readonly status: 'AVAILABLE' | 'BLOCKED' | 'NOT_DELIVERED';
    readonly code?: string;
    readonly reason?: string;
}

export interface OptionsVerdict {
    readonly available: boolean;
    readonly code?: string | null;
    readonly reason?: string | null;
}

export interface AvailabilityContext {
    /** (a) K6-01 한 행. 있으면 이것이 정본이다. */
    readonly server?: InputAvailability | null;
    /** (c) 그 입력의 *-options 응답(또는 같은 모양의 판정). 없으면 누를 수 있게 두고 서버가 제출 때 판정한다. */
    readonly options?: OptionsVerdict | null;
}

export function deliveryOf(inputId: string): DeliveryState | null {
    return Object.prototype.hasOwnProperty.call(INPUT_DELIVERY, inputId) ? INPUT_DELIVERY[inputId] : null;
}

export function availabilityOf(inputId: string, ctx: AvailabilityContext = {}): InputAvailability | null {
    const delivery = deliveryOf(inputId);
    if (delivery == null) return null;
    if (ctx.server && ctx.server.inputId === inputId) return ctx.server;
    if (delivery === 'PLANNED') return { inputId, status: 'NOT_DELIVERED' };
    const o = ctx.options;
    if (!o || o.available) return { inputId, status: 'AVAILABLE' };
    return {
        inputId,
        status: 'BLOCKED',
        ...(o.code ? { code: o.code } : {}),
        ...(o.reason ? { reason: o.reason } : {}),
    };
}
