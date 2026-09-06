'use client';
// Phase 4X-C 09 「명령 봉인」 — 출병 계획 봉인(공격자) (spec v4.1 §7). 원천 /api/my-page · /api/city/{id} · /api/my-battle-plans ·
// /api/reserved-commands(턴 시각). 명령 3종은 인테이크(202 ≠ 성공). 태세는 돌격·탐색 2종만 활성(나머지 disabled + 사유), 조건은
// 플레이어 입력 2개, 「합류 전 추격 금지」 는 disabled + 「엔진에 추격이 없습니다」. 예상은 기존 simulate-battle(목록 첫 수비자 1인 기준).
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Chip, EmptyState, Panel, Portrait, SectionHeader, type ButtonProps } from '@opensamguk/ui';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { MyPageResponse, ReservedCommandsResponse } from '../../lib/types';
import type { BattlePlan, CityDetailResponse, MyBattlePlansResponse } from '../../types/game';

function GateButton({ reason, children, ...rest }: { readonly reason: string | null } & Omit<ButtonProps, 'disabled' | 'reason'>) {
    return reason != null ? <Button {...rest} disabled reason={reason}>{children}</Button> : <Button {...rest}>{children}</Button>;
}

type Estimate = { repeatCnt: number; killed: number; maxKilled: number; minKilled: number; dead: number; maxDead: number; minDead: number };

function parseTurnTime(tt?: string | null): Date | null {
    if (!tt) return null;
    const m = tt.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return null;
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6] ?? 0));
}

function hms(ms: number): string {
    const s = Math.max(0, Math.floor(ms / 1000));
    const pad = (v: number) => String(v).padStart(2, '0');
    return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

export default function BattlePlanPanel({ cityId, battleCenterHref }: { readonly cityId: number; readonly battleCenterHref: string }) {
    const [me, setMe] = useState<MyPageResponse | null>(null);
    const [city, setCity] = useState<CityDetailResponse | null>(null);
    const [plans, setPlans] = useState<MyBattlePlansResponse | null>(null);
    const [turnTime, setTurnTime] = useState<string | null>(null);
    const [error, setError] = useState('');
    const [busy, setBusy] = useState('');
    const [message, setMessage] = useState('');
    const [estimate, setEstimate] = useState<Estimate | null>(null);
    const [now, setNow] = useState(() => Date.now());
    const [form, setForm] = useState({ stance: 'assault', lossOn: false, loss: 50, moraleOn: false, morale: 40 });
    const [formSeeded, setFormSeeded] = useState(false);

    const load = useCallback(() => {
        Promise.resolve()
            .then(() => Promise.all([api.myPage<MyPageResponse>(), api.city<CityDetailResponse>(cityId), api.myBattlePlans<MyBattlePlansResponse>()]))
            .then(([mine, c, p]) => {
                setMe(mine); setCity(c); setPlans(p); setError('');
                return api.reservedCommands(mine.generalId).then((r: ReservedCommandsResponse) => setTurnTime(r.turnTime ?? null)).catch(() => setTurnTime(null));
            })
            .catch(() => setError('계획 정보를 불러올 수 없습니다.'));
    }, [cityId]);
    useEffect(load, [load]);
    useEffect(() => { const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t); }, []);

    const existing: BattlePlan | undefined = useMemo(() => plans?.plans.find((p) => p.targetCityId === cityId), [plans, cityId]);
    useEffect(() => {
        if (existing && !formSeeded) {
            setForm({ stance: existing.stance, lossOn: existing.retreatLossPct != null, loss: existing.retreatLossPct ?? 50, moraleOn: existing.retreatMoraleBelow != null, morale: existing.retreatMoraleBelow ?? 40 });
            setFormSeeded(true);
        }
    }, [existing, formSeeded]);

    const run = async (label: string, code: string, args: Record<string, unknown>, confirmMessage?: string) => {
        if (me == null) return;
        if (confirmMessage != null && !window.confirm(confirmMessage)) return;
        setBusy(label); setMessage('');
        try {
            const out = await submitCommandAndAwaitResult(() => api.command(code, args, me.generalId));
            if (out.status === 'applied') { setMessage('처리되었습니다.'); setFormSeeded(false); load(); } else setMessage(out.reason ?? '처리 지연');
        } catch { setMessage('요청에 실패했습니다.'); } finally { setBusy(''); }
    };

    if (error) return <EmptyState title={error} />;
    if (me == null || city == null || plans == null) return <p className="text-muted">계획 정보를 불러오는 중…</p>;
    const { rules } = plans;
    const sealed = existing?.sealed === true;
    const lockReason = busy !== '' ? '처리 중' : sealed ? '봉인된 계획입니다' : null;
    const ownCity = me.nationId !== 0 && city.nationId === me.nationId;
    const defenders = city.showDetailedInfo ? city.generals : [];
    const firstDefender = defenders[0] ?? null;
    const estimateReason = busy !== '' ? '처리 중' : firstDefender == null ? '수비 장수 없음 — 성 방어만' : null;
    const next = parseTurnTime(turnTime);
    const countdown = next == null ? null : hms(next.getTime() - now);
    const planArgs = { targetCityId: cityId, stance: form.stance, retreatLossPct: form.lossOn ? form.loss : null, retreatMoraleBelow: form.moraleOn ? form.morale : null };

    const runEstimate = async () => {
        if (firstDefender == null) return;
        setBusy('estimate'); setMessage('');
        try {
            const r = await api.simulateBattle<Estimate & { result?: boolean; error?: string }>({ attackerGeneralId: me.generalId, defenderGeneralId: firstDefender.no, repeatCnt: 20 });
            if (r.result === false) setMessage(r.error ?? '예상에 실패했습니다.'); else setEstimate(r);
        } catch { setMessage('예상에 실패했습니다.'); } finally { setBusy(''); }
    };

    return (
        <div className="bp" id="battle-plan">
            <div className="bp__meta">
                <Chip tone={sealed ? 'bronze' : 'neutral'}>{sealed ? `봉인됨 · ${existing!.sealedDate ? `${existing!.sealedDate.year}年 ${existing!.sealedDate.month}月 ${['', '상순', '중순', '하순'][existing!.sealedDate.phase] ?? ''}` : ''}` : existing ? `초안 v${existing.version}` : '계획 없음'}</Chip>
                <span className="bp__countdown os-num" title="다음 내 턴 시각 — 같은 순 봉인도 그 턴에 적용됩니다">
                    {countdown == null ? '턴 시각 미확인' : `${sealed ? '해결' : '봉인'}까지 ${countdown}`}
                </span>
                {rules.provisional && <Chip tone="neutral" title="입력 범위만 잠정 상수 — 임계값은 플레이어 입력">잠정</Chip>}
                <a className="os-button os-button--ghost os-button--sm" href={battleCenterHref}>감찰부 기록</a>
            </div>
            <div className="bp__grid">
                <Panel className="bp__panel" aria-label="아군">
                    <SectionHeader title="아군" sub="본인 부대 1개" />
                    <div className="bp__unit bp__unit--hero">
                        <Portrait picture={me.picture} imageServer={me.imageServer} size="card" alt={me.name} />
                        <div className="bp__unit-body">
                            <b>{me.name}</b>
                            <span className="os-num">병력 {me.crew.toLocaleString()} · 훈련 {me.train} · 사기 {me.atmos}</span>
                        </div>
                    </div>
                    <div className="bp__unit bp__unit--dashed" data-reason="이 절편은 본인 부대만 — 호위·별동 협동은 엔진에 대응물이 없습니다">
                        <span>다중 부대 봉인</span>
                        <span className="bp__reason">이 절편은 본인 부대만</span>
                    </div>
                </Panel>
                <Panel className="bp__panel" aria-label="적군 · 정찰 정보">
                    <SectionHeader title="적군 · 정찰 정보" sub={city.name} />
                    {ownCity ? (
                        <EmptyState title="아군 도시입니다." hint="출병 목표가 될 수 없습니다." />
                    ) : !city.showDetailedInfo ? (
                        <p className="bp__reason">정찰 시야 밖 — 수비 장수를 알 수 없습니다.</p>
                    ) : defenders.length === 0 ? (
                        <p className="bp__reason">수비 장수 없음 — 성 방어만</p>
                    ) : (
                        <ul className="bp__defenders" aria-label="수비 장수">
                            {defenders.map((g, i) => (
                                <li key={g.no} className="bp__unit">
                                    <span className="bp__unit-body">
                                        <b>{g.name}</b>{i === 0 && <Chip tone="neutral">목록 첫 수비자</Chip>}
                                        <span className="os-num">{g.crew >= 0 ? `병력 ${g.crew.toLocaleString()}` : '병력 ?'}{g.crewTypeName ? ` · ${g.crewTypeName}` : ''}</span>
                                    </span>
                                </li>
                            ))}
                        </ul>
                    )}
                    <p className="bp__note">적 정보는 정찰 시점 기준입니다. 봉인 뒤에는 바꿀 수 없습니다.</p>
                </Panel>
                <Panel className="bp__panel" aria-label="명령">
                    <SectionHeader title="명령" sub={sealed ? '봉인됨 · 읽기 전용' : '봉인 전까지 수정 가능'} />
                    <fieldset className="bp__field" disabled={sealed}>
                        <legend>부대 태세</legend>
                        <div className="bp__stances" role="radiogroup" aria-label="부대 태세">
                            {rules.stances.map((s) => (
                                <label key={s.value} className={`bp__stance${s.enabled ? '' : ' bp__stance--off'}`} title={s.enabled ? s.description : s.reason ?? undefined} data-reason={s.enabled ? undefined : s.reason ?? undefined}>
                                    <input type="radio" name="stance" value={s.value} checked={form.stance === s.value} disabled={!s.enabled} onChange={() => setForm({ ...form, stance: s.value })} />
                                    {s.label}{!s.enabled && <span className="bp__reason"> · {s.reason}</span>}
                                </label>
                            ))}
                        </div>
                    </fieldset>
                    <fieldset className="bp__field" disabled={sealed}>
                        <legend>조건</legend>
                        <label className="bp__cond">
                            <input type="checkbox" checked={form.lossOn} onChange={(e) => setForm({ ...form, lossOn: e.target.checked })} />
                            병력 <input type="number" aria-label="퇴각 손실 %" min={rules.retreatLossPctMin} max={rules.retreatLossPctMax} value={form.loss} disabled={!form.lossOn} onChange={(e) => setForm({ ...form, loss: Number(e.target.value) })} />% 손실 시 퇴각
                            <span className="bp__reason">({rules.retreatLossPctMin}~{rules.retreatLossPctMax})</span>
                        </label>
                        <label className="bp__cond">
                            <input type="checkbox" checked={form.moraleOn} onChange={(e) => setForm({ ...form, moraleOn: e.target.checked })} />
                            사기 <input type="number" aria-label="퇴각 사기 임계" min={rules.retreatMoraleMin} max={rules.retreatMoraleMax} value={form.morale} disabled={!form.moraleOn} onChange={(e) => setForm({ ...form, morale: Number(e.target.value) })} /> 미만이면 퇴각
                            <span className="bp__reason">(공격자에겐 「방어로 전환」 이 없어 퇴각으로 적습니다)</span>
                        </label>
                        <label className="bp__cond bp__cond--off" title="엔진에 추격이 없습니다" data-reason="엔진에 추격이 없습니다">
                            <input type="checkbox" disabled /> 합류 전 추격 금지 <span className="bp__reason">· 엔진에 추격이 없습니다</span>
                        </label>
                        <p className="bp__note">퇴각은 부상 판정을 받습니다(자연 퇴각과 같은 비용).</p>
                    </fieldset>
                    <div className="bp__estimate">
                        <GateButton variant="ghost" size="sm" reason={estimateReason} onClick={() => void runEstimate()}>예상 (결정론 시뮬)</GateButton>
                        <span className="bp__reason">목록 첫 수비자 1인 기준 예상</span>
                        {estimate && (
                            <dl className="bp__estimate-rows os-num" aria-label="예상 결과">
                                <dt>적 사상</dt><dd>{estimate.minKilled.toLocaleString()} / {estimate.killed.toLocaleString()} / {estimate.maxKilled.toLocaleString()}</dd>
                                <dt>아군 손실</dt><dd>{estimate.minDead.toLocaleString()} / {estimate.dead.toLocaleString()} / {estimate.maxDead.toLocaleString()}</dd>
                                <dt>반복</dt><dd>{estimate.repeatCnt}회 · 최소/평균/최대</dd>
                            </dl>
                        )}
                    </div>
                    <div className="bp__actions">
                        <GateButton variant="ghost" size="sm" reason={lockReason ?? (ownCity ? '아군 도시입니다' : null)} onClick={() => void run('save', 'battlePlanSave', planArgs)}>저장</GateButton>
                        <GateButton variant="primary" size="sm" reason={lockReason ?? (existing == null ? '먼저 저장하세요' : null)} onClick={() => void run('seal', 'battlePlanSeal', { planId: existing?.id }, '봉인 뒤에는 바꿀 수 없습니다. 봉인하시겠습니까?')}>봉인</GateButton>
                        <GateButton variant="ghost" size="sm" reason={lockReason ?? (existing == null ? '계획이 없습니다' : null)} onClick={() => void run('delete', 'battlePlanDelete', { planId: existing?.id }, '초안을 삭제하시겠습니까?')}>삭제</GateButton>
                    </div>
                    <p className="bp__note">봉인하면 다음 내 턴의 출병에 적용되고, 결과는 리플레이로 재생됩니다(계획이 없는 출병은 기록하지 않습니다).</p>
                </Panel>
            </div>
            {message && <p className="bp__message" role="status">{message}</p>}
        </div>
    );
}
