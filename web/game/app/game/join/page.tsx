'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import Shell from '../../../components/Shell';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';

// 능력치 상수 — 레거시 GameConst(d_setting)·BE common GameConst.kt와 동일값.
//   defaultStatTotal=165 / defaultStatMin=15 / defaultStatMax=80.
// (api.gameConst()의 FE 타입 GameConstResponse에는 이 세 값이 노출돼 있지 않아
//  현재는 BE와 동일한 상수로 보존한다. types.ts에 defaultStat* 추가 시 동적 주입 가능 — follow-up.)
const DEFAULT_STAT_TOTAL = 165;
const STAT_MIN = 15;
const STAT_MAX = 80;

// 능력치 분배식 — 레거시 hwe/ts/util/generalStats.ts를 그대로 포팅(통/무/지 순).
// PHP는 패러티 오라클이 아니다(폼 편의 기능, RNG draw 게이트 밖) → Vue 정본을 충실 이식.
type Stats = { min: number; max: number; total: number };

// abilityRand: 각 스탯 = random*65+10 → 비율 정규화 → floor → 부족분은 통솔에 가산 → 범위 벗어나면 재추첨.
function abilityRand(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 65 + 10;
  let strength = Math.random() * 65 + 10;
  let intel = Math.random() * 65 + 10;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    leadership += 1;
  }

  if (
    leadership > stats.max || strength > stats.max || intel > stats.max ||
    leadership < stats.min || strength < stats.min || intel < stats.min
  ) {
    return abilityRand(stats);
  }

  return [leadership, strength, intel];
}

// abilityLeadpow(통솔무력형): 통6:무6:지1 가중 → 부족분은 무력 가산 → min/max 클램프 캐스케이드.
function abilityLeadpow(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 6;
  let strength = Math.random() * 6;
  let intel = Math.random() * 1;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    strength += 1;
  }

  if (intel < stats.min) {
    leadership -= stats.min - intel;
    intel = stats.min;
  }
  if (leadership > stats.max) {
    strength += leadership - stats.max;
    leadership = stats.max;
  }
  if (strength > stats.max) {
    leadership += strength - stats.max;
    strength = stats.max;
  }
  if (leadership > stats.max) {
    intel += leadership - stats.max;
    leadership = stats.max;
  }

  return [leadership, strength, intel];
}

// abilityLeadint(통솔지력형): 통6:무1:지6 가중 → 부족분은 지력 가산 → min/max 클램프 캐스케이드.
function abilityLeadint(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 6;
  let strength = Math.random() * 1;
  let intel = Math.random() * 6;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    intel += 1;
  }

  if (strength < stats.min) {
    leadership -= stats.min - strength;
    strength = stats.min;
  }
  if (leadership > stats.max) {
    intel += leadership - stats.max;
    leadership = stats.max;
  }
  if (intel > stats.max) {
    leadership += intel - stats.max;
    intel = stats.max;
  }
  if (leadership > stats.max) {
    strength += leadership - stats.max;
    leadership = stats.max;
  }

  return [leadership, strength, intel];
}

// abilityPowint(무력지력형): 통1:무6:지6 가중 → 부족분은 지력 가산 → min/max 클램프 캐스케이드.
function abilityPowint(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 1;
  let strength = Math.random() * 6;
  let intel = Math.random() * 6;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    intel += 1;
  }

  if (leadership < stats.min) {
    strength -= stats.min - leadership;
    leadership = stats.min;
  }
  if (strength > stats.max) {
    intel += strength - stats.max;
    strength = stats.max;
  }
  if (intel > stats.max) {
    strength += intel - stats.max;
    intel = stats.max;
  }
  if (strength > stats.max) {
    leadership += strength - stats.max;
    strength = stats.max;
  }

  return [leadership, strength, intel];
}

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

  // 레거시 PageJoin 기본 분배: 통=total-2*floor(total/3), 무=floor(total/3), 지=floor(total/3) (165→55/55/55).
  const [name, setName] = useState(memberName);
  const [leadership, setLeadership] = useState(DEFAULT_STAT_TOTAL - 2 * Math.floor(DEFAULT_STAT_TOTAL / 3));
  const [strength, setStrength] = useState(Math.floor(DEFAULT_STAT_TOTAL / 3));
  const [intel, setIntel] = useState(Math.floor(DEFAULT_STAT_TOTAL / 3));
  const [character, setCharacter] = useState('Random');
  const [pic, setPic] = useState(true); // 전콘 사용 — 레거시 args.pic 기본 true
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
        pic, // 전콘 사용 여부 — 레거시 Join.php 'pic' 필드
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

  // 4가지 능력치 프리셋 — 레거시 PageJoin.vue 버튼(랜덤형/통솔무력형/통솔지력형/무력지력형).
  function preset(type: 'random' | 'leadpow' | 'leadint' | 'powint') {
    const stats: Stats = { min: STAT_MIN, max: STAT_MAX, total: DEFAULT_STAT_TOTAL };
    let next: [number, number, number];
    switch (type) {
      case 'random': next = abilityRand(stats); break;
      case 'leadpow': next = abilityLeadpow(stats); break;
      case 'leadint': next = abilityLeadint(stats); break;
      case 'powint': next = abilityPowint(stats); break;
    }
    const [l, s, i] = next;
    setLeadership(l); setStrength(s); setIntel(i);
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

        {/* 전콘 사용 — 레거시 PageJoin.vue 'args.pic' 체크박스(Join.php 'pic' 필드로 전송) */}
        <div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', fontWeight: 600 }}>
            <input
              type="checkbox"
              checked={pic}
              onChange={(e) => setPic(e.target.checked)}
            />
            전콘 사용
          </label>
        </div>

        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>
            능력치 (합계 {total} / {DEFAULT_STAT_TOTAL}) {remaining >= 0 ? `(남음 ${remaining})` : <span style={{ color: 'var(--color-danger)' }}>초과 {-remaining}</span>}
          </label>

          {/* 능력치 조절 프리셋 — 레거시 PageJoin.vue 4버튼(랜덤형/통솔무력형/통솔지력형/무력지력형) */}
          <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', flexWrap: 'wrap' }}>
            {([
              ['random', '랜덤형'],
              ['leadpow', '통솔무력형'],
              ['leadint', '통솔지력형'],
              ['powint', '무력지력형'],
            ] as const).map(([t, label]) => (
              <button key={t} type="button" onClick={() => preset(t)} style={{ fontSize: 'var(--text-sm)', padding: '4px 8px' }}>
                {label}
              </button>
            ))}
          </div>

          {[
            { label: '통솔', value: leadership, set: setLeadership },
            { label: '무력', value: strength, set: setStrength },
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
