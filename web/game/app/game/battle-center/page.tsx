'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { LogText, Panel, SectionHeader, type SectionTone } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GeneralBasicCard from '../../../components/game/GeneralBasicCard';
import BattleReplayList from '../../../components/game/BattleReplayList';
import { api, type GeneralLogType, type NationGeneralListResponse } from '../../../lib/api';
import type { FrontGeneralInfo, FrontNationInfo } from '../../../lib/types';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

type SortKey = 'recent_war' | 'warnum' | 'turntime' | 'name';

type GeneralRow = Record<string, unknown>;

interface BattleCenterGeneral extends FrontGeneralInfo {
  no: number;
  npc: number;
  recentWar: string | null;
  turntime: string | null;
  warnum: number;
}

interface LogEntry {
  id: number;
  text: string;
}

type LogState = Record<GeneralLogType, LogEntry[]>;
type LogErrorState = Partial<Record<GeneralLogType, string>>;

const SORTS: Record<SortKey, { label: string; asc: boolean; extra: (general: BattleCenterGeneral) => string }> = {
  recent_war: {
    label: '최근 전투',
    asc: false,
    extra: (general) => `[${lastFive(general.recentWar)}]`,
  },
  warnum: {
    label: '전투 횟수',
    asc: false,
    extra: (general) => `[${general.warnum}회]`,
  },
  turntime: {
    label: '최근 턴',
    asc: false,
    extra: () => '',
  },
  name: {
    label: '이름',
    asc: true,
    extra: () => '',
  },
};

// 구획 4종(라벨 verbatim). 색은 팔레트 톤으로: 전투 = 적갈, 열전·개인 = 정보.
const LOG_SECTIONS: { type: GeneralLogType; title: string; tone: SectionTone }[] = [
  { type: 'generalHistory', title: '장수 열전', tone: 'info' },
  { type: 'battleDetail', title: '전투 기록', tone: 'rust' },
  { type: 'battleResult', title: '전투 결과', tone: 'rust' },
  { type: 'generalAction', title: '개인 기록', tone: 'info' },
];

function lastFive(value: string | null | undefined): string {
  if (!value) return '--:--';
  const timePart = value.includes(' ') ? value.split(' ')[1] : value;
  return timePart.length >= 5 ? timePart.slice(0, 5) : timePart;
}

function raw(row: GeneralRow, ...keys: string[]): unknown {
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(row, key)) return row[key];
  }
  return undefined;
}

function numberValue(row: GeneralRow, keys: string[], fallback = 0): number {
  const value = raw(row, ...keys);
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringValue(row: GeneralRow, keys: string[], fallback = ''): string {
  const value = raw(row, ...keys);
  return typeof value === 'string' ? value : fallback;
}

function nullableString(row: GeneralRow, keys: string[]): string | null {
  const value = raw(row, ...keys);
  return typeof value === 'string' ? value : null;
}

function tupleToRow(columns: string[], values: unknown[]): GeneralRow {
  const row: GeneralRow = {};
  columns.forEach((column, index) => {
    row[column] = values[index];
  });
  return row;
}

function rowToGeneral(row: GeneralRow): BattleCenterGeneral {
  const no = numberValue(row, ['no']);
  const nationId = numberValue(row, ['nation', 'nationId']);
  const officerLevel = numberValue(row, ['officerLevel', 'officer_level']);
  const picture = nullableString(row, ['picture']);
  const imageServer = numberValue(row, ['imgsvr', 'imageServer'], 0);
  const recentWar = nullableString(row, ['recentWar', 'recent_war']);
  const turntime = nullableString(row, ['turntime', 'turnTime']);
  const warnum = numberValue(row, ['warnum']);

  return {
    no,
    hasGeneral: true,
    generalId: no,
    name: stringValue(row, ['name'], '-'),
    nationId,
    officerLevel,
    permission: 0,
    showSecret: false,
    leadership: numberValue(row, ['leadership']),
    strength: numberValue(row, ['strength']),
    intel: numberValue(row, ['intel']),
    politics: numberValue(row, ['politics']),
    charm: numberValue(row, ['charm']),
    injury: numberValue(row, ['injury']),
    gold: numberValue(row, ['gold']),
    rice: numberValue(row, ['rice']),
    crew: numberValue(row, ['crew']),
    cityId: numberValue(row, ['city', 'cityId']),
    picture,
    imageServer,
    experience: numberValue(row, ['experience']),
    dedication: numberValue(row, ['dedication']),
    train: numberValue(row, ['train']),
    atmos: numberValue(row, ['atmos']),
    crewTypeId: numberValue(row, ['crewtype', 'crewTypeId']),
    troop: numberValue(row, ['troop']),
    horse: nullableString(row, ['horse']),
    weapon: nullableString(row, ['weapon']),
    book: nullableString(row, ['book']),
    item: nullableString(row, ['item']),
    age: numberValue(row, ['age']),
    specialDomestic: nullableString(row, ['specialDomestic', 'special']),
    specialWar: nullableString(row, ['specialWar', 'special2']),
    personal: nullableString(row, ['personal']),
    explevel: numberValue(row, ['explevel']),
    dedlevel: numberValue(row, ['dedlevel']),
    killturn: numberValue(row, ['killturn']),
    officerLevelText: nullableString(row, ['officerLevelText', 'officer_level_text']),
    honorText: nullableString(row, ['honorText', 'honor_text']),
    dedLevelText: nullableString(row, ['dedLevelText', 'ded_level_text']),
    lbonus: numberValue(row, ['lbonus']),
    bill: numberValue(row, ['bill']),
    leadershipExp: numberValue(row, ['leadershipExp', 'leadership_exp']),
    strengthExp: numberValue(row, ['strengthExp', 'strength_exp']),
    intelExp: numberValue(row, ['intelExp', 'intel_exp']),
    warnum,
    killnum: numberValue(row, ['killnum']),
    deathnum: numberValue(row, ['deathnum']),
    killcrew: numberValue(row, ['killcrew']),
    deathcrew: numberValue(row, ['deathcrew']),
    firenum: numberValue(row, ['firenum']),
    belong: numberValue(row, ['belong']),
    npc: numberValue(row, ['npc']),
    recentWar,
    turntime,
  };
}

function normalizeGenerals(response: NationGeneralListResponse): BattleCenterGeneral[] {
  return response.list.map((values) => rowToGeneral(tupleToRow(response.column, values)));
}

function compareValue(general: BattleCenterGeneral, key: SortKey): string | number {
  if (key === 'recent_war') return general.recentWar ?? '';
  if (key === 'warnum') return general.warnum;
  if (key === 'turntime') return general.turntime ?? '';
  return `${general.npc} ${general.name ?? ''}`;
}

function sortGenerals(generals: BattleCenterGeneral[], sortKey: SortKey): BattleCenterGeneral[] {
  const config = SORTS[sortKey];
  return [...generals].sort((a, b) => {
    const aVal = compareValue(a, sortKey);
    const bVal = compareValue(b, sortKey);
    if (aVal === bVal) return 0;
    const cmp = aVal > bVal ? 1 : -1;
    return config.asc ? cmp : -cmp;
  });
}

function optionLabel(general: BattleCenterGeneral, sortKey: SortKey): string {
  const name = general.officerLevel > 4 ? `*${general.name}*` : (general.name ?? '-');
  return `${name}(${lastFive(general.turntime)})${SORTS[sortKey].extra(general)}`;
}

function emptyLogs(): LogState {
  return {
    generalAction: [],
    battleDetail: [],
    battleResult: [],
    generalHistory: [],
  };
}

function LogPanel({
  id,
  title,
  tone,
  logs,
  loading,
  error,
}: { id: string; title: string; tone: SectionTone; logs: LogEntry[]; loading: boolean; error?: string }) {
  return (
    <Panel className="record-panel" id={id} aria-label={title}>
      <SectionHeader title={title} tone={tone} sub={loading || error ? undefined : `${logs.length}`} />
      {error ? (
        <p role="alert" className="record-empty" style={{ color: 'var(--rust)' }}>
          {error}
        </p>
      ) : loading ? (
        <p className="record-empty">불러오는 중...</p>
      ) : logs.length === 0 ? (
        <p className="record-empty">기록이 없습니다.</p>
      ) : (
        <div className="bc-log">
          {logs.map((entry) => (
            <div key={entry.id} className="bc-log__row"><LogText text={entry.text} /></div>
          ))}
        </div>
      )}
    </Panel>
  );
}

export default function BattleCenterPage() {
  const [generals, setGenerals] = useState<BattleCenterGeneral[]>([]);
  const [nation, setNation] = useState<FrontNationInfo | null>(null);
  const [sortKey, setSortKey] = useState<SortKey>('turntime');
  const [targetId, setTargetId] = useState<number | null>(null);
  const [logs, setLogs] = useState<LogState>(emptyLogs);
  const [logErrors, setLogErrors] = useState<LogErrorState>({});
  const [loading, setLoading] = useState(true);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [error, setError] = useState('');

  const orderedGenerals = useMemo(() => sortGenerals(generals, sortKey), [generals, sortKey]);
  const targetGeneral = useMemo(() => orderedGenerals.find((general) => general.no === targetId) ?? null, [orderedGenerals, targetId]);

  // background=true는 턴 갱신용 — 보고 있던 목록/로그를 지우지 않고 장수 목록만 새로 읽는다.
  const loadGenerals = useCallback(async (background = false) => {
    if (!background) {
      setLoading(true);
      setLogs(emptyLogs());
      setLogErrors({});
    }
    setError('');
    try {
      const [listResponse, frontInfo] = await Promise.all([api.nationGeneralList(), api.frontInfo()]);
      if (listResponse.permission === 0) {
        setGenerals([]);
        setNation(frontInfo.nation);
        setTargetId(null);
        setError(listResponse.reason ?? '권한이 부족합니다.');
        return;
      }
      const normalized = normalizeGenerals(listResponse);
      setGenerals(normalized);
      setNation(frontInfo.nation);
      const sorted = sortGenerals(normalized, 'turntime');
      setTargetId((prev) => (prev != null && sorted.some((general) => general.no === prev) ? prev : (sorted[0]?.no ?? null)));
    } catch (e) {
      setGenerals([]);
      setNation(null);
      setTargetId(null);
      setError(e instanceof Error ? e.message : '감찰부 정보를 불러올 수 없습니다.');
    } finally {
      if (!background) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadGenerals();
  }, [loadGenerals]);

  useTurnRefresh(() => void loadGenerals(true));

  useEffect(() => {
    if (targetId == null) {
      setLogs(emptyLogs());
      setLogErrors({});
      return;
    }
    let active = true;
    setLoadingLogs(true);
    setLogs(emptyLogs());
    setLogErrors({});
    const load = async () => {
      const nextLogs = emptyLogs();
      const nextErrors: LogErrorState = {};
      await Promise.all(
        LOG_SECTIONS.map(async ({ type }) => {
          try {
            const res = await api.generalLog(targetId, type);
            if (!res.result) {
              nextErrors[type] = res.reason ?? '로그를 불러올 수 없습니다.';
              return;
            }
            nextLogs[type] = Object.entries(res.log ?? {})
              .map(([id, text]) => ({ id: Number(id), text }))
              .sort((a, b) => b.id - a.id);
          } catch (e) {
            nextErrors[type] = e instanceof Error ? e.message : '로그를 불러올 수 없습니다.';
          }
        }),
      );
      if (active) {
        setLogs(nextLogs);
        setLogErrors(nextErrors);
        setLoadingLogs(false);
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, [targetId]);

  function changeTargetByOffset(offset: number) {
    if (orderedGenerals.length === 0 || targetId == null) return;
    const current = orderedGenerals.findIndex((general) => general.no === targetId);
    if (current < 0) return;
    const next = (current + offset + orderedGenerals.length) % orderedGenerals.length;
    setTargetId(orderedGenerals[next].no);
  }

  function handleSortChange(nextSort: SortKey) {
    const sorted = sortGenerals(generals, nextSort);
    setSortKey(nextSort);
    setTargetId(sorted[0]?.no ?? null);
  }

  return (
    <Shell>
      <PageHead title="감찰부" chip={nation?.name || undefined} />
      {loading ? (
        <p className="text-muted">로딩 중...</p>
      ) : (
        <>
          {error && (
            <div className="record-bar">
              <p role="alert" style={{ color: 'var(--rust)', margin: 0 }}>
                {error}
              </p>
              <button type="button" className="os-button os-button--sm" onClick={() => void loadGenerals()}>
                다시 시도
              </button>
            </div>
          )}
          {!error && orderedGenerals.length === 0 ? (
            <div className="record-bar">
              <p className="text-muted" style={{ margin: 0 }}>감찰할 장수가 없습니다.</p>
              <button type="button" className="os-button os-button--sm" onClick={() => void loadGenerals()}>
                다시 시도
              </button>
            </div>
          ) : null}
          {/* Phase 4X-C: 감찰부 리플레이 열 — 계획을 봉인한 출병만 기록된다(없으면 점선 「기록 없음(계획 미봉인)」). */}
          {!error && <BattleReplayList hrefFor={(id) => `battle-replay/${id}`} />}
          {orderedGenerals.length > 0 && (
            <div className="bc-layout">
              <div className="bc-main">
                {targetGeneral && (
                  <Panel className="record-panel">
                    <SectionHeader title="장수 정보" tone="info" sub={targetGeneral.name ?? undefined} />
                    <GeneralBasicCard general={targetGeneral} nation={nation} />
                  </Panel>
                )}
                {targetGeneral && LOG_SECTIONS.map(({ type, title, tone }) => (
                  <LogPanel
                    key={type}
                    id={`bc-${type}`}
                    title={title}
                    tone={tone}
                    logs={loadingLogs ? [] : logs[type]}
                    loading={loadingLogs}
                    error={logErrors[type]}
                  />
                ))}
              </div>
              {/* 우측 레일 — 정렬 4종 · 대상 장수 · 이전/다음 · 기록 구획(앵커) */}
              <aside className="bc-rail os-panel os-panel--static record-panel" aria-label="감찰 대상">
                <SectionHeader title="감찰 대상" sub={`${orderedGenerals.length}명`} />
                <div className="bc-rail__form">
                  <label className="bc-rail__field">
                    정렬
                    <select aria-label="정렬" value={sortKey} onChange={(e) => handleSortChange(e.target.value as SortKey)}>
                      {Object.entries(SORTS).map(([key, config]) => (
                        <option key={key} value={key}>
                          {config.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="bc-rail__field">
                    대상 장수
                    <select aria-label="대상 장수" value={targetId ?? ''} onChange={(e) => setTargetId(Number(e.target.value))}>
                      {orderedGenerals.map((general) => (
                        <option key={general.no} value={general.no}>
                          {optionLabel(general, sortKey)}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
                <div className="bc-rail__nav">
                  <button type="button" className="os-button os-button--sm os-button--ghost" onClick={() => changeTargetByOffset(-1)}>
                    ◀ 이전
                  </button>
                  <button type="button" className="os-button os-button--sm os-button--ghost" onClick={() => changeTargetByOffset(1)}>
                    다음 ▶
                  </button>
                </div>
                <SectionHeader title="기록 구획" tone="rust" as="h4" />
                {LOG_SECTIONS.map(({ type, title }) => (
                  <a key={type} className="bc-rail__section" href={`#bc-${type}`}>
                    <span>{title}</span>
                    <span className="os-num">{loadingLogs ? '…' : logs[type].length}</span>
                  </a>
                ))}
              </aside>
            </div>
          )}
        </>
      )}
    </Shell>
  );
}
