"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Eye } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export default function SpyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, nations, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id);
    loadAll(currentWorld.id);
  }, [currentWorld, fetchMyGeneral, loadAll]);

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const spyGenerals = useMemo(() => {
    if (!myGeneral) return [];
    return generals.filter(
      (g) =>
        g.nationId === myGeneral.nationId &&
        g.npcState === 0 &&
        g.permission === "spy",
    );
  }, [generals, myGeneral]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={Eye} title="첩보부" />

      <Card>
        <CardHeader>
          <CardTitle>첩자 목록</CardTitle>
        </CardHeader>
        <CardContent>
          {spyGenerals.length === 0 ? (
            <EmptyState icon={Eye} title="첩자가 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>위치</TableHead>
                  <TableHead>잠입 국가</TableHead>
                  <TableHead>병력</TableHead>
                  <TableHead>상태</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {spyGenerals.map((g) => {
                  const city = cityMap.get(g.cityId);
                  const nation = nationMap.get(g.nationId);
                  return (
                    <TableRow key={g.id}>
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
                      <TableCell>{city?.name ?? "-"}</TableCell>
                      <TableCell>
                        {nation ? (
                          <Badge
                            variant="secondary"
                            style={{ borderColor: nation.color }}
                          >
                            {nation.name}
                          </Badge>
                        ) : (
                          "-"
                        )}
                      </TableCell>
                      <TableCell>{g.crew.toLocaleString()}</TableCell>
                      <TableCell>
                        <Badge variant="outline">활동중</Badge>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>첩보 안내</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground space-y-2">
          <p>
            첩자는 적국에 잠입하여 정보를 수집하거나 파괴 공작을 수행합니다.
          </p>
          <p>첩자 파견은 커맨드에서 실행할 수 있습니다.</p>
        </CardContent>
      </Card>
    </div>
  );
}
