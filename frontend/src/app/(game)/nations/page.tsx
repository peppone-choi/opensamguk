"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Globe } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
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

type SortKey =
  | "name"
  | "capital"
  | "level"
  | "gold"
  | "rice"
  | "tech"
  | "power"
  | "typeCode"
  | "generalCount";

export default function NationsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();
  const [sortKey, setSortKey] = useState<SortKey>("power");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const generalCountMap = useMemo(() => {
    const map = new Map<number, number>();
    for (const g of generals) {
      if (g.nationId) map.set(g.nationId, (map.get(g.nationId) ?? 0) + 1);
    }
    return map;
  }, [generals]);

  const sorted = useMemo(() => {
    return [...nations].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else if (sortKey === "capital") {
        const ca = cityMap.get(a.capitalCityId)?.name ?? "";
        const cb = cityMap.get(b.capitalCityId)?.name ?? "";
        cmp = ca.localeCompare(cb);
      } else if (sortKey === "typeCode") {
        cmp = a.typeCode.localeCompare(b.typeCode);
      } else if (sortKey === "generalCount") {
        cmp =
          (generalCountMap.get(a.id) ?? 0) - (generalCountMap.get(b.id) ?? 0);
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [nations, sortKey, sortDir, cityMap, generalCountMap]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

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

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "이름" },
    { key: "capital", label: "수도" },
    { key: "level", label: "레벨" },
    { key: "gold", label: "금" },
    { key: "rice", label: "쌀" },
    { key: "tech", label: "기술" },
    { key: "power", label: "국력" },
    { key: "typeCode", label: "유형" },
    { key: "generalCount", label: "장수수" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Globe} title="국가 일람" />
      <Table>
        <TableHeader>
          <TableRow>
            {columns.map((col) => (
              <TableHead
                key={col.key}
                className="cursor-pointer hover:text-foreground"
                onClick={() => toggleSort(col.key)}
              >
                {col.label}
                {arrow(col.key)}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {sorted.map((n) => {
            const capital = cityMap.get(n.capitalCityId);
            return (
              <TableRow key={n.id}>
                <TableCell>
                  <NationBadge name={n.name} color={n.color} />
                </TableCell>
                <TableCell>{capital?.name ?? "-"}</TableCell>
                <TableCell>
                  <Badge variant="secondary">
                    {LEVEL_LABELS[n.level] ?? n.level}
                  </Badge>
                </TableCell>
                <TableCell>{n.gold.toLocaleString()}</TableCell>
                <TableCell>{n.rice.toLocaleString()}</TableCell>
                <TableCell>{n.tech}</TableCell>
                <TableCell>{n.power.toLocaleString()}</TableCell>
                <TableCell>{n.typeCode}</TableCell>
                <TableCell>{generalCountMap.get(n.id) ?? 0}</TableCell>
              </TableRow>
            );
          })}
          {sorted.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={9}
                className="text-center text-muted-foreground"
              >
                국가가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
