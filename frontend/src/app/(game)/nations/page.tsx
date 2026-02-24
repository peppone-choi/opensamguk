"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Globe, ChevronDown, ChevronRight } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatOfficerLevelText } from "@/lib/game-utils";

type UserType = "통" | "무" | "지" | "만능" | "평범" | "무지" | "무능";

function getUserType(leadership: number, strength: number, intel: number): UserType {
  if (leadership < 40) {
    if (strength + intel < 40) return "무능";
    return "무지";
  }
  const max = Math.max(leadership, strength, intel);
  const min2sum = Math.min(leadership + strength, strength + intel, intel + leadership);
  if (max >= 70 && min2sum >= max * 1.7) return "만능";
  if (strength >= 60 && intel < strength * 0.8) return "무";
  if (intel >= 60 && strength < intel * 0.8) return "지";
  if (leadership >= 60 && strength + intel < leadership) return "통";
  return "평범";
}

const USER_TYPE_COLORS: Record<UserType, string> = {
  만능: "#f59e0b",
  통: "#3b82f6",
  무: "#ef4444",
  지: "#22c55e",
  평범: "#94a3b8",
  무지: "#6b7280",
  무능: "#4b5563",
};

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
  const [expandedNations, setExpandedNations] = useState<Set<number>>(
    new Set(),
  );

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

  // Generals grouped by nation
  const generalsByNation = useMemo(() => {
    const map = new Map<number, typeof generals>();
    for (const g of generals) {
      const nid = g.nationId ?? 0;
      if (!map.has(nid)) map.set(nid, []);
      map.get(nid)!.push(g);
    }
    // Sort each group by officer level desc, then name
    for (const [, list] of map) {
      list.sort((a, b) => {
        if (b.officerLevel !== a.officerLevel)
          return b.officerLevel - a.officerLevel;
        return a.name.localeCompare(b.name);
      });
    }
    return map;
  }, [generals]);

  // Cities grouped by nation
  const citiesByNation = useMemo(() => {
    const map = new Map<number, typeof cities>();
    for (const c of cities) {
      const nid = c.nationId ?? 0;
      if (!map.has(nid)) map.set(nid, []);
      map.get(nid)!.push(c);
    }
    return map;
  }, [cities]);

  // Chief and advisor per nation
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

  // 재야 generals
  const roninGenerals = useMemo(
    () =>
      generals
        .filter((g) => !g.nationId || g.nationId === 0)
        .sort((a, b) => a.name.localeCompare(b.name)),
    [generals],
  );

  // 무소속 cities
  const unownedCities = useMemo(
    () =>
      cities
        .filter((c) => !c.nationId || c.nationId === 0)
        .sort((a, b) => a.name.localeCompare(b.name)),
    [cities],
  );

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

  const toggleExpand = (nationId: number) => {
    setExpandedNations((prev) => {
      const next = new Set(prev);
      if (next.has(nationId)) next.delete(nationId);
      else next.add(nationId);
      return next;
    });
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
              <TableHead className="w-8" />
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
              const isExpanded = expandedNations.has(n.id);
              const nationGens = generalsByNation.get(n.id) ?? [];
              const nationCities = citiesByNation.get(n.id) ?? [];

              return (
                <>
                  <TableRow
                    key={n.id}
                    className="cursor-pointer hover:bg-white/5"
                    style={
                      n.color
                        ? { borderLeft: `3px solid ${n.color}` }
                        : undefined
                    }
                    onClick={() => toggleExpand(n.id)}
                  >
                    {/* Expand toggle */}
                    <TableCell className="w-8 px-1">
                      {isExpanded ? (
                        <ChevronDown className="size-4" />
                      ) : (
                        <ChevronRight className="size-4" />
                      )}
                    </TableCell>

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

                    <TableCell>{capital?.name ?? "-"}</TableCell>

                    <TableCell>
                      <Badge variant="secondary">
                        {LEVEL_LABELS[n.level] ?? n.level}
                      </Badge>
                    </TableCell>

                    <TableCell>
                      <div className="flex items-center gap-2">
                        <span className="tabular-nums text-sm min-w-[4rem] text-right">
                          {n.power.toLocaleString()}
                        </span>
                        <Progress value={powerPct} className="h-2 w-20" />
                      </div>
                    </TableCell>

                    <TableCell>{genCount}</TableCell>
                    <TableCell>{cityCnt}</TableCell>
                    <TableCell>{n.gold.toLocaleString()}</TableCell>
                    <TableCell>{n.rice.toLocaleString()}</TableCell>
                    <TableCell>{n.tech}</TableCell>
                    <TableCell>{n.typeCode}</TableCell>
                  </TableRow>

                  {/* Expanded detail block */}
                  {isExpanded && (
                    <TableRow key={`${n.id}-detail`}>
                      <TableCell
                        colSpan={columns.length + 1}
                        className="bg-muted/10 px-4 py-3"
                      >
                        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                          {/* Generals list */}
                          <div>
                            <h4
                              className="text-sm font-semibold mb-2"
                              style={{ color: n.color }}
                            >
                              장수 ({nationGens.length}명)
                            </h4>
                            {/* Type classification summary */}
                            {nationGens.length > 0 && (() => {
                              const byType: Record<UserType, typeof nationGens> = {
                                만능: [], 통: [], 무: [], 지: [], 평범: [], 무지: [], 무능: [],
                              };
                              let combatUserCount = 0;
                              let combatUserLeadership = 0;
                              let combatNpcCount = 0;
                              let combatNpcLeadership = 0;
                              for (const g of nationGens) {
                                const t = getUserType(g.leadership, g.strength, g.intel);
                                byType[t].push(g);
                                if (t !== "무능" && t !== "무지") {
                                  if (g.npcState < 2) {
                                    combatUserCount++;
                                    combatUserLeadership += g.leadership;
                                  } else {
                                    combatNpcCount++;
                                    combatNpcLeadership += g.leadership;
                                  }
                                }
                              }
                              return (
                                <div className="mb-2 p-2 rounded border border-muted/30 text-xs space-y-1">
                                  <div className="font-semibold text-yellow-400 text-center">
                                    총({nationGens.length}), 전투장({combatUserCount}, 약{" "}
                                    {(combatUserLeadership * 100).toLocaleString()}명), 전투N장({combatNpcCount}, 약{" "}
                                    {(combatNpcLeadership * 100).toLocaleString()}명)
                                  </div>
                                  {(["만능", "통", "무", "지", "평범", "무지", "무능"] as UserType[]).map((t) => {
                                    if (byType[t].length === 0) return null;
                                    return (
                                      <div key={t} className="flex flex-wrap gap-0.5">
                                        <span
                                          className="inline-block w-10 text-right mr-1 font-medium"
                                          style={{ color: USER_TYPE_COLORS[t] }}
                                        >
                                          {t}장({byType[t].length})
                                        </span>
                                        {byType[t].map((g, i) => (
                                          <span
                                            key={g.id}
                                            className={g.npcState >= 2 ? "text-cyan-400" : ""}
                                            style={
                                              (g.penalty && Object.keys(g.penalty).length > 0)
                                                ? { color: "#facc15" }
                                                : undefined
                                            }
                                          >
                                            {g.name}{i < byType[t].length - 1 ? ", " : ""}
                                          </span>
                                        ))}
                                      </div>
                                    );
                                  })}
                                </div>
                              );
                            })()}
                            {nationGens.length === 0 ? (
                              <p className="text-xs text-muted-foreground">
                                소속 장수 없음
                              </p>
                            ) : (
                              <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                  <thead>
                                    <tr className="border-b border-muted/50">
                                      <th className="text-left py-1 px-1">
                                        이름
                                      </th>
                                      <th className="text-left py-1 px-1">
                                        관직
                                      </th>
                                      <th className="text-left py-1 px-1">
                                        도시
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        통솔
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        무력
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        지력
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        병력
                                      </th>
                                      <th className="text-center py-1 px-1">
                                        벌점
                                      </th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {nationGens.map((g) => {
                                      const hasPenalty =
                                        Object.keys(g.penalty || {}).length > 0;
                                      return (
                                        <tr
                                          key={g.id}
                                          className="border-b border-muted/20 hover:bg-muted/20"
                                        >
                                          <td className="py-1 px-1">
                                            <div className="flex items-center gap-1">
                                              <GeneralPortrait
                                                picture={g.picture}
                                                name={g.name}
                                                size="sm"
                                              />
                                              <span
                                                className={
                                                  g.npcState > 0
                                                    ? "text-gray-400"
                                                    : ""
                                                }
                                              >
                                                {g.name}
                                              </span>
                                              {g.npcState > 0 && (
                                                <Badge
                                                  variant="outline"
                                                  className="text-[8px] px-1 py-0"
                                                >
                                                  NPC
                                                </Badge>
                                              )}
                                            </div>
                                          </td>
                                          <td className="py-1 px-1 whitespace-nowrap">
                                            {formatOfficerLevelText(
                                              g.officerLevel,
                                              n.level,
                                            )}
                                          </td>
                                          <td className="py-1 px-1">
                                            {cityMap.get(g.cityId)?.name ??
                                              "-"}
                                          </td>
                                          <td className="py-1 px-1 text-right">
                                            {g.leadership}
                                          </td>
                                          <td className="py-1 px-1 text-right">
                                            {g.strength}
                                          </td>
                                          <td className="py-1 px-1 text-right">
                                            {g.intel}
                                          </td>
                                          <td className="py-1 px-1 text-right">
                                            {g.crew.toLocaleString()}
                                          </td>
                                          <td className="py-1 px-1 text-center">
                                            {hasPenalty ? (
                                              <span className="text-red-400">
                                                ●
                                              </span>
                                            ) : (
                                              "-"
                                            )}
                                          </td>
                                        </tr>
                                      );
                                    })}
                                  </tbody>
                                </table>
                              </div>
                            )}
                          </div>

                          {/* Cities list */}
                          <div>
                            <h4
                              className="text-sm font-semibold mb-2"
                              style={{ color: n.color }}
                            >
                              속령 ({nationCities.length}개)
                            </h4>
                            {nationCities.length === 0 ? (
                              <p className="text-xs text-muted-foreground">
                                소속 도시 없음
                              </p>
                            ) : (
                              <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                  <thead>
                                    <tr className="border-b border-muted/50">
                                      <th className="text-left py-1 px-1">
                                        도시
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        인구
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        농업
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        상업
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        치안
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        수비
                                      </th>
                                      <th className="text-right py-1 px-1">
                                        성벽
                                      </th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {nationCities.map((c) => (
                                      <tr
                                        key={c.id}
                                        className="border-b border-muted/20 hover:bg-muted/20"
                                      >
                                        <td className="py-1 px-1 font-medium">
                                          {c.name}
                                          {n.capitalCityId === c.id && (
                                            <span className="text-yellow-400 ml-1">
                                              ★
                                            </span>
                                          )}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.pop.toLocaleString()}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.agri}/{c.agriMax}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.comm}/{c.commMax}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.secu}/{c.secuMax}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.def}/{c.defMax}
                                        </td>
                                        <td className="py-1 px-1 text-right">
                                          {c.wall}/{c.wallMax}
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                </>
              );
            })}
            {sorted.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={columns.length + 1}
                  className="text-center text-muted-foreground"
                >
                  국가가 없습니다.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* 재야 (Ronin) Section */}
      {(roninGenerals.length > 0 || unownedCities.length > 0) && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">재야 / 무소속</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {/* Ronin generals */}
              <div>
                <h4 className="text-sm font-semibold mb-2 text-muted-foreground">
                  재야 장수 ({roninGenerals.length}명)
                </h4>
                {roninGenerals.length === 0 ? (
                  <p className="text-xs text-muted-foreground">없음</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-muted/50">
                          <th className="text-left py-1 px-1">이름</th>
                          <th className="text-left py-1 px-1">도시</th>
                          <th className="text-right py-1 px-1">통솔</th>
                          <th className="text-right py-1 px-1">무력</th>
                          <th className="text-right py-1 px-1">지력</th>
                          <th className="text-center py-1 px-1">NPC</th>
                        </tr>
                      </thead>
                      <tbody>
                        {roninGenerals.map((g) => (
                          <tr
                            key={g.id}
                            className="border-b border-muted/20 hover:bg-muted/20"
                          >
                            <td className="py-1 px-1">
                              <div className="flex items-center gap-1">
                                <GeneralPortrait
                                  picture={g.picture}
                                  name={g.name}
                                  size="sm"
                                />
                                {g.name}
                              </div>
                            </td>
                            <td className="py-1 px-1">
                              {cityMap.get(g.cityId)?.name ?? "-"}
                            </td>
                            <td className="py-1 px-1 text-right">
                              {g.leadership}
                            </td>
                            <td className="py-1 px-1 text-right">
                              {g.strength}
                            </td>
                            <td className="py-1 px-1 text-right">
                              {g.intel}
                            </td>
                            <td className="py-1 px-1 text-center">
                              {g.npcState > 0 ? "NPC" : "-"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>

              {/* Unowned cities */}
              <div>
                <h4 className="text-sm font-semibold mb-2 text-muted-foreground">
                  무소속 도시 ({unownedCities.length}개)
                </h4>
                {unownedCities.length === 0 ? (
                  <p className="text-xs text-muted-foreground">없음</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-muted/50">
                          <th className="text-left py-1 px-1">도시</th>
                          <th className="text-right py-1 px-1">인구</th>
                          <th className="text-right py-1 px-1">수비</th>
                          <th className="text-right py-1 px-1">성벽</th>
                        </tr>
                      </thead>
                      <tbody>
                        {unownedCities.map((c) => (
                          <tr
                            key={c.id}
                            className="border-b border-muted/20 hover:bg-muted/20"
                          >
                            <td className="py-1 px-1 font-medium">{c.name}</td>
                            <td className="py-1 px-1 text-right">
                              {c.pop.toLocaleString()}
                            </td>
                            <td className="py-1 px-1 text-right">
                              {c.def}/{c.defMax}
                            </td>
                            <td className="py-1 px-1 text-right">
                              {c.wall}/{c.wallMax}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
