'use client';

import { ReasonTooltip, type ReasonTooltipProps } from '@opensamguk/ui';
import { useReasonHelp } from '@/hooks/useHelp';

/**
 * ReasonTooltip + K7 도움말 고리 — 서버 사유 코드(`code`)를 보이는 후보 · 결정 행의 사유 시트에 원장의 「이렇게 하면 됩니다」와
 * 그 입력 도움말 고리를 붙인다(HelpedInputAction 과 같은 규칙, 2026-10-05). 행마다 쓰는 사유 시트를 훅 규칙 안에서 감싸려고 부품으로 둔다.
 * `inputId` 는 그 행이 막힌 입력(배치 후보면 `placement.assign`)이다.
 */
export function HelpedReasonTooltip({ inputId, ...props }: ReasonTooltipProps & { readonly inputId: string }) {
    const help = useReasonHelp(props.code, inputId);
    return <ReasonTooltip inputId={inputId} {...props} {...help} />;
}
