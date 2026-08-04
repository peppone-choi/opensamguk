'use client';

import { useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import type { PublicGeneral } from '../../../types/game';

interface SimResult {
  result: boolean;
  reason: string;
  repeatCnt: number;
  avgWar: number;
  phase: number;
  killed: number;
  maxKilled: number;
  minKilled: number;
  dead: number;
  maxDead: number;
  minDead: number;
  attackerSkills: Record<string, number>;
  defendersSkills: Array<Record<string, number>>;
  attackerCrew: number;
  defenderCityDefense: number;
  defenderCityWall: number;
  conquerCity: boolean;
}

function generalOptionLabel(general: PublicGeneral): string {
  return `${general.name} (통${general.leadership} 무${general.strength} 지${general.intel} 정치${general.politics ?? '-'} 매력${general.charm ?? '-'})`;
}

export default function SimulatorPage() {
  const [generals, setGenerals] = useState<PublicGeneral[]>([]);
  const [attackerId, setAttackerId] = useState<number | ''>('');
  const [defenderId, setDefenderId] = useState<number | ''>('');
  const [result, setResult] = useState<SimResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [toast, setToast] = useState<string>('');

  const fetchGenerals = useCallback(async () => {
    try {
      const data = await api.generalsList();
      setGenerals(data);
    } catch {
      setError('장수 목록을 불러올 수 없습니다.');
    }
  }, []);

  async function simulate() {
    if (!attackerId || !defenderId) {
      setToast('공격자와 방어자를 모두 선택하세요.');
      setTimeout(() => setToast(''), 3000);
      return;
    }
    if (attackerId === defenderId) {
      setToast('같은 장수를 선택할 수 없습니다.');
      setTimeout(() => setToast(''), 3000);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const data = await api.simulateBattle<SimResult>({
        attackerGeneralId: attackerId,
        defenderGeneralId: defenderId,
        repeatCnt: 1,
      });
      setResult(data);
    } catch {
      setError('시뮬레이션에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  }

  const attacker = generals.find((g) => g.generalId === Number(attackerId));
  const defender = generals.find((g) => g.generalId === Number(defenderId));
  const attackerSkillEntries = result ? Object.entries(result.attackerSkills) : [];
  const defenderSkillGroups = result
    ? result.defendersSkills.map((skills, idx) => ({ idx, entries: Object.entries(skills) })).filter((group) => group.entries.length > 0)
    : [];

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>전투 시뮬레이터</h1>

      <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
        <button onClick={fetchGenerals}>장수 목록 불러오기</button>
      </div>

      {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

      {toast && (
        <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
          {toast}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-md)' }}>
        {/* Attacker selector */}
        <GameCard>
          <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--crimson)' }}>공격자</h2>
          <label
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 'var(--space-xs)',
              fontSize: 'var(--text-sm)',
              color: 'var(--text-secondary)',
              marginBottom: 'var(--space-sm)',
            }}
          >
            장수 선택
            <select value={attackerId} onChange={(e) => setAttackerId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">선택...</option>
              {generals.map((g) => (
                <option key={g.generalId} value={g.generalId}>
                  {generalOptionLabel(g)}
                </option>
              ))}
            </select>
          </label>
          {attacker && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}>
              <span style={{ color: 'var(--text-muted)' }}>통솔</span>
              <strong>{attacker.leadership}</strong>
              <span style={{ color: 'var(--text-muted)' }}>무력</span>
              <strong>{attacker.strength}</strong>
              <span style={{ color: 'var(--text-muted)' }}>지력</span>
              <strong>{attacker.intel}</strong>
              <span style={{ color: 'var(--text-muted)' }}>정치</span>
              <strong>{attacker.politics ?? '-'}</strong>
              <span style={{ color: 'var(--text-muted)' }}>매력</span>
              <strong>{attacker.charm ?? '-'}</strong>
              <span style={{ color: 'var(--text-muted)' }}>병력</span>
              <strong>{attacker.crew.toLocaleString()}</strong>
              <span style={{ color: 'var(--text-muted)' }}>소속</span>
              <strong>{attacker.nationName}</strong>
              <span style={{ color: 'var(--text-muted)' }}>도시</span>
              <strong>{attacker.cityName}</strong>
            </div>
          )}
        </GameCard>

        {/* Defender selector */}
        <GameCard>
          <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--jade)' }}>방어자</h2>
          <label
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 'var(--space-xs)',
              fontSize: 'var(--text-sm)',
              color: 'var(--text-secondary)',
              marginBottom: 'var(--space-sm)',
            }}
          >
            장수 선택
            <select value={defenderId} onChange={(e) => setDefenderId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">선택...</option>
              {generals.map((g) => (
                <option key={g.generalId} value={g.generalId}>
                  {generalOptionLabel(g)}
                </option>
              ))}
            </select>
          </label>
          {defender && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}>
              <span style={{ color: 'var(--text-muted)' }}>통솔</span>
              <strong>{defender.leadership}</strong>
              <span style={{ color: 'var(--text-muted)' }}>무력</span>
              <strong>{defender.strength}</strong>
              <span style={{ color: 'var(--text-muted)' }}>지력</span>
              <strong>{defender.intel}</strong>
              <span style={{ color: 'var(--text-muted)' }}>정치</span>
              <strong>{defender.politics ?? '-'}</strong>
              <span style={{ color: 'var(--text-muted)' }}>매력</span>
              <strong>{defender.charm ?? '-'}</strong>
              <span style={{ color: 'var(--text-muted)' }}>병력</span>
              <strong>{defender.crew.toLocaleString()}</strong>
              <span style={{ color: 'var(--text-muted)' }}>소속</span>
              <strong>{defender.nationName}</strong>
              <span style={{ color: 'var(--text-muted)' }}>도시</span>
              <strong>{defender.cityName}</strong>
            </div>
          )}
        </GameCard>
      </div>

      <div style={{ marginBottom: 'var(--space-md)' }}>
        <button
          onClick={simulate}
          disabled={loading || !attackerId || !defenderId}
          style={{
            background: 'var(--gold)',
            color: 'var(--bg-base)',
            fontWeight: 600,
            padding: 'var(--space-sm) var(--space-lg)',
          }}
        >
          {loading ? '시뮬레이션 중...' : '전투 시뮬레이션'}
        </button>
      </div>

      {result && (
        <GameCard>
          <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>시뮬레이션 결과</h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)' }}>
            <StatusBadge variant={result.conquerCity ? 'gold' : 'muted'}>{result.conquerCity ? '공성 성공' : '교전 종료'}</StatusBadge>
            <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>{result.reason}</span>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
              gap: 'var(--space-md)',
              marginBottom: 'var(--space-md)',
              fontSize: 'var(--text-sm)',
            }}
          >
            <div>
              <h3 style={{ color: 'var(--crimson)', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>공격 집계</h3>
              <div>
                교전 수: <strong>{result.avgWar.toLocaleString()}</strong>
              </div>
              <div>
                페이즈: <strong>{result.phase.toLocaleString()}</strong>
              </div>
              <div>
                잔여 병력: <strong>{result.attackerCrew.toLocaleString()}</strong>
              </div>
            </div>
            <div>
              <h3 style={{ color: 'var(--jade)', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>피해 집계</h3>
              <div>
                살상: <strong>{result.killed.toLocaleString()}</strong>
              </div>
              <div>
                전사: <strong>{result.dead.toLocaleString()}</strong>
              </div>
              <div>
                살상 범위:{' '}
                <strong>
                  {result.minKilled.toLocaleString()}~{result.maxKilled.toLocaleString()}
                </strong>
              </div>
              <div>
                전사 범위:{' '}
                <strong>
                  {result.minDead.toLocaleString()}~{result.maxDead.toLocaleString()}
                </strong>
              </div>
            </div>
            <div>
              <h3 style={{ color: 'var(--text-secondary)', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>성 상태</h3>
              <div>
                방어: <strong>{result.defenderCityDefense.toLocaleString()}</strong>
              </div>
              <div>
                성벽: <strong>{result.defenderCityWall.toLocaleString()}</strong>
              </div>
              <div>
                반복: <strong>{result.repeatCnt.toLocaleString()}</strong>
              </div>
            </div>
          </div>
          {(attackerSkillEntries.length > 0 || defenderSkillGroups.length > 0) && (
            <div
              style={{
                border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-sm)',
                padding: 'var(--space-sm)',
                background: 'var(--bg-base)',
                maxHeight: '16rem',
                overflowY: 'auto',
              }}
            >
              <h3 style={{ fontSize: 'var(--text-sm)', fontWeight: 600, marginBottom: 'var(--space-xs)', color: 'var(--text-secondary)' }}>발동 스킬</h3>
              <div style={{ display: 'grid', gap: 'var(--space-sm)', fontSize: 'var(--text-xs)', fontFamily: 'var(--font-mono)' }}>
                {attackerSkillEntries.length > 0 && (
                  <div>
                    <div style={{ color: 'var(--crimson)', fontWeight: 600, marginBottom: 2 }}>공격자</div>
                    {attackerSkillEntries.map(([name, count]) => (
                      <div key={name} style={{ color: 'var(--text-secondary)' }}>
                        {name}: {count.toLocaleString()}
                      </div>
                    ))}
                  </div>
                )}
                {defenderSkillGroups.map((group) => (
                  <div key={group.idx}>
                    <div style={{ color: 'var(--jade)', fontWeight: 600, marginBottom: 2 }}>방어자 {group.idx + 1}</div>
                    {group.entries.map(([name, count]) => (
                      <div key={name} style={{ color: 'var(--text-secondary)' }}>
                        {name}: {count.toLocaleString()}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          )}
        </GameCard>
      )}
    </Shell>
  );
}
