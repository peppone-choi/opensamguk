"use client";

import { useCallback, useEffect, useState } from "react";
import { UserCog } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import type { AdminUser } from "@/types";

type UserActionPayload = {
  type: "setAdmin" | "removeAdmin" | "delete" | "setGrade";
  grade?: number;
};

const GRADE_LABELS: Record<number, string> = {
  0: "차단",
  1: "일반",
  2: "특수2",
  3: "특수3",
  4: "특수4",
  5: "서버운영",
  6: "시스템운영",
  7: "최고운영",
};

const GRADES = [0, 1, 2, 3, 4, 5, 6, 7] as const;

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [pendingGrades, setPendingGrades] = useState<Record<number, number>>(
    {},
  );
  const [busyUserId, setBusyUserId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    adminApi
      .listUsers()
      .then((res) => {
        setUsers(res.data);
        setPendingGrades(
          Object.fromEntries(res.data.map((user) => [user.id, user.grade])),
        );
      })
      .catch(() => {
        toast.error("전역 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const doAction = async (id: number, action: UserActionPayload) => {
    setBusyUserId(id);
    try {
      await adminApi.userAction(id, action);
      const actionText =
        action.type === "setAdmin"
          ? "관리자 지정"
          : action.type === "removeAdmin"
            ? "관리자 해제"
            : action.type === "setGrade"
              ? `등급 ${action.grade ?? "-"} 적용`
              : "유저 삭제";
      toast.success(`${actionText} 완료`);
      load();
    } catch {
      toast.error("권한 부족 또는 등급 규칙 위반으로 실패했습니다.");
    } finally {
      setBusyUserId(null);
    }
  };

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4">
      <PageHeader icon={UserCog} title="유저 관리" />
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ID</TableHead>
            <TableHead>로그인ID</TableHead>
            <TableHead>닉네임</TableHead>
            <TableHead>권한</TableHead>
            <TableHead>등급</TableHead>
            <TableHead>가입일</TableHead>
            <TableHead>최근 접속</TableHead>
            <TableHead>액션</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {users.map((u) => (
            <TableRow key={u.id}>
              <TableCell>{u.id}</TableCell>
              <TableCell>{u.loginId}</TableCell>
              <TableCell className="font-medium">{u.displayName}</TableCell>
              <TableCell>
                {u.grade >= 5 ? (
                  <Badge variant="destructive">ADMIN</Badge>
                ) : (
                  <Badge variant="outline">USER</Badge>
                )}
              </TableCell>
              <TableCell>
                <Badge variant="outline">
                  {u.grade} ({GRADE_LABELS[u.grade] ?? "기타"})
                </Badge>
              </TableCell>
              <TableCell className="text-muted-foreground">
                {new Date(u.createdAt).toLocaleDateString("ko-KR")}
              </TableCell>
              <TableCell className="text-muted-foreground">
                {u.lastLoginAt
                  ? new Date(u.lastLoginAt).toLocaleDateString("ko-KR")
                  : "-"}
              </TableCell>
              <TableCell className="space-x-1">
                {u.grade >= 5 ? (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyUserId === u.id}
                    onClick={() => doAction(u.id, { type: "removeAdmin" })}
                  >
                    관리자 해제
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyUserId === u.id}
                    onClick={() => doAction(u.id, { type: "setAdmin" })}
                  >
                    관리자 지정
                  </Button>
                )}
                <select
                  className="h-8 rounded-md border border-border bg-background px-2 text-sm"
                  value={pendingGrades[u.id] ?? u.grade}
                  disabled={busyUserId === u.id}
                  onChange={(e) => {
                    const value = Number(e.target.value);
                    setPendingGrades((prev) => ({ ...prev, [u.id]: value }));
                  }}
                >
                  {GRADES.map((grade) => (
                    <option key={grade} value={grade}>
                      {grade} ({GRADE_LABELS[grade] ?? "기타"})
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busyUserId === u.id}
                  onClick={() =>
                    doAction(u.id, {
                      type: "setGrade",
                      grade: pendingGrades[u.id] ?? u.grade,
                    })
                  }
                >
                  등급 적용
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={busyUserId === u.id}
                  onClick={() => doAction(u.id, { type: "delete" })}
                >
                  삭제
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {users.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={8}
                className="text-center text-muted-foreground"
              >
                유저가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
