// 입력 가능 여부 어댑터 — 화면의 입력 단추(K3 InputAction)가 받는 한 행을 만든다. K4 · K5 · K6 · K7이 같이 쓴다.
//
// 정본 순서(K0 결정 2026-09-30):
//  (a) 서버 K6-01(`GET /api/inputs/availability`) 한 행 — 생기면 부른 쪽이 ctx.server로 넘긴다(그대로 돌려준다).
//  (b) 그 전엔 원장 전달 상태 스냅숏(input-delivery.generated.ts)으로 PLANNED를 「준비 중」(NOT_DELIVERED)으로 가린다.
//  (c) 입력별 *-options 응답의 available · code · reason을 BLOCKED로 합친다.
//      옵션을 모르면(읽기가 없음) · 읽는 중이면 AVAILABLE — 누를 수 있게 두고 제출 때 서버가 판정한다.
//      「모름」을 막힘으로 그리면 사유를 지어내게 된다(K0 확인 2026-09-30). 읽는 중 로딩은 부른 쪽 패널이 보인다.
//  (d) 제출이 서버에서 거절됐으면 그 code · reason으로 BLOCKED(사유 시트에 그대로). 다시 고치면 부른 쪽이 지운다.
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
    /**
     * (c) 그 입력의 *-options 응답(또는 같은 모양의 판정).
     * null · 없음 = 옵션 읽기가 없다, 'loading' = 읽는 중 — 둘 다 AVAILABLE(부른 쪽이 읽는 중 로딩을 보인다).
     */
    readonly options?: OptionsVerdict | 'loading' | null;
    /** (d) 마지막 제출을 서버가 거절했다 — 서버가 준 code · reason 그대로. */
    readonly rejected?: { readonly code?: string | null; readonly reason?: string | null } | null;
}

export function deliveryOf(inputId: string): DeliveryState | null {
    return Object.prototype.hasOwnProperty.call(INPUT_DELIVERY, inputId) ? INPUT_DELIVERY[inputId] : null;
}

export function availabilityOf(inputId: string, ctx: AvailabilityContext = {}): InputAvailability | null {
    const delivery = deliveryOf(inputId);
    if (delivery == null) return null;
    if (ctx.server && ctx.server.inputId === inputId) return ctx.server;
    if (delivery === 'PLANNED') return { inputId, status: 'NOT_DELIVERED' };
    if (ctx.rejected) return blocked(inputId, ctx.rejected);
    const o = ctx.options;
    if (!o || o === 'loading' || o.available) return { inputId, status: 'AVAILABLE' };
    return blocked(inputId, o);
}

function blocked(inputId: string, from: { readonly code?: string | null; readonly reason?: string | null }): InputAvailability {
    return {
        inputId,
        status: 'BLOCKED',
        ...(from.code ? { code: from.code } : {}),
        ...(from.reason ? { reason: from.reason } : {}),
    };
}
