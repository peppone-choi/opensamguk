'use client';

// OPENSAM-196 — 화면별 턴 갱신 훅.
//
// 턴이 끝나면 화면이 보여 주는 게임 데이터가 낡는다. 화면은 자기 fetch 클로저를 여기에 넘겨
// 그 데이터만 다시 읽는다. 페이지 전체를 리로드하지 않으므로 스크롤·입력 중인 폼·열린 모달이
// 그대로 남는다.
//
// 콜백은 매 렌더마다 새로 만들어져도 된다 — ref로 최신 것을 붙잡으므로 구독이 다시 걸리지 않는다
// (`useSSE`와 같은 규약). 신호원은 Shell의 SSE 하나뿐이다([lib/turnEvents] 참고).

import { useEffect, useRef } from 'react';

import { subscribeTurnCompleted } from '@/lib/turnEvents';

export function useTurnRefresh(onTurn: () => void) {
    const onTurnRef = useRef(onTurn);

    useEffect(() => {
        onTurnRef.current = onTurn;
    }, [onTurn]);

    useEffect(() => subscribeTurnCompleted(() => onTurnRef.current()), []);
}
