"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { accountApi } from "@/lib/gameApi";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";

export default function AccountPage() {
  const router = useRouter();
  const user = useAuthStore((s) => s.user);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [pwMsg, setPwMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [vacationLoading, setVacationLoading] = useState(false);
  const [vacationMsg, setVacationMsg] = useState("");

  const handleChangePassword = async () => {
    if (newPassword !== confirmPassword) {
      setPwMsg("새 비밀번호가 일치하지 않습니다.");
      return;
    }
    if (newPassword.length < 4) {
      setPwMsg("비밀번호는 4자 이상이어야 합니다.");
      return;
    }
    setSaving(true);
    setPwMsg("");
    try {
      await accountApi.changePassword(currentPassword, newPassword);
      setPwMsg("비밀번호가 변경되었습니다.");
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
    } catch {
      setPwMsg("비밀번호 변경에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!confirmDelete) {
      setConfirmDelete(true);
      return;
    }
    setDeleting(true);
    try {
      await accountApi.deleteAccount();
      localStorage.removeItem("token");
      router.push("/login");
    } catch {
      setDeleting(false);
    }
  };

  const handleToggleVacation = async () => {
    setVacationLoading(true);
    setVacationMsg("");
    try {
      await accountApi.toggleVacation();
      setVacationMsg("휴가 상태를 변경했습니다.");
    } catch {
      setVacationMsg("휴가 상태 변경에 실패했습니다.");
    } finally {
      setVacationLoading(false);
    }
  };

  return (
    <Card className="w-full max-w-md p-8">
      <CardContent className="space-y-6 p-0">
        <h1 className="text-xl font-bold">계정 관리</h1>

        <div className="space-y-2 rounded-md border p-3">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-muted-foreground">
              프로필
            </h2>
            <Badge variant="outline">{user?.role ?? "USER"}</Badge>
          </div>
          <div className="text-sm">
            <p>
              <span className="text-muted-foreground">계정:</span>{" "}
              {user?.loginId ?? "-"}
            </p>
            <p>
              <span className="text-muted-foreground">표시 이름:</span>{" "}
              {user?.displayName ?? "-"}
            </p>
          </div>
        </div>

        <div className="space-y-2 rounded-md border p-3">
          <h2 className="text-sm font-semibold text-muted-foreground">
            OAuth 연동
          </h2>
          <p className="text-xs text-muted-foreground">
            카카오 로그인 연동 상태 표시는 준비 중입니다.
          </p>
        </div>

        <div className="space-y-2 rounded-md border p-3">
          <h2 className="text-sm font-semibold text-muted-foreground">휴가 모드</h2>
          <p className="text-xs text-muted-foreground">
            휴가 모드 토글은 즉시 적용됩니다.
          </p>
          <Button onClick={handleToggleVacation} disabled={vacationLoading}>
            {vacationLoading ? "변경 중..." : "휴가 모드 토글"}
          </Button>
          {vacationMsg && (
            <p
              className={`text-xs ${vacationMsg.includes("실패") ? "text-red-400" : "text-green-400"}`}
            >
              {vacationMsg}
            </p>
          )}
        </div>

        {/* Password Change */}
        <div className="space-y-3">
          <h2 className="text-sm font-semibold text-muted-foreground">
            비밀번호 변경
          </h2>
          <div className="space-y-2">
            <Input
              type="password"
              placeholder="현재 비밀번호"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
            <Input
              type="password"
              placeholder="새 비밀번호"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
            <Input
              type="password"
              placeholder="새 비밀번호 확인"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </div>
          {pwMsg && (
            <p
              className={`text-sm ${pwMsg.includes("변경되었") ? "text-green-400" : "text-red-400"}`}
            >
              {pwMsg}
            </p>
          )}
          <Button
            onClick={handleChangePassword}
            disabled={saving || !currentPassword || !newPassword}
          >
            {saving ? "변경 중..." : "비밀번호 변경"}
          </Button>
        </div>

        <Separator />

        {/* Account Deletion */}
        <div className="space-y-3">
          <h2 className="text-sm font-semibold text-red-400">계정 삭제</h2>
          <p className="text-xs text-muted-foreground">
            계정을 삭제하면 모든 데이터가 영구적으로 제거됩니다.
          </p>
          <Button
            variant="destructive"
            onClick={handleDelete}
            disabled={deleting}
          >
            {deleting
              ? "삭제 중..."
              : confirmDelete
                ? "정말 삭제하시겠습니까?"
                : "계정 삭제"}
          </Button>
          {confirmDelete && !deleting && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setConfirmDelete(false)}
            >
              취소
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
