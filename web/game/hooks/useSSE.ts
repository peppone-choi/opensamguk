'use client';

import { useEffect, useRef, useCallback } from 'react';

import { COMMAND_SETTLED_EVENT, deliverCommandSettled } from '@/lib/commandResultEvents';

// SSE through the same-origin proxy → game-api /sse/turn (emits `turnCompleted`).
// EventSource is same-origin so the sam_access cookie rides along; the proxy attaches the Bearer
// and streams text/event-stream un-buffered.
const SSE_URL = '/api/game/sse/turn';
const SSE_EVENT = 'turnCompleted';

export function useSSE(onEvent: () => void) {
    const esRef = useRef<EventSource | null>(null);
    const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const delayRef = useRef(1000);
    const onEventRef = useRef(onEvent);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    const connect = useCallback(() => {
        const current = esRef.current;
        if (current && current.readyState !== EventSource.CLOSED) return;

        const es = new EventSource(SSE_URL);
        esRef.current = es;

        es.onopen = () => {
            delayRef.current = 1000;
        };

        es.addEventListener(SSE_EVENT, () => {
            delayRef.current = 1000;
            onEventRef.current();
        });

        // OPENSAM-45 — 명령 결과 깨움 신호는 이 연결에 얹어 받는다. 제출할 때 두 번째 EventSource를
        // 새로 열면 구독이 붙기 전에 발행된 자기 신호를 놓치고(pub/sub replay 없음) 탭마다 상시 연결이
        // 하나 더 늘어난다. 페이지 갱신과 무관하므로 onEvent는 부르지 않는다.
        es.addEventListener(COMMAND_SETTLED_EVENT, e => {
            deliverCommandSettled((e as MessageEvent<string>).data);
        });

        es.onerror = () => {
            es.close();
            esRef.current = null;
            delayRef.current = Math.min(delayRef.current * 2, 30000);
            if (reconnectRef.current) clearTimeout(reconnectRef.current);
            reconnectRef.current = setTimeout(() => {
                reconnectRef.current = null;
                connect();
            }, delayRef.current);
        };
    }, []);

    useEffect(() => {
        connect();
        return () => {
            esRef.current?.close();
            if (reconnectRef.current) clearTimeout(reconnectRef.current);
        };
    }, [connect]);
}
