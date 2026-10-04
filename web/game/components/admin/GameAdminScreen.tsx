'use client';

// 게임 관리(P-A03) — K5 설계서 §6.2, 보드 V31K5GameAdmin(인물 조치) · V31K5GameAdminNations(세력 개요) · V31K5MGameAdmin.
// 운영자가 한 게임 서버 안의 세력 · 인물 · 외교 · 기록 · 서버 상태를 본다. 탭 다섯은 `?tab=` 로 연다(셸 머리 탭).
// 서버가 지금 주는 것만 실제 값으로 그린다(계약판 K5-13 중 main 에 있는 것):
//  - 세력 개요: GET /api/admin/nations — 표 전체.
//  - 인물 조치 · 인물 기록의 사람 고르기: GET /api/admin/people — 이름 · 소속 · 5능력.
//  - 서버 상태: 지금 상태는 GET /api/admin/game-settings, 바꾸기는 POST /api/admin/server-status(202 접수).
// 조치 쓰기 · 운영자 사건 읽기 · 외교 전체판 · 사람/NPC · 차단 · 다음 개인 턴은 서버가 아직 주지 않는다 — 서버 대기로 그린다.
// 게임 설정(턴 길이 · 시작 시각 · 상한)은 운영 콘솔(P-G09 서버 탭)이 맡는다(§9 Q-A1).

import { useCallback, useEffect, useState } from 'react';
import { StatusView, type PersonOption } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { readAllAdminPeople, type AdminPeopleLoad } from '@/lib/admin-reads';
import type { DirectoryPerson } from '@/lib/directory-reads';
import type { NavScreen } from '@/lib/nav31';
import AdminNations from './AdminNations';
import AdminPeopleActions, { AdminPeopleRecords } from './AdminPeople';
import AdminServerStatus from './AdminServerStatus';

export const ADMIN_TABS = [
    { key: 'nations', label: '세력 개요' },
    { key: 'people', label: '인물 조치' },
    { key: 'records', label: '인물 기록' },
    { key: 'diplomacy', label: '외교 관계' },
    { key: 'status', label: '서버 상태' },
] as const;
export type AdminTabKey = (typeof ADMIN_TABS)[number]['key'];

/** 옛 탭 이름(게임 설정 · 장수 조치 · 일제정보 · 로그정보 · 외교정보)으로 들어와도 새 탭을 연다. */
const LEGACY_TABS: Readonly<Record<string, AdminTabKey>> = {
    settings: 'status', generals: 'people', stats: 'nations', logs: 'records',
};

export function adminTabOf(raw: string | null): AdminTabKey {
    if (raw === null) return 'nations';
    const tab = ADMIN_TABS.find((t) => t.key === raw);
    return tab ? tab.key : LEGACY_TABS[raw] ?? 'nations';
}

export const ADMIN_SCREENS: readonly NavScreen[] = ADMIN_TABS.map((t) => ({ label: t.label, path: `admin?tab=${t.key}`, built: true }));

export function adminTabLabel(tab: AdminTabKey): string {
    return ADMIN_TABS.find((t) => t.key === tab)!.label;
}

export type Load<T> = { readonly state: 'loading' } | { readonly state: 'ready'; readonly data: T } | { readonly state: 'error'; readonly error: unknown };

/** 한 번 읽기 — `seq` 를 올리면 다시 읽는다. 다시 읽는 동안 받은 값을 지우지 않는다. `read` 가 null 이면 읽지 않는다. */
export function useAdminRead<T>(read: ((signal: AbortSignal) => Promise<T>) | null, seq: number): Load<T> {
    const [load, setLoad] = useState<Load<T>>({ state: 'loading' });
    useEffect(() => {
        if (!read) return;
        const controller = new AbortController();
        setLoad((l) => (l.state === 'ready' ? l : { state: 'loading' }));
        read(controller.signal).then(
            (data) => { if (!controller.signal.aborted) setLoad({ state: 'ready', data }); },
            (error: unknown) => { if (!controller.signal.aborted) setLoad({ state: 'error', error }); },
        );
        return () => controller.abort();
    }, [read, seq]);
    return load;
}

/** 운영자 사람 고르기 목록 — 소속은 서버 값 그대로(재야 = null). 묶음은 서버가 주지 않아 「전체」 하나다. */
export function adminPersonOption(person: DirectoryPerson): PersonOption {
    return {
        generalId: person.generalId,
        name: person.name,
        picture: person.portrait.picture,
        imageServer: person.portrait.imageServer,
        nation: person.affiliation ? { id: person.affiliation.nationId, name: person.affiliation.name, color: person.affiliation.color } : null,
        groups: [],
    };
}

export interface AdminPeopleState {
    readonly load: Load<AdminPeopleLoad>;
    readonly reload: () => void;
}

export default function GameAdminScreen({ tab }: { readonly tab: AdminTabKey }) {
    // 인물 목록은 인물 조치 · 인물 기록이 처음 열릴 때 한 번 받고 두 탭이 같이 쓴다.
    const wantsPeople = tab === 'people' || tab === 'records';
    const [peopleWanted, setPeopleWanted] = useState(wantsPeople);
    useEffect(() => { if (wantsPeople) setPeopleWanted(true); }, [wantsPeople]);
    const [peopleSeq, setPeopleSeq] = useState(0);
    const readPeople = useCallback((signal: AbortSignal) => readAllAdminPeople(api.admin.people, signal), []);
    const peopleLoad = useAdminRead<AdminPeopleLoad>(peopleWanted ? readPeople : null, peopleSeq);
    const people: AdminPeopleState = { load: peopleLoad, reload: () => setPeopleSeq((n) => n + 1) };

    return (
        <div role="tabpanel" aria-label={adminTabLabel(tab)}>
            {tab === 'nations' ? <AdminNations /> : null}
            {tab === 'people' ? <AdminPeopleActions people={people} /> : null}
            {tab === 'records' ? <AdminPeopleRecords people={people} /> : null}
            {tab === 'diplomacy' ? (
                <StatusView
                    kind="waiting"
                    title="외교 관계를 준비하고 있습니다"
                    body="모든 세력 사이의 관계(전쟁 · 강화 · 불가침)와 언제부터 · 언제까지를 서버가 아직 주지 않습니다. 준비되면 이 자리에 표로 보입니다."
                />
            ) : null}
            {tab === 'status' ? <AdminServerStatus /> : null}
        </div>
    );
}
