'use client';

import { useEffect, useRef, useCallback } from 'react';

// SSE through the same-origin proxy → game-api /sse/turn (emits `turnCompleted`).
// EventSource is same-origin so the sam_access cookie rides along; the proxy attaches the Bearer
// and streams text/event-stream un-buffered.
const SSE_URL = '/api/game/sse/turn';
const SSE_EVENT = 'turnCompleted';

export function useSSE(onEvent: () => void) {
    const esRef = useRef<EventSource | null>(null);
    const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const delayRef = useRef(1000);

    const connect = useCallback(() => {
        if (esRef.current?.readyState === EventSource.OPEN) return;

        const es = new EventSource(SSE_URL);
        esRef.current = es;

        es.addEventListener(SSE_EVENT, () => {
            delayRef.current = 1000;
            onEvent();
        });

        es.onerror = () => {
            es.close();
            esRef.current = null;
            delayRef.current = Math.min(delayRef.current * 2, 30000);
            reconnectRef.current = setTimeout(connect, delayRef.current);
        };
    }, [onEvent]);

    useEffect(() => {
        connect();
        return () => {
            esRef.current?.close();
            if (reconnectRef.current) clearTimeout(reconnectRef.current);
        };
    }, [connect]);
}
