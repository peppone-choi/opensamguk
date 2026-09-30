'use client';

// 첫걸음(P-A02) — 막대 · 지금 단계 카드 · 8단계 목록 · 본 서버 안내판 · 목표 표시.
// 칩 숫자 = 완료 수(tutorial.done), 카드 = 단계 번호(「4단계 · 첫 발령」). 완료는 서버 진척으로만 바뀐다.
import { useEffect, useLayoutEffect, useState } from 'react';
import { useHelpTopic, type Load } from '@/hooks/useHelp';
import type { ObjectiveProgress, TutorialProgressResponse } from '@/lib/help';
import { helpText } from '@/lib/help-labels';
import { TUTORIAL_STEPS, stepById, type TutorialStep } from '@/lib/tutorial-steps';
import { Chip, Icon, Skeleton, StateBlock } from './HelpBits';
import { LoadFailure } from './HelpStates';
import s from './Help.module.css';

export function tutorialDone(progress: TutorialProgressResponse): number {
    return progress.objectives.filter((o) => o.status === 'COMPLETED').length;
}

export function currentObjective(progress: TutorialProgressResponse): ObjectiveProgress | undefined {
    return progress.objectives.find((o) => o.status === 'CURRENT');
}

function doneTime(iso: string | null): string {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
}

export function TutorialBar({ done, cur }: { done: number; cur: number | null }) {
    return (
        <div className={s.bar} role="img" aria-label={`첫걸음 ${done} / 8 완료${cur ? `, 지금 ${cur}단계` : ''}`}>
            <div className={s.barTop}><strong>첫걸음</strong><Chip tone="info">연습 서버</Chip><span className={s.count}>{done} / 8</span></div>
            <div className={s.cells}>
                {TUTORIAL_STEPS.map((st) => <i key={st.id} className={st.order <= done ? s.done : st.order === cur ? s.now : ''} />)}
            </div>
        </div>
    );
}

/** 목표 문장 — 튜토리얼 주제 글(K7-07)이 오면 그 설명, 없으면 화면 초안. */
function useGoal(step: TutorialStep, helpTopicId: string | null): { text: string; draft: boolean } {
    const [topic] = useHelpTopic(helpTopicId);
    if (topic.status === 'ready') return { text: helpText(topic.data.topic.sections.explanation), draft: topic.data.topic.reviewState === 'DRAFT' };
    return { text: step.goalDraft, draft: true };
}

export function TutorialStepCard({ objective, status, statusTone = 'muted', onGo, onHelp, here }: {
    objective: ObjectiveProgress; status?: string; statusTone?: 'moss' | 'info' | 'muted';
    onGo?: (step: TutorialStep) => void; onHelp?: (objective: ObjectiveProgress) => void; here?: boolean;
}) {
    const step = stepById(objective.id);
    const goal = useGoal(step ?? TUTORIAL_STEPS[0], objective.helpTopicId);
    if (!step) return null;
    return (
        <section className={s.card} aria-label="지금 단계">
            <div className={s.cardTop}>
                <h3>{step.order}단계 · {objective.title || step.name}</h3>
                <Chip tone="bronze">지금</Chip>
                {here ? <Chip tone="bronze">지금 이 화면</Chip>
                    : step.path !== null && onGo ? <button type="button" className={s.btn} onClick={() => onGo(step)}><Icon name="next" />그 화면으로</button> : <span />}
            </div>
            <span style={{ fontSize: 13, lineHeight: 1.5 }}>
                {goal.text} {goal.draft ? <Chip tone="info">초안</Chip> : null} <span className={s.note}>어디서 — {step.where}</span>
            </span>
            {step.how.length > 0 ? (
                <ol className={s.how}>{step.how.map((h, i) => <li key={h}><b>{i + 1}</b>{h}</li>)}</ol>
            ) : null}
            <span className={[s.status, s[statusTone]].join(' ')}><Icon name="clock" /><span>{status ?? '이 단계를 마치면 서버가 확인해 다음 단계를 엽니다.'}</span></span>
            {onHelp && objective.helpTopicId ? (
                <div className={s.cardBtns}>
                    <button type="button" className={s.btn} onClick={() => onHelp(objective)}><Icon name="help" />도움말 — {step.name}</button>
                </div>
            ) : null}
        </section>
    );
}

export function TutorialStepList({ objectives, fold }: { objectives: readonly ObjectiveProgress[]; fold?: boolean }) {
    const [openId, setOpenId] = useState<string | null>(null);
    const [unfold, setUnfold] = useState(false);
    const done = objectives.filter((o) => o.status === 'COMPLETED').length;
    const rows = fold && !unfold ? objectives.filter((o) => o.status !== 'COMPLETED') : objectives;
    return (
        <ul className={s.list} aria-label="첫걸음 8단계">
            {fold && !unfold && done > 0 ? (
                <li>
                    <button type="button" className={s.row} style={{ minHeight: 44 }} aria-expanded="false" onClick={() => setUnfold(true)}>
                        <span className={s.rowName} style={{ fontSize: 14 }}>1–{done}단계</span>
                        <span className={s.rowEnd}><Chip tone="moss">완료 {done}</Chip><span className={s.note}>펼치기</span></span>
                    </button>
                </li>
            ) : null}
            {rows.map((o) => {
                const step = stepById(o.id);
                const cur = o.status === 'CURRENT';
                return (
                    <li key={o.id}>
                        <button type="button" className={[s.row, cur ? s.current : ''].join(' ')} style={{ minHeight: 44 }}
                            aria-expanded={openId === o.id} onClick={() => setOpenId((v) => (v === o.id ? null : o.id))}>
                            <span className={[s.stepNo, cur ? s.now : ''].join(' ')}>{o.order}</span>
                            <span className={[s.rowName, o.status === 'LOCKED' ? s.locked : ''].join(' ')} style={{ fontSize: 14 }}>{o.title || step?.name}</span>
                            <span className={s.rowEnd}>
                                {o.status === 'COMPLETED' ? <><span className={s.note}>{doneTime(o.completedAt)}</span><Chip tone="moss">완료</Chip></>
                                    : cur ? <Chip tone="bronze">지금</Chip> : <Chip>잠김</Chip>}
                            </span>
                        </button>
                        {openId === o.id ? (
                            <p className={s.expand}>{o.status === 'LOCKED' ? '앞 단계를 마치면 열립니다.' : `어디서 — ${step?.where ?? ''}`}</p>
                        ) : null}
                    </li>
                );
            })}
        </ul>
    );
}

/** 본 서버 — 숫자 칩 없이 여덟 걸음을 어디서 하는지 안내만(2026-09-30 승인: 첫걸음 칩은 연습 서버에서만). */
export function TutorialGuide({ onTry }: { onTry?: () => void }) {
    return (
        <div className={s.content}>
            <span className={s.text}>본 서버에서는 숫자 칩이 없습니다. 여덟 걸음을 어디서 하는지 안내만 합니다.</span>
            <ol className={s.how}>
                {TUTORIAL_STEPS.map((st) => <li key={st.id}><b>{st.order}</b>{st.name} <span className={s.note}>— {st.where}</span></li>)}
            </ol>
            {onTry ? <button type="button" className={[s.btn, s.primary, s.wide].join(' ')} onClick={onTry}><Icon name="next" />연습 서버에서 해 보기</button> : null}
        </div>
    );
}

/** 첫걸음 보기 — 연습 서버면 진척, 아니면 안내판. */
export function TutorialView({ practice, progress, onGo, onHelpObjective, onTry, onHide, fold, retry }: {
    practice: boolean; progress: Load<TutorialProgressResponse>; onGo?: (step: TutorialStep) => void;
    onHelpObjective?: (o: ObjectiveProgress) => void; onTry?: () => void; onHide?: () => void; fold?: boolean; retry?: () => void;
}) {
    if (!practice) return <TutorialGuide onTry={onTry} />;
    if (progress.status === 'idle' || progress.status === 'loading') return <Skeleton rows={5} />;
    if (progress.status === 'error') {
        if (progress.kind === 'AUTH') return <StateBlock title="로그인하면 첫걸음을 이어 갑니다" body="진행은 계정마다 서버에 남습니다." />;
        if (progress.kind === 'NOT_FOUND') {
            // 튜토리얼 진척 API(계약판 K7-02)가 아직 없다 — 보드의 「서버 대기」.
            return <StateBlock title="첫걸음 진행은 서버 준비 중입니다" body="준비되면 이 자리에 여덟 걸음과 지금 할 일이 보입니다." />;
        }
        return <LoadFailure kind={progress.kind} retry={retry} />;
    }
    const data = progress.data;
    if (data.objectives.length === 0) return <StateBlock title="첫걸음을 준비 중입니다" />;
    const done = tutorialDone(data);
    const cur = currentObjective(data);
    const finished = done === data.objectives.length;
    return (
        <>
            <div className={s.content}>
                <TutorialBar done={done} cur={cur?.order ?? null} />
                {cur ? <TutorialStepCard objective={cur} onGo={onGo} onHelp={onHelpObjective} /> : null}
                {finished ? <StateBlock title="첫걸음을 마쳤습니다" body="연습 서버의 장수는 본 서버로 넘어가지 않습니다." /> : null}
            </div>
            <TutorialStepList objectives={data.objectives} fold={fold} />
            {onHide ? (
                <div className={s.foot}><button type="button" className={[s.btn, s.ghost, s.wide].join(' ')} onClick={onHide}>첫걸음 안내 숨기기</button></div>
            ) : null}
        </>
    );
}

// ── 목표 표시 ───────────────────────────────────────────────────────────────
interface Box { top: number; left: number; width: number; height: number }

function findTarget(step: TutorialStep): HTMLElement | null {
    for (const sel of step.anchors) {
        const el = document.querySelector<HTMLElement>(sel);
        if (el && el.getClientRects().length > 0) return el;
    }
    return null;
}

/**
 * 지금 단계의 대상 조작 둘레에 청동 테두리(누르기를 먹지 않는다) + 작은 카드(누를 수 있는 것은 카드뿐).
 * 대상이 이 화면에 없으면 카드만 띄운다(「그 화면으로」). 화면 전체를 덮는 상자는 두지 않는다.
 */
export function CoachMark({ objective, hidden, onHelp, onHide, onGo }: {
    objective: ObjectiveProgress | undefined; hidden?: boolean; onHelp?: () => void; onHide?: () => void; onGo?: (step: TutorialStep) => void;
}) {
    const step = objective ? stepById(objective.id) : undefined;
    const [box, setBox] = useState<Box | null>(null);
    const [tick, setTick] = useState(0);
    const goal = useGoal(step ?? TUTORIAL_STEPS[0], objective?.helpTopicId ?? null);

    useEffect(() => {
        if (!step || hidden) return undefined;
        const bump = () => setTick((t) => t + 1);
        window.addEventListener('resize', bump);
        window.addEventListener('scroll', bump, true);
        const mo = new MutationObserver(bump);
        mo.observe(document.body, { childList: true, subtree: true });
        return () => {
            window.removeEventListener('resize', bump);
            window.removeEventListener('scroll', bump, true);
            mo.disconnect();
        };
    }, [step, hidden]);

    useLayoutEffect(() => {
        if (!step || hidden) {
            setBox(null);
            return undefined;
        }
        const el = findTarget(step);
        if (!el) {
            setBox(null);
            return undefined;
        }
        const r = el.getBoundingClientRect();
        setBox((prev) => (prev && prev.top === r.top && prev.left === r.left && prev.width === r.width && prev.height === r.height
            ? prev : { top: r.top, left: r.left, width: r.width, height: r.height }));
        const prevDesc = el.getAttribute('aria-describedby');
        el.setAttribute('aria-describedby', [prevDesc, 'k7-coach'].filter(Boolean).join(' '));
        return () => {
            if (prevDesc) el.setAttribute('aria-describedby', prevDesc);
            else el.removeAttribute('aria-describedby');
        };
    }, [step, hidden, tick]);

    if (!step || !objective || hidden) return null;
    const pad = 6;
    const cardTop = box ? Math.min(box.top + box.height + pad + 8, window.innerHeight - 180) : 64;
    const cardLeft = box ? Math.max(8, Math.min(box.left, window.innerWidth - 308)) : 8;
    return (
        <>
            {box ? (
                <span className={s.ring} aria-hidden="true" data-coach-ring=""
                    style={{ top: box.top - pad, left: box.left - pad, width: box.width + pad * 2, height: box.height + pad * 2 }} />
            ) : null}
            <section id="k7-coach" role="region" aria-label="첫걸음 안내" className={s.coach} style={{ top: cardTop, left: cardLeft }}>
                <b>첫걸음 · {step.order}단계 — {objective.title || step.name}</b>
                <p>{goal.text}</p>
                {!box && step.path !== null ? <span className={s.note}>{step.where}에서 합니다.</span> : null}
                <div className={s.coachBtns}>
                    {!box && step.path !== null && onGo ? <button type="button" className={s.btn} onClick={() => onGo(step)}>그 화면으로</button> : null}
                    {onHelp ? <button type="button" className={[s.btn, s.ghost].join(' ')} onClick={onHelp}><Icon name="help" />도움말</button> : null}
                    {onHide ? <button type="button" className={[s.btn, s.ghost].join(' ')} onClick={onHide}>숨기기</button> : null}
                </div>
            </section>
        </>
    );
}
