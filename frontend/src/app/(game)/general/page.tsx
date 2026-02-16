"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { cityApi, nationApi } from "@/lib/gameApi";
import type { City, Nation } from "@/types";
import { User } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";
import { ResourceDisplay } from "@/components/game/resource-display";
import { NationBadge } from "@/components/game/nation-badge";

const EQUIP_LABELS: Record<string, string> = {
  weaponCode: "무기",
  bookCode: "서적",
  horseCode: "군마",
  itemCode: "도구",
};

export default function GeneralPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, loading, fetchMyGeneral } = useGeneralStore();
  const [city, setCity] = useState<City | null>(null);
  const [nation, setNation] = useState<Nation | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id).catch(() =>
      setError("장수 정보를 불러올 수 없습니다."),
    );
  }, [currentWorld, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral) return;
    if (myGeneral.cityId) {
      cityApi
        .get(myGeneral.cityId)
        .then(({ data }) => setCity(data))
        .catch(() => {});
    }
    if (myGeneral.nationId) {
      nationApi
        .get(myGeneral.nationId)
        .then(({ data }) => setNation(data))
        .catch(() => {});
    }
  }, [myGeneral]);

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral) return <LoadingState message="장수 정보가 없습니다." />;

  const g = myGeneral;
  const equipKeys = [
    "weaponCode",
    "bookCode",
    "horseCode",
    "itemCode",
  ] as const;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={User} title="내 장수" />

      {/* Profile */}
      <Card>
        <CardHeader>
          <CardTitle>프로필</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex gap-4 items-start">
            <GeneralPortrait picture={g.picture} name={g.name} size="lg" />
            <div className="space-y-1">
              <p className="text-lg font-semibold">{g.name}</p>
              <p className="text-sm text-muted-foreground">나이 {g.age}세</p>
              {nation && (
                <NationBadge name={nation.name} color={nation.color} />
              )}
              {city && (
                <p className="text-sm text-muted-foreground">
                  위치: {city.name}
                </p>
              )}
              <p className="text-sm text-muted-foreground">
                관직: {g.officerLevel}
              </p>
              {g.injury > 0 && (
                <Badge variant="destructive">부상: {g.injury}</Badge>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 5-stat */}
      <Card>
        <CardHeader>
          <CardTitle>능력치</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <StatBar label="통솔" value={g.leadership} color="bg-red-500" />
          <StatBar label="무력" value={g.strength} color="bg-orange-500" />
          <StatBar label="지력" value={g.intel} color="bg-blue-500" />
          <StatBar label="정치" value={g.politics} color="bg-green-500" />
          <StatBar label="매력" value={g.charm} color="bg-purple-500" />
        </CardContent>
      </Card>

      {/* Resources */}
      <Card>
        <CardHeader>
          <CardTitle>자원</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <ResourceDisplay gold={g.gold} rice={g.rice} crew={g.crew} />
          <div className="flex gap-4 text-sm text-muted-foreground">
            <span>훈련: {g.train}</span>
            <span>사기: {g.atmos}</span>
          </div>
        </CardContent>
      </Card>

      {/* Experience / Dedication */}
      <Card>
        <CardHeader>
          <CardTitle>경험 / 공헌</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <StatBar
            label="경험"
            value={g.experience}
            max={1000}
            color="bg-yellow-500"
          />
          <StatBar
            label="공헌"
            value={g.dedication}
            max={1000}
            color="bg-teal-500"
          />
        </CardContent>
      </Card>

      {/* Equipment */}
      <Card>
        <CardHeader>
          <CardTitle>장비</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            {equipKeys.map((key) => (
              <Badge
                key={key}
                variant={g[key] === "None" ? "outline" : "secondary"}
              >
                {EQUIP_LABELS[key]}: {g[key] === "None" ? "없음" : g[key]}
              </Badge>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Special / Personality */}
      <Card>
        <CardHeader>
          <CardTitle>특기 / 성격</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            <Badge variant={g.specialCode === "None" ? "outline" : "secondary"}>
              특기1: {g.specialCode === "None" ? "없음" : g.specialCode}
            </Badge>
            <Badge
              variant={g.special2Code === "None" ? "outline" : "secondary"}
            >
              특기2: {g.special2Code === "None" ? "없음" : g.special2Code}
            </Badge>
            <Badge variant="secondary">성격: {g.personalCode}</Badge>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
