'use client';

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import { api } from '../../../../lib/api';
import { getNPCColor } from '../../../../lib/utilGame';
import { useTurnRefresh } from '../../../../hooks/useTurnRefresh';
import type { KingdomRoster, KingdomRosterGeneral } from '../../../../types/game';

// legacy func_converter.php newColor() 충실 포팅 — 어두운 국가색 위에 올릴 헤더 텍스트 대비색.
// 나열된 어두운 색이면 흰색, 그 외엔 검정(표시 전용 대비 계산, 게임값 아님).
const DARK_COLORS = new Set([
  '',
  '#330000',
  '#FF0000',
  '#800000',
  '#A0522D',
  '#FF6347',
  '#808000',
  '#008000',
  '#2E8B57',
  '#008080',
  '#6495ED',
  '#0000FF',
  '#000080',
  '#483D8B',
  '#7B68EE',
  '#800080',
  '#A9A9A9',
  '#000000',
]);
function newColor(color: string): string {
  // PHP switch는 대문자 hex 리터럴과 정확히 일치 비교(빈 문자열 포함). 대소문자 정규화 후 매칭.
  return DARK_COLORS.has(color === '' ? '' : color.toUpperCase()) ? '#FFFFFF' : '#000000';
}

// formatName 동치: npc 타입색으로 이름 렌더(공석 "-"는 그대로).
function GenName({ g }: { g: KingdomRosterGeneral }) {
  const color = getNPCColor(g.npc);
  return <span style={{ color: color ?? undefined }}>{g.name}</span>;
}

export default function KingdomsPage() {
  const [data, setData] = useState<KingdomRoster | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // 성향(nation type) code→한글명 — legacy buildNationTypeClass()->getName() 등가.
  // /api/const 의 iAction.nationType[] 가 {value: che_code, name: typeName} 직렬화
  // (GetConstController.nationTypeItem, FrontInfoController 성향 해석과 동일 소스). 상수라 1회 로드.
  const [typeNameMap, setTypeNameMap] = useState<Map<string, string>>(new Map());

  // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
  const fetchData = useCallback((background = false) => {
    if (!background) setLoading(true);
    api.rankings
      .kingdomRoster<KingdomRoster>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => { if (!background) setLoading(false); });
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  // OPENSAM-196 — 턴 종료 시 세력 일람 재조회.
  useTurnRefresh(() => fetchData(true));

  useEffect(() => {
    let on = true;
    api
      .gameConst()
      .then((c) => {
        if (!on) return;
        const m = new Map<string, string>();
        (c.iAction?.nationType ?? []).forEach((it) => {
          if (it.name) m.set(it.value, it.name);
        });
        setTypeNameMap(m);
      })
      .catch(() => {
        /* graceful: cityConst/nationType 미로드시 raw type_code 폴백 유지 */
      });
    return () => {
      on = false;
    };
  }, []);

  if (loading)
    return (
      <Shell>
        <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 일람</h1>
        <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
      </Shell>
    );

  if (error || !data)
    return (
      <Shell>
        <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 일람</h1>
        <p style={{ color: 'var(--crimson)' }}>{error || '데이터가 없습니다.'}</p>
      </Shell>
    );

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 일람</h1>

      {data.nations.map((n) => (
        <GameCard key={n.nationId}>
          {/* 국가 헤더 — 국가색 배경 + newColor 대비 텍스트 */}
          <div
            style={{
              background: n.color,
              color: newColor(n.color),
              textAlign: 'center',
              fontWeight: 700,
              padding: 'var(--space-xs) 0',
              marginBottom: 'var(--space-sm)',
            }}
          >
            【 {n.name} 】
          </div>

          {/* 성향 / 작위 / 국력 / 장수·속령 */}
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto 1fr', gap: 'var(--space-xs) var(--space-sm)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
            <span style={{ color: 'var(--text-secondary)' }}>성 향</span>
            {/* 성향 한글명 = gameConst nationType typeName(legacy getName 등가). map 미스/미로드시에도
                `che_` 접두사는 절대 화면에 노출하지 않음 — 접두사 제거 폴백(표준 타입은 클래스명=che_+name). */}
            <span style={{ color: 'var(--gold)' }}>{typeNameMap.get(n.typeCode) || n.typeCode.replace(/^che_/, '')}</span>
            <span style={{ color: 'var(--text-secondary)' }}>작 위</span>
            <span>{n.levelText}</span>
            <span style={{ color: 'var(--text-secondary)' }}>국 력</span>
            <span>{n.power}</span>
            <span style={{ color: 'var(--text-secondary)' }}>장수 / 속령</span>
            <span>{n.genNum} / {n.cityCount}</span>
          </div>

          {/* officer_level 12→5 수뇌 직책표(8칸) */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, auto 1fr)', gap: '2px var(--space-sm)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
            {n.chiefs.map((c, i) => (
              <span key={i} style={{ display: 'contents' }}>
                <span style={{ color: 'var(--text-secondary)' }}>{c.officerLevelText}</span>
                <GenName g={{ name: c.name, npc: c.npc }} />
              </span>
            ))}
          </div>

          {/* 외교권자 / 조언자 — BLOCKED(permission 컬럼 부재) → 공란 / 0명 */}
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto auto', gap: 'var(--space-xs) var(--space-sm)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
            <span style={{ color: 'var(--text-secondary)' }}>외교권자</span>
            <span>{n.ambassadors.join(', ')}</span>
            <span style={{ color: 'var(--text-secondary)' }}>조언자</span>
            <span>{n.auditorCount}명</span>
          </div>

          {/* 속령 일람 (수도 cyan 강조) — level==0이면 PHP는 '현재 위치'를 보이지만 동일 도시목록으로 렌더 */}
          <div style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
            <span style={{ color: 'var(--text-secondary)' }}>속령 일람 : </span>
            {n.cities.map((c, i) => (
              <span key={c.cityId} style={{ color: c.cityId === n.capitalCityId ? 'cyan' : undefined }}>
                {c.cityId === n.capitalCityId ? `[${c.name}]` : c.name}
                {i < n.cities.length - 1 ? ', ' : ''}
              </span>
            ))}
          </div>

          {/* 장수 일람 (dedication DESC, npc색) */}
          <div style={{ fontSize: 'var(--text-sm)' }}>
            <span style={{ color: 'var(--text-secondary)' }}>장수 일람 : </span>
            {n.generals.map((g, i) => (
              <span key={i}>
                <GenName g={g} />
                {i < n.generals.length - 1 ? ', ' : ''}
              </span>
            ))}
          </div>
        </GameCard>
      ))}

      {/* 재야(nation 0) 섹션 */}
      <GameCard>
        <div style={{ textAlign: 'center', fontWeight: 700, padding: 'var(--space-xs) 0', marginBottom: 'var(--space-sm)' }}>
          【 재 야 】
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto 1fr', gap: 'var(--space-xs) var(--space-sm)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>장 수</span>
          <span>{data.neutral.genNum}</span>
          <span style={{ color: 'var(--text-secondary)' }}>속 령</span>
          <span>{data.neutral.cityCount}</span>
        </div>
        <div style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>속령 일람 : </span>
          {data.neutral.cities.map((c, i) => (
            <span key={c.cityId}>
              {c.name}
              {i < data.neutral.cities.length - 1 ? ', ' : ''}
            </span>
          ))}
        </div>
        <div style={{ fontSize: 'var(--text-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>장수 일람 : </span>
          {data.neutral.generals.map((g, i) => (
            <span key={i}>
              <GenName g={g} />
              {i < data.neutral.generals.length - 1 ? ', ' : ''}
            </span>
          ))}
        </div>
      </GameCard>
    </Shell>
  );
}
