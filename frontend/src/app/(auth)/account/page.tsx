"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { accountApi } from "@/lib/gameApi";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";

export default function AccountPage() {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [pwMsg, setPwMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

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

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold">계정 관리</h1>

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
    </div>
  );
}
