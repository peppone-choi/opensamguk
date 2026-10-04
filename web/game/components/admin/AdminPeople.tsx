'use client';

// 인물 조치(P-A03 GM1–GM23) · 인물 기록(GL1–GL13) — 보드 V31K5GameAdmin · V31K5MGameAdmin.
// 사람 고르기는 GET /api/admin/people(모든 인물)로 채운다. 서버가 아직 주지 않는 것은 서버 대기다:
//  - 조치 쓰기(계약판 K5-13 people/actions) — 단추는 그리되 누르면 사유가 열린다.
//  - 고른 인물 표의 사람/NPC · 차단 · 다음 개인 턴 · 1 · 2순 — 「—」.
//  - 그 인물의 사건(운영자는 모든 audience 를 읽는다) — 서버 대기 상태.
// 삼모 조치(무한삭턴 · 숙련 10000 · 하야입력 · 방랑해산 · 「NPC유저」)는 뺐다.

import { useMemo, useState } from 'react';
import { Button, Modal, Panel, PeoplePicker, SectionHeader, StatusView, useViewportClass, type PersonOption } from '@opensamguk/ui';
import { formatNumber } from '@/lib/format';
import type { DirectoryPerson } from '@/lib/directory-reads';
import { ADMIN_PEOPLE_MAX_PAGES, ADMIN_PEOPLE_PAGE_LIMIT } from '@/lib/admin-reads';
import { PEOPLE_SORT_LABEL } from '@/lib/people-view';
import { httpStatusOf } from '@/lib/records-reads';
import { adminPersonOption, type AdminPeopleState } from './GameAdminScreen';
import styles from './game-admin.module.css';

const DASH = '—';
const ACTION_WAIT = '운영 조치를 서버가 아직 받지 않습니다. 준비되면 이 단추가 바로 동작합니다.';
const PICK_FIRST = '먼저 대상 인물을 고르세요';
const MESSAGE_MAX = 255;

type Variant = 'ghost' | 'danger';
const ACTION_GROUPS: readonly { readonly title: string; readonly note?: string; readonly actions: readonly { readonly label: string; readonly variant: Variant }[] }[] = [
    {
        title: '접속', note: '전체 대상은 확인을 거칩니다', actions: [
            { label: '접속 허용', variant: 'ghost' }, { label: '접속 제한', variant: 'ghost' },
            { label: '모두 접속 허용', variant: 'danger' }, { label: '모두 접속 제한', variant: 'danger' },
        ],
    },
    {
        title: '차단', actions: [
            { label: '차단 풀기', variant: 'ghost' }, { label: '말하기 막기', variant: 'ghost' },
            { label: '턴 막기', variant: 'ghost' }, { label: '3단계', variant: 'ghost' },
        ],
    },
    { title: '강제 사망', note: '되돌릴 수 없습니다', actions: [{ label: '강제 사망', variant: 'danger' }] },
];

const STATS: readonly { readonly key: 'leadership' | 'strength' | 'intel' | 'politics' | 'charm'; readonly label: string }[] = [
    { key: 'leadership', label: PEOPLE_SORT_LABEL.LEADERSHIP },
    { key: 'strength', label: PEOPLE_SORT_LABEL.STRENGTH },
    { key: 'intel', label: PEOPLE_SORT_LABEL.INTEL },
    { key: 'politics', label: PEOPLE_SORT_LABEL.POLITICS },
    { key: 'charm', label: PEOPLE_SORT_LABEL.CHARM },
];

type PickerLoad = { readonly state: 'loading' } | { readonly state: 'error'; readonly onRetry: () => void; readonly errorCode?: string } | { readonly state: 'ready'; readonly people: readonly PersonOption[] };

/** 받은 인물 목록 → 사람 고르기 load · 이름표 · 알림. */
function usePeopleView(people: AdminPeopleState) {
    const { load, reload } = people;
    return useMemo(() => {
        const empty: readonly DirectoryPerson[] = [];
        if (load.state === 'loading') return { picker: { state: 'loading' } as PickerLoad, byId: new Map<number, DirectoryPerson>(), denied: false, notice: null as string | null, list: empty };
        if (load.state === 'error') {
            const status = httpStatusOf(load.error);
            return { picker: { state: 'error', onRetry: reload, errorCode: status ? String(status) : undefined } as PickerLoad, byId: new Map<number, DirectoryPerson>(), denied: status === 403, notice: null, list: empty };
        }
        const { status, people: list } = load.data;
        if (status !== 'READY' && status !== 'TRUNCATED') {
            return { picker: { state: 'error', onRetry: reload, errorCode: status } as PickerLoad, byId: new Map<number, DirectoryPerson>(), denied: false, notice: null, list: empty };
        }
        const notice = status === 'TRUNCATED'
            ? `인물이 많아 앞 ${formatNumber(ADMIN_PEOPLE_MAX_PAGES * ADMIN_PEOPLE_PAGE_LIMIT)}명만 불러왔습니다. 이름으로 찾지 못하면 운영 콘솔에서 확인하세요.`
            : null;
        return {
            picker: { state: 'ready', people: list.map(adminPersonOption) } as PickerLoad,
            byId: new Map(list.map((p) => [p.generalId, p])),
            denied: false,
            notice,
            list,
        };
    }, [load, reload]);
}

function Denied() {
    return <StatusView kind="denied" title="관리자 권한이 필요합니다." howTo="운영자 계정으로 다시 로그인하면 볼 수 있습니다." />;
}

function affiliationOf(person: DirectoryPerson | undefined): string {
    if (!person) return DASH;
    return person.affiliation ? `${person.affiliation.name} 소속` : '재야';
}

// ── 인물 조치 ─────────────────────────────────────────────────────────────

function ActionGroups({ hasTarget }: { readonly hasTarget: boolean }) {
    const [message, setMessage] = useState('');
    const reason = hasTarget ? ACTION_WAIT : PICK_FIRST;
    return (
        <div className={styles.actionGroups}>
            <p className={styles.notice} role="note">조치는 서버 준비 중입니다 — {ACTION_WAIT}</p>
            {ACTION_GROUPS.map((g) => (
                <Panel key={g.title} className={styles.actionGroup} aria-label={g.title}>
                    <SectionHeader title={g.title} sub={g.note} />
                    <div className={styles.actionRow}>
                        {g.actions.map((a) => <Button key={a.label} variant={a.variant} disabled reason={a.label.startsWith('모두') ? ACTION_WAIT : reason}>{a.label}</Button>)}
                    </div>
                </Panel>
            ))}
            <Panel className={styles.actionGroup} aria-label="운영 알림 보내기">
                <SectionHeader title="운영 알림 보내기" sub="받는 인물에게 서신으로" />
                <div className={styles.messageRow}>
                    <input
                        type="text"
                        className={`os-input ${styles.messageInput}`}
                        aria-label="운영 알림 내용"
                        placeholder={`받는 인물에게 서신으로 — ${MESSAGE_MAX}자까지`}
                        maxLength={MESSAGE_MAX}
                        value={message}
                        onChange={(event) => setMessage(event.target.value)}
                    />
                    <Button variant="primary" disabled reason={reason}>보내기</Button>
                </div>
            </Panel>
        </div>
    );
}

function ChosenTable({ chosen }: { readonly chosen: readonly (DirectoryPerson | undefined)[] }) {
    if (chosen.length === 0) return <StatusView kind="empty" title="고른 인물이 없습니다" body="대상 인물 목록에서 조치할 인물을 고르세요." />;
    return (
        <>
            <div className={styles.tableWrap}>
                <table className={styles.table}>
                    <caption className="sr-only">고른 인물</caption>
                    <thead>
                        <tr>
                            {['인물', '사람/NPC', '차단', '소속', '다음 개인 턴', '1순', '2순'].map((h, i) => (
                                <th key={h} scope="col" className={i === 0 ? styles.nameCol : undefined}>{h}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {chosen.map((p, i) => (
                            <tr key={p?.generalId ?? `missing-${i}`}>
                                <th scope="row" className={styles.nameCol}>{p?.name ?? DASH}</th>
                                <td>{DASH}</td>
                                <td>{DASH}</td>
                                <td>{affiliationOf(p)}</td>
                                <td>{DASH}</td>
                                <td>{DASH}</td>
                                <td>{DASH}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            <p className={styles.muted}>사람/NPC · 차단 · 다음 개인 턴 · 1 · 2순은 서버가 아직 주지 않아 「—」로 둡니다.</p>
        </>
    );
}

export default function AdminPeopleActions({ people }: { readonly people: AdminPeopleState }) {
    const view = usePeopleView(people);
    const [selected, setSelected] = useState<readonly number[]>([]);
    const [sheet, setSheet] = useState(false);
    const mobile = useViewportClass() === 'mobile';
    if (view.denied) return <Denied />;

    const chosen = selected.map((id) => view.byId.get(id));
    const picker = (
        <PeoplePicker multiple selected={selected} onChange={setSelected} load={view.picker} groups={['all']} label="대상 인물" />
    );
    const names = chosen.map((p) => p?.name ?? DASH).join(' · ');

    if (mobile) {
        return (
            <div className={styles.stack}>
                {view.notice ? <p className={styles.notice} role="note">{view.notice}</p> : null}
                <Panel className={styles.pickerPanel} aria-label="대상 인물">
                    <SectionHeader title="대상 인물" sub="여러 명" />
                    {picker}
                </Panel>
                {selected.length > 0
                    ? <Button variant="primary" block onClick={() => setSheet(true)}>{`고른 ${formatNumber(selected.length)}명 조치`}</Button>
                    : <Button variant="primary" block disabled reason={PICK_FIRST}>조치</Button>}
                {sheet ? (
                    <Modal ariaLabel={`${names} 조치`} onClose={() => setSheet(false)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.sheetHead}>
                            <h3 className={styles.sheetTitle}>{`${names} 조치`}</h3>
                            <button type="button" className="os-button os-button--sm" onClick={() => setSheet(false)}>닫기</button>
                        </div>
                        <ActionGroups hasTarget />
                        <Panel className={styles.panel}>
                            <SectionHeader title="고른 인물" sub={`${formatNumber(selected.length)}명`} />
                            <ChosenTable chosen={chosen} />
                        </Panel>
                    </Modal>
                ) : null}
            </div>
        );
    }

    return (
        <div className={styles.split}>
            <Panel className={styles.pickerPanel} aria-label="대상 인물">
                <SectionHeader title="대상 인물" sub="여러 명" />
                {view.notice ? <p className={styles.notice} role="note">{view.notice}</p> : null}
                {picker}
            </Panel>
            <div className={styles.side}>
                <ActionGroups hasTarget={selected.length > 0} />
                <Panel className={styles.panel}>
                    <SectionHeader title="고른 인물" sub={`${formatNumber(selected.length)}명`} />
                    <ChosenTable chosen={chosen} />
                </Panel>
            </div>
        </div>
    );
}

// ── 인물 기록 ─────────────────────────────────────────────────────────────

function PersonCard({ person }: { readonly person: DirectoryPerson }) {
    return (
        <Panel className={styles.panel} aria-label={`${person.name} 인물`}>
            <SectionHeader title={person.name} sub={affiliationOf(person)} />
            <dl className={styles.stats}>
                {STATS.map((s) => (
                    <div key={s.key} className={styles.stat}>
                        <dt>{s.label}</dt>
                        <dd>{person.stats ? formatNumber(person.stats[s.key]) : DASH}</dd>
                    </div>
                ))}
            </dl>
            <p className={styles.muted}>사람/NPC · 자리 · 다음 개인 턴은 서버가 아직 주지 않습니다.</p>
        </Panel>
    );
}

export function AdminPeopleRecords({ people }: { readonly people: AdminPeopleState }) {
    const view = usePeopleView(people);
    const [selected, setSelected] = useState<number | null>(null);
    if (view.denied) return <Denied />;
    const person = selected === null ? undefined : view.byId.get(selected);

    return (
        <div className={styles.split}>
            <Panel className={styles.pickerPanel} aria-label="대상 인물">
                <SectionHeader title="대상 인물" sub="한 명" />
                {view.notice ? <p className={styles.notice} role="note">{view.notice}</p> : null}
                <PeoplePicker selected={selected} onChange={setSelected} load={view.picker} groups={['all']} label="대상 인물" />
            </Panel>
            <div className={styles.side}>
                {person ? (
                    <>
                        <PersonCard person={person} />
                        <StatusView
                            kind="waiting"
                            title="인물 기록을 준비하고 있습니다"
                            body={`${person.name}의 개인 · 부와 세력 · 조정 · 전장 · 천하 사건을 운영자가 모두 읽는 조회를 서버가 아직 주지 않습니다. 준비되면 이 자리에 최근 순으로 보입니다.`}
                        />
                    </>
                ) : (
                    <StatusView kind="empty" title="대상 인물을 고르세요" body="인물을 고르면 그 인물의 능력과 기록이 여기 보입니다." />
                )}
            </div>
        </div>
    );
}
