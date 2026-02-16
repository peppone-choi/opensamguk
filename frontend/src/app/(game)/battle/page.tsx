"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import type { Nation, General } from "@/types";
import { Swords } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

function getNationName(nations: Nation[], id: number) {
  return nations.find((n) => n.id === id)?.name ?? `국가#${id}`;
}

function getNationColor(nations: Nation[], id: number) {
  return nations.find((n) => n.id === id)?.color ?? "#888";
}

export default function BattlePage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { cities, nations, generals, diplomacy, loading, loadAll } =
    useGameStore();

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  // Active wars: diplomacy with stateCode "war"
  const wars = useMemo(
    () => diplomacy.filter((d) => d.stateCode === "war" && !d.isDead),
    [diplomacy],
  );

  // Front line cities
  const frontCities = useMemo(
    () => cities.filter((c) => c.frontState > 0),
    [cities],
  );

  // Generals grouped by city
  const generalsByCity = useMemo(() => {
    const map = new Map<number, General[]>();
    for (const g of generals) {
      const list = map.get(g.cityId) ?? [];
      list.push(g);
      map.set(g.cityId, list);
    }
    return map;
  }, [generals]);

  // Battle stats per nation: count generals with crew > 0
  const nationMilitary = useMemo(() => {
    const map = new Map<number, { totalCrew: number; generalCount: number }>();
    for (const g of generals) {
      if (g.nationId === 0) continue;
      const entry = map.get(g.nationId) ?? { totalCrew: 0, generalCount: 0 };
      if (g.crew > 0) {
        entry.totalCrew += g.crew;
        entry.generalCount += 1;
      }
      map.set(g.nationId, entry);
    }
    return map;
  }, [generals]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={Swords} title="전투 센터" />

      {/* Active Wars */}
      <Card>
        <CardHeader>
          <CardTitle>진행 중인 전쟁</CardTitle>
        </CardHeader>
        <CardContent>
          {wars.length === 0 ? (
            <EmptyState icon={Swords} title="현재 전쟁이 없습니다." />
          ) : (
            <div className="space-y-2">
              {wars.map((w) => (
                <div
                  key={w.id}
                  className="flex items-center gap-3 rounded-lg border p-3"
                >
                  <NationBadge
                    name={getNationName(nations, w.srcNationId)}
                    color={getNationColor(nations, w.srcNationId)}
                  />
                  <Swords className="size-4 text-destructive shrink-0" />
                  <NationBadge
                    name={getNationName(nations, w.destNationId)}
                    color={getNationColor(nations, w.destNationId)}
                  />
                  <span className="ml-auto text-xs text-muted-foreground">
                    {w.term}턴 경과
                  </span>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Military Power by Nation */}
      <Card>
        <CardHeader>
          <CardTitle>국가별 군사력</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-2">
            {nations
              .filter((n) => nationMilitary.has(n.id))
              .sort(
                (a, b) =>
                  (nationMilitary.get(b.id)?.totalCrew ?? 0) -
                  (nationMilitary.get(a.id)?.totalCrew ?? 0),
              )
              .map((n) => {
                const m = nationMilitary.get(n.id)!;
                return (
                  <div key={n.id} className="rounded-lg border p-3">
                    <div className="flex items-center gap-2 mb-1">
                      <NationBadge name={n.name} color={n.color} />
                    </div>
                    <div className="text-xs text-muted-foreground">
                      병력: {m.totalCrew.toLocaleString()} / 장수:{" "}
                      {m.generalCount}명
                    </div>
                  </div>
                );
              })}
          </div>
        </CardContent>
      </Card>

      {/* Front Line Cities */}
      <Card>
        <CardHeader>
          <CardTitle>전선 도시</CardTitle>
        </CardHeader>
        <CardContent>
          {frontCities.length === 0 ? (
            <EmptyState icon={Swords} title="전선 도시가 없습니다." />
          ) : (
            <div className="space-y-3">
              {frontCities.map((c) => {
                const cityGenerals = generalsByCity.get(c.id) ?? [];
                const ownerNation = nations.find((n) => n.id === c.nationId);
                return (
                  <div key={c.id} className="rounded-lg border p-3 space-y-2">
                    <div className="flex items-center gap-2">
                      {ownerNation && (
                        <NationBadge
                          name={ownerNation.name}
                          color={ownerNation.color}
                        />
                      )}
                      <span className="font-medium">{c.name}</span>
                      <Badge variant="secondary">Lv.{c.level}</Badge>
                      <Badge variant="destructive" className="ml-auto">
                        전선 {c.frontState}
                      </Badge>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      성벽: {c.wall}/{c.wallMax} · 방어: {c.def}/{c.defMax}
                    </div>
                    {cityGenerals.length > 0 && (
                      <div className="text-xs space-y-1">
                        <span className="text-muted-foreground">
                          주둔 장수:
                        </span>
                        {cityGenerals.map((g) => (
                          <span key={g.id} className="ml-2">
                            {g.name}
                            <span className="text-muted-foreground">
                              ({g.crew.toLocaleString()}명)
                            </span>
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
