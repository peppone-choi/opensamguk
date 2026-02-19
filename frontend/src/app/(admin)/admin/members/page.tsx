"use client";

import { useCallback, useEffect, useState } from "react";
import { Users, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { AdminGeneral } from "@/types";

export default function AdminMembersPage() {
  const [generals, setGenerals] = useState<AdminGeneral[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");

  const load = useCallback(() => {
    adminApi.listGenerals().then((res) => {
      setGenerals(res.data);
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const doAction = async (id: number, type: string) => {
    try {
      await adminApi.generalAction(id, type);
      toast.success(`${type} 완료`);
      load();
    } catch {
      toast.error("실패");
    }
  };

  const filtered = search
    ? generals.filter((g) =>
        g.name.toLowerCase().includes(search.toLowerCase()),
      )
    : generals;

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4">
      <PageHeader icon={Users} title="장수 관리" />
      <div className="relative w-64">
        <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
        <Input
          placeholder="장수 검색..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-8"
        />
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ID</TableHead>
            <TableHead>이름</TableHead>
            <TableHead>국가</TableHead>
            <TableHead>병사</TableHead>
            <TableHead>레벨</TableHead>
            <TableHead>NPC</TableHead>
            <TableHead>상태</TableHead>
            <TableHead>액션</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.map((g) => (
            <TableRow key={g.id}>
              <TableCell>{g.id}</TableCell>
              <TableCell className="font-medium">{g.name}</TableCell>
              <TableCell>{g.nationId || "-"}</TableCell>
              <TableCell>{g.crew.toLocaleString()}</TableCell>
              <TableCell>{g.experience}</TableCell>
              <TableCell>
                {g.npcState > 0 ? <Badge variant="secondary">NPC</Badge> : "-"}
              </TableCell>
              <TableCell>
                {g.blockState > 0 ? (
                  <Badge variant="destructive">차단</Badge>
                ) : (
                  <Badge variant="outline">정상</Badge>
                )}
              </TableCell>
              <TableCell className="space-x-1">
                {g.blockState > 0 ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => doAction(g.id, "unblock")}
                  >
                    해제
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => doAction(g.id, "block")}
                  >
                    차단
                  </Button>
                )}
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={() => doAction(g.id, "kill")}
                >
                  처단
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {filtered.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={8}
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
