"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { generalApi, nationApi, troopApi } from "@/lib/gameApi";
import type { General, Nation, Troop } from "@/types";
import { Users, Shield, Swords, Eye, EyeOff } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { ErrorState } from "@/components/game/error-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatOfficerLevelText, CREW_TYPE_NAMES } from "@/lib/game-utils";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

type ColumnKey = "officer" | "stats" | "crew" | "train" | "troop" | "battle" | "equipment" | "status";

const ALL_COLUMNS: { key: ColumnKey; label: string; minOfficerLevel: number }[] = [
  { key: "officer", label: "관직", minOfficerLevel: 0 },
  { key: "stats", label: "능력치", minOfficerLevel: 0 },
  { key: "crew", label: "병력", minOfficerLevel: 0 },
  { key: "train", label: "훈련/사기", minOfficerLevel: 3 },
  { key: "troop", label: "부대", minOfficerLevel: 0 },
  { key: "battle", label: "전투기록", minOfficerLevel: 4 },
  { key: "equipment", label: "장비", minOfficerLevel: 5 },
  { key: "status", label: "상태", minOfficerLevel: 0 },
];

export default function NationGeneralsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral } = useGeneralStore();
  const { cities } = useGameStore();
  const [generals, setGenerals] = useState<General[]>([]);
  const [nation, setNation] = useState<Nation | null>(null);
  const [troops, setTroops] = useState<Troop[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [hiddenCols, setHiddenCols] = useState<Set<ColumnKey>>(new Set());

  const fetchData = useCallback(() => {
    if (!myGeneral?.nationId) return;
    setLoading(true);
    setError(false);
    Promise.all([
      generalApi.listByNation(myGeneral.nationId),
      nationApi.get(myGeneral.nationId),
      troopApi.listByNation(myGeneral.nationId),
    ])
      .then(([gRes, nRes, tRes]) => {
        setGenerals(gRes.data);
        setNation(nRes.data);
        setTroops(tRes.data.map((tw) => tw.troop));
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const troopMap = useMemo(() => new Map(troops.map((t) => [t.id, t])), [troops]);
  const cityMap = useMemo(() => new Map(cities.map((c) => [c.id, c])), [cities]);

  const myOfficerLevel = myGeneral?.officerLevel ?? 0;
  const visibleColumns = ALL_COLUMNS.filter(
    (col) => col.minOfficerLevel <= myOfficerLevel && !hiddenCols.has(col.key)
  );

  const toggleCol = (key: ColumnKey) => {
    setHiddenCols((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  if (!currentWorld || !myGeneral)
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  if (loading) return <LoadingState />;
  if (error) return <ErrorState title="세력 장수 정보를 불러오지 못했습니다." onRetry={fetchData} />;

  return (
    <div className="p-4 space-y-4 max-w-5xl mx-auto">
      <PageHeader icon={Users} title="세력장수" />
      {nation && (
        <p className="text-sm text-muted-foreground">
          <span className="inline-block size-3 rounded-full mr-1 align-middle" style={{ backgroundColor: nation.color }} />
          {nation.name} 소속 장수 ({generals.length}명)
        </p>
      )}

      {/* Column visibility toggles */}
      <div className="flex items-center gap-1 flex-wrap">
        <span className="text-[10px] text-muted-foreground mr-1">컬럼:</span>
        {ALL_COLUMNS.filter((c) => c.minOfficerLevel <= myOfficerLevel).map((col) => (
          <Button
            key={col.key}
            size="sm"
            variant={hiddenCols.has(col.key) ? "ghost" : "outline"}
            className="h-5 px-1.5 text-[10px]"
            onClick={() => toggleCol(col.key)}
          >
            {hiddenCols.has(col.key) ? <EyeOff className="size-2.5 mr-0.5" /> : <Eye className="size-2.5 mr-0.5" />}
            {col.label}
          </Button>
        ))}
      </div>

      {generals.length === 0 ? (
        <EmptyState icon={Users} title="소속 장수가 없습니다." />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                {visibleColumns.map((col) => (
                  <TableHead key={col.key}>{col.label}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {generals.map((g) => {
                const troop = g.troopId ? troopMap.get(g.troopId) : null;
                const city = cityMap.get(g.cityId);
                return (
                  <TableRow key={g.id}>
                    <TableCell className="font-medium">
                      <Link href={`/generals/${g.id}`} className="flex items-center gap-2 hover:underline">
                        <GeneralPortrait picture={g.picture} name={g.name} size="sm" />
                        <div>
                          <div>{g.name}</div>
                          {city && <div className="text-[10px] text-muted-foreground">{city.name}</div>}
                        </div>
                      </Link>
                    </TableCell>
                    {visibleColumns.map((col) => (
                      <TableCell key={col.key} className="text-xs">
                        {col.key === "officer" && (
                          <span className="text-muted-foreground">{formatOfficerLevelText(g.officerLevel, nation?.level)}</span>
                        )}
                        {col.key === "stats" && (
                          <span className="tabular-nums">통{g.leadership}/무{g.strength}/지{g.intel}</span>
                        )}
                        {col.key === "crew" && (
                          <span className="tabular-nums">{CREW_TYPE_NAMES[g.crewType] ?? g.crewType} {g.crew.toLocaleString()}</span>
                        )}
                        {col.key === "train" && (
                          <span className="tabular-nums">훈{g.train}/사{g.atmos}</span>
                        )}
                        {col.key === "troop" && (
                          troop ? (
                            <Badge variant="outline" className="text-[10px]">
                              <Shield className="size-2.5 mr-0.5" />
                              {troop.name}
                            </Badge>
                          ) : (
                            <span className="text-muted-foreground">-</span>
                          )
                        )}
                        {col.key === "battle" && (
                          <div className="flex items-center gap-1 text-[10px]">
                            <Swords className="size-2.5" />
                            <span className="text-green-400">{g.warnum ?? 0}전</span>
                            <span className="text-blue-400">{g.killnum ?? 0}승</span>
                            <span className="text-red-400">{g.deathnum ?? 0}패</span>
                          </div>
                        )}
                        {col.key === "equipment" && (
                          <div className="flex gap-0.5">
                            {g.weaponCode && g.weaponCode !== "0" && <Badge variant="outline" className="text-[9px] px-1">무기</Badge>}
                            {g.bookCode && g.bookCode !== "0" && <Badge variant="outline" className="text-[9px] px-1">서적</Badge>}
                            {g.horseCode && g.horseCode !== "0" && <Badge variant="outline" className="text-[9px] px-1">말</Badge>}
                            {g.itemCode && g.itemCode !== "0" && <Badge variant="outline" className="text-[9px] px-1">도구</Badge>}
                          </div>
                        )}
                        {col.key === "status" && (
                          g.npcState > 0 ? <Badge variant="secondary">NPC</Badge> : <Badge variant="outline">플레이어</Badge>
                        )}
                      </TableCell>
                    ))}
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
