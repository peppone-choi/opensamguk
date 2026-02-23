"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import { Users, Search, Columns3 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatOfficerLevelText, CREW_TYPE_NAMES } from "@/lib/game-utils";

type SortKey =
  | "name"
  | "nation"
  | "city"
  | "officerLevel"
  | "dedication"
  | "experience"
  | "leadership"
  | "strength"
  | "intel"
  | "politics"
  | "charm"
  | "gold"
  | "rice"
  | "crew"
  | "crewType"
  | "train"
  | "atmos"
  | "fame"
  | "npcState"
  | "totalStats"
  | "age"
  | "special"
  | "personal";

type NpcFilter = "all" | "user" | "npc";

const BASE_COLUMNS: { key: SortKey; label: string }[] = [
  { key: "name", label: "이름" },
  { key: "nation", label: "소속" },
  { key: "officerLevel", label: "관직" },
  { key: "leadership", label: "통솔" },
  { key: "strength", label: "무력" },
  { key: "intel", label: "지력" },
  { key: "politics", label: "정치" },
  { key: "charm", label: "매력" },
  { key: "totalStats", label: "종능" },
  { key: "crew", label: "병사" },
  { key: "experience", label: "레벨" },
];

const EXT_COLUMNS: { key: SortKey; label: string }[] = [
  { key: "special", label: "특기" },
  { key: "personal", label: "성격" },
  { key: "age", label: "연령" },
  { key: "crewType", label: "병종" },
  { key: "train", label: "훈련" },
  { key: "atmos", label: "사기" },
  { key: "gold", label: "금" },
  { key: "rice", label: "쌀" },
  { key: "dedication", label: "계급" },
];

const SORT_DROPDOWN_OPTIONS: { key: SortKey; label: string }[] = [
  { key: "experience", label: "레벨" },
  { key: "totalStats", label: "종능" },
  { key: "leadership", label: "통솔" },
  { key: "strength", label: "무력" },
  { key: "intel", label: "지력" },
  { key: "politics", label: "정치" },
  { key: "charm", label: "매력" },
  { key: "crew", label: "병사수" },
  { key: "train", label: "훈련" },
  { key: "atmos", label: "사기" },
  { key: "fame", label: "명성" },
  { key: "dedication", label: "계급" },
  { key: "age", label: "연령" },
  { key: "officerLevel", label: "관직" },
  { key: "nation", label: "소속" },
  { key: "city", label: "도시" },
  { key: "name", label: "이름" },
  { key: "npcState", label: "NPC" },
];

export default function GeneralsPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, nations, cities, loading, loadAll } = useGameStore();
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
  const [nationFilter, setNationFilter] = useState("all");
  const [npcFilter, setNpcFilter] = useState<NpcFilter>("all");
  const [showExtended, setShowExtended] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, fetchMyGeneral, loadAll]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const sorted = useMemo(() => {
    const filtered = generals.filter((g) => {
      if (search && !g.name.toLowerCase().includes(search.toLowerCase()))
        return false;
      if (nationFilter !== "all" && String(g.nationId) !== nationFilter)
        return false;
      if (npcFilter === "user" && g.npcState >= 2) return false;
      if (npcFilter === "npc" && g.npcState < 2) return false;
      return true;
    });

    return [...filtered].sort((a, b) => {
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
      } else if (sortKey === "totalStats") {
        cmp =
          a.leadership + a.strength + a.intel -
          (b.leadership + b.strength + b.intel);
      } else if (sortKey === "age") {
        cmp = (a.age ?? 0) - (b.age ?? 0);
      } else if (sortKey === "special") {
        cmp = (a.specialCode ?? "").localeCompare(b.specialCode ?? "");
      } else if (sortKey === "personal") {
        cmp = (a.personalCode ?? "").localeCompare(b.personalCode ?? "");
      } else {
        cmp =
          sortKey === "fame"
            ? a.experience - b.experience
            : (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [
    generals,
    search,
    nationFilter,
    npcFilter,
    sortKey,
    sortDir,
    nationMap,
    cityMap,
  ]);

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

  const columns = showExtended
    ? [...BASE_COLUMNS, ...EXT_COLUMNS]
    : BASE_COLUMNS;

  const canSpyAccess =
    (myGeneral?.permission === "spy" ||
      myGeneral?.permission === "auditor" ||
      (myGeneral?.officerLevel ?? 0) >= 5) &&
    (myGeneral?.nationId ?? 0) > 0;

  const canViewTroopInfo = (targetNationId: number) => {
    if (!myGeneral) return false;
    if (myGeneral.nationId === targetNationId) return true;
    return canSpyAccess;
  };

  const currentCommandLabel = (value: Record<string, unknown>): string => {
    const actionCode = value.actionCode;
    if (typeof actionCode === "string" && actionCode.length > 0)
      return actionCode;
    const brief = value.brief;
    if (typeof brief === "string" && brief.length > 0) return brief;
    return "-";
  };

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Users} title="장수일람" />

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            placeholder="장수 검색..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
          />
        </div>

        <Select value={nationFilter} onValueChange={setNationFilter}>
          <SelectTrigger className="w-32">
            <SelectValue placeholder="국가" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 국가</SelectItem>
            <SelectItem value="0">재야</SelectItem>
            {nations.map((n) => (
              <SelectItem key={n.id} value={String(n.id)}>
                {n.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select
          value={sortKey}
          onValueChange={(value) => {
            setSortKey(value as SortKey);
            setSortDir("desc");
          }}
        >
          <SelectTrigger className="w-36">
            <SelectValue placeholder="정렬 기준" />
          </SelectTrigger>
          <SelectContent>
            {SORT_DROPDOWN_OPTIONS.map((opt) => (
              <SelectItem key={opt.key} value={opt.key}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="flex border border-gray-600 rounded-md overflow-hidden">
          {(["all", "user", "npc"] as NpcFilter[]).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setNpcFilter(f)}
              className={`px-3 py-1.5 text-xs transition-colors ${
                npcFilter === f
                  ? "bg-[#141c65] text-white"
                  : "text-gray-400 hover:text-white"
              }`}
            >
              {f === "all" ? "전체" : f === "user" ? "유저" : "NPC"}
            </button>
          ))}
        </div>

        <Button
          variant="outline"
          size="sm"
          onClick={() => setShowExtended(!showExtended)}
          className="ml-auto gap-1"
        >
          <Columns3 className="size-4" />
          {showExtended ? "간략히" : "상세히"}
        </Button>

        <Badge variant="secondary">{sorted.length}명</Badge>
      </div>

      {/* Table */}
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
              <TableHead>병력정보</TableHead>
              <TableHead>현재 명령</TableHead>
              <TableHead>NPC</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sorted.map((g) => {
              const nation = nationMap.get(g.nationId);
              return (
                <TableRow
                  key={g.id}
                  className="cursor-pointer hover:bg-white/5"
                  style={
                    nation?.color
                      ? { borderLeft: `3px solid ${nation.color}` }
                      : undefined
                  }
                  onClick={() => router.push(`/generals/${g.id}`)}
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
                  <TableCell className="text-xs whitespace-nowrap">
                    {formatOfficerLevelText(g.officerLevel, nation?.level)}
                  </TableCell>
                  <TableCell>{g.leadership}</TableCell>
                  <TableCell>{g.strength}</TableCell>
                  <TableCell>{g.intel}</TableCell>
                  <TableCell>{g.politics}</TableCell>
                  <TableCell>{g.charm}</TableCell>
                  <TableCell>{g.leadership + g.strength + g.intel}</TableCell>
                  <TableCell>{g.crew.toLocaleString()}</TableCell>
                  <TableCell>{g.expLevel}</TableCell>
                  {showExtended && (
                    <>
                      <TableCell className="text-xs whitespace-nowrap">
                        {g.specialCode === "None" ? "-" : g.specialCode ?? "-"}
                        {g.special2Code && g.special2Code !== "None"
                          ? ` / ${g.special2Code}`
                          : ""}
                      </TableCell>
                      <TableCell className="text-xs">
                        {g.personalCode ?? "-"}
                      </TableCell>
                      <TableCell>{g.age ?? "-"}</TableCell>
                      <TableCell className="text-xs">
                        {CREW_TYPE_NAMES[g.crewType] ?? g.crewType}
                      </TableCell>
                      <TableCell>{g.train}</TableCell>
                      <TableCell>{g.atmos}</TableCell>
                      <TableCell>{g.gold.toLocaleString()}</TableCell>
                      <TableCell>{g.rice.toLocaleString()}</TableCell>
                      <TableCell>{g.dedication.toLocaleString()}</TableCell>
                    </>
                  )}
                  <TableCell className="whitespace-nowrap text-xs">
                    {canViewTroopInfo(g.nationId)
                      ? `${CREW_TYPE_NAMES[g.crewType] ?? g.crewType} / ${g.crew.toLocaleString()} / T${g.train} M${g.atmos}`
                      : "비공개"}
                  </TableCell>
                  <TableCell className="text-xs whitespace-nowrap">
                    {canSpyAccess
                      ? currentCommandLabel(g.lastTurn)
                      : "첩보권한 필요"}
                  </TableCell>
                  <TableCell className="text-center">
                    {g.npcState > 0 && <Badge variant="secondary">NPC</Badge>}
                  </TableCell>
                </TableRow>
              );
            })}
            {sorted.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={columns.length + 3}
                  className="text-center text-muted-foreground"
                >
                  장수가 없습니다.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
