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
