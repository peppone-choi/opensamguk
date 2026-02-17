"use client";

import { useEffect, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { Crown } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";

const LEVEL_LABELS: Record<number, string> = {
  0: "주자사",
  1: "주목",
  2: "자사",
  3: "목",
  4: "공",
  5: "왕",
  6: "왕",
  7: "황제",
};

function getLevelLabel(level: number): string {
  return LEVEL_LABELS[level] ?? `레벨 ${level}`;
}

export default function DynastyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const nationDetails = useMemo(() => {
    return nations.map((nation) => {
      const ruler = generals.find((g) => g.id === nation.chiefGeneralId);
      const nationGenerals = generals.filter((g) => g.nationId === nation.id);
      const nationCities = cities.filter((c) => c.nationId === nation.id);
      const totalPop = nationCities.reduce((sum, c) => sum + c.pop, 0);
      return {
        nation,
        ruler,
        generalCount: nationGenerals.length,
        cityCount: nationCities.length,
        totalPop,
      };
    });
  }, [nations, generals, cities]);

  const sorted = useMemo(
    () => [...nationDetails].sort((a, b) => b.nation.power - a.nation.power),
    [nationDetails],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Crown} title="왕조일람" />

      {/* Level progression reference */}
      <Card>
        <CardHeader className="py-2 px-4">
          <CardTitle className="text-sm">작위 등급</CardTitle>
        </CardHeader>
        <CardContent className="py-2 px-4">
          <div className="flex flex-wrap gap-1.5 text-xs text-muted-foreground">
            {["주자사", "주목", "자사", "목", "공", "왕", "황제"].map(
              (label, i, arr) => (
                <span key={label}>
                  {label}
                  {i < arr.length - 1 && (
                    <span className="mx-1 text-muted-foreground/50">→</span>
                  )}
                </span>
              ),
            )}
          </div>
        </CardContent>
      </Card>

      {/* Current dynasties */}
      {sorted.length === 0 ? (
        <EmptyState icon={Crown} title="세력이 없습니다." />
      ) : (
        <div className="space-y-3">
          {sorted.map(
            ({ nation, ruler, generalCount, cityCount, totalPop }) => (
              <Card key={nation.id}>
                <CardContent className="flex items-start gap-3 py-3">
                  <GeneralPortrait
                    picture={ruler?.picture}
                    name={ruler?.name ?? "?"}
                    size="md"
                  />
                  <div className="flex-1 min-w-0 space-y-1.5">
                    <div className="flex items-center gap-2 flex-wrap">
                      <NationBadge name={nation.name} color={nation.color} />
                      <Badge variant="secondary">
                        {getLevelLabel(nation.level)}
                      </Badge>
                    </div>
                    <div className="text-xs">
                      <span className="text-muted-foreground">군주:</span>{" "}
                      <span className="font-medium">
                        {ruler?.name ?? "없음"}
                      </span>
                    </div>
                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-0.5 text-xs">
                      <div>
                        <span className="text-muted-foreground">장수:</span>{" "}
                        {generalCount}명
                      </div>
                      <div>
                        <span className="text-muted-foreground">도시:</span>{" "}
                        {cityCount}개
                      </div>
                      <div>
                        <span className="text-muted-foreground">국력:</span>{" "}
                        {nation.power.toLocaleString()}
                      </div>
                      <div>
                        <span className="text-muted-foreground">기술:</span>{" "}
                        {nation.tech.toLocaleString()}
                      </div>
                      <div>
                        <span className="text-muted-foreground">금:</span>{" "}
                        {nation.gold.toLocaleString()}
                      </div>
                      <div>
                        <span className="text-muted-foreground">쌀:</span>{" "}
                        {nation.rice.toLocaleString()}
                      </div>
                      <div>
                        <span className="text-muted-foreground">인구:</span>{" "}
                        {totalPop.toLocaleString()}
                      </div>
                      <div>
                        <span className="text-muted-foreground">건국:</span>{" "}
                        {nation.createdAt?.substring(0, 10) ?? "-"}
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>
            ),
          )}
        </div>
      )}

      {/* Power comparison table */}
      {sorted.length > 0 && (
        <Card>
          <CardHeader className="py-2 px-4">
            <CardTitle className="text-sm">세력 비교</CardTitle>
          </CardHeader>
          <CardContent className="p-0 overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="text-xs">세력</TableHead>
                  <TableHead className="text-xs text-right">작위</TableHead>
                  <TableHead className="text-xs text-right">국력</TableHead>
                  <TableHead className="text-xs text-right">기술</TableHead>
                  <TableHead className="text-xs text-right">장수</TableHead>
                  <TableHead className="text-xs text-right">도시</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sorted.map(({ nation, generalCount, cityCount }) => (
                  <TableRow key={nation.id}>
                    <TableCell className="py-1.5">
                      <NationBadge name={nation.name} color={nation.color} />
                    </TableCell>
                    <TableCell className="text-xs text-right">
                      {getLevelLabel(nation.level)}
                    </TableCell>
                    <TableCell className="text-xs text-right">
                      {nation.power.toLocaleString()}
                    </TableCell>
                    <TableCell className="text-xs text-right">
                      {nation.tech.toLocaleString()}
                    </TableCell>
                    <TableCell className="text-xs text-right">
                      {generalCount}
                    </TableCell>
                    <TableCell className="text-xs text-right">
                      {cityCount}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
