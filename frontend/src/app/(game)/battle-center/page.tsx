"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { historyApi } from "@/lib/gameApi";
import type { General, Message } from "@/types";
import { Swords, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

interface GeneralBattleStats {
  general: General;
  warnum: number;
  killnum: number;
  deathnum: number;
  killcrew: number;
  deathcrew: number;
  winRate: number;
}

export default function BattleCenterPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { generals, loading, loadAll } = useGameStore();

  const [searchQuery, setSearchQuery] = useState("");
  const [battleLogs, setBattleLogs] = useState<Message[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  useEffect(() => {
    if (!currentWorld) return;
    setLogsLoading(true);
    historyApi
      .getWorldRecords(currentWorld.id)
      .then(({ data }) => {
        // Filter for battle-related records
        const battleRecords = data.filter(
          (m) =>
            m.messageType === "battle" ||
            m.messageType === "war" ||
            (m.payload.message as string | undefined)?.includes("전투") ||
            (m.payload.message as string | undefined)?.includes("출병") ||
            (m.payload.message as string | undefined)?.includes("공격"),
        );
        setBattleLogs(battleRecords.slice(0, 30));
      })
      .catch(() => {})
      .finally(() => setLogsLoading(false));
  }, [currentWorld]);

  // Build battle stats from GeneralFrontInfo fields
  const battleStats = useMemo(() => {
    const stats: GeneralBattleStats[] = [];
    for (const g of generals) {
      if (g.nationId === 0) continue;
      const meta = g.meta as Record<string, number | undefined>;
      const warnum = meta.warnum ?? 0;
      const killnum = meta.killnum ?? 0;
      const deathnum = meta.deathnum ?? 0;
      const killcrew = meta.killcrew ?? 0;
      const deathcrew = meta.deathcrew ?? 0;
      if (warnum === 0 && killcrew === 0) continue;
      stats.push({
        general: g,
        warnum,
        killnum,
        deathnum,
        killcrew,
        deathcrew,
        winRate: warnum > 0 ? Math.round((killnum / warnum) * 100) : 0,
      });
    }
    return stats.sort((a, b) => b.killcrew - a.killcrew);
  }, [generals]);

  // Filter by search
  const filtered = useMemo(() => {
    if (!searchQuery.trim()) return battleStats;
    const q = searchQuery.toLowerCase();
    return battleStats.filter((s) => s.general.name.toLowerCase().includes(q));
  }, [battleStats, searchQuery]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4 max-w-4xl mx-auto">
      <PageHeader
        icon={Swords}
        title="전투 기록"
        description="장수별 전투 통계 및 최근 전투 기록"
      />

      {/* Search */}
      <div className="flex items-center gap-2">
        <Search className="size-4 text-muted-foreground" />
        <Input
          placeholder="장수 이름으로 검색..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="max-w-xs h-8 text-sm"
        />
      </div>

      {/* Battle Stats Table */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            장수 전투 통계
            {filtered.length > 0 && (
              <Badge variant="secondary">{filtered.length}명</Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {filtered.length === 0 ? (
            <EmptyState icon={Swords} title="전투 기록이 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>장수</TableHead>
                  <TableHead className="text-right">전투</TableHead>
                  <TableHead className="text-right">승리</TableHead>
                  <TableHead className="text-right">패배</TableHead>
                  <TableHead className="text-right">승률</TableHead>
                  <TableHead className="text-right">살상</TableHead>
                  <TableHead className="text-right">피해</TableHead>
                  <TableHead className="text-right">킬률</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((s) => {
                  const killRate =
                    s.deathcrew > 0
                      ? Math.round((s.killcrew / s.deathcrew) * 100)
                      : s.killcrew > 0
                        ? 999
                        : 0;
                  return (
                    <TableRow key={s.general.id}>
                      <TableCell>
                        <span className="font-medium">{s.general.name}</span>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {s.warnum}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-green-400">
                        {s.killnum}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-red-400">
                        {s.deathnum}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        <Badge
                          variant={
                            s.winRate >= 60
                              ? "default"
                              : s.winRate >= 40
                                ? "secondary"
                                : "destructive"
                          }
                          className="text-[10px] px-1"
                        >
                          {s.winRate}%
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-green-400">
                        {s.killcrew.toLocaleString()}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-red-400">
                        {s.deathcrew.toLocaleString()}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {killRate}%
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Recent Battle Log */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            최근 전투 기록
            <Badge variant="outline">최근 30건</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {logsLoading ? (
            <LoadingState message="전투 기록 로딩중..." />
          ) : battleLogs.length === 0 ? (
            <EmptyState icon={Swords} title="전투 기록이 없습니다." />
          ) : (
            <div className="space-y-1">
              {battleLogs.map((log) => {
                const message =
                  (log.payload.message as string) ??
                  (log.payload.content as string) ??
                  "";
                return (
                  <div
                    key={log.id}
                    className="flex items-start gap-2 py-1.5 text-xs border-b border-gray-800 last:border-0"
                  >
                    <span className="text-muted-foreground whitespace-nowrap shrink-0">
                      {log.sentAt
                        ? new Date(log.sentAt).toLocaleDateString("ko-KR", {
                            month: "short",
                            day: "numeric",
                          })
                        : ""}
                    </span>
                    <span
                      className="text-gray-300"
                      dangerouslySetInnerHTML={{ __html: message }}
                    />
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
