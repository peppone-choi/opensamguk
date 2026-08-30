'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import GeneralName from '../../../../components/game/GeneralName';
import { api } from '../../../../lib/api';
import { useTurnRefresh } from '../../../../hooks/useTurnRefresh';
import type { NpcGeneral } from '../../../../types/game';

// legacy a_npcList.php 정렬 8종. PHP $sortType: [key, isAsc].
// 1 이름(asc) · 2 국가(asc) · 3 종능(desc) · 4 통솔(desc) · 5 무력(desc) · 6 지력(desc) · 7 명성(desc) · 8 계급(desc)
type SortType = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;
const SORT_OPTIONS: { value: SortType; label: string }[] = [
  { value: 1, label: '이름' },
  { value: 2, label: '국가' },
  { value: 3, label: '종능' },
  { value: 4, label: '통솔' },
  { value: 5, label: '무력' },
  { value: 6, label: '지력' },
  { value: 7, label: '명성' },
  { value: 8, label: '계급' },
];

export default function NpcsPage() {
  const [data, setData] = useState<NpcGeneral[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [sortType, setSortType] = useState<SortType>(1);

  // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
  const fetchData = useCallback((background = false) => {
    if (!background) setLoading(true);
    api.rankings
      .npcs<NpcGeneral[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => { if (!background) setLoading(false); });
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  // OPENSAM-196 — 턴 종료 시 빙의 일람 재조회.
  useTurnRefresh(() => fetchData(true));

  // PHP usort: asc=1·2, 그 외 desc. (PHP `<=>` 동치)
  const sorted = useMemo(() => {
    const arr = [...data];
    const cmp = (a: NpcGeneral, b: NpcGeneral): number => {
      switch (sortType) {
        case 1:
          return a.name.localeCompare(b.name); // 이름 asc
        case 2:
          return a.nation.localeCompare(b.nation); // 국가 asc
        case 3:
          return b.total - a.total; // 종능 desc
        case 4:
          return b.leadership - a.leadership;
        case 5:
          return b.strength - a.strength;
        case 6:
          return b.intel - a.intel;
        case 7:
          return b.experience - a.experience; // 명성 desc
        case 8:
          return b.devotion - a.devotion; // 계급 desc
        default:
          return 0;
      }
    };
    arr.sort(cmp);
    return arr;
  }, [data, sortType]);

  if (loading)
    return (
      <Shell>
        <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>빙의 일람</h1>
        <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
      </Shell>
    );

  if (error)
    return (
      <Shell>
        <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>빙의 일람</h1>
        <p style={{ color: 'var(--crimson)' }}>{error}</p>
      </Shell>
    );

  // legacy 12컬럼: 희생된 장수 | 악령 이름 | 레벨 | 국가 | 성격 | 특기 | 종능 | 통솔 | 무력 | 지력 | 명성 | 계급
  const headers = [
    '희생된 장수',
    '악령 이름',
    '레벨',
    '국가',
    '성격',
    '특기',
    '종능',
    '통솔',
    '무력',
    '지력',
    '정치',
    '매력',
    '명성',
    '계급',
  ];
  const rows = sorted.map((g) => [
    // formatName 동치: npc 타입색으로 이름 렌더.
    <GeneralName key="name" name={g.name} npcType={g.npc} />,
    g.ownerName ?? '-', // BLOCKED(owner_name 부재) → "-"
    `Lv ${g.explevel}`,
    <span key="nation" style={{ color: g.nationColor }}>
      {g.nation}
    </span>,
    g.personalText,
    `${g.specialDomesticName} / ${g.specialWarName}`,
    String(g.total),
    String(g.leadership),
    String(g.strength),
    String(g.intel),
    g.politics ?? '-', // 정치 (optional, injury/lbonus 미적용 평문)
    g.charm ?? '-', // 매력 (optional, injury/lbonus 미적용 평문)
    String(g.experience), // 명성 = raw experience
    String(g.devotion), // 계급 = raw dedication
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>빙의 일람</h1>

      <div style={{ marginBottom: 'var(--space-md)' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>정렬순서:</span>
          <select value={sortType} onChange={(e) => setSortType(Number(e.target.value) as SortType)} style={{ minWidth: 120 }}>
            {SORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <span style={{ color: 'var(--text-muted)' }}>{sorted.length}명</span>
        </label>
      </div>

      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
