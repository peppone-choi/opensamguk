"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Shield } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { formatOfficerLevelText } from "@/lib/game-utils";

export default function SuperiorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, nations, cities, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const nation = useMemo(
    () => (myGeneral ? nations.find((n) => n.id === myGeneral.nationId) : null),
    [myGeneral, nations],
  );

  const cityMap = useMemo(() => new Map(cities.map((c) => [c.id, c.name])), [cities]);

  const commandChain = useMemo(() => {
    if (!myGeneral || myGeneral.nationId <= 0) return [];
    return generals
      .filter((general) => general.nationId === myGeneral.nationId && general.officerLevel > 0)
      .sort(
        (a, b) =>
          b.officerLevel - a.officerLevel ||
          a.cityId - b.cityId ||
          a.name.localeCompare(b.name),
      );
  }, [myGeneral, generals]);

  const directSuperior = useMemo(() => {
    if (!myGeneral || myGeneral.nationId <= 0) return null;
    const candidates = commandChain
      .filter((general) => general.officerLevel > myGeneral.officerLevel)
      .sort((a, b) => a.officerLevel - b.officerLevel || a.name.localeCompare(b.name));
    return candidates[0] ?? null;
  }, [myGeneral, commandChain]);

  if (!currentWorld) {
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  }

  if (loading) return <LoadingState />;

  if (!myGeneral) {
    return <div className="p-4 text-muted-foreground">장수 정보가 없습니다.</div>;
  }

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Shield} title="상관 정보" />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">직속 상관</CardTitle>
        </CardHeader>
        <CardContent>
          {directSuperior ? (
            <div className="flex items-start gap-3">
              <GeneralPortrait
                picture={directSuperior.picture}
                name={directSuperior.name}
                size="md"
              />
              <div className="space-y-1">
                <div className="font-semibold">{directSuperior.name}</div>
                <div className="flex items-center gap-2">
                  <NationBadge name={nation?.name} color={nation?.color} />
                  <Badge variant="outline">
                    {formatOfficerLevelText(directSuperior.officerLevel, nation?.level)}
                  </Badge>
                </div>
                <div className="text-sm text-muted-foreground">
                  {cityMap.get(directSuperior.cityId) ?? "도시 미상"}
                </div>
                <div className="text-xs text-muted-foreground">
                  통{directSuperior.leadership} 무{directSuperior.strength} 지
                  {directSuperior.intel} / 병력 {directSuperior.crew.toLocaleString()}
                </div>
              </div>
            </div>
          ) : (
            <div className="text-sm text-muted-foreground">
              {myGeneral.officerLevel >= 12
                ? "현재 군주입니다. 직속 상관이 없습니다."
                : "직속 상관을 찾을 수 없습니다."}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">지휘 체계</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {commandChain.length === 0 ? (
            <div className="text-sm text-muted-foreground">지휘 체계 정보가 없습니다.</div>
          ) : (
            commandChain.map((general) => {
              const isMe = general.id === myGeneral.id;
              return (
                <div
                  key={general.id}
                  className={`rounded border p-2 ${isMe ? "border-amber-500/70 bg-amber-500/5" : ""}`}
                >
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{general.name}</span>
                    {isMe && <Badge className="bg-amber-500 text-black">나</Badge>}
                    <Badge variant="outline">
                      {formatOfficerLevelText(general.officerLevel, nation?.level)}
                    </Badge>
                    <span className="text-xs text-muted-foreground">
                      {cityMap.get(general.cityId) ?? "도시 미상"}
                    </span>
                  </div>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>
    </div>
  );
}
