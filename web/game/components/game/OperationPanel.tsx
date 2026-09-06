'use client';
// Phase 4X-B 작전 — 08 국가 운영 「작전 진행」 패널 (spec v4.1 §7). 원천 /api/operations. 명령 4종은 인테이크(202 ≠ 성공).
// 1차 표기는 「이정표 k/4」, % 는 보조. 아트보드의 「통제권 22%」 행은 그리지 않는다(생산자 없음). 상한·범위는 rules 로만.
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, Chip, EmptyState, Gauge, Panel, Portrait, SectionHeader, type ButtonProps, type ChipTone } from '@opensamguk/ui';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { Operation, OperationsResponse } from '../../types/game';

type CityListResponse = { cities: unknown[][] };

function GateButton({ reason, children, ...rest }: { readonly reason: string | null } & Omit<ButtonProps, 'disabled' | 'reason'>) {
    return reason != null ? <Button {...rest} disabled reason={reason}>{children}</Button> : <Button {...rest}>{children}</Button>;
}

const STATUS_TONE: Record<Operation['status'], ChipTone> = { declared: 'neutral', active: 'moss', achieved: 'bronze', failed: 'rust', closed: 'neutral' };
const MILESTONE_LABELS: [keyof Operation['milestones'], string][] = [['departed', '출발'], ['arrived', '도달'], ['supplied', '보급'], ['objective', '목표']];

export default function OperationPanel() {
    const [data, setData] = useState<OperationsResponse | null>(null);
    const [error, setError] = useState('');
    const [busy, setBusy] = useState('');
    const [message, setMessage] = useState('');
    const [form, setForm] = useState({ kind: 'capture_city', targetCityId: '', title: '', fallbackText: '', deadlineMonths: '' });
    const [roleDraft, setRoleDraft] = useState('main');
    // 목표 도시 select 원천: /api/cities 의 [city, nation, name, level] 4-튜플(선언 폼에서만 쓴다).
    const [cities, setCities] = useState<{ id: number; name: string }[]>([]);

    const load = useCallback(() => {
        Promise.resolve()
            .then(() => api.operations<OperationsResponse>())
            .then((r) => { setData(r); setError(''); })
            .catch(() => setError('작전 정보를 불러올 수 없습니다.'));
    }, []);
    useEffect(load, [load]);
    useEffect(() => {
        Promise.resolve()
            .then(() => api.cityList<CityListResponse>())
            .then((r) => setCities(r.cities.map((row) => ({ id: Number(row[0]), name: String(row[2]) })).filter((c) => Number.isFinite(c.id))))
            .catch(() => setCities([]));
    }, []);

    const run = async (label: string, code: string, args: Record<string, unknown>, confirmMessage?: string) => {
        if (data == null) return;
        if (confirmMessage != null && !window.confirm(confirmMessage)) return;
        setBusy(label); setMessage('');
        try {
            const out = await submitCommandAndAwaitResult(() => api.command(code, args, data?.myGeneralId ?? 0));
            if (out.status === 'applied') { setMessage('처리되었습니다.'); load(); } else setMessage(out.reason ?? '처리 지연');
        } catch { setMessage('요청에 실패했습니다.'); } finally { setBusy(''); }
    };

    if (error) return <EmptyState title={error} />;
    if (data == null) return <p className="text-muted">작전 정보를 불러오는 중…</p>;
    const { rules } = data;
    const chief = data.myPermission >= 2;
    const openCount = data.operations.filter((o) => o.status === 'declared' || o.status === 'active').length;
    const declareReason = !chief ? '권한이 부족합니다. 수뇌부가 아닙니다' : openCount >= rules.maxActivePerNation ? '진행 중인 작전이 가득 찼습니다' : null;
    const joinedOpen = data.operations.find((o) => (o.status === 'declared' || o.status === 'active') && o.units.some((u) => u.generalId === data.myGeneralId));

    const submitDeclare = (e: FormEvent) => {
        e.preventDefault();
        void run('declare', 'operationDeclare', {
            kind: form.kind, targetCityId: Number(form.targetCityId), title: form.title,
            fallbackText: form.fallbackText || null, deadlineMonths: Number(form.deadlineMonths),
        });
    };

    return (
        <div className="ops" id="operations">
            <Panel className="ops__panel" aria-label="작전 진행">
                <SectionHeader
                    title="작전 진행"
                    sub={`${openCount} / ${rules.maxActivePerNation}`}
                    actions={rules.provisional ? <Chip tone="neutral" title="잠정 상수 — 플레이테스트로 조정">잠정</Chip> : undefined}
                />
                {data.nationId === 0 ? (
                    <EmptyState title="국가에 소속되어 있지 않습니다." hint="작전은 국가 단위입니다." />
                ) : data.operations.length === 0 ? (
                    <EmptyState title="작전이 없습니다." hint="수뇌부가 선언하면 나옵니다." />
                ) : (
                    <ul className="ops__list">
                        {data.operations.map((o) => {
                            const mine = o.units.some((u) => u.generalId === data.myGeneralId);
                            const open = o.status === 'declared' || o.status === 'active';
                            const joinReason = !open ? '종료된 작전입니다' : mine ? '이미 참여 중입니다' : joinedOpen ? '이미 다른 작전에 참여 중입니다' : o.units.length >= rules.maxUnits ? '작전 편성이 가득 찼습니다' : null;
                            return (
                                <li key={o.id} className="ops__item" data-status={o.status}>
                                    <div className="ops__head">
                                        <b className="ops__title">{o.title}</b>
                                        <Chip tone={STATUS_TONE[o.status]}>{o.statusLabel}</Chip>
                                        <span className="ops__kind">{o.kindLabel} · {o.target.name}</span>
                                        <span className="ops__deadline">
                                            기한 <span className="os-num">{o.deadline.year}年 {o.deadline.month}月</span> 상순
                                            {o.remainingMonths != null && <> · 남은 <b className="os-num">{o.remainingMonths}</b>개월</>}
                                        </span>
                                    </div>
                                    {o.fallbackText && <p className="ops__fallback">대체 목표 · {o.fallbackText}</p>}
                                    <div className="ops__progress">
                                        <span className="ops__milestone-count">이정표 <b className="os-num">{MILESTONE_LABELS.filter(([k]) => o.milestones[k]).length}</b>/4</span>
                                        <Gauge label="진척(표시)" value={o.milestoneDisplayPct} max={100} tone="bronze" />
                                        <ul className="ops__milestones" aria-label="이정표">
                                            {MILESTONE_LABELS.map(([k, label]) => (
                                                <li key={k} data-on={o.milestones[k] ? 'true' : 'false'}>{o.milestones[k] ? '■' : '□'} {label}</li>
                                            ))}
                                        </ul>
                                    </div>
                                    {o.units.length > 0 && (
                                        <ul className="ops__units" aria-label={`${o.title} 참여 부대`}>
                                            {o.units.map((u) => (
                                                <li key={u.id} className="ops__unit">
                                                    <Portrait picture={u.picture} imageServer={u.imageServer} size="icon-40" alt={u.name} />
                                                    <span className="ops__unit-name">{u.name}</span>
                                                    <Chip tone={u.role === 'main' ? 'bronze' : 'neutral'}>{u.roleLabel}</Chip>
                                                    <span className="ops__unit-meta os-num">{u.crew.toLocaleString()} · {u.crewTypeName}{u.bugokTroops != null ? ` · 부곡 ${u.bugokTroops.toLocaleString()}` : ''}</span>
                                                    <span className="ops__unit-city">{u.cityName}</span>
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                    <div className="ops__actions">
                                        {mine ? (
                                            <GateButton variant="ghost" size="sm" reason={busy !== '' ? '처리 중' : null} onClick={() => void run(`leave-${o.id}`, 'operationLeave', { operationId: o.id }, `${o.title} 에서 이탈하시겠습니까?`)}>이탈</GateButton>
                                        ) : (
                                            <>
                                                <label className="ops__role">역할
                                                    <select value={roleDraft} onChange={(e) => setRoleDraft(e.target.value)} disabled={busy !== ''} title={busy !== '' ? '처리 중' : undefined}>
                                                        {rules.roles.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                                                    </select>
                                                </label>
                                                <GateButton variant="primary" size="sm" reason={busy !== '' ? '처리 중' : joinReason} onClick={() => void run(`join-${o.id}`, 'operationJoin', { operationId: o.id, role: roleDraft })}>참여</GateButton>
                                            </>
                                        )}
                                        <GateButton variant="ghost" size="sm" reason={busy !== '' ? '처리 중' : !chief ? '권한이 부족합니다. 수뇌부가 아닙니다' : !open ? '종료된 작전입니다' : null} onClick={() => void run(`close-${o.id}`, 'operationClose', { operationId: o.id }, `${o.title} 작전을 종료하시겠습니까?`)}>종료</GateButton>
                                    </div>
                                </li>
                            );
                        })}
                    </ul>
                )}
                <p className="ops__flow">작전 흐름 · 목표 선언 → 출병 계획 봉인(4X-C) → 이동·통제 → 접촉 시 전투 → 정산</p>
            </Panel>

            {data.nationId !== 0 && (
                <Panel className="ops__panel" aria-label="작전 선언">
                    <SectionHeader title="작전 선언" sub={`기한 ${rules.minDeadlineMonths}~${rules.maxDeadlineMonths}개월`} />
                    <form className="ops__form" onSubmit={submitDeclare}>
                        <label>종류
                            <select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                                {rules.kinds.map((k) => <option key={k.kind} value={k.kind} disabled={!k.declarable} title={k.reason ?? undefined}>{k.label}{k.declarable ? '' : ` (${k.reason})`}</option>)}
                            </select>
                        </label>
                        <label>목표 도시
                            <select value={form.targetCityId} onChange={(e) => setForm({ ...form, targetCityId: e.target.value })}>
                                <option value="">선택</option>
                                {cities.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                            </select>
                        </label>
                        <label>제목<input value={form.title} maxLength={40} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="2~40자" /></label>
                        <label>대체 목표<input value={form.fallbackText} maxLength={200} onChange={(e) => setForm({ ...form, fallbackText: e.target.value })} placeholder="선택" /></label>
                        <label>기한(개월)<input type="number" min={rules.minDeadlineMonths} max={rules.maxDeadlineMonths} value={form.deadlineMonths} onChange={(e) => setForm({ ...form, deadlineMonths: e.target.value })} /></label>
                        <GateButton type="submit" variant="primary" size="sm" reason={busy !== '' ? '처리 중' : declareReason}>선언</GateButton>
                    </form>
                </Panel>
            )}
            {message && <p className="ops__message" role="status">{message}</p>}
        </div>
    );
}
