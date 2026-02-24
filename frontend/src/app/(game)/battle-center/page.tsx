"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { generalLogApi, type GeneralLogEntry } from "@/lib/gameApi";
import type { General } from "@/types";
import { Swords, ChevronLeft, ChevronRight, ArrowLeftRight } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NationBadge } from "@/components/game/nation-badge";
import { formatLog } from "@/lib/formatLog";

/** NPC color helper matching legacy getNPCColor */
function getNPCColor(npcState: number): string | undefined {
  if (npcState === 0) return undefined;
  if (npcState >= 5) return "#69f";
  if (npcState >= 2) return "#c93";
  return "#999";
}

type SortKey = "recent_war" | "warnum" | "turntime" | "name" | "killcrew" | "killnum" | "deathnum";

const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: "recent_war", label: "최근 전투" },
  { key: "warnum", label: "전투 횟수" },
  { key: "killnum", label: "승리" },
  { key: "deathnum", label: "패배" },
  { key: "killcrew", label: "살상" },
  { key: "turntime", label: "최근 턴" },
  { key: "name", label: "이름" },
];

interface GeneralLogs {
  generalHistory: GeneralLogEntry[];
  battleResult: GeneralLogEntry[];
  battleDetail: GeneralLogEntry[];
  generalAction: GeneralLogEntry[];
}

const LOG_TYPES = ["generalHistory", "battleDetail", "battleResult", "generalAction"] as const;

export default function BattleCenterPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, nations, loading, loadAll } = useGameStore();

  const [orderBy, setOrderBy] = useState<SortKey>("turntime");
  const [targetGeneralId, setTargetGeneralId] = useState<number | null>(null);
  const [logs, setLogs] = useState<GeneralLogs | null>(null);
  const [logsLoading, setLogsLoading] = useState(false);

  // Comparison mode
  const [compareMode, setCompareMode] = useState(false);
  const [compareGeneralId, setCompareGeneralId] = useState<number | null>(null);

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);
      if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, loadAll, myGeneral, fetchMyGeneral]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const orderedGenerals = useMemo(() => {
    const list = generals.filter((g) => g.nationId > 0);
    list.sort((a, b) => {
      switch (orderBy) {
        case "recent_war":
          return (b.recentWarTime ?? "").localeCompare(a.recentWarTime ?? "");
        case "warnum":
          return (b.warnum ?? 0) - (a.warnum ?? 0);
        case "killnum":
          return (b.killnum ?? 0) - (a.killnum ?? 0);
        case "deathnum":
          return (b.deathnum ?? 0) - (a.deathnum ?? 0);
        case "killcrew":
          return (b.killcrew ?? 0) - (a.killcrew ?? 0);
        case "turntime":
          return (b.turnTime ?? "").localeCompare(a.turnTime ?? "");
        case "name": {
          return `${a.npcState} ${a.name}`.localeCompare(`${b.npcState} ${b.name}`);
        }
        default:
          return 0;
      }
    });
    return list;
  }, [generals, orderBy]);

  useEffect(() => {
    if (orderedGenerals.length > 0 && targetGeneralId === null) {
      setTargetGeneralId(orderedGenerals[0].id);
    }
  }, [orderedGenerals, targetGeneralId]);

  const targetGeneral = useMemo(
    () => orderedGenerals.find((g) => g.id === targetGeneralId) ?? null,
    [orderedGenerals, targetGeneralId],
  );

  const compareGeneral = useMemo(
    () => (compareGeneralId ? orderedGenerals.find((g) => g.id === compareGeneralId) ?? null : null),
    [orderedGenerals, compareGeneralId],
  );

  const currentIdx = useMemo(
    () => orderedGenerals.findIndex((g) => g.id === targetGeneralId),
    [orderedGenerals, targetGeneralId],
  );

  // Load per-general logs when target changes
  const loadLogs = useCallback(
    async (generalId: number) => {
      if (!myGeneral) return;
      setLogsLoading(true);
      const newLogs: GeneralLogs = {
        generalHistory: [],
        battleResult: [],
        battleDetail: [],
        generalAction: [],
      };
      await Promise.all(
        LOG_TYPES.map(async (type) => {
          try {
            const { data } = await generalLogApi.getOldLogs(
              myGeneral.id,
              generalId,
              type,
            );
            if (data.result) {
              newLogs[type] = data.logs;
            }
          } catch {
            // ignore - generalHistory may not be supported by backend
          }
        }),
      );
      setLogs(newLogs);
      setLogsLoading(false);
    },
    [myGeneral],
  );

  useEffect(() => {
    if (targetGeneralId && myGeneral) {
      loadLogs(targetGeneralId);
    }
  }, [targetGeneralId, myGeneral, loadLogs]);

  const changeTarget = (offset: number) => {
    if (orderedGenerals.length === 0) return;
    let newIdx = currentIdx + offset;
    if (newIdx < 0) newIdx = orderedGenerals.length - 1;
    if (newIdx >= orderedGenerals.length) newIdx = 0;
    setTargetGeneralId(orderedGenerals[newIdx].id);
  };

  function getSortSuffix(g: General): string {
    switch (orderBy) {
      case "recent_war":
        return g.recentWarTime ? `[${g.recentWarTime.slice(-5)}]` : "";
      case "warnum":
        return `[${g.warnum ?? 0}회]`;
      case "killnum":
        return `[승${g.killnum ?? 0}]`;
      case "deathnum":
        return `[패${g.deathnum ?? 0}]`;
      case "killcrew":
        return `[${(g.killcrew ?? 0).toLocaleString()}]`;
      default:
        return "";
    }
  }

  if (!currentWorld)
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  if (loading) return <LoadingState />;

  const nation = targetGeneral ? nationMap.get(targetGeneral.nationId) : null;
  const compareNation = compareGeneral ? nationMap.get(compareGeneral.nationId) : null;

  return (
    <div className="space-y-4 max-w-5xl mx-auto">
      <PageHeader icon={Swords} title="감찰부" description="장수별 전투 기록 및 상세 정보" />

      {/* Navigation bar */}
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={() => changeTarget(-1)} className="shrink-0">
          <ChevronLeft className="size-4" /> 이전
        </Button>
        <select
          value={orderBy}
          onChange={(e) => {
            setOrderBy(e.target.value as SortKey);
            setTargetGeneralId(null);
          }}
          className="h-8 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded"
        >
          {SORT_OPTIONS.map((o) => (
            <option key={o.key} value={o.key}>{o.label}</option>
          ))}
        </select>
        <select
          value={targetGeneralId ?? ""}
          onChange={(e) => setTargetGeneralId(Number(e.target.value))}
          className="h-8 flex-1 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded min-w-0"
        >
          {orderedGenerals.map((g) => {
            const npcColor = getNPCColor(g.npcState);
            const nameDisplay = g.officerLevel > 4 ? `*${g.name}*` : g.name;
            return (
              <option key={g.id} value={g.id} style={npcColor ? { color: npcColor } : undefined}>
                {nameDisplay}({g.turnTime?.slice(-5) ?? ""}) {getSortSuffix(g)}
              </option>
            );
          })}
        </select>
        <Button variant="outline" size="sm" onClick={() => changeTarget(1)} className="shrink-0">
          다음 <ChevronRight className="size-4" />
        </Button>
      </div>

      {/* Comparison mode toggle + selector */}
      <div className="flex items-center gap-2">
        <Button
          variant={compareMode ? "default" : "outline"}
          size="sm"
          onClick={() => {
            setCompareMode(!compareMode);
            if (compareMode) setCompareGeneralId(null);
          }}
        >
          <ArrowLeftRight className="size-4 mr-1" />
          비교 모드
        </Button>
        {compareMode && (
          <select
            value={compareGeneralId ?? ""}
            onChange={(e) => setCompareGeneralId(Number(e.target.value) || null)}
            className="h-8 flex-1 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded min-w-0"
          >
            <option value="">비교 대상 선택...</option>
            {orderedGenerals
              .filter((g) => g.id !== targetGeneralId)
              .map((g) => {
                const npcColor = getNPCColor(g.npcState);
                const nameDisplay = g.officerLevel > 4 ? `*${g.name}*` : g.name;
                return (
                  <option key={g.id} value={g.id} style={npcColor ? { color: npcColor } : undefined}>
                    {nameDisplay}({g.turnTime?.slice(-5) ?? ""})
                  </option>
                );
              })}
          </select>
        )}
      </div>

      {/* Comparison View */}
      {compareMode && targetGeneral && compareGeneral ? (
        <ComparisonView
          generalA={targetGeneral}
          generalB={compareGeneral}
          nationA={nation}
          nationB={compareNation}
        />
      ) : targetGeneral ? (
        /* Normal Detail View */
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="space-y-4">
            <GeneralBasicCard general={targetGeneral} nation={nation} />
            <GeneralSupplementCard general={targetGeneral} />
          </div>

          <div className="space-y-4">
            <LogSection
              title="장수 열전"
              titleColor="text-orange-400"
              logs={logs?.generalHistory ?? []}
              loading={logsLoading}
              emptyText="열전 기록이 없습니다."
            />
          </div>

          <LogSection
            title="전투 기록"
            logs={logs?.battleDetail ?? []}
            loading={logsLoading}
            emptyText="전투 기록이 없습니다."
          />

          <LogSection
            title="전투 결과"
            logs={logs?.battleResult ?? []}
            loading={logsLoading}
            emptyText="전투 결과가 없습니다."
          />

          {(logs?.generalAction?.length ?? 0) > 0 && (
            <LogSection
              title="개인 기록"
              logs={logs?.generalAction ?? []}
              loading={logsLoading}
              emptyText=""
            />
          )}
        </div>
      ) : orderedGenerals.length === 0 ? (
        <EmptyState icon={Swords} title="조회 가능한 장수가 없습니다." />
      ) : null}
    </div>
  );
}

/* ---- Comparison View ---- */

function ComparisonView({
  generalA,
  generalB,
  nationA,
  nationB,
}: {
  generalA: General;
  generalB: General;
  nationA?: { name: string; color: string } | null;
  nationB?: { name: string; color: string } | null;
}) {
  const stats: { label: string; a: number; b: number }[] = [
    { label: "통솔", a: generalA.leadership, b: generalB.leadership },
    { label: "무력", a: generalA.strength, b: generalB.strength },
    { label: "지력", a: generalA.intel, b: generalB.intel },
    { label: "정치", a: generalA.politics, b: generalB.politics },
    { label: "매력", a: generalA.charm, b: generalB.charm },
    { label: "병사", a: generalA.crew, b: generalB.crew },
    { label: "훈련", a: generalA.train, b: generalB.train },
    { label: "사기", a: generalA.atmos, b: generalB.atmos },
    { label: "수비훈", a: generalA.defenceTrain, b: generalB.defenceTrain },
    { label: "전투", a: generalA.warnum ?? 0, b: generalB.warnum ?? 0 },
    { label: "승리", a: generalA.killnum ?? 0, b: generalB.killnum ?? 0 },
    { label: "패배", a: generalA.deathnum ?? 0, b: generalB.deathnum ?? 0 },
    { label: "살상", a: generalA.killcrew ?? 0, b: generalB.killcrew ?? 0 },
    { label: "피해", a: generalA.deathcrew ?? 0, b: generalB.deathcrew ?? 0 },
    { label: "경험", a: generalA.experience, b: generalB.experience },
    { label: "공헌", a: generalA.dedication, b: generalB.dedication },
  ];

  const dexLabels = ["창", "궁", "기", "귀/차", "노"];
  const dexStats = dexLabels.map((label, i) => ({
    label,
    a: [generalA.dex1, generalA.dex2, generalA.dex3, generalA.dex4, generalA.dex5][i],
    b: [generalB.dex1, generalB.dex2, generalB.dex3, generalB.dex4, generalB.dex5][i],
  }));

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm flex items-center gap-2">
          <ArrowLeftRight className="size-4" />
          장수 비교
        </CardTitle>
      </CardHeader>
      <CardContent className="text-xs space-y-4">
        {/* Header row */}
        <div className="grid grid-cols-3 gap-2 text-center font-bold border-b border-gray-700 pb-2">
          <div className="text-left flex items-center gap-1">
            <span style={{ color: getNPCColor(generalA.npcState) }}>{generalA.name}</span>
            {nationA && <NationBadge name={nationA.name} color={nationA.color} />}
          </div>
          <div className="text-muted-foreground">항목</div>
          <div className="text-right flex items-center justify-end gap-1">
            {nationB && <NationBadge name={nationB.name} color={nationB.color} />}
            <span style={{ color: getNPCColor(generalB.npcState) }}>{generalB.name}</span>
          </div>
        </div>

        {/* Stat comparison rows */}
        {stats.map((s) => (
          <div key={s.label} className="grid grid-cols-3 gap-2 text-center items-center">
            <div className={`text-left tabular-nums ${s.a > s.b ? "text-green-400 font-bold" : s.a < s.b ? "text-red-400" : ""}`}>
              {s.a.toLocaleString()}
            </div>
            <div className="text-muted-foreground">{s.label}</div>
            <div className={`text-right tabular-nums ${s.b > s.a ? "text-green-400 font-bold" : s.b < s.a ? "text-red-400" : ""}`}>
              {s.b.toLocaleString()}
            </div>
          </div>
        ))}

        {/* Dex comparison */}
        <div className="border-t border-gray-700 pt-2">
          <div className="text-muted-foreground text-center mb-1">숙련도</div>
          {dexStats.map((s) => (
            <div key={s.label} className="grid grid-cols-3 gap-2 text-center items-center">
              <div className={`text-left tabular-nums ${s.a > s.b ? "text-green-400 font-bold" : s.a < s.b ? "text-red-400" : ""}`}>
                {s.a}
              </div>
              <div className="text-muted-foreground">{s.label}</div>
              <div className={`text-right tabular-nums ${s.b > s.a ? "text-green-400 font-bold" : s.b < s.a ? "text-red-400" : ""}`}>
                {s.b}
              </div>
            </div>
          ))}
        </div>

        {/* Equipment comparison */}
        <div className="border-t border-gray-700 pt-2">
          <div className="text-muted-foreground text-center mb-1">장비</div>
          {(["weaponCode", "bookCode", "horseCode", "itemCode"] as const).map((key) => {
            const labels: Record<string, string> = { weaponCode: "무기", bookCode: "서적", horseCode: "명마", itemCode: "아이템" };
            return (
              <div key={key} className="grid grid-cols-3 gap-2 text-center items-center">
                <div className="text-left">{generalA[key] || "없음"}</div>
                <div className="text-muted-foreground">{labels[key]}</div>
                <div className="text-right">{generalB[key] || "없음"}</div>
              </div>
            );
          })}
        </div>

        {/* Special skills comparison */}
        <div className="border-t border-gray-700 pt-2">
          <div className="text-muted-foreground text-center mb-1">특기</div>
          <div className="grid grid-cols-3 gap-2 text-center items-center">
            <div className="text-left">{generalA.specialCode || "-"}</div>
            <div className="text-muted-foreground">내정특기</div>
            <div className="text-right">{generalB.specialCode || "-"}</div>
          </div>
          <div className="grid grid-cols-3 gap-2 text-center items-center">
            <div className="text-left">{generalA.special2Code || "-"}</div>
            <div className="text-muted-foreground">전투특기</div>
            <div className="text-right">{generalB.special2Code || "-"}</div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ---- General Basic Card ---- */

const CREW_TYPES: Record<number, string> = {
  0: "보병", 1: "궁병", 2: "기병", 3: "귀병", 4: "차병", 5: "노병",
  6: "연노병", 7: "근위기병", 8: "무당병", 9: "서량기병", 10: "등갑병", 11: "수군",
};

const DEX_LABELS = ["창", "궁", "기", "귀/차", "노"];

function GeneralBasicCard({
  general,
  nation,
}: {
  general: General;
  nation?: { name: string; color: string } | null;
}) {
  const npcColor = getNPCColor(general.npcState);
  const nameDisplay = general.officerLevel > 4 ? `*${general.name}*` : general.name;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm flex items-center gap-2" style={{ color: "skyblue" }}>
          장수 정보
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-xs">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-base font-bold" style={npcColor ? { color: npcColor } : undefined}>
            {nameDisplay}
          </span>
          {nation && <NationBadge name={nation.name} color={nation.color} />}
          {general.npcState > 0 && (
            <Badge variant="outline" className="text-[10px]">NPC</Badge>
          )}
        </div>

        <div className="grid grid-cols-3 gap-x-4 gap-y-1">
          <StatRow label="통솔" value={general.leadership} exp={general.leadershipExp} />
          <StatRow label="무력" value={general.strength} exp={general.strengthExp} />
          <StatRow label="지력" value={general.intel} exp={general.intelExp} />
          <StatRow label="정치" value={general.politics} />
          <StatRow label="매력" value={general.charm} />
          <StatRow label="부상" value={general.injury} valueColor={
            general.injury > 60 ? "text-red-500" :
            general.injury > 40 ? "text-fuchsia-400" :
            general.injury > 20 ? "text-orange-400" :
            general.injury > 0 ? "text-yellow-400" : "text-white"
          } />
        </div>

        <div className="grid grid-cols-2 gap-x-4 gap-y-1">
          <EquipRow label="무기" value={general.weaponCode} />
          <EquipRow label="서적" value={general.bookCode} />
          <EquipRow label="명마" value={general.horseCode} />
          <EquipRow label="아이템" value={general.itemCode} />
        </div>

        <div className="grid grid-cols-3 gap-x-4 gap-y-1">
          <div><span className="text-muted-foreground">병종:</span> {CREW_TYPES[general.crewType] ?? general.crewType}</div>
          <div><span className="text-muted-foreground">병사:</span> {general.crew.toLocaleString()}</div>
          <div><span className="text-muted-foreground">훈련:</span> {general.train}</div>
          <div><span className="text-muted-foreground">사기:</span> {general.atmos}</div>
          <div><span className="text-muted-foreground">수비훈:</span> {general.defenceTrain}</div>
        </div>

        <div className="flex flex-wrap gap-x-3 gap-y-1">
          {DEX_LABELS.map((label, i) => {
            const val = [general.dex1, general.dex2, general.dex3, general.dex4, general.dex5][i];
            return (
              <span key={label}>
                <span className="text-muted-foreground">{label}:</span>{" "}
                <span className="tabular-nums">{val}</span>
              </span>
            );
          })}
        </div>

        <div className="grid grid-cols-3 gap-x-4 gap-y-1">
          <div><span className="text-muted-foreground">전투:</span> {general.warnum ?? 0}</div>
          <div><span className="text-green-400">승리:</span> {general.killnum ?? 0}</div>
          <div><span className="text-red-400">패배:</span> {general.deathnum ?? 0}</div>
          <div><span className="text-green-400">살상:</span> {(general.killcrew ?? 0).toLocaleString()}</div>
          <div><span className="text-red-400">피해:</span> {(general.deathcrew ?? 0).toLocaleString()}</div>
          <div><span className="text-muted-foreground">화공:</span> {general.firenum ?? 0}</div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ---- General Supplement Card ---- */

function GeneralSupplementCard({ general }: { general: General }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">부가 정보</CardTitle>
      </CardHeader>
      <CardContent className="text-xs space-y-1">
        <div className="grid grid-cols-2 gap-x-4 gap-y-1">
          <div><span className="text-muted-foreground">성격:</span> {general.personalCode || "-"}</div>
          <div><span className="text-muted-foreground">전투특기:</span> {general.special2Code || "-"}</div>
          <div><span className="text-muted-foreground">내정특기:</span> {general.specialCode || "-"}</div>
          <div><span className="text-muted-foreground">경험:</span> {general.experience.toLocaleString()} (Lv.{general.expLevel})</div>
          <div><span className="text-muted-foreground">공헌:</span> {general.dedication.toLocaleString()} (Lv.{general.dedLevel})</div>
          <div><span className="text-muted-foreground">금:</span> {general.gold.toLocaleString()}</div>
          <div><span className="text-muted-foreground">쌀:</span> {general.rice.toLocaleString()}</div>
          <div><span className="text-muted-foreground">소속:</span> {general.belong}턴</div>
          <div><span className="text-muted-foreground">배반:</span> {general.betray}턴</div>
          <div><span className="text-muted-foreground">나이:</span> {general.age}세</div>
          <div>
            <span className="text-muted-foreground">최근 턴:</span>{" "}
            {general.turnTime ? general.turnTime.slice(-8) : "-"}
          </div>
          <div>
            <span className="text-muted-foreground">최근 전투:</span>{" "}
            {general.recentWarTime ? general.recentWarTime.slice(-8) : "-"}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ---- Log Section ---- */

const LOG_PAGE_SIZE = 30;

function LogSection({
  title,
  titleColor,
  logs,
  loading: isLoading,
  emptyText,
}: {
  title: string;
  titleColor?: string;
  logs: GeneralLogEntry[];
  loading: boolean;
  emptyText: string;
}) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(logs.length / LOG_PAGE_SIZE));
  const pageLogs = logs.slice(page * LOG_PAGE_SIZE, (page + 1) * LOG_PAGE_SIZE);

  return (
    <Card>
      <CardHeader className="pb-1">
        <div className="flex items-center justify-between">
          <CardTitle className={`text-sm ${titleColor ?? ""}`}>{title}</CardTitle>
          {logs.length > LOG_PAGE_SIZE && (
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon-sm"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="size-3.5" />
              </Button>
              <span className="text-[10px] text-muted-foreground tabular-nums">
                {page + 1}/{totalPages}
              </span>
              <Button
                variant="ghost"
                size="icon-sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              >
                <ChevronRight className="size-3.5" />
              </Button>
            </div>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <LoadingState message="로딩 중..." />
        ) : logs.length === 0 ? (
          emptyText ? (
            <p className="text-xs text-muted-foreground">{emptyText}</p>
          ) : null
        ) : (
          <>
            <div className="max-h-64 overflow-y-auto space-y-0.5 text-xs">
              {pageLogs.map((log) => (
                <div key={log.id} className="text-gray-300 leading-relaxed">
                  {formatLog(log.message)}
                </div>
              ))}
            </div>
            <div className="mt-1 text-[10px] text-muted-foreground text-right">
              총 {logs.length}건
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/* ---- Helpers ---- */

function StatRow({
  label,
  value,
  exp,
  valueColor,
}: {
  label: string;
  value: number;
  exp?: number;
  valueColor?: string;
}) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-muted-foreground w-6">{label}</span>
      <span className={`tabular-nums font-medium ${valueColor ?? ""}`}>{value}</span>
      {exp !== undefined && exp > 0 && (
        <span className="text-muted-foreground text-[10px]">(+{exp})</span>
      )}
    </div>
  );
}

function EquipRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className="text-muted-foreground">{label}:</span>{" "}
      <span className={value ? "" : "text-muted-foreground"}>{value || "없음"}</span>
    </div>
  );
}
