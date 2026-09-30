'use client';

// 주제 보기 — 입력 주제(문맥: 원장 투영 + 안 되는 경우)와 원장 밖 주제(개념 · 첫걸음, K7-07 뒤).
// 비용 · 대상은 「제출 전 명령 화면에서」 — 도움말은 실행 성공 · 비용을 약속하지 않는다(계약).
import { useState } from 'react';
import { useFailureHelp, useHelpContext, useHelpTopic, type Load } from '@/hooks/useHelp';
import type { HelpTopic, InputContract } from '@/lib/help';
import { KIND_LABEL, SCOPE_LABEL, helpText, inputName, targetLabel, timingLabel, whoLabel } from '@/lib/help-labels';
import type { HelpView } from '@/lib/help-route';
import { StatusView } from '@opensamguk/ui';
import { Chip, CostLine, DraftChip, Icon, PlannedChip } from './HelpBits';
import { LoadFailure } from './HelpStates';
import s from './Help.module.css';

const PLUMBING = new Set(['WRONG_RULE_PROFILE', 'UNKNOWN_INPUT', 'INVALID_REQUEST', 'INVALID_INPUT_CHANNEL', 'ACTOR_NOT_FOUND']);
const FIRST_FAILS = 5;

function Para({ label, text }: { label: string; text: string }) {
    return (
        <div className={s.para}>
            <span className={s.label}>{label}</span>
            <span className={s.text}>{helpText(text)}</span>
        </div>
    );
}

function Sections({ topic }: { topic: HelpTopic }) {
    const sec = topic.sections;
    return (
        <>
            <Para label="설명" text={sec.explanation} />
            <Para label="이렇게 합니다" text={sec.example} />
            <div className={s.pair}>
                <div className={s.ok}><b>잘 되면</b><span>{helpText(sec.successExample)}</span></div>
                <div className={s.no}><b>안 되면</b><span>{helpText(sec.failureExample)}</span></div>
            </div>
            <Para label="다시 하려면" text={sec.recoveryAdvice} />
        </>
    );
}

function Rules({ input }: { input: InputContract }) {
    const rows: [string, React.ReactNode][] = [
        ['누가', whoLabel(input)],
        ['언제', timingLabel(input.timing)],
        ['어디에', SCOPE_LABEL[input.effectScope] ?? ''],
        ['대상', targetLabel(input)],
        ['비용', <CostLine key="cost" cost={input.costSchema} />],
    ];
    return (
        <section className={s.rules} aria-label="이 명령의 규칙">
            <span className={s.label}>이 명령의 규칙</span>
            <dl style={{ margin: 0, display: 'flex', flexDirection: 'column', gap: 3 }}>
                {rows.filter(([, v]) => v !== '').map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
            </dl>
            <span className={s.note}>실제 비용과 대상은 제출 전에 명령 화면에서 보여 줍니다.</span>
        </section>
    );
}

function FailRow({ code, inputId, hi, onOpen }: { code: string; inputId: string; hi: boolean; onOpen: () => void }) {
    const [help] = useFailureHelp(code, inputId);
    const text = help.status === 'ready' ? helpText(help.data.explanation) : help.status === 'error' ? '설명을 불러오지 못했습니다' : '…';
    return (
        <li>
            <button type="button" className={[s.failRow, hi ? s.hi : ''].join(' ')} onClick={onOpen} aria-current={hi ? 'true' : undefined}>
                <span style={{ minWidth: 0, flex: 1 }}>{text}</span>
                <Icon name="next" />
            </button>
        </li>
    );
}

function Fails({ input, highlight, onNavigate }: { input: InputContract; highlight?: string; onNavigate: (v: HelpView) => void }) {
    const codes = input.failureReasons.filter((c) => !PLUMBING.has(c) || c === highlight);
    const [open, setOpen] = useState(Boolean(highlight));
    const [all, setAll] = useState(false);
    const ordered = highlight && codes.includes(highlight) ? [highlight, ...codes.filter((c) => c !== highlight)] : codes;
    const shown = all ? ordered : ordered.slice(0, FIRST_FAILS);
    return (
        <div className={s.fails}>
            <button type="button" className={[s.btn, s.ghost, s.wide].join(' ')} aria-expanded={open} onClick={() => setOpen((o) => !o)}
                style={{ justifyContent: 'space-between' }}>
                <span>안 되는 경우 {input.failureReasons.length}가지</span>
                <span className={s.note}>{open ? '접기' : '펼치기'}</span>
            </button>
            {open ? (
                <>
                    <ul className={s.list}>
                        {shown.map((c) => (
                            <FailRow key={c} code={c} inputId={input.inputId} hi={c === highlight}
                                onOpen={() => onNavigate({ kind: 'failure', reason: c, inputId: input.inputId })} />
                        ))}
                    </ul>
                    {!all && ordered.length > shown.length ? (
                        <button type="button" className={s.link} onClick={() => setAll(true)}>{ordered.length - shown.length}가지 더 보기</button>
                    ) : null}
                </>
            ) : null}
        </div>
    );
}

function TopicBody({ topic, input, highlight, onNavigate, goToInput }: {
    topic: HelpTopic; input?: InputContract; highlight?: string; onNavigate: (v: HelpView) => void; goToInput?: (inputId: string) => void;
}) {
    const planned = input?.deliveryState === 'PLANNED';
    const title = input ? inputName(input.inputId, input.displayName ?? topic.title) : helpText(topic.title);
    return (
        <>
            <div className={s.content}>
                <div className={s.topicHead}>
                    <h3 className={s.topicTitle}>{title}</h3>
                    {input ? <Chip>{KIND_LABEL[input.kind]}</Chip> : null}
                    <DraftChip state={topic.reviewState} />
                    <PlannedChip deliveryState={input?.deliveryState} />
                </div>
                {planned
                    ? <span className={s.warn}>아직 준비 중인 명령입니다. 설명만 볼 수 있습니다.</span>
                    : topic.reviewState === 'DRAFT' ? <span className={s.note}>초안 — 아직 검수 전인 설명입니다. 실제 결과와 다르면 결과를 믿으세요.</span> : null}
                <Sections topic={topic} />
                {input ? <Rules input={input} /> : null}
                {input ? <Fails input={input} highlight={highlight} onNavigate={onNavigate} /> : null}
                {topic.relatedTopicIds.length > 0 ? (
                    <div className={s.para}>
                        <span className={s.label}>관련 도움말</span>
                        {topic.relatedTopicIds.map((id) => (
                            <button key={id} type="button" className={s.link} onClick={() => onNavigate(topicViewOf(id))}>
                                {id.startsWith('commands.') ? inputName(id.slice('commands.'.length)) : '관련 도움말'} →
                            </button>
                        ))}
                    </div>
                ) : null}
            </div>
            {input && goToInput ? (
                <div className={s.foot}>
                    {planned ? (
                        <button type="button" className={[s.btn, s.wide].join(' ')} aria-disabled="true" data-input-id={input.inputId}>이 명령 하러 가기 — 준비 중</button>
                    ) : (
                        <button type="button" className={[s.btn, s.primary, s.wide].join(' ')} data-input-id={input.inputId} onClick={() => goToInput(input.inputId)}>
                            <Icon name="next" />이 명령 하러 가기
                        </button>
                    )}
                </div>
            ) : null}
        </>
    );
}

/** 주제 id → 보기. 입력 주제는 `commands.<inputId>` 규칙(원장 74행 모두 이 규칙)이라 문맥 보기로 연다. */
export function topicViewOf(topicId: string): HelpView {
    return topicId.startsWith('commands.') ? { kind: 'input', inputId: topicId.slice('commands.'.length) } : { kind: 'topic', topicId };
}

function Loaded<T>({ load, retry, children }: { load: Load<T>; retry: () => void; children: (data: T) => React.ReactNode }) {
    if (load.status === 'idle' || load.status === 'loading') return <StatusView kind="loading" rows={6} />;
    if (load.status === 'error') return <LoadFailure kind={load.kind} retry={retry} />;
    return <>{children(load.data)}</>;
}

export function InputTopicView({ inputId, highlight, onNavigate, goToInput }: {
    inputId: string; highlight?: string; onNavigate: (v: HelpView) => void; goToInput?: (inputId: string) => void;
}) {
    const [load, retry] = useHelpContext(inputId);
    return <Loaded load={load} retry={retry}>{(d) => <TopicBody topic={d.topic} input={d.input} highlight={highlight} onNavigate={onNavigate} goToInput={goToInput} />}</Loaded>;
}

export function PlainTopicView({ topicId, onNavigate }: { topicId: string; onNavigate: (v: HelpView) => void }) {
    const [load, retry] = useHelpTopic(topicId);
    return <Loaded load={load} retry={retry}>{(d) => <TopicBody topic={d.topic} onNavigate={onNavigate} />}</Loaded>;
}

/** 실패 사유 보기 — 사유 한 문장 · 이렇게 하면 됩니다 · 이 사유가 나오는 명령. */
export function FailureView({ reason, inputId, onNavigate }: { reason: string; inputId?: string; onNavigate: (v: HelpView) => void }) {
    const [load, retry] = useFailureHelp(reason, inputId ?? null);
    return (
        <Loaded load={load} retry={retry}>
            {(d) => (
                <div className={s.content}>
                    <div className={s.topicHead}><h3 className={s.topicTitle}>{helpText(d.explanation)}</h3><DraftChip state={d.reviewState} /></div>
                    <div className={s.recovery}><b>이렇게 하면 됩니다</b><span className={s.text}>{helpText(d.recoveryAdvice)}</span></div>
                    {d.relatedTopicIds.length > 0 ? (
                        <div className={s.para}>
                            <span className={s.label}>이 사유가 나오는 명령</span>
                            {d.relatedTopicIds.slice(0, 5).map((id) => (
                                <button key={id} type="button" className={s.link} onClick={() => onNavigate(topicViewOf(id))}>
                                    {id.startsWith('commands.') ? inputName(id.slice('commands.'.length)) : '관련 도움말'} →
                                </button>
                            ))}
                            {d.relatedTopicIds.length > 5 ? <span className={s.note}>그 밖에 {d.relatedTopicIds.length - 5}개</span> : null}
                        </div>
                    ) : null}
                </div>
            )}
        </Loaded>
    );
}
