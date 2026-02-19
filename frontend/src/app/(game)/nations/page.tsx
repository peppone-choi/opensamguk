"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Globe } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatOfficerLevelText } from "@/lib/game-utils";

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
  | "generalCount"
  | "cityCount";

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

  const cityCountMap = useMemo(() => {
    const map = new Map<number, number>();
    for (const c of cities) {
      if (c.nationId) map.set(c.nationId, (map.get(c.nationId) ?? 0) + 1);
    }
    return map;
  }, [cities]);

  // Chief (officerLevel 12) and advisor (officerLevel 11) per nation
  const chiefMap = useMemo(() => {
    const map = new Map<
      number,
      { chief?: { name: string }; advisor?: { name: string } }
    >();
    for (const g of generals) {
      if (!g.nationId) continue;
      const entry = map.get(g.nationId) ?? {};
      if (g.officerLevel === 12) entry.chief = { name: g.name };
      else if (g.officerLevel === 11 && !entry.advisor)
        entry.advisor = { name: g.name };
      if (entry.chief || entry.advisor) map.set(g.nationId, entry);
    }
    return map;
  }, [generals]);

  const maxPower = Math.max(1, ...nations.map((n) => n.power));

  const sorted = useMemo(() => {
    return [...nations].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else if (sortKey === "capital") {
        const ca = cityMap.get(a.capitalCityId ?? 0)?.name ?? "";
        const cb = cityMap.get(b.capitalCityId ?? 0)?.name ?? "";
        cmp = ca.localeCompare(cb);
      } else if (sortKey === "typeCode") {
        cmp = a.typeCode.localeCompare(b.typeCode);
      } else if (sortKey === "generalCount") {
        cmp =
          (generalCountMap.get(a.id) ?? 0) - (generalCountMap.get(b.id) ?? 0);
      } else if (sortKey === "cityCount") {
        cmp = (cityCountMap.get(a.id) ?? 0) - (cityCountMap.get(b.id) ?? 0);
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [nations, sortKey, sortDir, cityMap, generalCountMap, cityCountMap]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " \u25B2" : " \u25BC") : "";

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
    { key: "name", label: "국가" },
    { key: "capital", label: "수도" },
    { key: "level", label: "작위" },
    { key: "power", label: "국력" },
    { key: "generalCount", label: "장수" },
    { key: "cityCount", label: "속령" },
    { key: "gold", label: "금" },
    { key: "rice", label: "쌀" },
    { key: "tech", label: "기술" },
    { key: "typeCode", label: "성향" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Globe} title="세력일람" />

      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((col) => (
                <TableHead
                  key={col.key}
                  className="cursor-pointer hover:text-foreground whitespace-nowrap"
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
              const capital = cityMap.get(n.capitalCityId ?? 0);
              const chiefs = chiefMap.get(n.id);
              const genCount = generalCountMap.get(n.id) ?? 0;
              const cityCnt = cityCountMap.get(n.id) ?? 0;
              const powerPct = (n.power / maxPower) * 100;

              return (
                <TableRow
                  key={n.id}
                  style={
                    n.color ? { borderLeft: `3px solid ${n.color}` } : undefined
                  }
                >
                  {/* Nation name + chief */}
                  <TableCell>
                    <div>
                      <NationBadge name={n.name} color={n.color} />
                      {chiefs && (
                        <div className="text-[10px] text-muted-foreground mt-0.5 pl-1">
                          {chiefs.chief && (
                            <span>
                              {formatOfficerLevelText(12, n.level)}:{" "}
                              {chiefs.chief.name}
                            </span>
                          )}
                          {chiefs.chief && chiefs.advisor && " / "}
                          {chiefs.advisor && (
                            <span>
                              {formatOfficerLevelText(11, n.level)}:{" "}
                              {chiefs.advisor.name}
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  </TableCell>

                  {/* Capital */}
                  <TableCell>{capital?.name ?? "-"}</TableCell>

                  {/* Level */}
                  <TableCell>
                    <Badge variant="secondary">
                      {LEVEL_LABELS[n.level] ?? n.level}
                    </Badge>
                  </TableCell>

                  {/* Power with bar */}
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <span className="tabular-nums text-sm min-w-[4rem] text-right">
                        {n.power.toLocaleString()}
                      </span>
                      <Progress value={powerPct} className="h-2 w-20" />
                    </div>
                  </TableCell>

                  {/* Generals */}
                  <TableCell>{genCount}</TableCell>

                  {/* Cities */}
                  <TableCell>{cityCnt}</TableCell>

                  {/* Gold */}
                  <TableCell>{n.gold.toLocaleString()}</TableCell>

                  {/* Rice */}
                  <TableCell>{n.rice.toLocaleString()}</TableCell>

                  {/* Tech */}
                  <TableCell>{n.tech}</TableCell>

                  {/* Type */}
                  <TableCell>{n.typeCode}</TableCell>
                </TableRow>
              );
            })}
            {sorted.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={columns.length}
                  className="text-center text-muted-foreground"
                >
                  국가가 없습니다.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
