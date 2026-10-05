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
} from '@/lib/help';
import { useHelpLink, type ReasonContent } from '@opensamguk/ui';
import { helpText, inputName } from '@/lib/help-labels';
import { formatHelpView } from '@/lib/help-route';

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
    const [tick, setTick] = useState(0);
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
    }, [q, composing, tick]);
    return { state, query: q, short, retry: useCallback(() => setTick((t) => t + 1), []) };
}

/**
 * 사유 시트(K3 ReasonSheet · InputAction · `ReasonContent`)에 넣을 도움말 칸 — `recovery`(「이렇게 하면 됩니다」 문장),
 * `helpTopic`(`{id, title}`, id 는 셸의 `?help=<id>` 값: 그 입력 주제를 이 사유를 펼친 채 연다),
 * `onHelp`(지금 쿼리를 두고 서랍을 연다 — /game 레이아웃의 HelpLinkScope 가 주는 것을 그대로 넘긴다. 레이아웃 밖이면 없다). 결과를 그대로 펼친다:
 * `<InputAction inputId=… availability=… {...useReasonHelp(code, inputId)} />`.
 * 시트는 먼저 열고 `recovery` 는 읽히면 채운다. 사유가 원장에 없거나(400 · 404) 읽기에 실패하면 `recovery` 를 비운다
 * (사유 문장은 시트가 서버가 준 그대로 보인다). 코드 · 입력 id 는 화면 글자로 쓰지 않는다.
 */
export type ReasonHelp = Pick<ReasonContent, 'recovery' | 'recoveryDraft' | 'helpTopic'> & { readonly onHelp?: (topicId: string) => void };

export function useReasonHelp(code: string | null | undefined, inputId?: string | null): ReasonHelp {
    // 서랍을 여는 법은 /game 레이아웃의 HelpLinkScope(useOpenHelp — 지금 쿼리를 둔 채 router.push)에서 받는다. 라우터를 여기서 부르지 않아
    // 레이아웃 밖(부품 시험 · 시험실)에서 그려도 깨지지 않고, 그때는 공용 부품의 기본 링크(`?help=`)로 간다(2026-10-03 — 결정 단추 11개에 고리를 붙이며).
    const onHelp = useHelpLink().open;
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
    if (load.status !== 'ready') return { recoveryDraft: false, helpTopic, onHelp };
    return { recovery: helpText(load.data.recoveryAdvice), recoveryDraft: load.data.reviewState === 'DRAFT', helpTopic, onHelp };
}
