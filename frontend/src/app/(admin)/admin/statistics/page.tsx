"use client";

import { useEffect, useMemo, useState } from "react";
import { BarChart3 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { adminApi } from "@/lib/gameApi";

interface NationStat {
  nationId: number;
  name: string;
  color: string;
  level: number;
  gold: number;
  rice: number;
  tech: number;
  power: number;
  genCount: number;
  cityCount: number;
  totalCrew: number;
  totalPop: number;
}

type SortKey = keyof Omit<NationStat, "color">;

export default function AdminStatisticsPage() {
  const [stats, setStats] = useState<NationStat[]>([]);
  const [loading, setLoading] = useState(true);
  const [sortKey, setSortKey] = useState<SortKey>("power");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  useEffect(() => {
    adminApi.getStatistics().then((res) => {
      setStats(res.data);
      setLoading(false);
    });
  }, []);

  const sorted = useMemo(() => {
    return [...stats].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "name") {
        cmp = a.name.localeCompare(b.name);
      } else {
        cmp = (a[sortKey] as number) - (b[sortKey] as number);
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [stats, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

  if (loading) return <LoadingState />;

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "국가" },
    { key: "level", label: "레벨" },
    { key: "gold", label: "금" },
    { key: "rice", label: "쌀" },
    { key: "tech", label: "기술" },
    { key: "power", label: "국력" },
    { key: "genCount", label: "장수" },
    { key: "cityCount", label: "도시" },
    { key: "totalCrew", label: "총병력" },
    { key: "totalPop", label: "총인구" },
  ];

  return (
    <div className="space-y-4">
      <PageHeader icon={BarChart3} title="국가 통계" />
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
          {sorted.map((s) => (
            <TableRow
              key={s.nationId}
              style={{ borderLeft: `3px solid ${s.color}` }}
            >
              <TableCell className="font-medium">{s.name}</TableCell>
              <TableCell>{s.level}</TableCell>
              <TableCell>{s.gold.toLocaleString()}</TableCell>
              <TableCell>{s.rice.toLocaleString()}</TableCell>
              <TableCell>{s.tech.toFixed(1)}</TableCell>
              <TableCell>{s.power.toLocaleString()}</TableCell>
              <TableCell>{s.genCount}</TableCell>
              <TableCell>{s.cityCount}</TableCell>
              <TableCell>{s.totalCrew.toLocaleString()}</TableCell>
              <TableCell>{s.totalPop.toLocaleString()}</TableCell>
            </TableRow>
          ))}
          {sorted.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={10}
                className="text-center text-muted-foreground"
              >
                국가가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
