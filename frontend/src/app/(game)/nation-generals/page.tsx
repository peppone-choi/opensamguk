"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi, nationApi } from "@/lib/gameApi";
import type { General, Nation } from "@/types";
import { Users } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { ErrorState } from "@/components/game/error-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { formatOfficerLevelText, CREW_TYPE_NAMES } from "@/lib/game-utils";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export default function NationGeneralsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral } = useGeneralStore();
  const [generals, setGenerals] = useState<General[]>([]);
  const [nation, setNation] = useState<Nation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const fetchData = useCallback(() => {
    if (!myGeneral?.nationId) return;
    setLoading(true);
    setError(false);
    Promise.all([
      generalApi.listByNation(myGeneral.nationId),
      nationApi.get(myGeneral.nationId),
    ])
      .then(([gRes, nRes]) => {
        setGenerals(gRes.data);
        setNation(nRes.data);
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  if (!currentWorld || !myGeneral)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (error) return <ErrorState title="세력 장수 정보를 불러오지 못했습니다." onRetry={fetchData} />;

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={Users} title="세력장수" />
      {nation && (
        <p className="text-sm text-muted-foreground">
          <span
            className="inline-block size-3 rounded-full mr-1 align-middle"
            style={{ backgroundColor: nation.color }}
          />
          {nation.name} 소속 장수 ({generals.length}명)
        </p>
      )}

      {generals.length === 0 ? (
        <EmptyState icon={Users} title="소속 장수가 없습니다." />
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>관직</TableHead>
              <TableHead>통솔</TableHead>
              <TableHead>무력</TableHead>
              <TableHead>지력</TableHead>
              <TableHead>병종</TableHead>
              <TableHead>병력</TableHead>
              <TableHead>상태</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {generals.map((g) => (
              <TableRow key={g.id}>
                <TableCell className="font-medium">
                  <Link href={`/generals/${g.id}`} className="flex items-center gap-2 hover:underline">
                    <GeneralPortrait
                      picture={g.picture}
                      name={g.name}
                      size="sm"
                    />
                    {g.name}
                  </Link>
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">{formatOfficerLevelText(g.officerLevel, nation?.level)}</TableCell>
                <TableCell>{g.leadership}</TableCell>
                <TableCell>{g.strength}</TableCell>
                <TableCell>{g.intel}</TableCell>
                <TableCell className="text-xs">{CREW_TYPE_NAMES[g.crewType] ?? `${g.crewType}`}</TableCell>
                <TableCell>{g.crew.toLocaleString()}</TableCell>
                <TableCell>
                  {g.npcState > 0 ? (
                    <Badge variant="secondary">NPC</Badge>
                  ) : (
                    <Badge variant="outline">플레이어</Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
