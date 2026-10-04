'use client';

// 장수 만들기 접수 · 결과 확인(계약판 K5-01) — 202 는 접수일 뿐이다. 결과(`GET /api/generals/creation/{requestId}`)를
// PENDING 동안 다시 묻고, CREATED · REJECTED 에서 멈춘다. 오래 걸리면 「아직 반영되지 않음」으로 멈추고 입구에서 이어 본다.
// 역사 인물(P-E03) · 새 장수(P-E02)가 같이 쓴다.

import { useCallback, useEffect, useRef, useState } from 'react';
import { CreationHttpError, readCreationResult, submitCreation } from '@/lib/creation-api';
import type { CreationChoice, CreationError } from '@/lib/creation-contract';

export type CreationPhase =
    | { readonly kind: 'idle' }
    | { readonly kind: 'sending' }
    | { readonly kind: 'pending'; readonly requestId: string }
    | { readonly kind: 'slow'; readonly requestId: string }
    | { readonly kind: 'created'; readonly generalId: number }
    /** 서버가 거절했다(접수 거절 4xx · 결과 REJECTED) — 문장은 서버 것 그대로. */
    | { readonly kind: 'rejected'; readonly error: CreationError }
    /** 연결 · 서버 오류(문장 없음). */
    | { readonly kind: 'failed'; readonly message: string };

/** 결과를 다시 묻는 간격(ms)과 횟수 — 약 1분. */
export const RESULT_POLL_MS = 1500;
export const RESULT_POLL_TRIES = 40;

/**
 * 접수 번호(clientRequestId) — 서버가 UUID 정규형(8-4-4-4-12)만 받는다(GeneralCreationService `UUID.fromString(id).toString() == id`).
 * `crypto.randomUUID` 는 보안 컨텍스트에만 있다 — 없으면 같은 모양의 v4 를 직접 만든다.
 */
export function newClientRequestId(c: Crypto = globalThis.crypto): string {
    if (typeof c?.randomUUID === 'function') return c.randomUUID();
    const bytes = new Uint8Array(16);
    c.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function useCreationRequest() {
    const [phase, setPhase] = useState<CreationPhase>({ kind: 'idle' });
    const alive = useRef(true);
    const run = useRef(0);
    // StrictMode(개발)는 마운트 직후 정리했다가 다시 실행한다 — 다시 실행될 때 살아 있음을 되돌린다(ref 값은 남는다).
    useEffect(() => {
        alive.current = true;
        return () => { alive.current = false; };
    }, []);

    const poll = useCallback(async (id: string, mine: number) => {
        for (let tries = 0; tries < RESULT_POLL_TRIES; tries += 1) {
            await new Promise((r) => setTimeout(r, RESULT_POLL_MS));
            if (!alive.current || mine !== run.current) return;
            try {
                const result = await readCreationResult(id);
                if (!alive.current || mine !== run.current) return;
                if (result.status === 'CREATED' && result.generalId !== null) { setPhase({ kind: 'created', generalId: result.generalId }); return; }
                if (result.status === 'REJECTED' && result.error) { setPhase({ kind: 'rejected', error: result.error }); return; }
            } catch (error) {
                if (!alive.current || mine !== run.current) return;
                // 결과 조회 실패는 다시 묻는다 — 접수는 이미 됐다. 서버가 문장을 준 거절(404 요청 없음 등)만 멈춘다.
                if (error instanceof CreationHttpError && error.code) { setPhase({ kind: 'rejected', error: { code: error.code, message: error.message } }); return; }
            }
        }
        if (alive.current && mine === run.current) setPhase({ kind: 'slow', requestId: id });
    }, []);

    const submit = useCallback(async (expectedWorldId: number, choice: CreationChoice) => {
        const mine = ++run.current;
        setPhase({ kind: 'sending' });
        try {
            const accepted = await submitCreation({ expectedWorldId, clientRequestId: newClientRequestId(), choice });
            if (!alive.current || mine !== run.current) return;
            setPhase({ kind: 'pending', requestId: accepted.requestId });
            void poll(accepted.requestId, mine);
        } catch (error) {
            if (!alive.current || mine !== run.current) return;
            if (error instanceof CreationHttpError && error.code) setPhase({ kind: 'rejected', error: { code: error.code, message: error.message } });
            else setPhase({ kind: 'failed', message: error instanceof Error && error.message ? error.message : '접수하지 못했습니다. 잠시 뒤 다시 해 보세요.' });
        }
    }, [poll]);

    const reset = useCallback(() => { run.current += 1; setPhase({ kind: 'idle' }); }, []);
    return { phase, submit, reset };
}
