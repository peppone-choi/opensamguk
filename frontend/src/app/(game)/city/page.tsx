"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { cityApi, generalApi, nationApi } from "@/lib/gameApi";
import type { City, General, Nation } from "@/types";
import { Building2 } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { DevBar } from "@/components/game/dev-bar";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";

const SUPPLY_LABELS: Record<number, string> = {
  0: "정상",
  1: "부족",
  2: "고립",
};
const FRONT_LABELS: Record<number, string> = {
  0: "후방",
  1: "전선",
  2: "최전선",
};

export default function CityPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const { mapData, loadMap, cities: allCities, loadAll } = useGameStore();
  const [city, setCity] = useState<City | null>(null);
  const [nation, setNation] = useState<Nation | null>(null);
  const [generals, setGenerals] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() =>
        setError("장수 정보를 불러올 수 없습니다."),
      );
    }
    const mapCode = (currentWorld.config?.mapCode as string) ?? "che";
    loadMap(mapCode).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadMap, loadAll]);

  useEffect(() => {
    if (!myGeneral?.cityId) return;
    Promise.all([
      cityApi.get(myGeneral.cityId),
      generalApi.listByCity(myGeneral.cityId),
    ])
      .then(([cityRes, gensRes]) => {
        setCity(cityRes.data);
        setGenerals(gensRes.data);
        if (cityRes.data.nationId) {
          nationApi
            .get(cityRes.data.nationId)
            .then(({ data }) => setNation(data))
            .catch(() => {});
        }
      })
      .catch(() => setError("도시 정보를 불러올 수 없습니다."))
      .finally(() => setLoading(false));
  }, [myGeneral?.cityId]);

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!city) return <LoadingState message="도시 정보가 없습니다." />;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Building2} title={city.name} />

      {/* Basic info */}
      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">레벨 {city.level}</Badge>
            <Badge variant="outline">지역: {city.region}</Badge>
            <Badge variant="secondary">
              보급: {SUPPLY_LABELS[city.supplyState] ?? city.supplyState}
            </Badge>
            <Badge variant={city.frontState >= 2 ? "destructive" : "secondary"}>
              전선: {FRONT_LABELS[city.frontState] ?? city.frontState}
            </Badge>
            <Badge variant="outline">교역율: {city.trade}%</Badge>
          </div>
          {nation && (
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">소속:</span>
              <NationBadge name={nation.name} color={nation.color} />
            </div>
          )}
        </CardContent>
      </Card>

      {/* Development */}
      <Card>
        <CardHeader>
          <CardTitle>개발</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <DevBar
            label="농업"
            value={city.agri}
            max={city.agriMax}
            color="bg-green-500"
          />
          <DevBar
            label="상업"
            value={city.comm}
            max={city.commMax}
            color="bg-yellow-500"
          />
          <DevBar
            label="치안"
            value={city.secu}
            max={city.secuMax}
            color="bg-blue-500"
          />
          <DevBar
            label="수비"
            value={city.def}
            max={city.defMax}
            color="bg-red-500"
          />
          <DevBar
            label="성벽"
            value={city.wall}
            max={city.wallMax}
            color="bg-gray-400"
          />
        </CardContent>
      </Card>

      {/* Population & Trust */}
      <Card>
        <CardHeader>
          <CardTitle>인구 / 민심</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <DevBar
            label="인구"
            value={city.pop}
            max={city.popMax}
            color="bg-teal-500"
          />
          <DevBar
            label="민심"
            value={city.trust}
            max={100}
            color="bg-purple-500"
          />
        </CardContent>
      </Card>

      {/* Adjacent cities */}
      <Card>
        <CardHeader>
          <CardTitle>인접 도시</CardTitle>
        </CardHeader>
        <CardContent>
          {(() => {
            const cityConst = mapData?.cities.find((c) => c.name === city.name);
            if (!cityConst)
              return (
                <p className="text-sm text-muted-foreground">
                  맵 데이터를 불러오는 중...
                </p>
              );
            const adjCities = cityConst.connections.map((connId) => {
              const constEntry = mapData?.cities.find((c) => c.id === connId);
              const liveCity = allCities.find(
                (c) => c.name === constEntry?.name,
              );
              return {
                name: constEntry?.name ?? `#${connId}`,
                nationId: liveCity?.nationId,
              };
            });
            if (adjCities.length === 0)
              return (
                <p className="text-sm text-muted-foreground">
                  인접 도시가 없습니다.
                </p>
              );
            return (
              <div className="flex flex-wrap gap-2">
                {adjCities.map((adj) => (
                  <Badge key={adj.name} variant="secondary">
                    {adj.name}
                  </Badge>
                ))}
              </div>
            );
          })()}
        </CardContent>
      </Card>

      {/* Generals in city */}
      <Card>
        <CardHeader>
          <CardTitle>주둔 장수 ({generals.length})</CardTitle>
        </CardHeader>
        <CardContent>
          {generals.length === 0 ? (
            <p className="text-sm text-muted-foreground">장수가 없습니다.</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>병력</TableHead>
                  <TableHead>훈련</TableHead>
                  <TableHead>사기</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {generals.map((gen) => (
                  <TableRow key={gen.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <GeneralPortrait
                          picture={gen.picture}
                          name={gen.name}
                          size="sm"
                        />
                        <span>{gen.name}</span>
                      </div>
                    </TableCell>
                    <TableCell>{gen.crew.toLocaleString()}</TableCell>
                    <TableCell>{gen.train}</TableCell>
                    <TableCell>{gen.atmos}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
