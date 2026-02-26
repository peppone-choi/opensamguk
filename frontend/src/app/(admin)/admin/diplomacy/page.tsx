"use client";

import { useEffect, useMemo, useState } from "react";
import {
  Handshake,
  Shield,
  Swords,
  Minus,
  RefreshCw,
  Timer,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
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
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { adminApi } from "@/lib/gameApi";
import type { Diplomacy, NationStatistic } from "@/types";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

const STATES = ["all", "ally", "nowar", "war", "ceasefire", "neutral"] as const;
type StateFilter = (typeof STATES)[number];

const stateLabel = (s: string) => {
  switch (s) {
    case "ally":
      return "동맹";
    case "nowar":
      return "불가침";
    case "war":
      return "전쟁";
    case "neutral":
      return "중립";
    case "ceasefire":
      return "휴전";
    case "all":
      return "전체";
    default:
      return s;
  }
};

const stateColor = (s: string) => {
  switch (s) {
    case "ally":
      return "bg-blue-600 text-white";
    case "nowar":
      return "bg-green-600 text-white";
    case "war":
      return "bg-red-600 text-white";
    case "ceasefire":
      return "bg-yellow-600 text-white";
    default:
      return "bg-gray-500 text-white";
  }
};

const stateVariant = (s: string) => {
  switch (s) {
    case "ally":
      return "default" as const;
    case "war":
      return "destructive" as const;
    default:
      return "secondary" as const;
  }
};

const stateIcon = (s: string) => {
  switch (s) {
    case "ally":
    case "nowar":
      return <Shield className="size-3" />;
    case "war":
      return <Swords className="size-3" />;
    default:
      return <Minus className="size-3" />;
  }
};

export default function AdminDiplomacyPage() {
  const [diplomacy, setDiplomacy] = useState<Diplomacy[]>([]);
  const [nationStats, setNationStats] = useState<NationStatistic[]>([]);
  const [stateFilter, setStateFilter] = useState<StateFilter>("all");
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    Promise.all([adminApi.getDiplomacy(), adminApi.getStatistics()])
      .then(([dipRes, statRes]) => {
        setDiplomacy(dipRes.data);
        setNationStats(statRes.data);
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  };

  useEffect(() => {
    Promise.all([adminApi.getDiplomacy(), adminApi.getStatistics()])
      .then(([dipRes, statRes]) => {
        setDiplomacy(dipRes.data);
        setNationStats(statRes.data);
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const nations = useMemo(() => {
    return [...nationStats].sort((a, b) => b.power - a.power);
  }, [nationStats]);

  const diplomacyMap = useMemo(() => {
    const map = new Map<string, Diplomacy>();
    for (const d of diplomacy) {
      map.set(`${d.srcNationId}-${d.destNationId}`, d);
      map.set(`${d.destNationId}-${d.srcNationId}`, d);
    }
    return map;
  }, [diplomacy]);

  const getRelation = (a: number, b: number): Diplomacy | undefined => {
    return diplomacyMap.get(`${a}-${b}`);
  };

  const summary = useMemo(() => {
    const acc: Record<string, number> = { all: diplomacy.length };
    let expiringSoon = 0;
    for (const d of diplomacy) {
      acc[d.stateCode] = (acc[d.stateCode] ?? 0) + 1;
      if (d.term > 0 && d.term <= 3) expiringSoon += 1;
    }
    return {
      counts: acc,
      expiringSoon,
    };
  }, [diplomacy]);

  const filteredRelations = useMemo(() => {
    const list =
      stateFilter === "all"
        ? diplomacy
        : diplomacy.filter((d) => d.stateCode === stateFilter);
    return [...list].sort((a, b) => {
      if (a.term !== b.term) return a.term - b.term;
      return a.stateCode.localeCompare(b.stateCode);
    });
  }, [diplomacy, stateFilter]);

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <PageHeader icon={Handshake} title="외교 현황" />
        <Button variant="outline" size="sm" onClick={load}>
          <RefreshCw className="size-4 mr-1" />
          새로고침
        </Button>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-2">
        {STATES.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setStateFilter(s)}
            className={cn(
              "rounded-md border px-3 py-2 text-left text-sm",
              stateFilter === s && "border-primary bg-primary/5",
            )}
          >
            <div className="text-muted-foreground text-xs">{stateLabel(s)}</div>
            <div className="font-semibold">{summary.counts[s] ?? 0}</div>
          </button>
        ))}
        <div className="rounded-md border px-3 py-2">
          <div className="text-muted-foreground text-xs flex items-center gap-1">
            <Timer className="size-3" />
            3개월 이내 만료
          </div>
          <div className="font-semibold">{summary.expiringSoon}</div>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">국가 종합 지표</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>국가</TableHead>
                  <TableHead className="text-right">장수</TableHead>
                  <TableHead className="text-right">도시</TableHead>
                  <TableHead className="text-right">병력</TableHead>
                  <TableHead className="text-right">금</TableHead>
                  <TableHead className="text-right">쌀</TableHead>
                  <TableHead className="text-right">기술</TableHead>
                  <TableHead className="text-right">국력</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {nations.map((n) => (
                  <TableRow key={n.nationId}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div
                          className="size-3 rounded-full shrink-0"
                          style={{ backgroundColor: n.color }}
                        />
                        <span className="font-medium">{n.name}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right">{n.genCount}</TableCell>
                    <TableCell className="text-right">{n.cityCount}</TableCell>
                    <TableCell className="text-right">
                      {n.totalCrew.toLocaleString()}
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
                    <TableCell className="text-right font-semibold">
                      {n.power.toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
                {nations.length === 0 && (
                  <TableRow>
                    <TableCell
                      colSpan={8}
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

      {nations.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">외교 관계 매트릭스</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="overflow-x-auto">
              <TooltipProvider>
                <table className="border-collapse text-xs">
                  <thead>
                    <tr>
                      <th className="p-2 border border-border bg-muted" />
                      {nations.map((n) => (
                        <th
                          key={n.nationId}
                          className="p-2 border border-border bg-muted text-center min-w-[60px]"
                        >
                          <div className="flex flex-col items-center gap-1">
                            <div
                              className="size-2 rounded-full"
                              style={{ backgroundColor: n.color }}
                            />
                            <span className="truncate max-w-[60px]">
                              {n.name}
                            </span>
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {nations.map((rowNation) => (
                      <tr key={rowNation.nationId}>
                        <td className="p-2 border border-border bg-muted font-medium whitespace-nowrap">
                          <div className="flex items-center gap-1">
                            <div
                              className="size-2 rounded-full shrink-0"
                              style={{ backgroundColor: rowNation.color }}
                            />
                            {rowNation.name}
                          </div>
                        </td>
                        {nations.map((colNation) => {
                          if (rowNation.nationId === colNation.nationId) {
                            return (
                              <td
                                key={colNation.nationId}
                                className="p-2 border border-border bg-muted/50 text-center"
                              >
                                <span className="text-muted-foreground">—</span>
                              </td>
                            );
                          }
                          const rel = getRelation(
                            rowNation.nationId,
                            colNation.nationId,
                          );
                          const state = rel?.stateCode ?? "neutral";
                          return (
                            <td
                              key={colNation.nationId}
                              className="p-1 border border-border text-center"
                            >
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <div
                                    className={cn(
                                      "inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium cursor-default",
                                      stateColor(state),
                                    )}
                                  >
                                    {stateIcon(state)}
                                    {stateLabel(state)}
                                    {rel && rel.term > 0 && (
                                      <span className="opacity-90">
                                        {rel.term}
                                      </span>
                                    )}
                                  </div>
                                </TooltipTrigger>
                                <TooltipContent>
                                  <p>
                                    {rowNation.name} ↔ {colNation.name}:{" "}
                                    {stateLabel(state)}
                                    {rel ? ` (${rel.term}개월)` : ""}
                                  </p>
                                </TooltipContent>
                              </Tooltip>
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TooltipProvider>
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">외교 관계 목록</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>국가 A</TableHead>
                <TableHead>국가 B</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>기간</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredRelations.map((d) => {
                const srcNation = nations.find(
                  (n) => n.nationId === d.srcNationId,
                );
                const destNation = nations.find(
                  (n) => n.nationId === d.destNationId,
                );
                return (
                  <TableRow key={d.id}>
                    <TableCell className="text-muted-foreground">
                      {d.id}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        {srcNation && (
                          <div
                            className="size-2 rounded-full"
                            style={{ backgroundColor: srcNation.color }}
                          />
                        )}
                        {srcNation?.name ?? `#${d.srcNationId}`}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        {destNation && (
                          <div
                            className="size-2 rounded-full"
                            style={{ backgroundColor: destNation.color }}
                          />
                        )}
                        {destNation?.name ?? `#${d.destNationId}`}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={stateVariant(d.stateCode)}>
                        {stateLabel(d.stateCode)}
                      </Badge>
                    </TableCell>
                    <TableCell>{d.term}개월</TableCell>
                  </TableRow>
                );
              })}
              {filteredRelations.length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className="text-center text-muted-foreground"
                  >
                    외교 관계가 없습니다.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
