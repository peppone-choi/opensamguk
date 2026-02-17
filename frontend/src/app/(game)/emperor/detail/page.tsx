"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Crown, Users } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { formatOfficerLevelText } from "@/lib/game-utils";

export default function EmperorDetailPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const emperorNation = useMemo(
    () => nations.find((n) => n.level >= 7),
    [nations],
  );

  const chiefGeneral = useMemo(
    () =>
      emperorNation
        ? generals.find((g) => g.id === emperorNation.chiefGeneralId)
        : null,
    [emperorNation, generals],
  );

  const capitalCity = useMemo(
    () =>
      emperorNation
        ? cities.find((c) => c.id === emperorNation.capitalCityId)
        : null,
    [emperorNation, cities],
  );

  const nationGenerals = useMemo(
    () =>
      emperorNation
        ? generals
            .filter((g) => g.nationId === emperorNation.id)
            .sort((a, b) => b.officerLevel - a.officerLevel)
        : [],
    [emperorNation, generals],
  );

  const nationCities = useMemo(
    () =>
      emperorNation
        ? cities.filter((c) => c.nationId === emperorNation.id)
        : [],
    [emperorNation, cities],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  if (!emperorNation) {
    return (
      <div className="p-4 space-y-6 max-w-2xl mx-auto">
        <PageHeader icon={Crown} title="황제 상세" />
        <Card>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              아직 황제를 칭한 국가가 없습니다.
            </p>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Crown} title="황제 상세" />

      {/* Emperor nation info */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Crown className="size-5 text-amber-400" />
            <NationBadge
              name={emperorNation.name}
              color={emperorNation.color}
            />
            <Badge variant="secondary">황제국</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-3 text-sm">
            <Row label="국가 레벨" value={String(emperorNation.level)} />
            <Row label="기술력" value={String(emperorNation.tech)} />
            <Row label="국력" value={String(emperorNation.power)} />
            <Row
              label="금"
              value={
                <span className="text-yellow-400">
                  {emperorNation.gold.toLocaleString()}
                </span>
              }
            />
            <Row
              label="쌀"
              value={
                <span className="text-green-400">
                  {emperorNation.rice.toLocaleString()}
                </span>
              }
            />
            <Row label="수도" value={capitalCity?.name ?? "-"} />
            <Row label="도시 수" value={`${nationCities.length}개`} />
            <Row label="장수 수" value={`${nationGenerals.length}명`} />
          </div>

          {/* Chief general */}
          {chiefGeneral && (
            <div className="border-t border-gray-600 pt-3">
              <h3 className="text-sm font-semibold mb-2">군주</h3>
              <div className="flex items-center gap-3">
                <GeneralPortrait
                  picture={chiefGeneral.picture}
                  name={chiefGeneral.name}
                  size="md"
                />
                <div>
                  <div className="font-bold">{chiefGeneral.name}</div>
                  <div className="text-xs text-muted-foreground">
                    통{chiefGeneral.leadership} 무{chiefGeneral.strength} 지
                    {chiefGeneral.intel} 정{chiefGeneral.politics} 매
                    {chiefGeneral.charm}
                  </div>
                </div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Key officers */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="size-4" />
            주요 관직
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {nationGenerals
              .filter((g) => g.officerLevel >= 5)
              .map((g) => (
                <div
                  key={g.id}
                  className="flex items-center gap-3 rounded-lg border p-2"
                >
                  <GeneralPortrait
                    picture={g.picture}
                    name={g.name}
                    size="sm"
                  />
                  <div className="flex-1">
                    <span className="font-medium">{g.name}</span>
                    <Badge variant="outline" className="ml-2 text-xs">
                      {formatOfficerLevelText(
                        g.officerLevel,
                        emperorNation.level,
                      )}
                    </Badge>
                  </div>
                  <div className="text-xs text-muted-foreground">
                    통{g.leadership} 무{g.strength} 지{g.intel}
                  </div>
                </div>
              ))}
            {nationGenerals.filter((g) => g.officerLevel >= 5).length === 0 && (
              <p className="text-sm text-muted-foreground">
                주요 관직 장수가 없습니다.
              </p>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex justify-between">
      <span className="text-muted-foreground">{label}</span>
      <span>{value}</span>
    </div>
  );
}
