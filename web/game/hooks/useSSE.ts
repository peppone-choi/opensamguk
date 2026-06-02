'use client';

import { useEffect, useRef, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

export function useSSE(onEvent: () => void) {
    const esRef = useRef<EventSource | null>(null);
    const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const delayRef = useRef(1000);

    const connect = useCallback(() => {
        if (esRef.current?.readyState === EventSource.OPEN) return;

        const es = new EventSource(`${API_BASE}/realtime/events`);
        esRef.current = es;

        es.addEventListener('realtime', () => {
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
