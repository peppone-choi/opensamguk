"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Truck } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const SUPPLY_LABELS: Record<
  number,
  { text: string; variant: "default" | "secondary" | "destructive" }
> = {
  0: { text: "정상", variant: "default" },
  1: { text: "부족", variant: "secondary" },
  2: { text: "고립", variant: "destructive" },
};

const FRONT_LABELS: Record<
  number,
  { text: string; variant: "outline" | "secondary" | "destructive" }
> = {
  0: { text: "후방", variant: "outline" },
  1: { text: "전선", variant: "secondary" },
  2: { text: "최전선", variant: "destructive" },
};

export default function TrafficPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { cities, nations, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
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
    <div className="p-4 space-y-4">
      <PageHeader icon={Truck} title="교통 / 보급 현황" />

      {cities.length === 0 ? (
        <EmptyState icon={Truck} title="도시 정보가 없습니다." />
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>도시</TableHead>
              <TableHead>소속</TableHead>
              <TableHead>보급 상태</TableHead>
              <TableHead>전선</TableHead>
              <TableHead>교역율</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {[...cities]
              .sort(
                (a, b) =>
                  b.supplyState - a.supplyState || a.name.localeCompare(b.name),
              )
              .map((c) => {
                const nation = nationMap.get(c.nationId);
                const supply = SUPPLY_LABELS[c.supplyState] ?? {
                  text: `${c.supplyState}`,
                  variant: "outline" as const,
                };
                const front = FRONT_LABELS[c.frontState] ?? {
                  text: `${c.frontState}`,
                  variant: "outline" as const,
                };
                return (
                  <TableRow key={c.id}>
                    <TableCell className="font-medium">{c.name}</TableCell>
                    <TableCell>
                      {nation ? (
                        <NationBadge name={nation.name} color={nation.color} />
                      ) : (
                        <NationBadge />
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant={supply.variant}>{supply.text}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={front.variant}>{front.text}</Badge>
                    </TableCell>
                    <TableCell>{c.trade}%</TableCell>
                  </TableRow>
                );
              })}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
