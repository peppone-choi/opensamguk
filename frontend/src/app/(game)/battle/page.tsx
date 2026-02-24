"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { subscribeWebSocket } from "@/lib/websocket";
import type { Nation, General } from "@/types";
import { Swords, ArrowUpDown, Shield, Flame, User, ScrollText } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useGeneralStore } from "@/stores/generalStore";
import { generalLogApi } from "@/lib/gameApi";
import { formatLog } from "@/lib/formatLog";

function getNation(nations: Nation[], id: number) {
  return nations.find((n) => n.id === id);
}

type SortKey =
  | "totalCrew"
  | "generalCount"
  | "avgTrain"
  | "avgAtmos"
  | "totalPower";

interface MilitaryRow {
  nation: Nation;
  totalCrew: number;
  generalCount: number;
  avgTrain: number;
  avgAtmos: number;
  totalPower: number;
}

function SortIndicator({ active }: { active: boolean }) {
  return (
    <ArrowUpDown
      className={`inline size-3 ml-0.5 ${active ? "text-white" : "text-gray-500"}`}
    />
  );
}

export default function BattlePage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { cities, nations, generals, diplomacy, loading, loadAll } =
    useGameStore();

  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const [sortKey, setSortKey] = useState<SortKey>("totalCrew");
  const [sortAsc, setSortAsc] = useState(false);
  const [personalLogs, setPersonalLogs] = useState<{ id: number; message: string; date: string }[]>([]);
  const [personalLogsLoaded, setPersonalLogsLoaded] = useState(false);
  const [personalLoading, setPersonalLoading] = useState(false);
  const [logStyle, setLogStyle] = useState<"modern" | "legacy">("modern");

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);
      if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, loadAll, myGeneral, fetchMyGeneral]);

  const loadPersonalLogs = async () => {
    if (!myGeneral || !currentWorld) return;
    setPersonalLoading(true);
    try {
      const { data } = await generalLogApi.getOldLogs(myGeneral.id, myGeneral.id, "battleResult");
      setPersonalLogs(data.logs ?? []);
      setPersonalLogsLoaded(true);
    } catch {
      setPersonalLogs([]);
    } finally {
      setPersonalLoading(false);
    }
  };

  useEffect(() => {
    if (!currentWorld) return;
    return subscribeWebSocket(`/topic/world/${currentWorld.id}/battle`, () => {
      loadAll(currentWorld.id);
    });
  }, [currentWorld, loadAll]);

  // Active wars
  const wars = useMemo(
    () => diplomacy.filter((d) => d.stateCode === "war" && !d.isDead),
    [diplomacy],
  );

  // War history (all war diplomacy entries)
  const warHistory = useMemo(
    () => diplomacy.filter((d) => d.stateCode === "war"),
    [diplomacy],
  );

  // Ceasefire proposals
  const ceasefires = useMemo(
    () =>
      diplomacy.filter(
        (d) =>
          (d.stateCode === "ceasefire" ||
            d.stateCode === "ceasefire_proposal") &&
          !d.isDead,
      ),
    [diplomacy],
  );

  // Military stats per nation
  const militaryRows = useMemo(() => {
    const map = new Map<
      number,
      {
        totalCrew: number;
        generalCount: number;
        totalTrain: number;
        totalAtmos: number;
      }
    >();
    for (const g of generals) {
      if (g.nationId === 0 || g.crew <= 0) continue;
      const entry = map.get(g.nationId) ?? {
        totalCrew: 0,
        generalCount: 0,
        totalTrain: 0,
        totalAtmos: 0,
      };
      entry.totalCrew += g.crew;
      entry.generalCount += 1;
      entry.totalTrain += g.train;
      entry.totalAtmos += g.atmos;
      map.set(g.nationId, entry);
    }

    const rows: MilitaryRow[] = [];
    for (const n of nations) {
      const m = map.get(n.id);
      if (!m) continue;
      rows.push({
        nation: n,
        totalCrew: m.totalCrew,
        generalCount: m.generalCount,
        avgTrain:
          m.generalCount > 0 ? Math.round(m.totalTrain / m.generalCount) : 0,
        avgAtmos:
          m.generalCount > 0 ? Math.round(m.totalAtmos / m.generalCount) : 0,
        totalPower: Math.round(
          m.totalCrew *
            (m.totalTrain / m.generalCount / 100) *
            (m.totalAtmos / m.generalCount / 100),
        ),
      });
    }
    return rows;
  }, [generals, nations]);

  // Sorted military
  const sortedMilitary = useMemo(() => {
    const sorted = [...militaryRows].sort((a, b) => {
      const diff = a[sortKey] - b[sortKey];
      return sortAsc ? diff : -diff;
    });
    return sorted;
  }, [militaryRows, sortKey, sortAsc]);

  const maxCrew = useMemo(
    () => Math.max(1, ...militaryRows.map((r) => r.totalCrew)),
    [militaryRows],
  );

  // Front line cities
  const frontCities = useMemo(
    () => cities.filter((c) => c.frontState > 0),
    [cities],
  );

  // Generals grouped by city
  const generalsByCity = useMemo(() => {
    const map = new Map<number, General[]>();
    for (const g of generals) {
      if (g.crew <= 0) continue;
      const list = map.get(g.cityId) ?? [];
      list.push(g);
      map.set(g.cityId, list);
    }
    return map;
  }, [generals]);

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(key);
      setSortAsc(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4 max-w-4xl mx-auto">
      <PageHeader
        icon={Swords}
        title="감찰부"
        description="전쟁 현황, 군사력, 전선 정보"
      />

      <Tabs defaultValue="wars">
        <TabsList>
          <TabsTrigger value="wars">전쟁 현황</TabsTrigger>
          <TabsTrigger value="military">군사력</TabsTrigger>
          <TabsTrigger value="frontline">전선</TabsTrigger>
          {myGeneral && (
            <TabsTrigger value="personal" onClick={() => { if (!personalLogsLoaded) loadPersonalLogs(); }}>
              내 전투기록
            </TabsTrigger>
          )}
        </TabsList>

        {/* Tab 1: War Status */}
        <TabsContent value="wars" className="mt-4 space-y-4">
          {/* Active Wars */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Flame className="size-4 text-red-400" />
                진행 중인 전쟁
                {wars.length > 0 && (
                  <Badge variant="destructive">{wars.length}</Badge>
                )}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {wars.length === 0 ? (
                <EmptyState icon={Swords} title="현재 전쟁이 없습니다." />
              ) : (
                <div className="space-y-2">
                  {wars.map((w) => {
                    const src = getNation(nations, w.srcNationId);
                    const dest = getNation(nations, w.destNationId);
                    return (
                      <div
                        key={w.id}
                        className="rounded-lg border border-red-900/50 bg-red-950/20 p-3 space-y-2"
                      >
                        <div className="flex items-center gap-3">
                          <NationBadge name={src?.name} color={src?.color} />
                          <Swords className="size-4 text-destructive shrink-0" />
                          <NationBadge name={dest?.name} color={dest?.color} />
                          <Badge variant="destructive" className="ml-auto">
                            {w.term}턴 경과
                          </Badge>
                        </div>
                        <div className="text-xs text-muted-foreground flex gap-4">
                          <span>
                            {src?.name ?? "?"}: 도시{" "}
                            {
                              cities.filter((c) => c.nationId === w.srcNationId)
                                .length
                            }
                            개
                          </span>
                          <span>
                            {dest?.name ?? "?"}: 도시{" "}
                            {
                              cities.filter(
                                (c) => c.nationId === w.destNationId,
                              ).length
                            }
                            개
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Ceasefire Negotiations */}
          {ceasefires.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  종전/휴전 현황
                  <Badge variant="secondary">{ceasefires.length}</Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {ceasefires.map((d) => {
                  const src = getNation(nations, d.srcNationId);
                  const dest = getNation(nations, d.destNationId);
                  return (
                    <div
                      key={d.id}
                      className="flex items-center gap-3 rounded-lg border p-3"
                    >
                      <NationBadge name={src?.name} color={src?.color} />
                      <Badge variant="outline">
                        {d.stateCode === "ceasefire" ? "휴전" : "종전제의"}
                      </Badge>
                      <NationBadge name={dest?.name} color={dest?.color} />
                      <span className="ml-auto text-xs text-muted-foreground">
                        {d.term}턴
                      </span>
                    </div>
                  );
                })}
              </CardContent>
            </Card>
          )}

          {/* War Declaration History */}
          {warHistory.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>선전포고 기록</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-1">
                  {warHistory.map((w) => {
                    const src = getNation(nations, w.srcNationId);
                    const dest = getNation(nations, w.destNationId);
                    return (
                      <div
                        key={w.id}
                        className="flex items-center gap-3 py-1.5 text-sm border-b border-gray-800 last:border-0"
                      >
                        <NationBadge name={src?.name} color={src?.color} />
                        <span className="text-muted-foreground">→</span>
                        <NationBadge name={dest?.name} color={dest?.color} />
                        <span className="ml-auto text-xs text-muted-foreground">
                          {w.term}턴
                        </span>
                        {w.isDead && (
                          <Badge variant="outline" className="text-gray-500">
                            종료
                          </Badge>
                        )}
                      </div>
                    );
                  })}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* Tab 2: Military Power */}
        <TabsContent value="military" className="mt-4 space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>국가별 군사력 비교</CardTitle>
            </CardHeader>
            <CardContent>
              {sortedMilitary.length === 0 ? (
                <EmptyState icon={Shield} title="군사 데이터가 없습니다." />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>국가</TableHead>
                      <TableHead
                        className="cursor-pointer select-none text-right"
                        onClick={() => handleSort("totalCrew")}
                      >
                        총 병력
                        <SortIndicator active={sortKey === "totalCrew"} />
                      </TableHead>
                      <TableHead
                        className="cursor-pointer select-none text-right"
                        onClick={() => handleSort("generalCount")}
                      >
                        장수
                        <SortIndicator active={sortKey === "generalCount"} />
                      </TableHead>
                      <TableHead
                        className="cursor-pointer select-none text-right"
                        onClick={() => handleSort("avgTrain")}
                      >
                        평균훈련
                        <SortIndicator active={sortKey === "avgTrain"} />
                      </TableHead>
                      <TableHead
                        className="cursor-pointer select-none text-right"
                        onClick={() => handleSort("avgAtmos")}
                      >
                        평균사기
                        <SortIndicator active={sortKey === "avgAtmos"} />
                      </TableHead>
                      <TableHead
                        className="cursor-pointer select-none text-right"
                        onClick={() => handleSort("totalPower")}
                      >
                        전투력
                        <SortIndicator active={sortKey === "totalPower"} />
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {sortedMilitary.map((row) => (
                      <TableRow key={row.nation.id}>
                        <TableCell>
                          <NationBadge
                            name={row.nation.name}
                            color={row.nation.color}
                          />
                        </TableCell>
                        <TableCell className="text-right">
                          <div className="flex items-center justify-end gap-2">
                            <div className="w-20 h-2 bg-gray-800 rounded-full overflow-hidden">
                              <div
                                className="h-full rounded-full"
                                style={{
                                  width: `${(row.totalCrew / maxCrew) * 100}%`,
                                  backgroundColor: row.nation.color,
                                }}
                              />
                            </div>
                            <span className="w-16 text-right tabular-nums">
                              {row.totalCrew.toLocaleString()}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {row.generalCount}명
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {row.avgTrain}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {row.avgAtmos}
                        </TableCell>
                        <TableCell className="text-right tabular-nums font-bold">
                          {row.totalPower.toLocaleString()}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Tab 3: Front Lines */}
        <TabsContent value="frontline" className="mt-4 space-y-4">
          {frontCities.length === 0 ? (
            <EmptyState icon={Shield} title="전선 도시가 없습니다." />
          ) : (
            frontCities.map((c) => {
              const cityGenerals = generalsByCity.get(c.id) ?? [];
              const ownerNation = nations.find((n) => n.id === c.nationId);
              const wallPercent =
                c.wallMax > 0 ? Math.round((c.wall / c.wallMax) * 100) : 0;
              const defPercent =
                c.defMax > 0 ? Math.round((c.def / c.defMax) * 100) : 0;
              return (
                <Card key={c.id}>
                  <CardHeader className="pb-2">
                    <CardTitle className="flex items-center gap-2 text-base">
                      {ownerNation && (
                        <NationBadge
                          name={ownerNation.name}
                          color={ownerNation.color}
                        />
                      )}
                      <span>{c.name}</span>
                      <Badge variant="secondary">Lv.{c.level}</Badge>
                      <Badge variant="destructive" className="ml-auto">
                        전선 {c.frontState}
                      </Badge>
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {/* Defense status */}
                    <div className="grid grid-cols-2 gap-3">
                      <div className="space-y-1">
                        <div className="flex justify-between text-xs">
                          <span className="text-muted-foreground">성벽</span>
                          <span>
                            {c.wall}/{c.wallMax} ({wallPercent}%)
                          </span>
                        </div>
                        <div className="h-2 bg-gray-800 rounded-full overflow-hidden">
                          <div
                            className="h-full bg-blue-500 rounded-full"
                            style={{ width: `${wallPercent}%` }}
                          />
                        </div>
                      </div>
                      <div className="space-y-1">
                        <div className="flex justify-between text-xs">
                          <span className="text-muted-foreground">방어</span>
                          <span>
                            {c.def}/{c.defMax} ({defPercent}%)
                          </span>
                        </div>
                        <div className="h-2 bg-gray-800 rounded-full overflow-hidden">
                          <div
                            className="h-full bg-green-500 rounded-full"
                            style={{ width: `${defPercent}%` }}
                          />
                        </div>
                      </div>
                    </div>

                    {/* Garrison generals */}
                    {cityGenerals.length > 0 && (
                      <div>
                        <div className="text-xs text-muted-foreground mb-1">
                          주둔 장수 ({cityGenerals.length}명)
                        </div>
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>이름</TableHead>
                              <TableHead className="text-right">병력</TableHead>
                              <TableHead className="text-right">훈련</TableHead>
                              <TableHead className="text-right">사기</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {cityGenerals.map((g) => (
                              <TableRow key={g.id}>
                                <TableCell className="py-1">
                                  <span className="text-sm">{g.name}</span>
                                </TableCell>
                                <TableCell className="text-right py-1 tabular-nums">
                                  {g.crew.toLocaleString()}
                                </TableCell>
                                <TableCell className="text-right py-1 tabular-nums">
                                  {g.train}
                                </TableCell>
                                <TableCell className="text-right py-1 tabular-nums">
                                  {g.atmos}
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    )}
                  </CardContent>
                </Card>
              );
            })
          )}
        </TabsContent>
        {/* Tab 4: Personal Battle Log */}
        {myGeneral && (
          <TabsContent value="personal" className="mt-4 space-y-4">
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <User className="size-4" />
                  {myGeneral.name}의 전투 기록
                  <div className="ml-auto flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">로그 스타일:</span>
                    <select
                      value={logStyle}
                      onChange={(e) => setLogStyle(e.target.value as "modern" | "legacy")}
                      className="h-6 border border-gray-600 bg-[#111] px-1 text-[10px] text-white"
                    >
                      <option value="modern">현대</option>
                      <option value="legacy">레거시</option>
                    </select>
                  </div>
                </CardTitle>
              </CardHeader>
              <CardContent>
                {personalLoading ? (
                  <div className="text-sm text-muted-foreground py-4 text-center">전투 기록 로딩 중...</div>
                ) : personalLogs.length === 0 ? (
                  <EmptyState icon={ScrollText} title="전투 기록이 없습니다." />
                ) : (
                  <div className={`max-h-96 overflow-y-auto space-y-1 ${logStyle === "legacy" ? "font-mono text-[11px] bg-black p-3 rounded border border-gray-800" : "text-sm"}`}>
                    {personalLogs.map((log) => (
                      <div
                        key={log.id}
                        className={`py-1 border-b border-gray-800 last:border-0 ${logStyle === "legacy" ? "text-green-400" : ""}`}
                      >
                        <span className="text-muted-foreground text-xs mr-2">
                          {logStyle === "legacy"
                            ? `[${log.date}]`
                            : new Date(log.date).toLocaleDateString("ko-KR", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })
                          }
                        </span>
                        {logStyle === "legacy" ? (
                          <span>{log.message.replace(/<[^>]*>/g, "")}</span>
                        ) : (
                          <span dangerouslySetInnerHTML={{ __html: formatLog(log.message) }} />
                        )}
                      </div>
                    ))}
                  </div>
                )}
                {!personalLoading && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="mt-2"
                    onClick={loadPersonalLogs}
                  >
                    새로고침
                  </Button>
                )}
              </CardContent>
            </Card>
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}
