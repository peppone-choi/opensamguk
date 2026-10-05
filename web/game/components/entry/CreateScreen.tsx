'use client';

// 새 장수 만들기(P-E02) — 보드 V31K5Create · MCreate0 · MCreate1 · MCreate2 · MCreate4(D83 · D84 · D83 보충), 요구 문서 §3.3.
// 생성 옵션(K5-02) · 생성 쓰기(K5-01, 서버 #1137) 계약 값만 쓴다.
//  - 역할: 「주공을 섬기며 시작」은 지금 계약 그대로 된다(CUSTOM = 재야로 만든 뒤 출사, D80).
//    「예비 주공으로 시작」은 요청에 역할 칸이 없어 서버 대기(사유 단추).
//  - 본관 현: 서버 후보(주 · 군 거르기 · 현 찾기 · 불가 사유). 지도 위 고르기 층은 다음 PR(지금은 목록).
//  - 적성 · 처음 명망 · 역할별 한도는 계약에 없다 — 서버 대기 문장.
// 서버가 없으면(404 · 503) 「생성 대기」. CREATED 면 세션(front-info)에 새 장수가 보인 뒤 출사(P-E04)로.

import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import { Button, Chip, Panel, Portrait, ReasonTooltip, SectionHeader, StatusView, TargetCandidateList, useTargetPicker, useViewportClass } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useCreationOptions } from '@/hooks/useCreationOptions';
import { useCreationRequest } from '@/hooks/useCreationRequest';
import { useEnterAfterCreated } from '@/hooks/useEnterAfterCreated';
import { useGameSession } from '@/lib/campaign-session';
import { campaignHref } from '@/lib/campaign-screens';
import type { CreationStats, GeneralCreationOptions } from '@/lib/creation-contract';
import {
    blockReason, bumpStat, commanderiesOf, countyCandidate, evenStats, filterCounties, nameHelp, nameProblem, provincesOf, reasonText,
    STAT_KEYS, statSum, type CreateDraft,
} from '@/lib/create-view';
import { STAT_LABELS } from '@/lib/historical-view';
import CreationProgress from './CreationProgress';
import CreationWaiting from './CreationWaiting';
import styles from './creation.module.css';

const RETAINER_NOTE = '먼저 재야로 만들고, 다음 화면에서 섬길 주공을 고릅니다. 다음 개인 턴에 그 주공의 부에 들어갑니다.';
const PRE_LORD_WAIT = '예비 주공으로 시작하기는 서버가 아직 받지 않습니다.';
const STEPS = ['역할', '본관', '능력', '주의 · 개성', '확인'] as const;
const LABEL = Object.fromEntries(STAT_LABELS.map((s) => [s.key, s.label])) as Record<keyof CreationStats, string>;

// ── 부품 ─────────────────────────────────────────────────────────────

function RolePick({ role, setRole }: { readonly role: CreateDraft['role']; readonly setRole: (r: CreateDraft['role']) => void }) {
    return (
        <div className={styles.rolePick}>
            <div role="listbox" aria-label="시작할 역할" className={styles.roleList}>
                <button type="button" role="option" aria-selected={role === 'RETAINER'} className={`${styles.roleOpt}${role === 'RETAINER' ? ` ${styles.cardOn}` : ''}`} onClick={() => setRole('RETAINER')}>
                    <span className={styles.roleName}>주공을 섬기며 시작</span>
                    <span className={styles.cardSub}>재야로 만든 뒤 섬길 주공을 고릅니다</span>
                    {role === 'RETAINER' ? <Chip tone="bronze">고름</Chip> : null}
                </button>
                <ReasonTooltip reason={PRE_LORD_WAIT}>
                    <button type="button" role="option" aria-selected={false} aria-disabled="true" className={styles.roleOpt}>
                        <span className={styles.roleName}>예비 주공으로 시작</span>
                        <span className={styles.cardSub}>본관 현에서 거병을 준비합니다 · 서버 준비 중</span>
                    </button>
                </ReasonTooltip>
            </div>
            {role === 'RETAINER' ? <p className={styles.help}>{RETAINER_NOTE}</p> : null}
        </div>
    );
}

function CountyPick({ options, countyId, setCountyId }: { readonly options: GeneralCreationOptions; readonly countyId: number | null; readonly setCountyId: (id: number | null) => void }) {
    const counties = options.nativeCounties;
    const [province, setProvince] = useState<string | null>(null);
    const [commandery, setCommandery] = useState<string | null>(null);
    const [q, setQ] = useState('');
    const shown = useMemo(() => filterCounties(counties, { province, commandery, q }), [counties, province, commandery, q]);
    const candidates = useMemo(() => shown.map((c) => countyCandidate(c, c.cityId === countyId)), [shown, countyId]);
    const picker = useTargetPicker({ kind: 'place', candidates, onCancel: () => setCountyId(null), initialSelected: countyId === null ? [] : [String(countyId)] });
    const picked = picker.selected[0];
    useEffect(() => {
        if (picked === undefined) return;
        const id = Number(picked);
        if (id !== countyId) setCountyId(id);
    }, [picked, countyId, setCountyId]);
    return (
        <div className={styles.countyPick}>
            <div className={styles.countyFilters}>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>주</span>
                    <select className="os-input" value={province ?? ''} onChange={(e) => { setProvince(e.target.value || null); setCommandery(null); }}>
                        <option value="">전체</option>
                        {provincesOf(counties).map((p) => <option key={p} value={p}>{p}</option>)}
                    </select>
                </label>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>군 · 국</span>
                    <select className="os-input" value={commandery ?? ''} onChange={(e) => setCommandery(e.target.value || null)}>
                        <option value="">전체</option>
                        {commanderiesOf(counties, province).map((c) => <option key={c} value={c}>{c}</option>)}
                    </select>
                </label>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>현 찾기</span>
                    <input type="search" className="os-input" placeholder="현 이름" value={q} onChange={(e) => setQ(e.target.value)} />
                </label>
            </div>
            <p className={styles.wait}>지도 위에서 고르기는 준비 중입니다. 목록에서 고르세요(성이 있는 현만 고를 수 있습니다).</p>
            {candidates.length === 0
                ? <StatusView kind="empty" title="맞는 현이 없습니다" body="거르기를 바꾸거나 현 이름을 줄여 보세요." />
                : <TargetCandidateList picker={picker} candidates={candidates} label="본관 현 후보" />}
        </div>
    );
}

/**
 * 능력 값 칸 — 치는 동안은 친 글자를 그대로 들고, 범위 안 정수가 되면 곧바로 반영한다(합 · 미리보기가 따라온다).
 * 범위 밖 · 빈 칸은 칸을 떠날 때 · Enter 에서 자르거나(범위 밖) 되돌린다(빈 칸). 치는 중에 자르면 「7」이 20 이 되어 75 를 칠 수 없다(#1329 리뷰).
 */
function StatValue({ label, value, rule, onValue }: {
    readonly label: string; readonly value: number; readonly rule: GeneralCreationOptions['statRule']; readonly onValue: (n: number) => void;
}) {
    const [text, setText] = useState<string | null>(null);
    // 바깥(−/+ · 고르게)에서 값이 바뀌면 치던 글자를 버린다 — 친 값이 반영돼 같아진 것은 그대로 둔다
    useEffect(() => { setText((t) => (t !== null && Number(t) !== value ? null : t)); }, [value]);
    const commit = () => {
        if (text === null) return;
        // 빈 칸은 NaN — bumpStat 이 원래 값을 둔다
        onValue(text.trim() === '' ? Number.NaN : Number(text));
        setText(null);
    };
    return (
        <input type="number" inputMode="numeric" className={`os-input ${styles.statValue}`} aria-label={label} min={rule.min} max={rule.max}
            value={text ?? String(value)}
            onChange={(e) => {
                const raw = e.target.value;
                setText(raw);
                const n = Number(raw);
                if (raw.trim() !== '' && Number.isInteger(n) && n >= rule.min && n <= rule.max) onValue(n);
            }}
            onBlur={commit}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commit(); } }} />
    );
}

function StatRows({ rule, stats, setStats }: { readonly rule: GeneralCreationOptions['statRule']; readonly stats: CreationStats; readonly setStats: (s: CreationStats) => void }) {
    const left = rule.total - statSum(stats);
    return (
        <div className={styles.statsEdit}>
            {STAT_KEYS.map((key) => {
                const v = stats[key];
                const pct = Math.round(((v - rule.min) / Math.max(1, rule.max - rule.min)) * 100);
                return (
                    <div key={key} className={styles.statEdit}>
                        <span className={styles.statName}>{LABEL[key]}</span>
                        {v <= rule.min
                            ? <Button variant="ghost" disabled reason={`${LABEL[key]}은 ${rule.min}보다 낮출 수 없습니다`} aria-label={`${LABEL[key]} 내리기`}>−</Button>
                            : <Button variant="ghost" aria-label={`${LABEL[key]} 내리기`} onClick={() => setStats(bumpStat(stats, key, v - 1, rule))}>−</Button>}
                        <span className={styles.statBar} aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
                        {v >= rule.max
                            ? <Button variant="ghost" disabled reason={`${LABEL[key]}은 ${rule.max}보다 올릴 수 없습니다`} aria-label={`${LABEL[key]} 올리기`}>+</Button>
                            : <Button variant="ghost" aria-label={`${LABEL[key]} 올리기`} onClick={() => setStats(bumpStat(stats, key, v + 1, rule))}>+</Button>}
                        <StatValue label={`${LABEL[key]} 값`} value={v} rule={rule} onValue={(n) => setStats(bumpStat(stats, key, n, rule))} />
                    </div>
                );
            })}
            <div className={styles.statFoot}>
                <span className={left === 0 ? styles.okLine : styles.errLine} role="status">
                    {left === 0 ? `합 ${rule.total} — 맞습니다` : left > 0 ? `${left}점이 남았습니다 — 합이 ${rule.total}이어야 합니다` : `${-left}점이 넘칩니다 — 합이 ${rule.total}이어야 합니다`}
                </span>
                <Button variant="ghost" onClick={() => setStats(evenStats(rule))}>고르게</Button>
            </div>
            <p className={styles.muted}>각 능력은 {rule.min}–{rule.max}, 합계는 {rule.total}입니다.</p>
        </div>
    );
}

function PickGrid({ label, items, value, onChange, help }: {
    readonly label: string; readonly items: GeneralCreationOptions['ideologies']; readonly value: string | null; readonly onChange: (id: string) => void; readonly help?: string;
}) {
    return (
        <div className={styles.field}>
            <span className={styles.fieldLabel}>{label}</span>
            <div role="group" aria-label={label} className={styles.pickGrid}>
                {items.map((it) => (
                    <button key={it.id} type="button" aria-pressed={it.id === value} className={`os-button${it.id === value ? ` ${styles.pickOn}` : ''}`} onClick={() => onChange(it.id)}>{it.label}</button>
                ))}
            </div>
            {help ? <p className={styles.muted}>{help}</p> : null}
        </div>
    );
}

function Preview({ options, draft }: { readonly options: GeneralCreationOptions; readonly draft: CreateDraft }) {
    const county = options.nativeCounties.find((c) => c.cityId === draft.countyId) ?? null;
    const ideology = options.ideologies.find((o) => o.id === draft.ideologyId) ?? null;
    const trait = options.traits.find((o) => o.id === draft.traitId) ?? null;
    const name = draft.name.normalize('NFC').trim();
    return (
        <div className={styles.preview}>
            <div className={styles.detailTop}>
                <Portrait picture={null} size="card" alt="새 장수 초상(기본 실루엣)" />
                <div className={styles.detailStats}>
                    <span className={styles.previewName}>{name || '이름을 쓰세요'}</span>
                    <span className={styles.chips}>
                        {county ? <Chip tone="bronze">{`향당 · ${county.name}`}</Chip> : null}
                        {ideology ? <Chip>{`주의 · ${ideology.label}`}</Chip> : null}
                        {trait ? <Chip>{`개성 · ${trait.label}`}</Chip> : null}
                    </span>
                    <dl className={styles.statRows}>
                        {STAT_KEYS.map((key) => (
                            <div key={key} className={styles.statRow}><dt>{LABEL[key]}</dt><dd>{draft.stats[key]}</dd></div>
                        ))}
                    </dl>
                </div>
            </div>
            <dl className={styles.kv}>
                <div><dt>신분</dt><dd>재야 → 출사</dd></div>
                <div><dt>시작</dt><dd>{county ? `${county.name}(본관 현)` : '본관 현을 고르세요'}</dd></div>
            </dl>
            <p className={styles.wait}>적성(장 · 리 · 사 · 사자)과 처음 명망은 만든 뒤 서버가 정합니다.</p>
            <p className={styles.muted}>본관이 같은 인물과 향당 결속이 생깁니다. 주의 · 개성은 지금은 표시용입니다.</p>
        </div>
    );
}

function SubmitButton({ reason, onSubmit, block = false }: { readonly reason: string | null; readonly onSubmit: () => void; readonly block?: boolean }) {
    return reason
        ? <Button variant="primary" block={block} disabled reason={reason} data-guide="tutorial.createGeneral">만들고 섬길 주공 고르기</Button>
        : <Button variant="primary" block={block} onClick={onSubmit} data-guide="tutorial.createGeneral">만들고 섬길 주공 고르기</Button>;
}

// ── 화면 ─────────────────────────────────────────────────────────────

function Editor({ options }: { readonly options: GeneralCreationOptions }) {
    const [draft, setDraft] = useState<CreateDraft>(() => ({
        role: 'RETAINER', countyId: null, name: '', stats: evenStats(options.statRule), ideologyId: null, traitId: null,
    }));
    const [step, setStep] = useState(0);
    const ids = useId();
    const { phase, submit, reset } = useCreationRequest();
    const mobile = useViewportClass() === 'mobile';
    const session = useGameSession();
    // CREATED 면 세션(front-info)에 새 장수가 보인 뒤 출사로 간다 — 바로 가면 출사가 옛 세션(장수 없음)으로 입구로 되돌린다.
    const enter = useEnterAfterCreated(phase.kind === 'created', campaignHref('join', session.serverId));

    const set = <K extends keyof CreateDraft>(key: K) => (value: CreateDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));
    const reason = blockReason(draft, { stat: options.statRule, name: options.nameRule }, options.nativeCounties);
    const doSubmit = () => {
        if (reason || draft.countyId === null || !draft.ideologyId || !draft.traitId) return;
        void submit(options.worldId, {
            kind: 'CUSTOM', name: draft.name.normalize('NFC').trim(), nativeCountyId: draft.countyId,
            stats: draft.stats, ideologyId: draft.ideologyId, traitId: draft.traitId,
        });
    };

    if (phase.kind !== 'idle') {
        // 만들었는데 세션에 끝내 장수가 안 보이면 「아직 반영되지 않음 · 입구에서 이어 보기」
        const shown = phase.kind === 'created' && enter === 'late' ? { kind: 'slow' as const, requestId: '' } : phase;
        return (
            <CreationProgress phase={shown} next="출사 화면으로" retryLabel="입력으로 돌아가기" onRetry={reset}
                alternate={{ slug: 'create/historical', label: '역사 인물 고르기' }} />
        );
    }

    const nameErr = draft.name ? nameProblem(draft.name, options.nameRule) : null;
    const nameField = (
        <div className={styles.field}>
            <label className={styles.fieldLabel} htmlFor={`${ids}-name`}>이름</label>
            <input id={`${ids}-name`} type="text" className="os-input" value={draft.name} onChange={(e) => set('name')(e.target.value)}
                aria-invalid={nameErr ? true : undefined} aria-describedby={`${ids}-name-help`} />
            <span id={`${ids}-name-help`} className={nameErr ? styles.errLine : styles.muted}>{nameErr ?? nameHelp(options.nameRule)}</span>
        </div>
    );
    const role = <div className={styles.field}><span className={styles.fieldLabel}>시작할 역할</span><RolePick role={draft.role} setRole={set('role')} /></div>;
    const county = <CountyPick options={options} countyId={draft.countyId} setCountyId={set('countyId')} />;
    const stats = <StatRows rule={options.statRule} stats={draft.stats} setStats={set('stats')} />;
    const picks = (
        <>
            <PickGrid label="주의" items={options.ideologies} value={draft.ideologyId} onChange={set('ideologyId')} />
            <PickGrid label="개성" items={options.traits} value={draft.traitId} onChange={set('traitId')} help="지금은 표시용 — 효과는 설계 뒤에" />
        </>
    );
    const help = <p className={styles.help}>역할을 고르고 이름 · 본관 · 다섯 능력 · 주의 · 개성을 정해 내 장수를 만듭니다.</p>;

    if (mobile) {
        const bodies: readonly ReactNode[] = [
            <>{<p className={styles.help}>어떤 자리로 시작할지 고릅니다.</p>}{role}</>,
            county,
            <>{nameField}{stats}</>,
            picks,
            <Panel key="preview" className={styles.panel} aria-label="미리보기"><SectionHeader title="미리보기" sub="유일 카드" /><Preview options={options} draft={draft} /></Panel>,
        ];
        return (
            <div className={styles.mobile}>
                <div className={styles.mobileHead}>
                    <h2 className={styles.title}>장수 만들기</h2>
                    <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
                </div>
                <nav aria-label="걸음" className={styles.steps}>
                    {STEPS.map((label, i) => (
                        <button key={label} type="button" aria-current={i === step ? 'step' : undefined} className={`${styles.step}${i === step ? ` ${styles.stepOn}` : ''}${i < step ? ` ${styles.stepDone}` : ''}`} onClick={() => setStep(i)}>
                            <span className={styles.stepNo}>{i + 1}</span><span>{label}</span>
                        </button>
                    ))}
                </nav>
                <div className={styles.stepBody}>{bodies[step]}</div>
                <div className={styles.stepFoot}>
                    {step === 0
                        ? <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
                        : <Button variant="ghost" onClick={() => setStep(step - 1)}>{`이전 — ${STEPS[step - 1]}`}</Button>}
                    {step < STEPS.length - 1
                        ? <Button variant="primary" onClick={() => setStep(step + 1)}>{`다음 — ${STEPS[step + 1]}`}</Button>
                        : <SubmitButton reason={reason} onSubmit={doSubmit} />}
                </div>
            </div>
        );
    }

    return (
        <div className={styles.screen}>
            <h2 className="sr-only">장수 만들기</h2>
            <Panel className={styles.countyPanel} aria-label="본관 현">
                <SectionHeader title="본관 현" sub="목록에서 고른다" />
                {county}
            </Panel>
            <Panel className={styles.editPanel} aria-label="역할 · 이름 · 다섯 능력 · 주의 · 개성">
                <SectionHeader title="역할 · 이름 · 다섯 능력 · 주의 · 개성" sub={`남은 점수 ${options.statRule.total - statSum(draft.stats)} / ${options.statRule.total}`} />
                <div className={styles.editBody}>{help}{role}{nameField}{stats}{picks}</div>
            </Panel>
            <Panel className={styles.previewPanel} aria-label="미리보기">
                <SectionHeader title="미리보기" sub="유일 카드" />
                <Preview options={options} draft={draft} />
                <div className={styles.previewFoot}>
                    <CampaignLink slug="create/historical" className={styles.textLink}>역사 인물로 바꾸기</CampaignLink>
                    <SubmitButton reason={reason} onSubmit={doSubmit} block />
                </div>
            </Panel>
        </div>
    );
}

export default function CreateScreen() {
    const { state, reload } = useCreationOptions();
    if (state.kind === 'loading') return <div className={styles.progressWrap}><StatusView kind="loading" rows={4} /></div>;
    // 본문 없는 404 · 503 = 생성 옵션 경로가 아직 없다(K5-02 서버 대기). 문장을 준 정책 닫힘은 서버가 답한 것.
    if (state.kind === 'waiting') return <CreationWaiting message={state.message} serverWait={state.code === null ? 'K5-02' : null} />;
    if (state.kind === 'error') {
        return <div className={styles.progressWrap}><StatusView kind="error" title="생성 옵션을 불러오지 못했습니다" body={state.error.message} onRetry={reload} /></div>;
    }
    if (!state.data.policy.customAllowed) return <CreationWaiting message={reasonText(state.data.policy.reason)} />;
    return <Editor options={state.data} />;
}
