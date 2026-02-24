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
import { CREW_TYPE_NAMES } from "@/lib/game-utils";
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
  | "totalStats"
  | "leadership"
  | "strength"
  | "intel"
  | "crew"
  | "experience"
  | "dedication";

const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: "name", label: "이름" },
  { key: "nation", label: "국가" },
  { key: "city", label: "도시" },
  { key: "totalStats", label: "종능" },
  { key: "leadership", label: "통솔" },
  { key: "strength", label: "무력" },
  { key: "intel", label: "지력" },
  { key: "crew", label: "병력" },
  { key: "experience", label: "명성" },
  { key: "dedication", label: "계급" },
];

export default function NpcListPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { generals, nations, cities, loading, loadAll } = useGameStore();
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

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  // NPC generals: npcState === 1 (possessed NPCs) plus pool generals (npcState === 0 from select_pool — included if available)
  const npcGenerals = useMemo(() => {
    let npcs = generals.filter((g) => g.npcState >= 1);

    if (search) {
      const q = search.toLowerCase();
      npcs = npcs.filter(
        (g) =>
          g.name.toLowerCase().includes(q) ||
          (g.ownerName ?? "").toLowerCase().includes(q),
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
      } else if (sortKey === "city") {
        const ca = cityMap.get(a.cityId)?.name ?? "";
        const cb = cityMap.get(b.cityId)?.name ?? "";
        cmp = ca.localeCompare(cb);
      } else if (sortKey === "crew") {
        cmp = a.crew - b.crew;
      } else if (sortKey === "totalStats") {
        const sa = a.leadership + a.strength + a.intel;
        const sb = b.leadership + b.strength + b.intel;
        cmp = sa - sb;
      } else if (sortKey === "experience") {
        cmp = a.experience - b.experience;
      } else if (sortKey === "dedication") {
        cmp = a.dedication - b.dedication;
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      // name and nation default ascending, numeric defaults descending
      const isAscDefault = sortKey === "name" || sortKey === "nation" || sortKey === "city";
      if (isAscDefault) {
        return sortDir === "asc" ? cmp : -cmp;
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [generals, search, sortKey, sortDir, nationFilter, nationMap]);

  const handleSortChange = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      // Numeric fields default to descending, text fields to ascending
      setSortDir(
        key === "name" || key === "nation" || key === "city" ? "asc" : "desc",
      );
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Bot} title="빙의일람" />

      <div className="flex gap-2 flex-wrap items-center">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            type="text"
            placeholder="장수/악령 검색..."
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

        {/* Sort dropdown matching legacy */}
        <select
          value={sortKey}
          onChange={(e) => {
            const key = e.target.value as SortKey;
            setSortKey(key);
            setSortDir(key === "name" || key === "nation" ? "asc" : "desc");
          }}
          className="h-9 min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-sm"
        >
          {SORT_OPTIONS.map((o) => (
            <option key={o.key} value={o.key}>
              {o.label}
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
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("name")}
                >
                  희생된 장수{arrow("name")}
                </TableHead>
                <TableHead>악령 이름</TableHead>
                <TableHead>Lv</TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("nation")}
                >
                  국가{arrow("nation")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("city")}
                >
                  도시{arrow("city")}
                </TableHead>
                <TableHead>성격</TableHead>
                <TableHead>특기</TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("totalStats")}
                >
                  종능{arrow("totalStats")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("leadership")}
                >
                  통솔{arrow("leadership")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("strength")}
                >
                  무력{arrow("strength")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("intel")}
                >
                  지력{arrow("intel")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("crew")}
                >
                  병력{arrow("crew")}
                </TableHead>
                <TableHead>병종</TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("experience")}
                >
                  명성{arrow("experience")}
                </TableHead>
                <TableHead
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => handleSortChange("dedication")}
                >
                  계급{arrow("dedication")}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {npcGenerals.map((g) => {
                const nation = nationMap.get(g.nationId);
                const totalStats = g.leadership + g.strength + g.intel;
                const specialDisplay =
                  g.specialCode && g.specialCode !== "None"
                    ? g.specialCode
                    : "-";
                const special2Display =
                  g.special2Code && g.special2Code !== "None"
                    ? g.special2Code
                    : "";
                const specialFull = special2Display
                  ? `${specialDisplay} / ${special2Display}`
                  : specialDisplay;

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
                        <span
                          style={
                            g.npcState >= 5
                              ? { color: "#69f" }
                              : g.npcState >= 2
                                ? { color: "#c93" }
                                : undefined
                          }
                        >
                          {g.name}
                        </span>
                        {g.npcState === 0 && (
                          <Badge variant="outline" className="text-[10px]">
                            풀
                          </Badge>
                        )}
                        {g.npcState >= 5 && (
                          <Badge className="text-[10px] bg-indigo-600/60 text-indigo-200">
                            악령
                          </Badge>
                        )}
                        {g.npcState >= 2 && g.npcState < 5 && (
                          <Badge className="text-[10px] bg-amber-700/60 text-amber-200">
                            희생
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="text-sm">
                      <span
                        style={
                          g.npcState >= 5
                            ? { color: "#69f", fontWeight: "bold" }
                            : undefined
                        }
                      >
                        {g.ownerName || "-"}
                      </span>
                    </TableCell>
                    <TableCell className="text-sm">
                      Lv {g.expLevel}
                    </TableCell>
                    <TableCell>
                      <NationBadge name={nation?.name} color={nation?.color} />
                    </TableCell>
                    <TableCell className="text-xs">
                      {cityMap.get(g.cityId)?.name ?? "-"}
                    </TableCell>
                    <TableCell className="text-xs">
                      {g.personalCode ?? "-"}
                    </TableCell>
                    <TableCell className="text-xs whitespace-nowrap">
                      {specialFull}
                    </TableCell>
                    <TableCell className="font-medium tabular-nums">{totalStats}</TableCell>
                    <TableCell>{g.leadership}</TableCell>
                    <TableCell>{g.strength}</TableCell>
                    <TableCell>{g.intel}</TableCell>
                    <TableCell className="tabular-nums">{g.crew.toLocaleString()}</TableCell>
                    <TableCell className="text-xs">{CREW_TYPE_NAMES[g.crewType] ?? "-"}</TableCell>
                    <TableCell>{g.experience.toLocaleString()}</TableCell>
                    <TableCell>{g.dedication.toLocaleString()}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
