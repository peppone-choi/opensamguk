'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { getNPCColor } from '../../../lib/utilGame';
import type { IntakeOutcome } from '../../../lib/types';
import type { MyBossGeneralSummary, MyBossOfficerSlot, MyBossResponse } from '../../../types/game';

const tableStyle: CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 'var(--text-sm)',
};

const thStyle: CSSProperties = {
  textAlign: 'left',
  padding: 'var(--space-xs)',
  borderBottom: '1px solid var(--border)',
  color: 'var(--text-secondary)',
};

const tdStyle: CSSProperties = {
  padding: 'var(--space-xs)',
  borderBottom: '1px solid var(--border-subtle)',
  verticalAlign: 'middle',
};

function roleText(role: string): string {
  if (role === 'ambassador') return '외교권자';
  if (role === 'auditor') return '감찰관';
  return '일반';
}

function slotKey(slot: MyBossOfficerSlot): string {
  return `${slot.slotType}:${slot.cityId ?? 0}:${slot.officerLevel}`;
}

function slotLabel(slot: MyBossOfficerSlot): string {
  if (slot.slotType === 'city') return `${slot.cityName ?? '-'} ${slot.officerLevelText}`;
  return slot.officerLevelText;
}

function parseSlotKey(value: string): { officerLevel: number; cityId: number } | null {
  const [, cityId, officerLevel] = value.split(':');
  const parsedCity = Number(cityId);
  const parsedLevel = Number(officerLevel);
  if (!Number.isInteger(parsedCity) || !Number.isInteger(parsedLevel)) return null;
  return { officerLevel: parsedLevel, cityId: parsedCity };
}

function GeneralName({ name, npcState }: { name: string; npcState: number }) {
  const color = getNPCColor(npcState);
  return <span style={{ color: color ?? undefined }}>{name}</span>;
}

function AssignedName({ slot }: { slot: MyBossOfficerSlot }) {
  if (slot.assignedName == null) return <span className="text-muted">공석</span>;
  return <GeneralName name={slot.assignedName} npcState={slot.assignedNpcState ?? 0} />;
}

export default function MyBossPage() {
  const [boss, setBoss] = useState<MyBossResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [selectedSlotKey, setSelectedSlotKey] = useState('');
  const [selectedAppointGeneralId, setSelectedAppointGeneralId] = useState(0);
  const [selectedKickGeneralId, setSelectedKickGeneralId] = useState(0);
  const [selectedAmbassadors, setSelectedAmbassadors] = useState<number[]>([]);
  const [selectedAuditors, setSelectedAuditors] = useState<number[]>([]);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await api.myBoss<MyBossResponse>();
      setBoss(res);
    } catch {
      setError('상관 정보를 불러올 수 없습니다.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const assignableSlots = useMemo(() => [...(boss?.chiefSlots ?? []), ...(boss?.citySlots ?? [])].filter((slot) => !slot.locked), [boss]);
  const appointableGenerals = useMemo(() => (boss?.roster ?? []).filter((g) => g.canBeAppointed), [boss]);
  const kickableGenerals = useMemo(() => (boss?.roster ?? []).filter((g) => g.canBeKicked), [boss]);
  const ambassadorCandidates = useMemo(() => (boss?.roster ?? []).filter((g) => g.canBeAmbassador || g.permissionRole === 'ambassador'), [boss]);
  const auditorCandidates = useMemo(() => (boss?.roster ?? []).filter((g) => g.canBeAuditor || g.permissionRole === 'auditor'), [boss]);

  useEffect(() => {
    if (assignableSlots.length > 0 && !assignableSlots.some((slot) => slotKey(slot) === selectedSlotKey)) {
      setSelectedSlotKey(slotKey(assignableSlots[0]));
    }
    if (assignableSlots.length === 0) setSelectedSlotKey('');
  }, [assignableSlots, selectedSlotKey]);

  useEffect(() => {
    if (appointableGenerals.length > 0 && !appointableGenerals.some((g) => g.generalId === selectedAppointGeneralId)) {
      setSelectedAppointGeneralId(appointableGenerals[0].generalId);
    }
    if (appointableGenerals.length === 0) setSelectedAppointGeneralId(0);
  }, [appointableGenerals, selectedAppointGeneralId]);

  useEffect(() => {
    if (kickableGenerals.length > 0 && !kickableGenerals.some((g) => g.generalId === selectedKickGeneralId)) {
      setSelectedKickGeneralId(kickableGenerals[0].generalId);
    }
    if (kickableGenerals.length === 0) setSelectedKickGeneralId(0);
  }, [kickableGenerals, selectedKickGeneralId]);

  useEffect(() => {
    setSelectedAmbassadors((boss?.roster ?? []).filter((g) => g.permissionRole === 'ambassador').map((g) => g.generalId));
    setSelectedAuditors((boss?.roster ?? []).filter((g) => g.permissionRole === 'auditor').map((g) => g.generalId));
  }, [boss]);

  function showToast(message: string) {
    setToast(message);
    setTimeout(() => setToast(''), 3000);
  }

  function toggleSelection(current: number[], generalId: number, checked: boolean): number[] {
    if (checked) return current.includes(generalId) ? current : [...current, generalId];
    return current.filter((id) => id !== generalId);
  }

  async function runPersonnelCommand(successMessage: string, submit: () => Promise<IntakeOutcome>) {
    setSubmitting(true);
    try {
      const out = await submitCommandAndAwaitResult(submit);
      if (out.status === 'rejected') {
        showToast(out.reason ?? '처리할 수 없습니다.');
        return;
      }
      if (out.status === 'pending') {
        showToast(out.reason);
        return;
      }
      showToast(successMessage);
      await fetchData();
    } catch (e) {
      showToast('요청에 실패했습니다: ' + (e instanceof Error ? e.message : ''));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleAppoint() {
    const generalId = boss?.myGeneralId;
    if (generalId == null) {
      showToast('장수 정보가 없습니다.');
      return;
    }
    const slot = parseSlotKey(selectedSlotKey);
    if (slot == null || selectedAppointGeneralId === 0) {
      showToast('직책과 장수를 선택하세요.');
      return;
    }
    await runPersonnelCommand('임명했습니다.', () =>
      api.commands.appoint(
        {
          officerLevel: slot.officerLevel,
          destGeneralID: selectedAppointGeneralId,
          destCityID: slot.cityId,
        },
        generalId,
      ),
    );
  }

  async function handleKick() {
    const generalId = boss?.myGeneralId;
    if (generalId == null) {
      showToast('장수 정보가 없습니다.');
      return;
    }
    const target = kickableGenerals.find((g) => g.generalId === selectedKickGeneralId);
    if (target == null) {
      showToast('추방할 장수를 선택하세요.');
      return;
    }
    if (!confirm(`${target.name}을(를) 추방하시겠습니까?`)) return;
    await runPersonnelCommand('추방했습니다.', () => api.commands.kick({ destGeneralID: target.generalId }, generalId));
  }

  async function handleChangePermission(isAmbassador: boolean) {
    const generalId = boss?.myGeneralId;
    if (generalId == null) {
      showToast('장수 정보가 없습니다.');
      return;
    }
    const genlist = isAmbassador ? selectedAmbassadors : selectedAuditors;
    await runPersonnelCommand(isAmbassador ? '외교권자를 저장했습니다.' : '감찰관을 저장했습니다.', () =>
      api.commands.changePermission({ isAmbassador, genlist }, generalId),
    );
  }

  if (loading) {
    return (
      <Shell>
        <div className="page-content">
          <h1>내 상관</h1>
          <p className="text-muted">로딩 중...</p>
        </div>
      </Shell>
    );
  }

  if (error) {
    return (
      <Shell>
        <div className="page-content">
          <h1>내 상관</h1>
          <div className="error-state">
            <p>{error}</p>
            <button onClick={fetchData}>다시 시도</button>
          </div>
        </div>
      </Shell>
    );
  }

  if (boss == null || boss.nationId === 0 || !boss.hasBoss) {
    return (
      <Shell>
        <div className="page-content">
          <h1>내 상관</h1>
          <p className="text-muted">재야입니다.</p>
        </div>
      </Shell>
    );
  }

  const roster = boss?.roster ?? [];
  const allSlots = [...(boss?.chiefSlots ?? []), ...(boss?.citySlots ?? [])];

  return (
    <Shell>
      <div className="page-content">
        <h1>내 상관</h1>
        {toast && (
          <div className="toast" role="alert">
            {toast}
          </div>
        )}
        <GameCard className="boss-card">
          <div className="card-header">
            <h2>{boss.bossName}</h2>
            <StatusBadge variant="gold">{boss.bossOfficerLevel}급</StatusBadge>
          </div>
          <p className="text-muted">{boss.nationName ?? '소속 국가'} 인사부</p>
        </GameCard>

        {boss.canManagePersonnel ? (
          <div style={{ display: 'grid', gap: 'var(--space-md)', marginTop: 'var(--space-md)' }}>
            <GameCard>
              <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>임명</h2>
              <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap', alignItems: 'end' }}>
                <label>
                  <span className="text-muted">직책</span>
                  <select
                    aria-label="임명 직책"
                    value={selectedSlotKey}
                    onChange={(e) => setSelectedSlotKey(e.target.value)}
                    disabled={submitting || assignableSlots.length === 0}
                  >
                    {assignableSlots.map((slot) => (
                      <option key={slotKey(slot)} value={slotKey(slot)}>
                        {slotLabel(slot)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span className="text-muted">대상</span>
                  <select
                    aria-label="임명 대상"
                    value={selectedAppointGeneralId}
                    onChange={(e) => setSelectedAppointGeneralId(Number(e.target.value))}
                    disabled={submitting || appointableGenerals.length === 0}
                  >
                    {appointableGenerals.map((g) => (
                      <option key={g.generalId} value={g.generalId}>
                        {g.name}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="button" onClick={handleAppoint} disabled={submitting || assignableSlots.length === 0 || appointableGenerals.length === 0}>
                  임명
                </button>
              </div>
            </GameCard>

            <GameCard>
              <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>추방</h2>
              <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap', alignItems: 'end' }}>
                <label>
                  <span className="text-muted">대상</span>
                  <select
                    aria-label="추방 대상"
                    value={selectedKickGeneralId}
                    onChange={(e) => setSelectedKickGeneralId(Number(e.target.value))}
                    disabled={submitting || kickableGenerals.length === 0}
                  >
                    {kickableGenerals.map((g) => (
                      <option key={g.generalId} value={g.generalId}>
                        {g.name}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="button" onClick={handleKick} disabled={submitting || kickableGenerals.length === 0}>
                  추방
                </button>
              </div>
            </GameCard>

            <GameCard>
              <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>권한</h2>
              <div style={{ display: 'grid', gap: 'var(--space-sm)' }}>
                <PermissionPicker
                  title="외교권자"
                  candidates={ambassadorCandidates}
                  selected={selectedAmbassadors}
                  disabled={submitting}
                  onChange={(generalId, checked) => setSelectedAmbassadors((current) => toggleSelection(current, generalId, checked))}
                />
                <button type="button" onClick={() => handleChangePermission(true)} disabled={submitting}>
                  외교권자 저장
                </button>
                <PermissionPicker
                  title="감찰관"
                  candidates={auditorCandidates}
                  selected={selectedAuditors}
                  disabled={submitting}
                  onChange={(generalId, checked) => setSelectedAuditors((current) => toggleSelection(current, generalId, checked))}
                />
                <button type="button" onClick={() => handleChangePermission(false)} disabled={submitting}>
                  감찰관 저장
                </button>
              </div>
            </GameCard>
          </div>
        ) : (
          <GameCard style={{ marginTop: 'var(--space-md)' }}>
            <p className="text-muted">수뇌부만 인사 명령을 실행할 수 있습니다.</p>
          </GameCard>
        )}

        <GameCard style={{ marginTop: 'var(--space-md)' }}>
          <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>직책 현황</h2>
          {allSlots.length === 0 ? (
            <p className="text-muted">표시할 직책이 없습니다.</p>
          ) : (
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}>직책</th>
                  <th style={thStyle}>도시</th>
                  <th style={thStyle}>장수</th>
                  <th style={thStyle}>상태</th>
                </tr>
              </thead>
              <tbody>
                {allSlots.map((slot) => (
                  <tr key={slotKey(slot)}>
                    <td style={tdStyle}>{slot.officerLevelText}</td>
                    <td style={tdStyle}>{slot.cityName ?? '-'}</td>
                    <td style={tdStyle}>
                      <AssignedName slot={slot} />
                    </td>
                    <td style={tdStyle}>{slot.locked ? '변경 완료' : '변경 가능'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </GameCard>

        <GameCard style={{ marginTop: 'var(--space-md)' }}>
          <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>세력 장수</h2>
          {roster.length === 0 ? (
            <p className="text-muted">소속 장수가 없습니다.</p>
          ) : (
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}>이름</th>
                  <th style={thStyle}>관직</th>
                  <th style={thStyle}>소재</th>
                  <th style={thStyle}>통솔</th>
                  <th style={thStyle}>무력</th>
                  <th style={thStyle}>지력</th>
                  <th style={thStyle}>정치</th>
                  <th style={thStyle}>매력</th>
                  <th style={thStyle}>권한</th>
                </tr>
              </thead>
              <tbody>
                {roster.map((g) => (
                  <tr key={g.generalId}>
                    <td style={tdStyle}>
                      <GeneralName name={g.name} npcState={g.npcState} />
                    </td>
                    <td style={tdStyle}>{g.officerLevelText}</td>
                    <td style={tdStyle}>{g.cityName ?? '-'}</td>
                    <td style={tdStyle}>{g.leadership}</td>
                    <td style={tdStyle}>{g.strength}</td>
                    <td style={tdStyle}>{g.intel}</td>
                    <td style={tdStyle}>{g.politics ?? '-'}</td>
                    <td style={tdStyle}>{g.charm ?? '-'}</td>
                    <td style={tdStyle}>{roleText(g.permissionRole)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </GameCard>
      </div>
    </Shell>
  );
}

function PermissionPicker({
  title,
  candidates,
  selected,
  disabled,
  onChange,
}: {
  title: string;
  candidates: MyBossGeneralSummary[];
  selected: number[];
  disabled: boolean;
  onChange: (generalId: number, checked: boolean) => void;
}) {
  return (
    <fieldset style={{ border: '1px solid var(--border)', padding: 'var(--space-sm)', borderRadius: 'var(--radius-sm)' }}>
      <legend style={{ padding: '0 var(--space-xs)', color: 'var(--text-secondary)' }}>{title}</legend>
      {candidates.length === 0 ? (
        <p className="text-muted">선택 가능한 장수가 없습니다.</p>
      ) : (
        <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
          {candidates.map((g) => (
            <label key={g.generalId} style={{ display: 'inline-flex', gap: 'var(--space-xs)', alignItems: 'center' }}>
              <input type="checkbox" checked={selected.includes(g.generalId)} disabled={disabled} onChange={(e) => onChange(g.generalId, e.target.checked)} />
              <GeneralName name={g.name} npcState={g.npcState} />
            </label>
          ))}
        </div>
      )}
    </fieldset>
  );
}
