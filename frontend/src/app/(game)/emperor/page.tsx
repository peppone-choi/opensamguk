"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { historyApi } from "@/lib/gameApi";
import { Crown } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { Message } from "@/types";

export default function EmperorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, loading, loadAll } = useGameStore();
  const [dynastyLogs, setDynastyLogs] = useState<Message[]>([]);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    historyApi
      .getWorldHistory(currentWorld.id)
      .then(({ data }) => setDynastyLogs(data.slice(0, 20)))
      .catch(() => setDynastyLogs([]));
  }, [currentWorld, loadAll]);

  const emperorNation = useMemo(
    () => nations.find((n) => n.level >= 7),
    [nations],
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
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Crown} title="황제 정보" />

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
          <CardContent>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">국가 레벨</span>
                <span>{emperorNation.level}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">기술력</span>
                <span>{emperorNation.tech}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">국력</span>
                <span>{emperorNation.power}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">금</span>
                <span className="text-yellow-400">
                  {emperorNation.gold.toLocaleString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">쌀</span>
                <span className="text-green-400">
                  {emperorNation.rice.toLocaleString()}
                </span>
              </div>
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              아직 황제를 칭한 국가가 없습니다.
            </p>
          </CardContent>
        </Card>
      )}

      {/* Nation levels overview */}
      <div className="space-y-2">
        <h2 className="font-semibold">국가 레벨 현황</h2>
        {nations.length === 0 ? (
          <p className="text-sm text-muted-foreground">국가가 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>국가</TableHead>
                <TableHead>레벨</TableHead>
                <TableHead>칭호</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...nations]
                .sort((a, b) => b.level - a.level)
                .map((n) => (
                  <TableRow key={n.id}>
                    <TableCell>
                      <NationBadge name={n.name} color={n.color} />
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{n.level}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {n.level >= 7
                        ? "황제"
                        : n.level >= 6
                          ? "왕"
                          : n.level >= 5
                            ? "공"
                            : "주자사"}
                    </TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>왕조 연표</CardTitle>
        </CardHeader>
        <CardContent>
          {dynastyLogs.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              연표 데이터가 없습니다.
            </p>
          ) : (
            <div className="space-y-2">
              {dynastyLogs.map((log) => {
                const raw = log.payload?.content;
                const text =
                  typeof raw === "string"
                    ? raw
                    : JSON.stringify(log.payload ?? {});
                return (
                  <div key={log.id} className="rounded border p-2 text-xs">
                    <p className="text-muted-foreground">{log.sentAt}</p>
                    <p>{text}</p>
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
