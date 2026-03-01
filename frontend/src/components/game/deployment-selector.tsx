"use client";

import { useState, useMemo } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import { cn } from "@/lib/utils";
import type { General, City, Nation } from "@/types";

// ── Crew type name mapping ──

const CREW_TYPE_NAMES: Record<number, string> = {
  0: "없음",
  1100: "보병",
  1101: "청주병",
  1102: "수병",
  1103: "자객병",
  1104: "근위병",
  1200: "궁병",
  1201: "궁기병",
  1202: "연노병",
  1203: "강궁병",
  1300: "기병",
  1301: "백마병",
  1302: "중장기병",
  1303: "돌격기병",
  1400: "귀병",
  1401: "신귀병",
  1402: "백귀병",
  1500: "정란",
  1501: "충차",
};

// ── Distance grouping ──

type DistanceGroup = "근접" | "중거리" | "원거리";

function getDistanceGroup(dist: number): DistanceGroup {
  if (dist <= 1) return "근접";
  if (dist <= 3) return "중거리";
  return "원거리";
}

// Simple city distance heuristic using IDs (in production, use map graph)
function estimateCityDistance(cityA: number, cityB: number): number {
  return Math.abs(cityA - cityB) % 10; // placeholder
}

interface DeploymentSelectorProps {
  onSubmit: (generalId: number, cityId: number) => void;
}

export function DeploymentSelector({ onSubmit }: DeploymentSelectorProps) {
  const { cities, nations, generals } = useGameStore();
  const { myGeneral } = useGeneralStore();
  const [selectedGeneralId, setSelectedGeneralId] = useState<number | null>(
    null,
  );
  const [selectedCityId, setSelectedCityId] = useState<number | null>(null);

  // Only own-nation generals
  const myNationGenerals = useMemo(() => {
    if (!myGeneral) return [];
    return generals.filter((g) => g.nationId === myGeneral.nationId);
  }, [generals, myGeneral]);

  // Only own-nation cities
  const myCities = useMemo(() => {
    if (!myGeneral) return [];
    return cities.filter((c) => c.nationId === myGeneral.nationId);
  }, [cities, myGeneral]);

  // Group cities by distance from selected general's current city
  const groupedCities = useMemo(() => {
    const selectedGen = myNationGenerals.find(
      (g) => g.id === selectedGeneralId,
    );
    const baseCityId = selectedGen?.cityId ?? myGeneral?.cityId ?? 0;

    const groups: Record<DistanceGroup, (City & { dist: number })[]> = {
      근접: [],
      중거리: [],
      원거리: [],
    };

    for (const city of myCities) {
      const dist = estimateCityDistance(baseCityId, city.id);
      const group = getDistanceGroup(dist);
      groups[group].push({ ...city, dist });
    }

    // Sort each group by distance
    for (const g of Object.values(groups)) {
      g.sort((a, b) => a.dist - b.dist);
    }

    return groups;
  }, [myCities, selectedGeneralId, myNationGenerals, myGeneral]);

  const getNation = (nationId: number): Nation | undefined =>
    nations.find((n) => n.id === nationId);

  const getCityName = (cityId: number): string =>
    cities.find((c) => c.id === cityId)?.name ?? `도시${cityId}`;

  const handleSubmit = () => {
    if (selectedGeneralId != null && selectedCityId != null) {
      onSubmit(selectedGeneralId, selectedCityId);
    }
  };

  return (
    <div className="space-y-3">
      {/* Help text */}
      <div className="rounded-md bg-amber-900/20 border border-amber-800/40 px-3 py-2 text-xs text-amber-200/90">
        선택된 도시로 아국 장수를 발령합니다. 아국 도시로만 발령이 가능합니다.
      </div>

      {/* General selector */}
      <div className="space-y-1">
        <label className="text-[10px] text-muted-foreground font-medium">
          장수 선택
        </label>
        <div className="max-h-48 overflow-y-auto space-y-1">
          {myNationGenerals.length === 0 ? (
            <p className="text-xs text-muted-foreground text-center py-3">
              소속 장수 없음
            </p>
          ) : (
            myNationGenerals.map((gen) => {
              const isSelected = selectedGeneralId === gen.id;
              const crewName =
                CREW_TYPE_NAMES[gen.crewType] ?? `${gen.crewType}`;
              return (
                <button
                  key={gen.id}
                  onClick={() => setSelectedGeneralId(gen.id)}
                  className={cn(
                    "w-full text-left px-3 py-1.5 rounded-md border text-xs transition-colors",
                    isSelected
                      ? "border-amber-500 bg-amber-900/30 text-amber-100"
                      : "border-border hover:border-amber-700/50 hover:bg-amber-900/10",
                  )}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{gen.name}</span>
                      <span className="text-[10px] text-muted-foreground">
                        {getCityName(gen.cityId)}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                      <span>
                        {gen.leadership}/{gen.strength}/{gen.intel}
                      </span>
                    </div>
                  </div>
                  <div className="flex gap-2 mt-0.5 text-[10px] text-muted-foreground">
                    <span>병 {(gen.crew ?? 0).toLocaleString()}</span>
                    <span>{crewName}</span>
                    <span>훈 {gen.train}</span>
                    <span>사 {gen.atmos}</span>
                  </div>
                </button>
              );
            })
          )}
        </div>
      </div>

      {/* City selector grouped by distance */}
      <div className="space-y-1">
        <label className="text-[10px] text-muted-foreground font-medium">
          발령 도시
        </label>
        <div className="max-h-48 overflow-y-auto space-y-2">
          {(["근접", "중거리", "원거리"] as DistanceGroup[]).map((group) => {
            const citiesInGroup = groupedCities[group];
            if (citiesInGroup.length === 0) return null;
            return (
              <div key={group}>
                <div className="flex items-center gap-1 mb-1">
                  <Badge
                    variant="outline"
                    className={cn(
                      "text-[9px] px-1.5 py-0",
                      group === "근접"
                        ? "border-green-600 text-green-400"
                        : group === "중거리"
                          ? "border-yellow-600 text-yellow-400"
                          : "border-red-600 text-red-400",
                    )}
                  >
                    {group}
                  </Badge>
                </div>
                <div className="space-y-0.5">
                  {citiesInGroup.map((city) => {
                    const isSelected = selectedCityId === city.id;
                    const nation = getNation(city.nationId);
                    return (
                      <button
                        key={city.id}
                        onClick={() => setSelectedCityId(city.id)}
                        className={cn(
                          "w-full text-left px-3 py-1.5 rounded-md border text-xs transition-colors",
                          isSelected
                            ? "border-amber-500 bg-amber-900/30 text-amber-100"
                            : "border-border hover:border-amber-700/50 hover:bg-amber-900/10",
                        )}
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{city.name}</span>
                            <span className="text-[10px] text-muted-foreground">
                              Lv.{city.level}
                            </span>
                            {nation && (
                              <Badge
                                variant="outline"
                                className="text-[9px] px-1 py-0"
                              >
                                {nation.name}
                              </Badge>
                            )}
                          </div>
                        </div>
                        <div className="flex gap-2 mt-0.5 text-[10px] text-muted-foreground">
                          <span>인구 {(city.pop ?? 0).toLocaleString()}</span>
                          <span>방어 {(city.def ?? 0).toLocaleString()}</span>
                          <span>치안 {city.secu ?? 0}</span>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Submit */}
      <Button
        size="sm"
        onClick={handleSubmit}
        disabled={selectedGeneralId == null || selectedCityId == null}
        className="w-full"
      >
        발령 확인
      </Button>
    </div>
  );
}
