'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import Shell from '../../../components/Shell';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';

const DEFAULT_STAT_TOTAL = 165;
const STAT_MIN = 15;
const STAT_MAX = 80;

const PERSONALITIES = [
  'Random',
  'che_안전',
  'che_유지',
  'che_재간',
  'che_출세',
  'che_할거',
  'che_정복',
  'che_패권',
  'che_의협',
  'che_대의',
  'che_왕좌',
];

export default function JoinPage() {
  const router = useRouter();
  const { frontInfo } = useFrontInfo();
  const memberName = frontInfo?.general?.name ?? '';

  const [name, setName] = useState(memberName);
  const [leadership, setLeadership] = useState(55);
  const [strength, setStrength] = useState(55);
  const [intel, setIntel] = useState(55);
  const [character, setCharacter] = useState('Random');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const total = leadership + strength + intel;
  const remaining = DEFAULT_STAT_TOTAL - total;

  useEffect(() => {
    if (memberName && !name) setName(memberName);
  }, [memberName, name]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (total > DEFAULT_STAT_TOTAL) {
      setError(`능력치 합계가 ${DEFAULT_STAT_TOTAL}를 초과합니다.`);
      return;
    }
    if (leadership < STAT_MIN || leadership > STAT_MAX ||
        strength < STAT_MIN || strength > STAT_MAX ||
        intel < STAT_MIN || intel > STAT_MAX) {
      setError(`각 능력치는 ${STAT_MIN}~${STAT_MAX} 사이여야 합니다.`);
      return;
    }
    setLoading(true);
    try {
      const res = await api.join({
        name: name.trim(),
        leadership,
        strength,
        intel,
        character,
      });
      if (res.status === 'AVAILABLE') {
        alert('장수가 생성되었습니다!');
        router.push('/game');
      } else {
        setError(res.reason ?? '등록할 수 없습니다.');
      }
    } catch (err: any) {
      setError(err?.message ?? '서버 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  }

  function preset(type: 'random' | 'leadership' | 'strength' | 'intel' | 'balanced') {
    switch (type) {
      case 'random':
        const r = () => Math.floor(Math.random() * (STAT_MAX - STAT_MIN + 1)) + STAT_MIN;
        let a = r(), b = r(), c = r();
        while (a + b + c > DEFAULT_STAT_TOTAL) { a = r(); b = r(); c = r(); }
        setLeadership(a); setStrength(b); setIntel(c);
        break;
      case 'leadership':
        setLeadership(80); setStrength(55); setIntel(30);
        break;
      case 'strength':
        setLeadership(30); setStrength(80); setIntel(55);
        break;
      case 'intel':
        setLeadership(30); setStrength(55); setIntel(80);
        break;
      case 'balanced':
        setLeadership(55); setStrength(55); setIntel(55);
        break;
    }
  }

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-lg)' }}>
        장수 등록
      </h1>

      {error && (
        <div style={{
          background: 'var(--color-danger-bg, #fee2e2)',
          color: 'var(--color-danger, #dc2626)',
          padding: 'var(--space-md)',
          borderRadius: 'var(--radius-md)',
          marginBottom: 'var(--space-md)',
        }}>
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} style={{ maxWidth: 480, display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>이름</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={18}
            required
            style={{ width: '100%', padding: 'var(--space-sm)', borderRadius: 'var(--radius-md)', border: '1px solid var(--color-border)' }}
          />
        </div>

        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>
            능력치 (합계 {total} / {DEFAULT_STAT_TOTAL}) {remaining >= 0 ? `(남음 ${remaining})` : <span style={{ color: 'var(--color-danger)' }}>초과 {-remaining}</span>}
          </label>

          <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', flexWrap: 'wrap' }}>
            {(['random', 'balanced', 'leadership', 'strength', 'intel'] as const).map((t) => (
              <button key={t} type="button" onClick={() => preset(t)} style={{ fontSize: 'var(--text-sm)', padding: '4px 8px' }}>
                {t === 'random' ? '랜덤' : t === 'balanced' ? '균형' : t === 'leadership' ? '통솔형' : t === 'strength' ? '묵력형' : '지력형'}
              </button>
            ))}
          </div>

          {[
            { label: '통솔', value: leadership, set: setLeadership },
            { label: '묵력', value: strength, set: setStrength },
            { label: '지력', value: intel, set: setIntel },
          ].map(({ label, value, set }) => (
            <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>
              <span style={{ width: 48, fontWeight: 500 }}>{label}</span>
              <input
                type="range"
                min={STAT_MIN}
                max={STAT_MAX}
                value={value}
                onChange={(e) => set(parseInt(e.target.value))}
                style={{ flex: 1 }}
              />
              <input
                type="number"
                min={STAT_MIN}
                max={STAT_MAX}
                value={value}
                onChange={(e) => set(Math.max(STAT_MIN, Math.min(STAT_MAX, parseInt(e.target.value) || STAT_MIN)))}
                style={{ width: 64, textAlign: 'center', padding: '4px' }}
              />
            </div>
          ))}
        </div>

        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>성격</label>
          <select
            value={character}
            onChange={(e) => setCharacter(e.target.value)}
            style={{ width: '100%', padding: 'var(--space-sm)', borderRadius: 'var(--radius-md)', border: '1px solid var(--color-border)' }}
          >
            {PERSONALITIES.map((p) => (
              <option key={p} value={p}>{p === 'Random' ? '무작위' : p}</option>
            ))}
          </select>
        </div>

        <button
          type="submit"
          disabled={loading || total > DEFAULT_STAT_TOTAL}
          style={{
            padding: 'var(--space-md)',
            borderRadius: 'var(--radius-md)',
            background: 'var(--color-primary)',
            color: '#fff',
            fontWeight: 700,
            opacity: loading || total > DEFAULT_STAT_TOTAL ? 0.6 : 1,
            cursor: loading || total > DEFAULT_STAT_TOTAL ? 'not-allowed' : 'pointer',
          }}
        >
          {loading ? '등록 중...' : '장수 등록'}
        </button>
      </form>
    </Shell>
  );
}
