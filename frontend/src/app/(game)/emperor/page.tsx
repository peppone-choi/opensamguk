"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { historyApi } from "@/lib/gameApi";
import {
  Crown,
  ChevronDown,
  ChevronRight,
  BarChart3,
  ScrollText,
  Trophy,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatOfficerLevelText } from "@/lib/game-utils";
import type { Message, YearbookSummary } from "@/types";

const LEVEL_LABELS: Record<number, string> = {
  0: "재야",
  1: "주자사",
  2: "주목",
  3: "자사",
  4: "목",
  5: "공",
  6: "왕",
  7: "황제",
};

export default function EmperorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();
  const [dynastyLogs, setDynastyLogs] = useState<Message[]>([]);
  const [records, setRecords] = useState<Message[]>([]);
  const [yearbook, setYearbook] = useState<YearbookSummary | null>(null);
  const [selectedYear, setSelectedYear] = useState<number | null>(null);
  const [yearbookLoading, setYearbookLoading] = useState(false);
  const [showTimeline, setShowTimeline] = useState(true);
  const [showRecords, setShowRecords] = useState(false);
  const [showStats, setShowStats] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    historyApi
      .getWorldHistory(currentWorld.id)
      .then(({ data }) => setDynastyLogs(data))
      .catch(() => setDynastyLogs([]));
    historyApi
      .getWorldRecords(currentWorld.id)
      .then(({ data }) => setRecords(data))
      .catch(() => setRecords([]));
  }, [currentWorld, loadAll]);

  const emperorNation = useMemo(
    () => nations.find((n) => n.level >= 7),
    [nations],
  );

  const chiefGeneral = useMemo(
    () =>
      emperorNation
        ? generals.find((g) => g.id === emperorNation.chiefGeneralId)
        : null,
    [emperorNation, generals],
  );

  const capitalCity = useMemo(
    () =>
      emperorNation
        ? cities.find((c) => c.id === emperorNation.capitalCityId)
        : null,
    [emperorNation, cities],
  );

  const nationGenerals = useMemo(
    () =>
      emperorNation
        ? generals
            .filter((g) => g.nationId === emperorNation.id)
            .sort((a, b) => b.officerLevel - a.officerLevel)
        : [],
    [emperorNation, generals],
  );

  const nationCities = useMemo(
    () =>
      emperorNation
        ? cities.filter((c) => c.nationId === emperorNation.id)
        : [],
    [emperorNation, cities],
  );

  // Dynasty timeline — group logs by type/era
  const dynastyTimeline = useMemo(() => {
    return dynastyLogs.map((log) => {
      const raw = log.payload?.content;
      const text =
        typeof raw === "string" ? raw : JSON.stringify(log.payload ?? {});
      const nationColor = (log.payload?.color as string) ?? undefined;
      const nationName = (log.payload?.nationName as string) ?? undefined;
      return { ...log, text, nationColor, nationName };
    });
  }, [dynastyLogs]);

  // Season records — group by type
  const recordsByType = useMemo(() => {
    const map = new Map<string, Message[]>();
    for (const r of records) {
      const type = (r.messageType ?? "기타") as string;
      if (!map.has(type)) map.set(type, []);
      map.get(type)!.push(r);
    }
    return map;
  }, [records]);

  // Nation power stats for histogram
  const nationStats = useMemo(() => {
    return [...nations]
      .sort((a, b) => b.power - a.power)
      .map((n) => ({
        id: n.id,
        name: n.name,
        color: n.color,
        power: n.power,
        generalCount: generals.filter((g) => g.nationId === n.id).length,
        cityCount: cities.filter((c) => c.nationId === n.id).length,
        gold: n.gold,
        rice: n.rice,
        tech: n.tech,
        level: n.level,
      }));
  }, [nations, generals, cities]);

  const maxPower = Math.max(1, ...nationStats.map((n) => n.power));
  const maxGold = Math.max(1, ...nationStats.map((n) => n.gold));
  const maxRice = Math.max(1, ...nationStats.map((n) => n.rice));
  const maxTech = Math.max(1, ...nationStats.map((n) => n.tech));

  const loadYearbook = useCallback(
    async (year: number) => {
      if (!currentWorld) return;
      setYearbookLoading(true);
      try {
        const { data } = await historyApi.getYearbook(currentWorld.id, year);
        setYearbook(data);
        setSelectedYear(year);
      } catch {
        setYearbook(null);
      } finally {
        setYearbookLoading(false);
      }
    },
    [currentWorld],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading)
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );

  return (
    <div className="p-4 space-y-6 max-w-4xl mx-auto">
      <PageHeader icon={Crown} title="황제 정보" />

      {/* Current Emperor */}
      {emperorNation ? (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Crown className="size-5 text-amber-400" />
              <NationBadge
                name={emperorNation.name}
                color={emperorNation.color}
              />
              <Badge variant="secondary">황제국</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              <StatItem label="국가 레벨" value={String(emperorNation.level)} />
              <StatItem label="기술력" value={String(emperorNation.tech)} />
              <StatItem label="국력" value={emperorNation.power.toLocaleString()} />
              <StatItem label="수도" value={capitalCity?.name ?? "-"} />
              <StatItem
                label="금"
                value={
                  <span className="text-yellow-400">
                    {emperorNation.gold.toLocaleString()}
                  </span>
                }
              />
              <StatItem
                label="쌀"
                value={
                  <span className="text-green-400">
                    {emperorNation.rice.toLocaleString()}
                  </span>
                }
              />
              <StatItem label="도시" value={`${nationCities.length}개`} />
              <StatItem label="장수" value={`${nationGenerals.length}명`} />
            </div>

            {chiefGeneral && (
              <div className="border-t border-muted/30 pt-3">
                <h3 className="text-sm font-semibold mb-2">군주</h3>
                <div className="flex items-center gap-3">
                  <GeneralPortrait
                    picture={chiefGeneral.picture}
                    name={chiefGeneral.name}
                    size="md"
                  />
                  <div>
                    <div className="font-bold">{chiefGeneral.name}</div>
                    <div className="text-xs text-muted-foreground">
                      통{chiefGeneral.leadership} 무{chiefGeneral.strength} 지
                      {chiefGeneral.intel} 정{chiefGeneral.politics} 매
                      {chiefGeneral.charm}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Key officers */}
            {nationGenerals.filter((g) => g.officerLevel >= 5).length > 0 && (
              <div className="border-t border-muted/30 pt-3">
                <h3 className="text-sm font-semibold mb-2">주요 관직</h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {nationGenerals
                    .filter((g) => g.officerLevel >= 5)
                    .map((g) => (
                      <div
                        key={g.id}
                        className="flex items-center gap-2 rounded border border-muted/30 p-2"
                      >
                        <GeneralPortrait
                          picture={g.picture}
                          name={g.name}
                          size="sm"
                        />
                        <div className="flex-1 min-w-0">
                          <span className="font-medium text-sm">{g.name}</span>
                          <Badge variant="outline" className="ml-1 text-[10px]">
                            {formatOfficerLevelText(
                              g.officerLevel,
                              emperorNation.level,
                            )}
                          </Badge>
                        </div>
                        <div className="text-[10px] text-muted-foreground whitespace-nowrap">
                          통{g.leadership} 무{g.strength} 지{g.intel}
                        </div>
                      </div>
                    ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="py-4">
            <p className="text-sm text-muted-foreground text-center">
              아직 황제를 칭한 국가가 없습니다.
            </p>
          </CardContent>
        </Card>
      )}

      {/* Nation levels overview */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">국가 작위 현황</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>국가</TableHead>
                <TableHead>레벨</TableHead>
                <TableHead>칭호</TableHead>
                <TableHead className="text-right">국력</TableHead>
                <TableHead className="text-right">장수</TableHead>
                <TableHead className="text-right">속령</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...nations]
                .sort((a, b) => b.level - a.level || b.power - a.power)
                .map((n) => (
                  <TableRow key={n.id}>
                    <TableCell>
                      <NationBadge name={n.name} color={n.color} />
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{n.level}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {LEVEL_LABELS[n.level] ?? n.level}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {n.power.toLocaleString()}
                    </TableCell>
                    <TableCell className="text-right">
                      {generals.filter((g) => g.nationId === n.id).length}
                    </TableCell>
                    <TableCell className="text-right">
                      {cities.filter((c) => c.nationId === n.id).length}
                    </TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Statistics Histogram */}
      <Card>
        <CardHeader
          className="pb-2 cursor-pointer"
          onClick={() => setShowStats(!showStats)}
        >
          <CardTitle className="text-base flex items-center gap-2">
            <BarChart3 className="size-4" />
            통계 히스토그램
            {showStats ? (
              <ChevronDown className="size-4 ml-auto" />
            ) : (
              <ChevronRight className="size-4 ml-auto" />
            )}
          </CardTitle>
        </CardHeader>
        {showStats && (
          <CardContent className="space-y-4">
            <BarSection title="국력" stats={nationStats} field="power" max={maxPower} />
            <BarSection title="금" stats={nationStats} field="gold" max={maxGold} />
            <BarSection title="쌀" stats={nationStats} field="rice" max={maxRice} />
            <BarSection title="기술" stats={nationStats} field="tech" max={maxTech} />
          </CardContent>
        )}
      </Card>

      {/* Dynasty Timeline */}
      <Card>
        <CardHeader
          className="pb-2 cursor-pointer"
          onClick={() => setShowTimeline(!showTimeline)}
        >
          <CardTitle className="text-base flex items-center gap-2">
            <ScrollText className="size-4" />
            왕조 연표 ({dynastyTimeline.length}건)
            {showTimeline ? (
              <ChevronDown className="size-4 ml-auto" />
            ) : (
              <ChevronRight className="size-4 ml-auto" />
            )}
          </CardTitle>
        </CardHeader>
        {showTimeline && (
          <CardContent>
            {dynastyTimeline.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                연표 데이터가 없습니다.
              </p>
            ) : (
              <div className="space-y-1 max-h-[400px] overflow-y-auto">
                {dynastyTimeline.map((log) => (
                  <div
                    key={log.id}
                    className="flex gap-2 rounded border border-muted/20 p-2 text-xs"
                    style={
                      log.nationColor
                        ? { borderLeftColor: log.nationColor, borderLeftWidth: 3 }
                        : undefined
                    }
                  >
                    <span className="text-muted-foreground whitespace-nowrap shrink-0">
                      {log.sentAt}
                    </span>
                    <span className="flex-1">{log.text}</span>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        )}
      </Card>

      {/* Season Records */}
      <Card>
        <CardHeader
          className="pb-2 cursor-pointer"
          onClick={() => setShowRecords(!showRecords)}
        >
          <CardTitle className="text-base flex items-center gap-2">
            <Trophy className="size-4" />
            시즌 기록 ({records.length}건)
            {showRecords ? (
              <ChevronDown className="size-4 ml-auto" />
            ) : (
              <ChevronRight className="size-4 ml-auto" />
            )}
          </CardTitle>
        </CardHeader>
        {showRecords && (
          <CardContent>
            {records.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                기록 데이터가 없습니다.
              </p>
            ) : (
              <div className="space-y-3">
                {Array.from(recordsByType.entries()).map(([type, msgs]) => (
                  <div key={type}>
                    <h4 className="text-sm font-semibold mb-1 text-amber-400">
                      {type}
                    </h4>
                    <div className="space-y-1">
                      {msgs.map((msg) => {
                        const raw = msg.payload?.content;
                        const text =
                          typeof raw === "string"
                            ? raw
                            : JSON.stringify(msg.payload ?? {});
                        return (
                          <div
                            key={msg.id}
                            className="rounded border border-muted/20 p-2 text-xs"
                          >
                            <span className="text-muted-foreground mr-2">
                              {msg.sentAt}
                            </span>
                            {text}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        )}
      </Card>

      {/* Yearbook Viewer */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">연감 조회</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-2">
            <input
              type="number"
              min={1}
              placeholder="연도 입력"
              className="h-8 w-24 rounded border border-input bg-background px-2 text-sm"
              onChange={(e) => {
                const v = parseInt(e.target.value);
                if (!isNaN(v) && v > 0) setSelectedYear(v);
              }}
            />
            <Button
              size="sm"
              variant="outline"
              disabled={yearbookLoading || !selectedYear}
              onClick={() => selectedYear && loadYearbook(selectedYear)}
            >
              조회
            </Button>
          </div>
          {yearbookLoading && <LoadingState />}
          {yearbook && (
            <div className="space-y-2">
              <h4 className="text-sm font-semibold">
                {yearbook.year}년 {yearbook.month}월 현황
              </h4>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>국가</TableHead>
                    <TableHead className="text-right">속령</TableHead>
                    <TableHead className="text-right">장수</TableHead>
                    <TableHead>도시</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {yearbook.nations.map((yn) => (
                    <TableRow key={yn.id}>
                      <TableCell>
                        <NationBadge name={yn.name} color={yn.color} />
                      </TableCell>
                      <TableCell className="text-right">
                        {yn.territoryCount}
                      </TableCell>
                      <TableCell className="text-right">
                        {yn.generalCount ?? "-"}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {yn.cities.join(", ")}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {yearbook.keyEvents.length > 0 && (
                <div className="space-y-1">
                  <h5 className="text-xs font-semibold text-amber-400">
                    주요 사건
                  </h5>
                  {yearbook.keyEvents.map((ev) => {
                    const raw = ev.payload?.content;
                    const text =
                      typeof raw === "string"
                        ? raw
                        : JSON.stringify(ev.payload ?? {});
                    return (
                      <div
                        key={ev.id}
                        className="rounded border border-muted/20 p-2 text-xs"
                      >
                        {text}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function StatItem({
  label,
  value,
}: {
  label: string;
  value: React.ReactNode;
}) {
  return (
    <div className="flex justify-between">
      <span className="text-muted-foreground">{label}</span>
      <span>{value}</span>
    </div>
  );
}

function BarSection({
  title,
  stats,
  field,
  max,
}: {
  title: string;
  stats: { id: number; name: string; color: string; [k: string]: unknown }[];
  field: string;
  max: number;
}) {
  return (
    <div>
      <h4 className="text-xs font-semibold mb-1 text-muted-foreground">
        {title}
      </h4>
      <div className="space-y-1">
        {stats.map((n) => {
          const val = (n[field] as number) ?? 0;
          const pct = (val / max) * 100;
          return (
            <div key={n.id} className="flex items-center gap-2 text-xs">
              <span className="w-16 text-right shrink-0 truncate">
                {n.name}
              </span>
              <div className="flex-1 h-4 bg-muted/20 rounded overflow-hidden">
                <div
                  className="h-full rounded transition-all"
                  style={{
                    width: `${pct}%`,
                    backgroundColor: n.color || "#666",
                  }}
                />
              </div>
              <span className="w-16 text-right tabular-nums">
                {val.toLocaleString()}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
