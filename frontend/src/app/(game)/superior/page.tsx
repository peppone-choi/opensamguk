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
  const { generals, nations, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const nation = useMemo(
    () => (myGeneral ? nations.find((n) => n.id === myGeneral.nationId) : null),
    [myGeneral, nations],
  );

  // Build officer hierarchy: generals with higher officerLevel in same nation
  const superiors = useMemo(() => {
    if (!myGeneral || !myGeneral.nationId) return [];
    return generals
      .filter(
        (g) =>
          g.nationId === myGeneral.nationId &&
          g.officerLevel > myGeneral.officerLevel,
      )
      .sort((a, b) => b.officerLevel - a.officerLevel);
  }, [myGeneral, generals]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (!myGeneral)
    return (
      <div className="p-4 text-muted-foreground">장수 정보가 없습니다.</div>
    );

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Shield} title="상급자 정보" />

      {/* My position */}
      <Card>
        <CardHeader>
          <CardTitle>내 직위</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-3">
            <GeneralPortrait
              picture={myGeneral.picture}
              name={myGeneral.name}
              size="md"
            />
            <div>
              <div className="font-bold">{myGeneral.name}</div>
              <div className="flex items-center gap-2 mt-1">
                <NationBadge name={nation?.name} color={nation?.color} />
                <Badge variant="outline">
                  {formatOfficerLevelText(
                    myGeneral.officerLevel,
                    nation?.level,
                  )}
                </Badge>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Superior officers */}
      <Card>
        <CardHeader>
          <CardTitle>상급자 목록</CardTitle>
        </CardHeader>
        <CardContent>
          {superiors.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              {myGeneral.nationId === 0
                ? "소속 국가가 없습니다."
                : "상급자가 없습니다. (최고위직)"}
            </p>
          ) : (
            <div className="space-y-2">
              {superiors.map((g) => (
                <div
                  key={g.id}
                  className="flex items-center gap-3 rounded-lg border p-3"
                >
                  <GeneralPortrait
                    picture={g.picture}
                    name={g.name}
                    size="sm"
                  />
                  <div className="flex-1">
                    <span className="font-medium">{g.name}</span>
                    <Badge variant="outline" className="ml-2 text-xs">
                      {formatOfficerLevelText(g.officerLevel, nation?.level)}
                    </Badge>
                    {g.npcState > 0 && (
                      <Badge variant="secondary" className="ml-1 text-xs">
                        NPC
                      </Badge>
                    )}
                  </div>
                  <div className="text-xs text-muted-foreground text-right">
                    <div>
                      통{g.leadership} 무{g.strength} 지{g.intel}
                    </div>
                    <div>병사 {g.crew.toLocaleString()}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
