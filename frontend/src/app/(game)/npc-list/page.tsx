"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Bot, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
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
  | "leadership"
  | "strength"
  | "intel"
  | "politics"
  | "charm"
  | "crew";

export default function NpcListPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { generals, nations, loading, loadAll } = useGameStore();
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
  const [nationFilter, setNationFilter] = useState<string>("");

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const npcGenerals = useMemo(() => {
    let npcs = generals.filter((g) => g.npcState > 0);

    if (search) {
      npcs = npcs.filter((g) =>
        g.name.toLowerCase().includes(search.toLowerCase()),
      );
    }

    if (nationFilter) {
      npcs = npcs.filter((g) => g.nationId === Number(nationFilter));
    }

    return [...npcs].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else if (sortKey === "nation") {
        const na = nationMap.get(a.nationId)?.name ?? "";
        const nb = nationMap.get(b.nationId)?.name ?? "";
        cmp = na.localeCompare(nb);
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [generals, search, sortKey, sortDir, nationFilter, nationMap]);

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
  if (loading) return <LoadingState />;

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "이름" },
    { key: "nation", label: "소속" },
    { key: "leadership", label: "통솔" },
    { key: "strength", label: "무력" },
    { key: "intel", label: "지력" },
    { key: "politics", label: "정치" },
    { key: "charm", label: "매력" },
    { key: "crew", label: "병사" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Bot} title="NPC 일람" />

      <div className="flex gap-2 flex-wrap items-center">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            type="text"
            placeholder="장수 검색..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
          />
        </div>
        <select
          value={nationFilter}
          onChange={(e) => setNationFilter(e.target.value)}
          className="h-9 min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-sm"
        >
          <option value="">전체 국가</option>
          {nations.map((n) => (
            <option key={n.id} value={n.id}>
              {n.name}
            </option>
          ))}
        </select>
        <span className="text-xs text-muted-foreground">
          {npcGenerals.length}명
        </span>
      </div>

      {npcGenerals.length === 0 ? (
        <EmptyState icon={Bot} title="NPC 장수가 없습니다." />
      ) : (
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
            {npcGenerals.map((g) => {
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
                      <Badge variant="secondary" className="text-xs">
                        NPC
                      </Badge>
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
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
