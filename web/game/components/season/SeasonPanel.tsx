'use client';

// 계절 패널 내용(K8) — 머리줄 계절 칩을 누르면 셸이 여는 자리(데스크톱 떠 있는 패널 · 모바일 하단 시트) 안에 들어간다.
// 보드 V31SystemSeason · MSeason, K8 설계서 P-K07. 서버 없이 되는 것만 그린다:
//  - 1년 36순 달력(틀은 고정), 지금 순은 서버 값(month · turnPhase)만 — 없으면 「확인 중」, 칸을 칠하지 않는다.
//  - 닫힌 길 · 내 영지 계절 사건은 서버 읽기(계약판 K8-08, C5 Wave 2) 전까지 「준비 중」(StatusView waiting).
//  - 통행 읽기(passage)가 오면 닫힌 길 칸만 따로 그린다 — 자료가 빠지면(UNAVAILABLE) 「통행 정보 없음」 + 다시 읽기, 「닫힌 길 없음」은 READY 빈 목록일 때만.
//  - 계절 사건 읽기(events)가 오면 「내 영지 계절 사건」 줄을 그린다 — 현 · 사건 · 방향(보드 「▼ 민심 등」, 수치 없음).
//    셈하지 못함(unavailable)은 「사건 없음」과 따로 적는다. 사건 꼴 · 이름은 lib/season-events.

import { Icon, StatusView } from '@opensamguk/ui';
import { calendarCells, calendarSegments, momentFrom, momentLabel, passageView, seasonNow, type GameMoment, type SeasonPassageRead } from '@/lib/season';
import { effectMarks, effectMarkText, SEASON_EVENT_LABELS, seasonEventLabel, type SeasonEventsState } from '@/lib/season-events';
import styles from './season.module.css';

export interface SeasonPanelProps {
    /** 서버 front-info global.month. */
    readonly month: number | null | undefined;
    /** 서버 front-info global.turnPhase(1 상순 · 2 중순 · 3 하순). */
    readonly phase: number | null | undefined;
    readonly onClose: () => void;
    /** 셸이 dialog 의 aria-labelledby 로 쓰는 제목 id. */
    readonly titleId?: string;
    /** 계절 GET 의 통행 부분과 다시 읽기. 서버 읽기가 붙기 전에는 넘기지 않는다(닫힌 길 · 계절 사건을 한 「준비 중」으로 보인다). */
    readonly passage?: { readonly read: SeasonPassageRead; readonly onReload: () => void };
    /**
     * 내 영지 계절 사건과 다시 읽기. 서버 읽기(계약판 K8-08 · K8-EV)가 붙기 전에는 넘기지 않는다.
     * countyName 은 현 이름(지도 미리보기 등) — 모르면 null 이고 「어느 현」으로 적는다.
     */
    readonly events?: { readonly state: SeasonEventsState; readonly countyName: (countyId: number) => string | null; readonly onReload: () => void };
}

const SEASON_EVENTS = SEASON_EVENT_LABELS;

export default function SeasonPanel({ month, phase, onClose, titleId = 'season-panel-title', passage, events }: SeasonPanelProps) {
    const moment = momentFrom(month, phase);
    const now = moment ? seasonNow(moment) : null;
    return (
        <div className={styles.panel} data-testid="season-panel">
            <div className={styles.head}>
                <h2 id={titleId} className={styles.title}>{now ? `계절 — ${now.season}` : '계절'}</h2>
                <button type="button" className={styles.close} aria-label="계절 닫기" onClick={onClose} autoFocus>
                    <Icon name="close" size={20} />
                </button>
            </div>
            <div className={styles.body}>
                <Calendar moment={moment} />
                <p className={styles.summary}>
                    {now && moment ? (
                        <>
                            <b className={styles.bronze}>{now.season}</b> — {now.endMonth}월 하순까지 {now.remainingPhases}순 남았습니다. 다음은 {now.next.name}({now.next.startMonth}–{now.next.endMonth}월).
                        </>
                    ) : (
                        <span className={styles.muted}>지금이 몇 월 몇 순인지 확인 중입니다.</span>
                    )}
                </p>
                {passage || events ? (
                    <>
                        {passage ? <Passage read={passage.read} onReload={passage.onReload} /> : null}
                        {events ? (
                            <Events state={events.state} countyName={events.countyName} onReload={events.onReload} />
                        ) : (
                            <StatusView
                                kind="waiting"
                                className={styles.waiting}
                                title="계절 사건은 아직 없습니다"
                                body={`내 영지의 계절 사건(${SEASON_EVENTS})은 서버가 준비되면 이 자리에 보입니다.`}
                            />
                        )}
                    </>
                ) : (
                    <StatusView
                        kind="waiting"
                        className={styles.waiting}
                        title="계절 소식은 아직 없습니다"
                        body={`이번 계절에 닫힌 길과 내 영지의 계절 사건(${SEASON_EVENTS})은 서버가 준비되면 이 자리에 보입니다.`}
                    />
                )}
            </div>
        </div>
    );
}

function Passage({ read, onReload }: { readonly read: SeasonPassageRead; readonly onReload: () => void }) {
    const view = passageView(read);
    if (view.kind === 'unavailable') {
        return (
            <StatusView
                kind="unavailable"
                className={styles.waiting}
                title="통행 정보 없음"
                body="이번 계절에 어느 길이 닫혔는지 서버가 아직 셈하지 못했습니다. 길이 다 열렸다는 뜻은 아닙니다."
                onReload={onReload}
            />
        );
    }
    if (view.kind === 'waiting') return null;
    return (
        <p className={styles.summary} data-passage={view.kind}>
            {view.kind === 'all-open' ? '이번 계절에 닫힌 길이 없습니다.' : `이번 계절에 닫힌 길이 ${view.count}곳 있습니다.`}
        </p>
    );
}

/** 내 영지 계절 사건 — 줄마다 현 · 사건 · 방향. */
function Events({ state, countyName, onReload }: { readonly state: SeasonEventsState; readonly countyName: (countyId: number) => string | null; readonly onReload: () => void }) {
    if (state.kind === 'unavailable') {
        return (
            <StatusView
                kind="unavailable"
                className={styles.waiting}
                title="계절 사건 정보 없음"
                body="이번 순 내 영지의 계절 사건을 서버가 아직 셈하지 못했습니다. 사건이 없다는 뜻은 아닙니다."
                onReload={onReload}
            />
        );
    }
    if (state.occurrences.length === 0) return <p className={styles.summary} data-events="none">이번 계절 내 영지에 계절 사건이 없습니다.</p>;
    return (
        <section className={styles.events} aria-label="내 영지 계절 사건">
            <h3 className={styles.eventsHead}>내 영지 계절 사건</h3>
            <ul className={styles.eventList}>
                {state.occurrences.map((o, i) => (
                    <li key={`${o.countyId}-${o.kind}-${i}`} className={styles.eventRow}>
                        <span className={styles.eventCounty}>{countyName(o.countyId) ?? '어느 현'}</span>
                        <span className={styles.eventKind}>{seasonEventLabel(o.kind)}</span>
                        <span className={styles.eventMarks}>
                            {effectMarks(o.effect).map((m) => (
                                <span key={m.label} className={styles.mark}>{effectMarkText(m)}</span>
                            ))}
                        </span>
                    </li>
                ))}
            </ul>
        </section>
    );
}

function Calendar({ moment }: { readonly moment: GameMoment | null }) {
    const cells = calendarCells(moment);
    const label = moment ? `1년 36순 달력 — 지금 ${momentLabel(moment)}` : '1년 36순 달력 — 지금 날짜 확인 중';
    return (
        <div className={styles.calendar} role="img" aria-label={label} data-now={moment ? momentLabel(moment) : ''}>
            <div className={styles.segments} aria-hidden="true">
                {calendarSegments().map((s) => {
                    const on = moment !== null && moment.month >= s.startMonth && moment.month <= s.endMonth;
                    return (
                        <span key={`${s.name}-${s.startMonth}`} className={on ? styles.segOn : styles.seg} style={{ flexGrow: s.endMonth - s.startMonth + 1 }}>
                            {s.name}
                        </span>
                    );
                })}
            </div>
            <div className={styles.cells} aria-hidden="true">
                {cells.map((c, i) => <i key={i} className={styles[c]} data-cell={c} />)}
            </div>
            <div className={styles.ends} aria-hidden="true">
                <span>1월</span>
                <span>12월</span>
            </div>
        </div>
    );
}
