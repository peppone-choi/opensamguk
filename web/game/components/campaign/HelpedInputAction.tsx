'use client';

import { InputAction, type InputActionProps } from '@opensamguk/ui';
import { useReasonHelp } from '@/hooks/useHelp';

/**
 * InputAction + K7 도움말 고리(front-wave1 공통 규칙, K7 #1136). 막힌 사유 코드로 원장의 「이렇게 하면 됩니다」를 읽고,
 * 도움말 주제 고리 · 서랍 열기(지금 쿼리를 둔 채)를 붙인다. 행마다 쓰는 결정 단추를 훅 규칙 안에서 감싸려고 부품으로 둔다.
 */
export function HelpedInputAction(props: InputActionProps) {
    const help = useReasonHelp(props.availability?.code, props.inputId);
    return <InputAction {...props} {...help} />;
}
