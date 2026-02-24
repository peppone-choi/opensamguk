"use client";

import { useEffect, useMemo, useState } from "react";
import { BarChart3, RefreshCw, TrendingUp, Users, MapPin, Coins } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { adminApi } from "@/lib/gameApi";
import type { NationStatistic, Diplomacy } from "@/types";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

type SortKey = keyof Omit<NationStatistic, "color">;

const stateLabel = (s: string) => {
  switch (s) {
    case "ally": return "동맹";
    case "nowar": return "불가침";
    case "war": return "전쟁";
    case "ceasefire": return "휴전";
    case "neutral": return "중립";
    default: return s;
  }
};

const stateColor = (s: string) => {
  switch (s) {
    case "ally": return "text-blue-600 dark:text-blue-400";
    case "nowar": return "text-green-600 dark:text-green-400";
    case "war": return "text-red-600 dark:text-red-400";
    case "ceasefire": return "text-yellow-600 dark:text-yellow-400";
    default: return "text-muted-foreground";
  }
};

export default function AdminStatisticsPage() {
  const [stats, setStats] = useState<NationStatistic[]>([]);
  const [diplomacy, setDiplomacy] = useState<Diplomacy[]>([]);
  const [loading, setLoading] = useState(true);
  const [sortKey, setSortKey] = useState<SortKey>("power");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  const load = () => {
    setLoading(true);
    Promise.all([adminApi.getStatistics(), adminApi.getDiplomacy()])
      .then(([statRes, dipRes]) => {
        setStats(statRes.data);
        setDiplomacy(dipRes.data);
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  };

  useEffect(() => {
    load();
  }, []);

  const sorted = useMemo(() => {
    return [...stats].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [stats, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

  // Aggregate stats
  const totals = useMemo(() => {
    return {
      nations: stats.length,
      generals: stats.reduce((s, n) => s + n.genCount, 0),
      cities: stats.reduce((s, n) => s + n.cityCount, 0),
      totalCrew: stats.reduce((s, n) => s + n.totalCrew, 0),
      totalPop: stats.reduce((s, n) => s + n.totalPop, 0),
      totalGold: stats.reduce((s, n) => s + n.gold, 0),
      totalRice: stats.reduce((s, n) => s + n.rice, 0),
    };
  }, [stats]);

  // Power bar max for relative comparison
  const maxPower = useMemo(
    () => Math.max(1, ...stats.map((s) => s.power)),
    [stats]
  );
  const maxCrew = useMemo(
    () => Math.max(1, ...stats.map((s) => s.totalCrew)),
    [stats]
  );

  // Build diplomacy grid
  const nations = useMemo(
    () => [...stats].sort((a, b) => b.power - a.power),
    [stats]
  );

  const diplomacyMap = useMemo(() => {
    const map = new Map<string, Diplomacy>();
    for (const d of diplomacy) {
      map.set(`${d.srcNationId}-${d.destNationId}`, d);
      map.set(`${d.destNationId}-${d.srcNationId}`, d);
    }
    return map;
  }, [diplomacy]);

  if (loading) return <LoadingState />;

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "국가" },
    { key: "level", label: "레벨" },
    { key: "gold", label: "금" },
    { key: "rice", label: "쌀" },
    { key: "tech", label: "기술" },
    { key: "power", label: "국력" },
    { key: "genCount", label: "장수" },
    { key: "cityCount", label: "도시" },
    { key: "totalCrew", label: "총병력" },
    { key: "totalPop", label: "총인구" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <PageHeader icon={BarChart3} title="국가 통계" />
        <Button variant="outline" size="sm" onClick={load}>
          <RefreshCw className="size-4 mr-1" />
          새로고침
        </Button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center gap-2 text-muted-foreground text-xs mb-1">
              <TrendingUp className="size-3" />
              국가 수
            </div>
            <div className="text-2xl font-bold">{totals.nations}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center gap-2 text-muted-foreground text-xs mb-1">
              <Users className="size-3" />
              총 장수
            </div>
            <div className="text-2xl font-bold">{totals.generals}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center gap-2 text-muted-foreground text-xs mb-1">
              <MapPin className="size-3" />
              총 도시
            </div>
            <div className="text-2xl font-bold">{totals.cities}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center gap-2 text-muted-foreground text-xs mb-1">
              <Coins className="size-3" />
              총 병력
            </div>
            <div className="text-2xl font-bold">
              {totals.totalCrew.toLocaleString()}
            </div>
          </CardContent>
        </Card>
      </div>

      <Tabs defaultValue="table">
        <TabsList>
          <TabsTrigger value="table">국가 지표</TabsTrigger>
          <TabsTrigger value="power">국력 비교</TabsTrigger>
          <TabsTrigger value="diplomacy">외교 관계</TabsTrigger>
        </TabsList>

        {/* Nation statistics table */}
        <TabsContent value="table" className="mt-4">
          <Card>
            <CardContent className="p-0">
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      {columns.map((col) => (
                        <TableHead
                          key={col.key}
                          className="cursor-pointer hover:text-foreground select-none"
                          onClick={() => toggleSort(col.key)}
                        >
                          {col.label}
                          {arrow(col.key)}
                        </TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {sorted.map((s) => (
                      <TableRow
                        key={s.nationId}
                        style={{ borderLeft: `3px solid ${s.color}` }}
                      >
                        <TableCell className="font-medium">
                          <div className="flex items-center gap-1.5">
                            <div
                              className="size-2.5 rounded-full shrink-0"
                              style={{ backgroundColor: s.color }}
                            />
                            {s.name}
                          </div>
                        </TableCell>
                        <TableCell>{s.level}</TableCell>
                        <TableCell>{s.gold.toLocaleString()}</TableCell>
                        <TableCell>{s.rice.toLocaleString()}</TableCell>
                        <TableCell>{s.tech.toFixed(1)}</TableCell>
                        <TableCell className="font-semibold">
                          {s.power.toLocaleString()}
                        </TableCell>
                        <TableCell>{s.genCount}</TableCell>
                        <TableCell>{s.cityCount}</TableCell>
                        <TableCell>{s.totalCrew.toLocaleString()}</TableCell>
                        <TableCell>{s.totalPop.toLocaleString()}</TableCell>
                      </TableRow>
                    ))}
                    {sorted.length === 0 && (
                      <TableRow>
                        <TableCell
                          colSpan={10}
                          className="text-center text-muted-foreground"
                        >
                          국가가 없습니다.
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Power comparison chart (CSS bars) */}
        <TabsContent value="power" className="mt-4">
          <div className="space-y-4">
            {/* Power ranking */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">국력 순위</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {nations.map((n, idx) => (
                  <div key={n.nationId} className="space-y-1">
                    <div className="flex items-center justify-between text-sm">
                      <div className="flex items-center gap-2">
                        <span className="text-muted-foreground w-5 text-right">
                          {idx + 1}
                        </span>
                        <div
                          className="size-3 rounded-full"
                          style={{ backgroundColor: n.color }}
                        />
                        <span className="font-medium">{n.name}</span>
                      </div>
                      <span className="font-mono text-xs">
                        {n.power.toLocaleString()}
                      </span>
                    </div>
                    <div className="h-4 bg-muted rounded-full overflow-hidden ml-7">
                      <div
                        className="h-full rounded-full transition-all duration-500"
                        style={{
                          width: `${(n.power / maxPower) * 100}%`,
                          backgroundColor: n.color,
                          opacity: 0.8,
                        }}
                      />
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>

            {/* Military strength */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">병력 비교</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {[...nations]
                  .sort((a, b) => b.totalCrew - a.totalCrew)
                  .map((n, idx) => (
                    <div key={n.nationId} className="space-y-1">
                      <div className="flex items-center justify-between text-sm">
                        <div className="flex items-center gap-2">
                          <span className="text-muted-foreground w-5 text-right">
                            {idx + 1}
                          </span>
                          <div
                            className="size-3 rounded-full"
                            style={{ backgroundColor: n.color }}
                          />
                          <span className="font-medium">{n.name}</span>
                          <span className="text-xs text-muted-foreground">
                            (장수 {n.genCount}명)
                          </span>
                        </div>
                        <span className="font-mono text-xs">
                          {n.totalCrew.toLocaleString()}
                        </span>
                      </div>
                      <div className="h-4 bg-muted rounded-full overflow-hidden ml-7">
                        <div
                          className="h-full rounded-full transition-all duration-500"
                          style={{
                            width: `${(n.totalCrew / maxCrew) * 100}%`,
                            backgroundColor: n.color,
                            opacity: 0.8,
                          }}
                        />
                      </div>
                    </div>
                  ))}
              </CardContent>
            </Card>

            {/* Resource comparison */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">자원 비교</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>국가</TableHead>
                        <TableHead className="text-right">금</TableHead>
                        <TableHead className="text-right">쌀</TableHead>
                        <TableHead className="text-right">기술</TableHead>
                        <TableHead className="text-right">도시</TableHead>
                        <TableHead className="text-right">인구</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {nations.map((n) => (
                        <TableRow key={n.nationId}>
                          <TableCell>
                            <div className="flex items-center gap-1.5">
                              <div
                                className="size-2.5 rounded-full"
                                style={{ backgroundColor: n.color }}
                              />
                              <span className="font-medium">{n.name}</span>
                            </div>
                          </TableCell>
                          <TableCell className="text-right">
                            {n.gold.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-right">
                            {n.rice.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-right">
                            {n.tech.toFixed(1)}
                          </TableCell>
                          <TableCell className="text-right">
                            {n.cityCount}
                          </TableCell>
                          <TableCell className="text-right">
                            {n.totalPop.toLocaleString()}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* Diplomacy relationship grid */}
        <TabsContent value="diplomacy" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">외교 관계 테이블</CardTitle>
            </CardHeader>
            <CardContent>
              {nations.length === 0 ? (
                <p className="text-center text-muted-foreground py-4">
                  국가가 없습니다.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="border-collapse text-xs w-full">
                    <thead>
                      <tr>
                        <th className="p-2 border border-border bg-muted" />
                        {nations.map((n) => (
                          <th
                            key={n.nationId}
                            className="p-2 border border-border bg-muted text-center min-w-[56px]"
                          >
                            <div
                              className="size-2 rounded-full mx-auto mb-1"
                              style={{ backgroundColor: n.color }}
                            />
                            <span className="truncate">{n.name}</span>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {nations.map((row) => (
                        <tr key={row.nationId}>
                          <td className="p-2 border border-border bg-muted font-medium whitespace-nowrap">
                            <div className="flex items-center gap-1">
                              <div
                                className="size-2 rounded-full shrink-0"
                                style={{ backgroundColor: row.color }}
                              />
                              {row.name}
                            </div>
                          </td>
                          {nations.map((col) => {
                            if (row.nationId === col.nationId) {
                              return (
                                <td
                                  key={col.nationId}
                                  className="p-2 border border-border bg-muted/50 text-center"
                                >
                                  —
                                </td>
                              );
                            }
                            const rel = diplomacyMap.get(
                              `${row.nationId}-${col.nationId}`
                            );
                            const state = rel?.stateCode ?? "neutral";
                            return (
                              <td
                                key={col.nationId}
                                className={cn(
                                  "p-1.5 border border-border text-center font-medium",
                                  stateColor(state)
                                )}
                              >
                                {stateLabel(state)}
                                {rel && rel.term > 0 && (
                                  <div className="text-[9px] text-muted-foreground">
                                    {rel.term}월
                                  </div>
                                )}
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
