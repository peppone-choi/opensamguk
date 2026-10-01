'use client';

// 홈(이 화면) · 분류 · 찾기 결과.
import { useState } from 'react';
import { useHelpContext, useHelpSearch } from '@/hooks/useHelp';
import { KIND_LABEL, helpText, inputName } from '@/lib/help-labels';
import type { HelpIndexEntry } from '@/lib/help-index';
import type { HelpView } from '@/lib/help-route';
import { SCREEN_LABEL, generalActionGroups, kindGroups, screenGroups, type HelpScreen } from '@/lib/help-screens';
import { StatusView } from '@opensamguk/ui';
import { Chip, DraftChip, PlannedChip } from './HelpBits';
import { LoadFailure } from './HelpStates';
import { topicViewOf } from './HelpTopicView';
import s from './Help.module.css';

const FIRST_ROWS = 7;

/** 한 줄 — 이름은 색인에서 바로, 설명 · 초안 · 준비 중은 문맥 도움말에서(보이는 줄만 읽는다). */
function TopicRow({ entry, explain, onOpen }: { entry: HelpIndexEntry; explain: boolean; onOpen: () => void }) {
    const [load] = useHelpContext(explain ? entry.inputId : null);
    const ready = load.status === 'ready' ? load.data : null;
    return (
        <li>
            <button type="button" className={s.row} onClick={onOpen} data-help-input={entry.inputId}>
                <span className={s.rowMain}>
                    <span className={s.rowName}>{inputName(entry.inputId, entry.name)}</span>
                    {explain ? <span className={s.rowSub}>{ready ? helpText(ready.topic.sections.explanation) : ' '}</span> : null}
                </span>
                <span className={s.rowEnd}>
                    <PlannedChip deliveryState={ready?.input.deliveryState} />
                    <DraftChip state={ready?.topic.reviewState} />
                </span>
            </button>
        </li>
    );
}

function Group({ label, sub }: { label: string; sub?: string }) {
    return <div className={s.ghead}><b>{label}</b>{sub ? <span>{sub}</span> : null}</div>;
}

export function HelpHome({ screen, onNavigate }: { screen: HelpScreen; onNavigate: (v: HelpView) => void }) {
    const groups = screenGroups(screen);
    const [on, setOn] = useState(groups[0]?.key ?? '');
    const [all, setAll] = useState(false);
    const current = groups.find((g) => g.key === on) ?? groups[0];
    const rows = current ? (all ? current.entries : current.entries.slice(0, FIRST_ROWS)) : [];
    const total = groups.reduce((n, g) => n + g.entries.length, 0);
    return (
        <>
            <Group label={`${SCREEN_LABEL[screen]}에서 하는 일`} sub={total ? `${total}` : undefined} />
            {groups.length > 1 ? (
                <div className={s.searchRow}>
                    <div role="group" aria-label="단계" className={s.seg}>
                        {groups.map((g) => (
                            <button key={g.key} type="button" aria-pressed={g.key === current?.key} onClick={() => { setOn(g.key); setAll(false); }}>
                                {g.label}<span className={s.n}>{g.entries.length}</span>
                            </button>
                        ))}
                    </div>
                </div>
            ) : null}
            {rows.length > 0 ? (
                <ul className={s.list} aria-label="도움말 주제">
                    {rows.map((e) => <TopicRow key={e.inputId} entry={e} explain onOpen={() => onNavigate({ kind: 'input', inputId: e.inputId })} />)}
                </ul>
            ) : (
                <StatusView kind="empty" title="이 화면에서 따로 하는 일은 없습니다" body="분류에서 골라 보세요."
                    actions={<button type="button" className={s.btn} onClick={() => onNavigate({ kind: 'browse' })}>분류로 찾기</button>} />
            )}
            {current && !all && current.entries.length > rows.length ? (
                <div className={s.more}>
                    <button type="button" className={[s.btn, s.ghost, s.wide].join(' ')} onClick={() => setAll(true)}>{current.entries.length - rows.length}개 더 보기</button>
                </div>
            ) : null}
            <Group label="개념" sub="부 · 소속 · 순 · 명망 · 보급 …" />
            {/* 개념 주제는 아직 없다(계약판 K7-07, 도움말 저장소가 원장 밖 주제를 받게 된 뒤). */}
            <StatusView kind="waiting" title="개념 도움말은 준비 중입니다" body="지금은 명령마다 설명이 있습니다. 부 · 소속 · 순 같은 말의 풀이는 곧 이 자리에 들어옵니다." />
        </>
    );
}

export function HelpBrowse({ onNavigate }: { onNavigate: (v: HelpView) => void }) {
    const kinds = kindGroups();
    const [on, setOn] = useState(kinds[0].kind);
    const current = kinds.find((k) => k.kind === on)!;
    const sub = on === 'GENERAL_ACTION' ? generalActionGroups() : [{ key: on, label: KIND_LABEL[on], entries: current.entries }];
    return (
        <>
            <div className={s.searchRow}>
                <div role="group" aria-label="명령 종류" className={s.seg} style={{ flexWrap: 'wrap' }}>
                    {kinds.map((k) => (
                        <button key={k.kind} type="button" aria-pressed={k.kind === on} onClick={() => setOn(k.kind)}>
                            {KIND_LABEL[k.kind]}<span className={s.n}>{k.entries.length}</span>
                        </button>
                    ))}
                </div>
            </div>
            {sub.map((g) => (
                <section key={g.key} aria-label={g.label}>
                    {sub.length > 1 ? <Group label={g.label} sub={`${g.entries.length}`} /> : null}
                    <ul className={s.list}>
                        {g.entries.map((e) => <TopicRow key={e.inputId} entry={e} explain={false} onOpen={() => onNavigate({ kind: 'input', inputId: e.inputId })} />)}
                    </ul>
                </section>
            ))}
        </>
    );
}

const SECTION_WORD: Record<string, string> = { title: '제목', explanation: '설명', example: '예' };

export function HelpSearchResults({ raw, composing, onNavigate }: { raw: string; composing: boolean; onNavigate: (v: HelpView) => void }) {
    const { state, short, retry } = useHelpSearch(raw, composing);
    if (short) return <p className={s.hint} role="status" style={{ padding: '0 14px' }}>두 글자 이상 적어 주세요.</p>;
    if (state.status === 'idle' || state.status === 'loading') return <StatusView kind="loading" rows={4} />;
    if (state.status === 'error') return <LoadFailure kind={state.kind} retry={retry} />;
    const { hits, query } = state.data;
    if (hits.length === 0) {
        return (
            <StatusView kind="empty" title={`"${query}"에 맞는 도움말이 없습니다`} body="다른 말로 찾거나 분류에서 골라 보세요. 예: 출병 · 징병 · 발령"
                actions={<button type="button" className={s.btn} onClick={() => onNavigate({ kind: 'browse' })}>분류로 찾기</button>} />
        );
    }
    return (
        <>
            <p className={s.note} role="status" aria-live="polite" style={{ padding: '0 14px', margin: 0 }}>결과 {hits.length}개</p>
            <ul className={s.list} aria-label="찾은 도움말">
                {hits.map((h) => (
                    <li key={h.id}>
                        <button type="button" className={s.row} style={{ minHeight: 60 }} onClick={() => onNavigate(topicViewOf(h.id))}>
                            <span className={s.rowMain}>
                                <span className={s.rowName}>{h.id.startsWith('commands.') ? inputName(h.id.slice(9), h.title) : helpText(h.title)}</span>
                                <span className={s.rowSub}>{helpText(h.excerpt)}</span>
                            </span>
                            <span className={s.rowEnd}>
                                {SECTION_WORD[h.matchedSection] ? <Chip>{SECTION_WORD[h.matchedSection]}</Chip> : null}
                                <DraftChip state={h.reviewState} />
                            </span>
                        </button>
                    </li>
                ))}
            </ul>
        </>
    );
}
