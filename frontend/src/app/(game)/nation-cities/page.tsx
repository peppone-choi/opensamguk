"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { cityApi } from "@/lib/gameApi";
import type { City } from "@/types";
import { Building2 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

type SortKey =
  | "name"
  | "level"
  | "pop"
  | "agri"
  | "comm"
  | "secu"
  | "def"
  | "wall"
  | "supplyState";

export default function NationCitiesPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);

  const [cities, setCities] = useState<City[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() =>
        setError("장수 정보를 불러올 수 없습니다."),
      );
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    cityApi
      .listByNation(myGeneral.nationId)
      .then(({ data }) => setCities(data))
      .catch(() => setError("도시 정보를 불러올 수 없습니다."));
  }, [myGeneral?.nationId]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

  const sorted = [...(cities ?? [])].sort((a, b) => {
    let cmp = 0;
    if (sortKey === "name") {
      cmp = a.name.localeCompare(b.name);
    } else {
      cmp = (a[sortKey] as number) - (b[sortKey] as number);
    }
    return sortDir === "asc" ? cmp : -cmp;
  });

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (myGeneral?.nationId && cities === null) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4">
        <EmptyState
          icon={Building2}
          title="소속 국가가 없습니다."
          description="국가에 가입한 후 이용할 수 있습니다."
        />
      </div>
    );

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "이름" },
    { key: "level", label: "레벨" },
    { key: "pop", label: "인구" },
    { key: "agri", label: "농업" },
    { key: "comm", label: "상업" },
    { key: "secu", label: "치안" },
    { key: "def", label: "수비" },
    { key: "wall", label: "성벽" },
    { key: "supplyState", label: "보급" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Building2} title="세력 도시" />

      {(cities ?? []).length === 0 ? (
        <EmptyState icon={Building2} title="보유 도시가 없습니다." />
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((col) => (
                <TableHead
                  key={col.key}
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => toggleSort(col.key)}
                >
                  {col.label}
                  {arrow(col.key)}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {sorted.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="font-medium">{c.name}</TableCell>
                <TableCell>{c.level}</TableCell>
                <TableCell>
                  {c.pop.toLocaleString()}/{c.popMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  {c.agri.toLocaleString()}/{c.agriMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  {c.comm.toLocaleString()}/{c.commMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  {c.secu.toLocaleString()}/{c.secuMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  {c.def.toLocaleString()}/{c.defMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  {c.wall.toLocaleString()}/{c.wallMax.toLocaleString()}
                </TableCell>
                <TableCell>
                  <Badge
                    variant={c.supplyState === 1 ? "default" : "destructive"}
                  >
                    {c.supplyState === 1 ? "보급" : "단절"}
                  </Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
