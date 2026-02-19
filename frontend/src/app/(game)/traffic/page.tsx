"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Truck } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

type MovingInfo = {
  generalId: number;
  generalName: string;
  nationName?: string;
  nationColor?: string;
  fromCity: string;
  toCity: string;
  etaMs: number;
  eta: string;
  remainText: string;
  commandName: string;
};

export default function TrafficPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const cityMap = useMemo(() => new Map(cities.map((c) => [c.id, c])), [cities]);
  const nationMap = useMemo(
    () => new Map(nations.map((nation) => [nation.id, nation])),
    [nations],
  );

  const movingGenerals = useMemo<MovingInfo[]>(() => {
    return generals
      .flatMap((general) => {
        const command = extractCurrentCommand(general.lastTurn);
        const destinationId = extractDestinationCityId(general.lastTurn);
        const isMoveCommand = command.includes("이동") || command.includes("접경귀환");
        const hasDestination = destinationId != null;
        const etaTime = parseEta(general.commandEndTime);
        const hasRunningEta = etaTime != null && etaTime > now;

        if (!isMoveCommand && !hasDestination) return [];
        if (!hasRunningEta) return [];

        const fromCity = cityMap.get(general.cityId)?.name ?? `도시 #${general.cityId}`;
        const toCity = destinationId
          ? (cityMap.get(destinationId)?.name ?? `도시 #${destinationId}`)
          : "목표 도시 미확인";

        const nation = nationMap.get(general.nationId);

        return [
          {
            generalId: general.id,
            generalName: general.name,
            nationName: nation?.name,
            nationColor: nation?.color,
            fromCity,
            toCity,
            etaMs: etaTime,
            eta: new Date(etaTime).toLocaleString("ko-KR"),
            remainText: formatRemain(etaTime - now),
            commandName: command,
          },
        ];
      })
      .sort((a, b) => {
        const etaCompare = a.etaMs - b.etaMs;
        if (etaCompare !== 0) return etaCompare;
        return a.generalName.localeCompare(b.generalName);
      });
  }, [generals, cityMap, nationMap, now]);

  if (!currentWorld) {
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  }

  if (loading) {
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={Truck} title="이동 현황" />

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">이동 중인 장수</CardTitle>
        </CardHeader>
        <CardContent>
          {movingGenerals.length === 0 ? (
            <EmptyState icon={Truck} title="현재 이동 중인 장수가 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>장수</TableHead>
                  <TableHead>국가</TableHead>
                  <TableHead>출발</TableHead>
                  <TableHead>도착</TableHead>
                  <TableHead>명령</TableHead>
                  <TableHead className="text-right">ETA</TableHead>
                  <TableHead className="text-right">남은 시간</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {movingGenerals.map((row) => (
                  <TableRow key={row.generalId}>
                    <TableCell className="font-medium">{row.generalName}</TableCell>
                    <TableCell>
                      <NationBadge name={row.nationName} color={row.nationColor} />
                    </TableCell>
                    <TableCell>{row.fromCity}</TableCell>
                    <TableCell>{row.toCity}</TableCell>
                    <TableCell>{row.commandName}</TableCell>
                    <TableCell className="text-right text-xs">{row.eta}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {row.remainText}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function extractCurrentCommand(lastTurn: Record<string, unknown>): string {
  const actionCode = lastTurn.actionCode;
  if (typeof actionCode === "string" && actionCode.length > 0) {
    return actionCode;
  }
  const brief = lastTurn.brief;
  if (typeof brief === "string" && brief.length > 0) {
    return brief;
  }
  return "-";
}

function extractDestinationCityId(lastTurn: Record<string, unknown>): number | null {
  const arg = lastTurn.arg;
  if (!arg || typeof arg !== "object" || Array.isArray(arg)) return null;

  const values = arg as Record<string, unknown>;
  const candidates = [
    values.destCityId,
    values.destCityID,
    values.toCityId,
    values.cityId,
  ];

  for (const value of candidates) {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value === "string") {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) return parsed;
    }
  }

  return null;
}

function parseEta(commandEndTime: string | null): number | null {
  if (!commandEndTime) return null;
  const eta = new Date(commandEndTime).getTime();
  if (Number.isNaN(eta)) return null;
  return eta;
}

function formatRemain(ms: number): string {
  if (ms <= 0) return "도착";
  const sec = Math.floor(ms / 1000);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}시간 ${m}분`;
  if (m > 0) return `${m}분 ${s}초`;
  return `${s}초`;
}
