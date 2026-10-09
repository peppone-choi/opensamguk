'use client';

// 영지 입력의 문맥 수명 — 탭 · 시트 · 알림 · 제출을 연 문맥(주소 · 뒤로 가기 · 장수 · 쿠키 서버)에 묶는다.
// 새 수명이면 시트 · 알림은 없고 탭은 주소가 준 칸(없으면 그대로). 옛 수명에서 보낸 제출의 늦은 결과는 읽기만 다시 한다.

import { useEffect, useRef, useState } from 'react';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useGameSession } from '@/lib/campaign-session';
import { readServerCookie } from '@/lib/serverGameUrl';
import { useContextLifetime } from '@/lib/territory/use-context-lifetime';
import type { PolicyRow } from '@/lib/territory-view';

export type TerritoryKind = 'placement' | 'policy' | 'work';
export type TerritorySubmission = TerritoryKind | 'reduce';
export type TerritorySheet =
    | { readonly kind: 'placement'; readonly cardId: number }
    | { readonly kind: 'policy'; readonly row: PolicyRow }
    | { readonly kind: 'work' | 'reduce'; readonly countyId: number };

interface TerritoryNotice {
    readonly tone: 'ok' | 'error';
    readonly text: string;
}

const OK_TEXT: Readonly<Record<TerritorySubmission, string>> = {
    placement: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.',
    policy: '방침을 접수했습니다 — 다음 턴부터 적용합니다.',
    work: '공사를 접수했습니다 — 다음 순 경계부터 진척합니다.',
    reduce: '성방 감축을 접수했습니다 — 다음 순 경계부터 적용합니다.',
};

interface TerritoryInputs {
    /** 지금 문맥의 수명 번호 — 방침 칸 key 처럼 문맥마다 새로 그릴 것에 쓴다. */
    readonly lifetime: number;
    /** 읽기 다시 부르기 번호 — 칸 읽기의 의존 값. */
    readonly reload: number;
    readonly again: () => void;
    readonly tab: TerritoryKind;
    readonly setTab: (value: TerritoryKind) => void;
    readonly sheet: TerritorySheet | null;
    readonly setSheet: (next: TerritorySheet | null) => void;
    readonly busy: boolean;
    readonly notice: TerritoryNotice | null;
    readonly setNotice: (next: TerritoryNotice) => void;
    readonly submit: (kind: TerritorySubmission, body: Readonly<Record<string, unknown>>) => Promise<void>;
}

/** contextKey 는 탭 서버 · 주소 쿼리 — 장수 · 쿠키 서버는 여기서 더한다. initialView 는 새 수명의 첫 탭. */
export function useTerritoryInputs(contextKey: string, initialView: TerritoryKind | null): TerritoryInputs {
    const { generalId } = useGameSession();
    // 주소 · 뒤로 가기 · 장수 · 서버가 바뀌면 새 수명 — 옛 수명의 편집 시트 · 알림 · 늦은 제출 결과는 버린다.
    const lifetime = useContextLifetime(`${contextKey}|${generalId ?? ''}|${readServerCookie() ?? ''}`);
    const lifetimeRef = useRef(lifetime);
    useEffect(() => { lifetimeRef.current = lifetime; }, [lifetime]);
    const [reload, setReload] = useState(0);
    const again = () => setReload((n) => n + 1);
    const [tabState, setTabState] = useState<{ gen: number; value: TerritoryKind }>({ gen: lifetime, value: initialView ?? 'placement' });
    const tab: TerritoryKind = tabState.gen !== lifetime && initialView ? initialView : tabState.value;
    const setTab = (value: TerritoryKind) => setTabState({ gen: lifetime, value });
    const [sheetState, setSheetState] = useState<{ gen: number; sheet: TerritorySheet } | null>(null);
    const sheet = sheetState?.gen === lifetime ? sheetState.sheet : null;
    const setSheet = (next: TerritorySheet | null) => setSheetState(next ? { gen: lifetime, sheet: next } : null);
    const [busy, setBusy] = useState(false);
    const [noticeState, setNoticeState] = useState<{ gen: number } & TerritoryNotice | null>(null);
    const notice = noticeState?.gen === lifetime ? noticeState : null;
    const setNotice = (next: TerritoryNotice) => setNoticeState({ gen: lifetime, ...next });

    const submit = async (kind: TerritorySubmission, body: Readonly<Record<string, unknown>>) => {
        if (generalId == null || kind === 'reduce' && busy) return;
        const started = lifetime;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, kind, body);
            // 보낸 뒤 문맥이 바뀌었으면 결과를 새 문맥의 알림 · 시트에 쓰지 않는다(읽기만 다시).
            if (lifetimeRef.current !== started) {
                if (isIntakeQueued(out)) again();
                return;
            }
            if (isIntakeQueued(out)) {
                setNotice({ tone: 'ok', text: OK_TEXT[kind] });
                setSheet(null);
                again();
            } else if (isIntakeDenied(out)) {
                setNotice({ tone: 'error', text: out.reason?.trim() || '접수하지 못했습니다.' });
            }
        } catch {
            if (lifetimeRef.current === started) setNotice({ tone: 'error', text: '보내지 못했습니다 — 다시 해 보세요.' });
        } finally {
            setBusy(false);
        }
    };

    return { lifetime, reload, again, tab, setTab, sheet, setSheet, busy, notice, setNotice, submit };
}
