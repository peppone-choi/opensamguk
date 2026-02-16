"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi, nationApi } from "@/lib/gameApi";
import type { General, Nation } from "@/types";
import { Users } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
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

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    Promise.all([
      generalApi.listByNation(myGeneral.nationId),
      nationApi.get(myGeneral.nationId),
    ])
      .then(([gRes, nRes]) => {
        setGenerals(gRes.data);
        setNation(nRes.data);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  if (!currentWorld || !myGeneral)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

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
              <TableHead>통솔</TableHead>
              <TableHead>무력</TableHead>
              <TableHead>지력</TableHead>
              <TableHead>정치</TableHead>
              <TableHead>매력</TableHead>
              <TableHead>병력</TableHead>
              <TableHead>상태</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {generals.map((g) => (
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
                <TableCell>{g.leadership}</TableCell>
                <TableCell>{g.strength}</TableCell>
                <TableCell>{g.intel}</TableCell>
                <TableCell>{g.politics}</TableCell>
                <TableCell>{g.charm}</TableCell>
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
