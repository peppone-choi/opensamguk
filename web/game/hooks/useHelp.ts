'use client';

// 도움말 · 튜토리얼 읽기 훅. 상태는 늘 네 가지 — idle(부를 것 없음) · loading · ready · error(종류 보존).
import { useCallback, useEffect, useRef, useState } from 'react';
import {
    helpApi,
    helpErrorKind,
    searchQuery,
    type ContextHelpResponse,
    type FailureHelpResponse,
    type HelpErrorKind,
    type HelpSearchResponse,
    type HelpTopicResponse,
    type TutorialProgressResponse,
} from '@/lib/help';
import { subscribeCommandSettled } from '@/lib/commandResultEvents';
import { helpText, inputName } from '@/lib/help-labels';
import { formatHelpView } from '@/lib/help-route';
import { subscribeTurnCompleted } from '@/lib/turnEvents';

export type Load<T> =
    | { readonly status: 'idle' }
    | { readonly status: 'loading' }
    | { readonly status: 'ready'; readonly data: T }
    | { readonly status: 'error'; readonly kind: HelpErrorKind; readonly message: string };

function useLoad<T>(key: string | null, load: () => Promise<T>): [Load<T>, () => void] {
    const [state, setState] = useState<Load<T>>(key ? { status: 'loading' } : { status: 'idle' });
    const [tick, setTick] = useState(0);
    const loadRef = useRef(load);
    loadRef.current = load;
    useEffect(() => {
        if (!key) {
            setState({ status: 'idle' });
            return undefined;
        }
        let live = true;
        setState({ status: 'loading' });
        loadRef.current().then(
            (data) => live && setState({ status: 'ready', data }),
            (error: unknown) => live && setState({ status: 'error', kind: helpErrorKind(error), message: error instanceof Error ? error.message : '' }),
        );
        return () => {
            live = false;
        };
    }, [key, tick]);
    return [state, useCallback(() => setTick((t) => t + 1), [])];
}

export function useHelpTopic(topicId: string | null) {
    return useLoad<HelpTopicResponse>(topicId ? `topic:${topicId}` : null, () => helpApi.topic(topicId!));
}

export function useHelpContext(inputId: string | null) {
    return useLoad<ContextHelpResponse>(inputId ? `input:${inputId}` : null, () => helpApi.context(inputId!));
}

/** 사유 시트의 「이렇게 하면 됩니다」. 코드가 없으면(화면이 스스로 막은 경우) idle. */
export function useFailureHelp(code: string | null | undefined, inputId?: string | null) {
    return useLoad<FailureHelpResponse>(code ? `failure:${code}@${inputId ?? ''}` : null, () => helpApi.failure(code!, inputId));
}

export const SEARCH_DEBOUNCE_MS = 300;

/**
 * 찾기 — 2–80자만 보내고, 한글 조합 중에는 보내지 않는다(composing). 조합이 끝나고 300ms 뒤 보낸다.
 * 이전 요청은 취소한다. `short` = 한 글자만 적힌 상태(안내 문구용).
 */
export function useHelpSearch(raw: string, composing: boolean) {
    const [state, setState] = useState<Load<HelpSearchResponse>>({ status: 'idle' });
    const q = searchQuery(raw);
    const short = !q && raw.trim().length > 0 && raw.trim().length < 2;
    useEffect(() => {
        if (composing) return undefined;
        if (!q) {
            setState({ status: 'idle' });
            return undefined;
        }
        const controller = new AbortController();
        const timer = setTimeout(() => {
            setState({ status: 'loading' });
            helpApi.search(q, undefined, controller.signal).then(
                (data) => setState({ status: 'ready', data }),
                (error: unknown) => {
                    if (controller.signal.aborted) return;
                    setState({ status: 'error', kind: helpErrorKind(error), message: error instanceof Error ? error.message : '' });
                },
            );
        }, SEARCH_DEBOUNCE_MS);
        return () => {
            clearTimeout(timer);
            controller.abort();
        };
    }, [q, composing]);
    return { state, query: q, short };
}

/**
 * 첫걸음 진척. 다시 읽는 때: 처음 · 턴 완료 신호 · 명령 결과 신호 · 탭이 다시 보일 때. 주기 폴링은 하지 않는다.
 * `enabled=false`(본 서버 · 로그인 전)면 부르지 않는다. `newlyCompleted` = 직전 응답보다 새로 끝난 목표 id(달성 알림용, 한 번).
 */
export function useTutorialProgress(enabled: boolean) {
    const [state, setState] = useState<Load<TutorialProgressResponse>>(enabled ? { status: 'loading' } : { status: 'idle' });
    const [newlyCompleted, setNewlyCompleted] = useState<readonly string[]>([]);
    const done = useRef<Set<string> | null>(null);
    const [tick, setTick] = useState(0);
    const refresh = useCallback(() => setTick((t) => t + 1), []);

    useEffect(() => {
        if (!enabled) return undefined;
        const offTurn = subscribeTurnCompleted(refresh);
        const offCommand = subscribeCommandSettled(refresh);
        const onVisible = () => {
            if (document.visibilityState === 'visible') refresh();
        };
        document.addEventListener('visibilitychange', onVisible);
        return () => {
            offTurn();
            offCommand();
            document.removeEventListener('visibilitychange', onVisible);
        };
    }, [enabled, refresh]);

    useEffect(() => {
        if (!enabled) {
            setState({ status: 'idle' });
            return undefined;
        }
        const controller = new AbortController();
        helpApi.tutorialProgress(controller.signal).then(
            (data) => {
                const now = new Set(data.objectives.filter((o) => o.status === 'COMPLETED').map((o) => o.id));
                const before = done.current;
                if (before) setNewlyCompleted([...now].filter((id) => !before.has(id)));
                done.current = now;
                setState({ status: 'ready', data });
            },
            (error: unknown) => {
                if (controller.signal.aborted) return;
                // 실패해도 마지막 값은 지우지 않는다(칩은 마지막 값을 유지한다).
                setState((prev) => (prev.status === 'ready' ? prev : { status: 'error', kind: helpErrorKind(error), message: error instanceof Error ? error.message : '' }));
            },
        );
        return () => controller.abort();
    }, [enabled, tick]);

    return { state, newlyCompleted, refresh, acknowledge: useCallback(() => setNewlyCompleted([]), []) };
}

/**
 * 사유 시트(K3 ReasonSheet · `ReasonContent`)에 넣을 도움말 두 칸 — `recovery`(「이렇게 하면 됩니다」 문장)와
 * `helpTopic`(`{id, title}`, id 는 셸의 `?help=<id>` 값: 그 입력 주제를 이 사유를 펼친 채 연다).
 * 시트는 먼저 열고 `recovery` 는 읽히면 채운다. 사유가 원장에 없거나(400 · 404) 읽기에 실패하면 `recovery` 를 비운다
 * (사유 문장은 시트가 서버가 준 그대로 보인다). 코드 · 입력 id 는 화면 글자로 쓰지 않는다.
 */
export function useReasonHelp(code: string | null | undefined, inputId?: string | null): {
    readonly recovery?: string;
    readonly recoveryDraft: boolean;
    readonly helpTopic?: { readonly id: string; readonly title: string };
} {
    const [load] = useFailureHelp(code, inputId);
    useEffect(() => {
        if (load.status === 'error' && (load.kind === 'NOT_FOUND' || load.kind === 'BAD_QUERY')) {
            // 원장 · 처리기 불일치 신호 — 화면에는 띄우지 않는다.
            console.warn('[help] 원장에 없는 실패 사유', { code, inputId });
        }
    }, [load, code, inputId]);
    const helpTopic = inputId
        ? { id: formatHelpView({ kind: 'input', inputId, ...(code ? { reason: code } : {}) }), title: inputName(inputId) }
        : undefined;
    if (load.status !== 'ready') return { recoveryDraft: false, helpTopic };
    return { recovery: helpText(load.data.recoveryAdvice), recoveryDraft: load.data.reviewState === 'DRAFT', helpTopic };
}
