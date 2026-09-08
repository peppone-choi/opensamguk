'use client';
// Phase 4X-A 휘하 인물(가신)·부곡 — 07 아트보드 「휘하 인물 · 부곡」 구획 (spec v3 §7).
// 원천: /api/my-retinue (DB, 엔진 flush 결과). 명령은 인테이크 6종 → submitCommandAndAwaitResult(202 ≠ 성공).
// 상한·비용·선택지는 응답 rules 로만 표시한다(하드코딩 금지). 잠정 상수는 「잠정」 칩.
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, Chip, EmptyState, Gauge, Panel, Portrait, SectionHeader, type ButtonProps } from '@opensamguk/ui';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { RetinueResponse } from '../../types/game';

type Props = { readonly generalId: number; readonly onChanged?: () => void };

/** 사유가 있으면 점선 disabled(+툴팁), 없으면 활성 — Button 의 disabled→reason 필수 계약을 한 곳에서 만족시킨다. */
function GateButton({ reason, children, ...rest }: { readonly reason: string | null } & Omit<ButtonProps, 'disabled' | 'reason'>) {
    return reason != null ? <Button {...rest} disabled reason={reason}>{children}</Button> : <Button {...rest}>{children}</Button>;
}

export default function RetinuePanels({ generalId, onChanged }: Props) {
    const [data, setData] = useState<RetinueResponse | null>(null);
    const [error, setError] = useState('');
    const [busy, setBusy] = useState('');
    const [message, setMessage] = useState('');
    const [pledge, setPledge] = useState({ targetGeneralId: '', relation: 'lieutenant', role: 'NONE' });
    const [form, setForm] = useState({ troops: '', rice: '' });

    const load = useCallback(() => {
        // 동기 예외(테스트의 부분 mock 등)도 실패 상태로 흡수한다 — 페이지 전체를 죽이지 않는다.
        Promise.resolve()
            .then(() => api.myRetinue<RetinueResponse>())
            .then((r) => { setData(r); setError(''); })
            .catch(() => setError('휘하 정보를 불러올 수 없습니다.'));
    }, []);
    useEffect(load, [load]);

    const run = async (label: string, code: string, args: Record<string, unknown>, confirmMessage?: string) => {
        if (confirmMessage != null && !window.confirm(confirmMessage)) return;
        setBusy(label);
        setMessage('');
        try {
            const out = await submitCommandAndAwaitResult(() => api.command(code, args, generalId));
            if (out.status === 'applied') {
                setMessage('처리되었습니다.');
                load();
                onChanged?.();
            } else {
                setMessage(out.reason ?? '처리 지연');
            }
        } catch {
            setMessage('요청에 실패했습니다.');
        } finally {
            setBusy('');
        }
    };

    if (error) return <EmptyState title={error} />;
    if (data == null) return <p className="text-muted">휘하 정보를 불러오는 중…</p>;

    const { rules } = data;
    const retainersFull = data.retainers.length >= rules.maxRetainers;
    const bugokFull = data.bugoks.length >= rules.maxBugok;
    const lieutenants = data.retainers.filter((r) => r.relation === 'lieutenant');
    const candidates = data.candidates ?? [];
    const selectedCandidate = candidates.find((candidate) => String(candidate.generalId) === pledge.targetGeneralId);
    const pledgeDisabledReason = retainersFull ? '가신이 가득 찼습니다' : data.gold < rules.pledgeCostGold ? '자금이 부족합니다' : candidates.length === 0 ? '서약 가능한 NPC 장수가 없습니다.' : null;
    const formDisabledReason = bugokFull ? '부곡이 가득 찼습니다' : data.crew < rules.minBugokTroops ? `병력이 ${rules.minBugokTroops} 미만입니다` : null;

    const submitPledge = (e: FormEvent) => {
        e.preventDefault();
        if (selectedCandidate == null) return;
        void run('pledge', 'retainerPledge', { targetGeneralId: selectedCandidate.generalId, relation: pledge.relation, role: pledge.role });
    };
    const submitForm = (e: FormEvent) => {
        e.preventDefault();
        void run('form', 'bugokForm', { troops: Number(form.troops), rice: Number(form.rice || 0) });
    };

    return (
        <div className="retinue" id="retinue">
            <Panel className="retinue__panel" aria-label="휘하 인물">
                <SectionHeader
                    title="휘하 인물"
                    sub={`${data.retainers.length} / ${rules.maxRetainers}`}
                    actions={rules.provisional ? <Chip tone="neutral" title="잠정 상수 — 플레이테스트로 조정">잠정</Chip> : undefined}
                />
                {data.retainers.length === 0 ? (
                    <EmptyState title="휘하 인물이 없습니다." hint="서약하면 여기 나옵니다." />
                ) : (
                    <ul className="retinue__list">
                        {data.retainers.map((r) => (
                            <li key={r.id} className="retinue__person">
                                <Portrait picture={r.picture ?? null} imageServer={r.imageServer ?? 0} size="card-44" alt={r.name} frameClassName="retinue__portrait" />
                                <div className="retinue__body">
                                    <div className="retinue__name">
                                        <b>{r.name}</b>
                                        <Chip tone={r.relation === 'lieutenant' ? 'bronze' : 'neutral'}>{r.relationLabel}</Chip>
                                        <span className="retinue__role">{r.roleLabel}</span>
                                    </div>
                                    <Gauge label="충성" value={r.loyalty} max={100} tone={r.loyalty < 20 ? 'rust' : 'moss'} />
                                    <div className="retinue__controls">
                                        <label className="retinue__task">
                                            임무
                                            <select
                                                value={r.task}
                                                disabled={busy !== ''}
                                                title={busy !== '' ? '처리 중' : undefined}
                                                onChange={(e) => void run(`task-${r.id}`, 'retainerTask', { retainerId: r.id, task: e.target.value })}
                                            >
                                                {rules.tasks.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                                            </select>
                                        </label>
                                        <GateButton
                                            variant="ghost"
                                            size="sm"
                                            reason={busy !== '' ? '처리 중' : null}
                                            onClick={() => void run(`release-${r.id}`, 'retainerRelease', { retainerId: r.id }, `${r.name}을(를) 해제하시겠습니까?`)}
                                        >
                                            해제
                                        </GateButton>
                                    </div>
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
                <form className="retinue__form" onSubmit={submitPledge} aria-label="가신 서약">
                    <label>NPC 장수
                        <select value={selectedCandidate == null ? '' : pledge.targetGeneralId} disabled={busy !== ''} onChange={(e) => setPledge({ ...pledge, targetGeneralId: e.target.value })}>
                            <option value="">장수를 선택하세요</option>
                            {candidates.map((candidate) => <option key={candidate.generalId} value={candidate.generalId}>{candidate.name} · {candidate.nationId === 0 ? '재야' : '같은 국가'}</option>)}
                        </select>
                    </label>
                    <label>관계
                        <select value={pledge.relation} onChange={(e) => setPledge({ ...pledge, relation: e.target.value })}>
                            {rules.relations.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                    </label>
                    <label>역할
                        <select value={pledge.role} onChange={(e) => setPledge({ ...pledge, role: e.target.value })}>
                            {rules.roles.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                    </label>
                    <span className="retinue__cost">비용 <b className="os-num">{rules.pledgeCostGold.toLocaleString()}</b> 금 · 기존 장수 유지비 없음</span>
                    <GateButton type="submit" variant="primary" size="sm" reason={busy !== '' ? '처리 중' : pledgeDisabledReason ?? (selectedCandidate == null ? 'NPC 장수를 선택하세요' : null)}>서약</GateButton>
                    <GateButton type="button" variant="ghost" size="sm" reason={busy !== '' ? '처리 중' : pledgeDisabledReason}
                        onClick={() => void run('pledge-random', 'retainerPledge', { random: true, relation: pledge.relation, role: pledge.role })}>무작위 서약</GateButton>
                </form>
                {candidates.length === 0 && <p className="text-muted retinue__candidate-note">서약 가능한 NPC 장수가 없습니다.</p>}
                {selectedCandidate != null && (
                    <div className="retinue__person retinue__candidate-preview">
                        <Portrait picture={selectedCandidate.picture} imageServer={selectedCandidate.imageServer} size="card-44" alt={selectedCandidate.name} />
                        <div><b>{selectedCandidate.name}</b><p className="text-muted">통솔 {selectedCandidate.leadership} · 무력 {selectedCandidate.strength} · 지력 {selectedCandidate.intel}</p></div>
                    </div>
                )}
                <p className="text-muted retinue__candidate-note">기존 장수와 서약합니다. 소속과 병력은 유지되며 NPC는 계속 스스로 행동합니다. 해제해도 장수는 남습니다.</p>
            </Panel>

            <Panel className="retinue__panel" aria-label="부곡">
                <SectionHeader title="부곡" sub={`${data.bugoks.length} / ${rules.maxBugok} · 국가군 병력 ${data.crew.toLocaleString()} · 군량 ${data.rice.toLocaleString()}`} />
                {data.bugoks.length === 0 ? (
                    <EmptyState title="부곡이 없습니다." hint="편성하면 여기 나옵니다." />
                ) : (
                    <div className="table-scroll">
                        <table className="os-table retinue__table">
                            <thead>
                                <tr><th>편제</th><th>병종</th><th>지휘</th><th>병력</th><th>훈련</th><th>사기</th><th>피로</th><th>군량</th><th>조치</th></tr>
                            </thead>
                            <tbody>
                                {data.bugoks.map((b) => (
                                    <tr key={b.id}>
                                        <td>{b.name}</td>
                                        <td>{b.crewTypeName}</td>
                                        <td>
                                            <select
                                                aria-label={`${b.name} 지휘관`}
                                                value={b.commanderRetainerId ?? ''}
                                                disabled={busy !== ''}
                                                title={busy !== '' ? '처리 중' : undefined}
                                                onChange={(e) => void run(`cmd-${b.id}`, 'bugokAssignCommander', { bugokId: b.id, retainerId: e.target.value === '' ? null : Number(e.target.value) })}
                                            >
                                                <option value="">없음</option>
                                                {lieutenants.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
                                            </select>
                                        </td>
                                        <td className="os-num">{b.troops.toLocaleString()}</td>
                                        <td className="os-num">{b.training}</td>
                                        <td className="os-num">{b.morale}</td>
                                        <td className="os-num">{b.fatigue}</td>
                                        <td className="os-num">{b.provisions.toLocaleString()} <span className="text-muted">({b.provisionMonths}개월)</span></td>
                                        <td>
                                            <GateButton
                                                variant="ghost"
                                                size="sm"
                                                reason={busy !== '' ? '처리 중' : b.crewTypeId !== data.crewTypeId ? '병종이 다릅니다' : null}
                                                onClick={() => void run(`disband-${b.id}`, 'bugokDisband', { bugokId: b.id }, `${b.name}을(를) 해산하시겠습니까?`)}
                                            >
                                                해산
                                            </GateButton>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
                <form className="retinue__form" onSubmit={submitForm} aria-label="부곡 편성">
                    <label>병력<input type="number" min={rules.minBugokTroops} max={data.crew} value={form.troops} onChange={(e) => setForm({ ...form, troops: e.target.value })} placeholder={`최소 ${rules.minBugokTroops}`} /></label>
                    <label>군량<input type="number" min={0} max={data.rice} value={form.rice} onChange={(e) => setForm({ ...form, rice: e.target.value })} placeholder="0" /></label>
                    <span className="retinue__cost">월 급여 병력 100당 {rules.payGoldPer100Troops} 금 · 군량 병력당 {rules.provisionPerTroopMonth}</span>
                    <GateButton type="submit" variant="primary" size="sm" reason={busy !== '' ? '처리 중' : formDisabledReason}>편성</GateButton>
                </form>
                <p className="retinue__note">부곡은 장수 개인의 사병입니다. 국가군과 별도이며, 부장을 지휘관으로 배정하면 사기 +{rules.commanderMoraleBonus}.</p>
            </Panel>
            {message && <p className="retinue__message" role="status">{message}</p>}
        </div>
    );
}
