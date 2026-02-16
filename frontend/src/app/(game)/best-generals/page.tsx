"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { rankingApi } from "@/lib/gameApi";
import type { General } from "@/types";
import { Medal } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

type StatKey =
  | "leadership"
  | "strength"
  | "intel"
  | "politics"
  | "charm"
  | "total";

const TABS: { key: StatKey; label: string }[] = [
  { key: "leadership", label: "통솔" },
  { key: "strength", label: "무력" },
  { key: "intel", label: "지력" },
  { key: "politics", label: "정치" },
  { key: "charm", label: "매력" },
  { key: "total", label: "총합" },
];

export default function BestGeneralsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, loadAll } = useGameStore();
  const [tab, setTab] = useState<StatKey>("total");
  const [rankedGenerals, setRankedGenerals] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  useEffect(() => {
    if (!currentWorld) return;
    const sortBy = tab === "total" ? "experience" : tab;
    rankingApi
      .bestGenerals(currentWorld.id, sortBy, 50)
      .then(({ data }) => setRankedGenerals(data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [currentWorld, tab]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const ranked = useMemo(() => {
    return rankedGenerals.map((g) => ({
      ...g,
      total: g.leadership + g.strength + g.intel + g.politics + g.charm,
    }));
  }, [rankedGenerals]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading)
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Medal} title="장수 랭킹" />

      <Tabs value={tab} onValueChange={(v) => setTab(v as StatKey)}>
        <TabsList>
          {TABS.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-10">#</TableHead>
            <TableHead>이름</TableHead>
            <TableHead>소속</TableHead>
            <TableHead>통솔</TableHead>
            <TableHead>무력</TableHead>
            <TableHead>지력</TableHead>
            <TableHead>정치</TableHead>
            <TableHead>매력</TableHead>
            <TableHead>총합</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {ranked.map((g, idx) => {
            const nation = nationMap.get(g.nationId);
            return (
              <TableRow key={g.id}>
                <TableCell>
                  <Badge variant="outline">#{idx + 1}</Badge>
                </TableCell>
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
                <TableCell>
                  <NationBadge name={nation?.name} color={nation?.color} />
                </TableCell>
                <TableCell
                  className={
                    tab === "leadership" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.leadership}
                </TableCell>
                <TableCell
                  className={
                    tab === "strength" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.strength}
                </TableCell>
                <TableCell
                  className={
                    tab === "intel" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.intel}
                </TableCell>
                <TableCell
                  className={
                    tab === "politics" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.politics}
                </TableCell>
                <TableCell
                  className={
                    tab === "charm" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.charm}
                </TableCell>
                <TableCell
                  className={
                    tab === "total" ? "text-amber-400 font-medium" : ""
                  }
                >
                  {g.total}
                </TableCell>
              </TableRow>
            );
          })}
          {ranked.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={9}
                className="text-center text-muted-foreground"
              >
                장수가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
