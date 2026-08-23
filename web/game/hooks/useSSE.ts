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
    // onerror의 세션 확인(fetch)이 비동기라 언마운트와 경합한다 — cleanup이 끝난 뒤 도착한
    // .then이 재연결 타이머/EventSource를 새로 만들면 아무도 안 닫는 좀비가 된다. false가 되면
    // 이후의 스케줄링을 전부 건너뛴다.
    const aliveRef = useRef(true);

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
        // 신호 payload는 시각뿐이라 읽을 게 없다 — 도착 자체가 "지금 정본을 읽어라"라는 뜻이다.
        es.addEventListener(COMMAND_SETTLED_EVENT, () => {
            deliverCommandSettled();
        });

        es.onerror = () => {
            es.close();
            esRef.current = null;
            // 네이티브 EventSource는 onerror에 응답 status를 실어주지 않는다(#514) — upstream이
            // 401을 준 경우와 일시적 네트워크 끊김을 구분할 수 없다. web/game/lib/api.ts:351과
            // 동일한 패턴으로 /api/auth/me를 불러 세션이 실제로 만료됐는지 확인한다. fetch 자체가
            // 실패하면(네트워크 장애) 일시적 문제로 보고 기존 backoff를 유지한다.
            void fetch('/api/auth/me', { cache: 'no-store' })
                // /api/auth/me는 401/403만 실제 인증 실패로 본다 — 그 외(예: 502, 게이트웨이 재기동
                // 중 일시 다운)는 세션 쿠키를 건드리지 않고 그대로 반환한다(app/api/auth/me/route.ts).
                // res.ok로 뭉치면 그 502까지 "세션 만료"로 오판해 멀쩡한 세션을 리로드로 쫓아낸다.
                .then((res) => res.status !== 401 && res.status !== 403)
                .catch(() => true)
                .then((sessionAlive) => {
                    if (!aliveRef.current) return; // 그 사이 언마운트됨 — 재연결 예약 금지(좀비 방지)
                    if (!sessionAlive) {
                        // 만료 확정 — 30초 간격 영구 재연결을 멈춘다. 전체 리로드로 AuthGate의
                        // 기존 /api/auth/me → 미인증 → 게이트웨이 로그인 리다이렉트 경로를 그대로 태운다.
                        window.location.reload();
                        return;
                    }
                    delayRef.current = Math.min(delayRef.current * 2, 30000);
                    if (reconnectRef.current) clearTimeout(reconnectRef.current);
                    reconnectRef.current = setTimeout(() => {
                        reconnectRef.current = null;
                        connect();
                    }, delayRef.current);
                });
        };
    }, []);

    useEffect(() => {
        aliveRef.current = true;
        connect();
        return () => {
            aliveRef.current = false;
            esRef.current?.close();
            if (reconnectRef.current) clearTimeout(reconnectRef.current);
        };
    }, [connect]);
}
