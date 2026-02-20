"use client";

import { useEffect, useState } from "react";
import { Handshake } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
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
import type { Diplomacy } from "@/types";
import { toast } from "sonner";

const stateLabel = (s: string) => {
  switch (s) {
    case "ally":
      return "동맹";
    case "war":
      return "전쟁";
    case "neutral":
      return "중립";
    default:
      return s;
  }
};

const stateVariant = (s: string) => {
  switch (s) {
    case "ally":
      return "default" as const;
    case "war":
      return "destructive" as const;
    default:
      return "secondary" as const;
  }
};

export default function AdminDiplomacyPage() {
  const [diplomacy, setDiplomacy] = useState<Diplomacy[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi
      .getDiplomacy()
      .then((res) => {
        setDiplomacy(res.data);
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4">
      <PageHeader icon={Handshake} title="외교 현황" />
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ID</TableHead>
            <TableHead>국가 A</TableHead>
            <TableHead>국가 B</TableHead>
            <TableHead>상태</TableHead>
            <TableHead>기간</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {diplomacy.map((d) => (
            <TableRow key={d.id}>
              <TableCell>{d.id}</TableCell>
              <TableCell>{d.srcNationId}</TableCell>
              <TableCell>{d.destNationId}</TableCell>
              <TableCell>
                <Badge variant={stateVariant(d.stateCode)}>
                  {stateLabel(d.stateCode)}
                </Badge>
              </TableCell>
              <TableCell>{d.term}개월</TableCell>
            </TableRow>
          ))}
          {diplomacy.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={5}
                className="text-center text-muted-foreground"
              >
                외교 관계가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
