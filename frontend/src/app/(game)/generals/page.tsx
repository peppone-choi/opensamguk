"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Users, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

type SortKey =
  | "name"
  | "nation"
  | "city"
  | "leadership"
  | "strength"
  | "intel"
  | "politics"
  | "charm"
  | "crew"
  | "experience";

export default function GeneralsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { generals, nations, loading, loadAll } = useGameStore();
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const sorted = useMemo(() => {
    const filtered = search
      ? generals.filter((g) =>
          g.name.toLowerCase().includes(search.toLowerCase()),
        )
      : generals;

    return [...filtered].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else if (sortKey === "nation") {
        const na = nationMap.get(a.nationId)?.name ?? "";
        const nb = nationMap.get(b.nationId)?.name ?? "";
        cmp = na.localeCompare(nb);
      } else if (sortKey === "city") {
        cmp = a.cityId - b.cityId;
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [generals, search, sortKey, sortDir, nationMap]);

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
    { key: "nation", label: "소속" },
    { key: "leadership", label: "통솔" },
    { key: "strength", label: "무력" },
    { key: "intel", label: "지력" },
    { key: "politics", label: "정치" },
    { key: "charm", label: "매력" },
    { key: "crew", label: "병사" },
    { key: "experience", label: "레벨" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Users} title="장수 일람" />
      <div className="relative w-64">
        <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
        <Input
          type="text"
          placeholder="장수 검색..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-8"
        />
      </div>
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
            <TableHead>NPC</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {sorted.map((g) => {
            const nation = nationMap.get(g.nationId);
            return (
              <TableRow
                key={g.id}
                style={
                  nation?.color
                    ? { borderLeft: `3px solid ${nation.color}` }
                    : undefined
                }
              >
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    <GeneralPortrait
                      picture={g.picture}
                      name={g.name}
                      size="sm"
                    />
                    {g.name}
                  </div>
                </TableCell>
                <TableCell>
                  <NationBadge name={nation?.name} color={nation?.color} />
                </TableCell>
                <TableCell>{g.leadership}</TableCell>
                <TableCell>{g.strength}</TableCell>
                <TableCell>{g.intel}</TableCell>
                <TableCell>{g.politics}</TableCell>
                <TableCell>{g.charm}</TableCell>
                <TableCell>{g.crew.toLocaleString()}</TableCell>
                <TableCell>{g.experience}</TableCell>
                <TableCell className="text-center">
                  {g.npcState > 0 && <Badge variant="secondary">NPC</Badge>}
                </TableCell>
              </TableRow>
            );
          })}
          {sorted.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={10}
                className="text-center text-muted-foreground"
              >
                장수가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
