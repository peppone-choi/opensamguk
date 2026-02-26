"use client";

import { Suspense, useState, useEffect, useCallback } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { accountApi } from "@/lib/gameApi";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import type { OAuthProviderInfo } from "@/types";
import {
  Link2,
  Unlink,
  Loader2,
  ShieldAlert,
  AlertTriangle,
  X,
  Upload,
  Trash2,
  RefreshCw,
  Info,
} from "lucide-react";

const OAUTH_PROVIDERS = [
  { id: "kakao", name: "카카오", color: "#FEE500", textColor: "#000" },
  { id: "google", name: "구글", color: "#4285F4", textColor: "#fff" },
  { id: "naver", name: "네이버", color: "#03C75A", textColor: "#fff" },
] as const;

function AccountPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const user = useAuthStore((s) => s.user);

  // Password change
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [pwMsg, setPwMsg] = useState("");
  const [saving, setSaving] = useState(false);

  // Vacation
  const [vacationLoading, setVacationLoading] = useState(false);
  const [vacationMsg, setVacationMsg] = useState("");

  // Profile picture
  const [pictureUrl, setPictureUrl] = useState("");
  const [pictureLoading, setPictureLoading] = useState(false);
  const [pictureMsg, setPictureMsg] = useState("");

  // OAuth
  const [oauthProviders, setOauthProviders] = useState<OAuthProviderInfo[]>([]);
  const [oauthLoading, setOauthLoading] = useState(true);
  const [oauthActionLoading, setOauthActionLoading] = useState<string | null>(
    null,
  );
  const [oauthMsg, setOauthMsg] = useState("");

  // Icon file upload
  const [iconFile, setIconFile] = useState<File | null>(null);
  const [iconPreview, setIconPreview] = useState<string | null>(null);
  const [iconUploading, setIconUploading] = useState(false);
  const [iconMsg, setIconMsg] = useState("");

  // Icon sync modal
  const [showIconSync, setShowIconSync] = useState(false);
  const [iconSyncLoading, setIconSyncLoading] = useState(false);

  // Third-party consent withdrawal
  const [thirdUseStatus, setThirdUseStatus] = useState<boolean>(
    user?.thirdUse ?? false,
  );
  const [thirdUseLoading, setThirdUseLoading] = useState(false);

  // Detailed user info
  const [detailedInfo, setDetailedInfo] = useState<Record<
    string,
    unknown
  > | null>(null);

  // Account deletion modal
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [deletePassword, setDeletePassword] = useState("");
  const [deleteConfirmText, setDeleteConfirmText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");

  const fetchOAuthProviders = useCallback(async () => {
    setOauthLoading(true);
    try {
      const { data } = await accountApi.getOAuthProviders();
      setOauthProviders(data);
    } catch {
      setOauthProviders(user?.oauthProviders ?? []);
    } finally {
      setOauthLoading(false);
    }
  }, [user?.oauthProviders]);

  useEffect(() => {
    fetchOAuthProviders();
    // Fetch detailed user info (legacy parity: user_info.php)
    accountApi
      .getDetailedInfo()
      .then(({ data }) => {
        setDetailedInfo(data);
        if (typeof data.thirdUse === "boolean")
          setThirdUseStatus(data.thirdUse);
      })
      .catch(() => {});
  }, [fetchOAuthProviders]);

  useEffect(() => {
    const oauthStatus = searchParams.get("oauth");
    if (!oauthStatus) return;

    if (oauthStatus === "linked") {
      setOauthMsg("소셜 계정 연동이 완료되었습니다.");
      void fetchOAuthProviders();
    } else if (oauthStatus === "link_failed") {
      setOauthMsg("소셜 계정 연동에 실패했습니다. 다시 시도해주세요.");
    }

    router.replace("/account");
  }, [fetchOAuthProviders, router, searchParams]);

  const handleChangePassword = async () => {
    if (newPassword !== confirmPassword) {
      setPwMsg("새 비밀번호가 일치하지 않습니다.");
      return;
    }
    if (newPassword.length < 6) {
      setPwMsg("비밀번호는 6자 이상이어야 합니다.");
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

  const handleIconFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setIconFile(file);
    const reader = new FileReader();
    reader.onload = () => setIconPreview(reader.result as string);
    reader.readAsDataURL(file);
  };

  const handleIconUpload = async () => {
    if (!iconFile) return;
    setIconUploading(true);
    setIconMsg("");
    try {
      const formData = new FormData();
      formData.append("icon", iconFile);
      await accountApi.uploadIcon(formData);
      setIconMsg("전콘이 업로드되었습니다.");
      setIconFile(null);
    } catch {
      setIconMsg("전콘 업로드에 실패했습니다.");
    } finally {
      setIconUploading(false);
    }
  };

  const handleIconDelete = async () => {
    if (!confirm("전콘을 삭제하시겠습니까?")) return;
    try {
      await accountApi.deleteIcon();
      setIconMsg("전콘이 삭제되었습니다.");
      setIconPreview(null);
    } catch {
      setIconMsg("전콘 삭제에 실패했습니다.");
    }
  };

  const handleIconSync = async () => {
    setIconSyncLoading(true);
    try {
      await accountApi.syncIcon();
      setShowIconSync(false);
      setIconMsg("모든 서버에 전콘이 동기화되었습니다.");
    } catch {
      setIconMsg("전콘 동기화에 실패했습니다.");
    } finally {
      setIconSyncLoading(false);
    }
  };

  const handleThirdUseWithdraw = async () => {
    if (!confirm("제3자 제공 동의를 철회하시겠습니까?")) return;
    setThirdUseLoading(true);
    try {
      await accountApi.updateSettings({
        thirdUse: false,
      } as Record<string, unknown> & import("@/types").AccountSettings);
      setThirdUseStatus(false);
    } catch {
      // ignore
    } finally {
      setThirdUseLoading(false);
    }
  };

  const handleChangePicture = async () => {
    if (!pictureUrl.trim()) return;
    setPictureLoading(true);
    setPictureMsg("");
    try {
      await accountApi.updateSettings({
        picture: pictureUrl.trim(),
      } as Record<string, unknown> & import("@/types").AccountSettings);
      setPictureMsg("전콘이 변경되었습니다.");
    } catch {
      setPictureMsg("전콘 변경에 실패했습니다.");
    } finally {
      setPictureLoading(false);
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

  const withOAuthLinkMode = (redirectUrl: string, provider: string) => {
    try {
      const parsed = new URL(redirectUrl, window.location.origin);
      if (!parsed.searchParams.get("mode"))
        parsed.searchParams.set("mode", "link");
      if (!parsed.searchParams.get("provider"))
        parsed.searchParams.set("provider", provider);
      return parsed.toString();
    } catch {
      return redirectUrl;
    }
  };

  const handleLinkOAuth = async (provider: string) => {
    setOauthActionLoading(provider);
    setOauthMsg("");
    try {
      const { data } = await accountApi.linkOAuth(provider);
      if (data.redirectUrl) {
        window.location.href = withOAuthLinkMode(data.redirectUrl, provider);
      } else {
        setOauthMsg("연동 요청이 처리되었습니다.");
        await fetchOAuthProviders();
      }
    } catch {
      setOauthMsg("연동에 실패했습니다. 다시 시도해주세요.");
    } finally {
      setOauthActionLoading(null);
    }
  };

  const handleUnlinkOAuth = async (provider: string) => {
    if (!confirm(`${provider} 연동을 해제하시겠습니까?`)) return;
    setOauthActionLoading(provider);
    setOauthMsg("");
    try {
      await accountApi.unlinkOAuth(provider);
      setOauthMsg("연동이 해제되었습니다.");
      await fetchOAuthProviders();
    } catch {
      setOauthMsg("연동 해제에 실패했습니다.");
    } finally {
      setOauthActionLoading(null);
    }
  };

  const handleDeleteAccount = async () => {
    if (deleteConfirmText !== "탈퇴합니다") {
      setDeleteError("'탈퇴합니다'를 정확히 입력해주세요.");
      return;
    }
    setDeleting(true);
    setDeleteError("");
    try {
      await accountApi.deleteAccount(deletePassword || undefined);
      localStorage.removeItem("token");
      useAuthStore.getState().logout();
      router.push("/login");
    } catch {
      setDeleteError("계정 삭제에 실패했습니다. 비밀번호를 확인해주세요.");
      setDeleting(false);
    }
  };

  const linkedProviderIds = new Set(oauthProviders.map((p) => p.provider));

  return (
    <>
      <Card className="w-full max-w-md p-8">
        <CardContent className="space-y-6 p-0">
          <h1 className="text-xl font-bold">계정 관리</h1>

          {/* Detailed Profile (legacy parity: user_info.php) */}
          <div className="space-y-2 rounded-md border p-3">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-muted-foreground flex items-center gap-1">
                <Info className="size-4" />
                프로필
              </h2>
              <Badge variant="outline">{user?.role ?? "USER"}</Badge>
            </div>
            <div className="text-sm space-y-1">
              <p>
                <span className="text-muted-foreground">계정 ID:</span>{" "}
                {user?.loginId ?? "-"}
              </p>
              <p>
                <span className="text-muted-foreground">표시 이름:</span>{" "}
                {user?.displayName ?? "-"}
              </p>
              {detailedInfo && (
                <>
                  <p>
                    <span className="text-muted-foreground">등급:</span>{" "}
                    {String(detailedInfo.grade ?? "-")}
                  </p>
                  <p>
                    <span className="text-muted-foreground">ACL:</span>{" "}
                    {String(detailedInfo.acl ?? "-")}
                  </p>
                  <p>
                    <span className="text-muted-foreground">가입일:</span>{" "}
                    {detailedInfo.joinDate
                      ? new Date(
                          String(detailedInfo.joinDate),
                        ).toLocaleDateString("ko-KR")
                      : "-"}
                  </p>
                  <p>
                    <span className="text-muted-foreground">
                      제3자 제공 동의:
                    </span>{" "}
                    <span
                      className={
                        thirdUseStatus
                          ? "text-green-400"
                          : "text-muted-foreground"
                      }
                    >
                      {thirdUseStatus ? "동의" : "미동의"}
                    </span>
                  </p>
                  {detailedInfo.oauthType && (
                    <p>
                      <span className="text-muted-foreground">OAuth 유형:</span>{" "}
                      {String(detailedInfo.oauthType)}
                    </p>
                  )}
                  {detailedInfo.tokenValidUntil && (
                    <p>
                      <span className="text-muted-foreground">
                        토큰 유효기간:
                      </span>{" "}
                      {new Date(
                        String(detailedInfo.tokenValidUntil),
                      ).toLocaleString("ko-KR")}
                    </p>
                  )}
                </>
              )}
            </div>
          </div>

          {/* Third-party consent withdrawal */}
          {thirdUseStatus && (
            <div className="space-y-2 rounded-md border border-yellow-500/30 p-3">
              <h2 className="text-sm font-semibold text-muted-foreground">
                제3자 제공 동의 철회
              </h2>
              <p className="text-xs text-muted-foreground">
                개인정보 제3자 제공 동의를 철회합니다.
              </p>
              <Button
                size="sm"
                variant="outline"
                onClick={handleThirdUseWithdraw}
                disabled={thirdUseLoading}
              >
                {thirdUseLoading ? "처리 중..." : "동의 철회"}
              </Button>
            </div>
          )}

          {/* Profile Picture (전콘) */}
          <div className="space-y-2 rounded-md border p-3">
            <h2 className="text-sm font-semibold text-muted-foreground">
              전콘 (프로필 이미지)
            </h2>
            <p className="text-xs text-muted-foreground">
              장수 생성 시 사용되는 프로필 이미지를 변경할 수 있습니다.
            </p>
            <div className="flex items-center gap-3">
              <div className="size-16 rounded border border-input bg-muted flex items-center justify-center text-xs text-muted-foreground overflow-hidden">
                {iconPreview ? (
                  <img
                    src={iconPreview}
                    alt="전콘 미리보기"
                    className="size-full object-cover"
                  />
                ) : user?.picture ? (
                  <img
                    src={user.picture}
                    alt="전콘"
                    className="size-full object-cover"
                  />
                ) : (
                  "없음"
                )}
              </div>
              <div className="space-y-2 flex-1">
                {/* File upload */}
                <div className="space-y-1">
                  <label
                    htmlFor="icon-upload-input"
                    className="text-xs text-muted-foreground"
                  >
                    파일 업로드
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      id="icon-upload-input"
                      type="file"
                      accept="image/*"
                      onChange={handleIconFileChange}
                      className="text-xs file:mr-2 file:py-1 file:px-2 file:border file:border-input file:rounded file:text-xs file:bg-background"
                    />
                    <Button
                      size="sm"
                      onClick={handleIconUpload}
                      disabled={iconUploading || !iconFile}
                    >
                      <Upload className="size-3 mr-1" />
                      {iconUploading ? "업로드 중..." : "업로드"}
                    </Button>
                  </div>
                </div>
                {/* URL input */}
                <div className="flex items-center gap-2">
                  <Input
                    type="text"
                    placeholder="이미지 URL 입력"
                    value={pictureUrl}
                    onChange={(e) => setPictureUrl(e.target.value)}
                    className="flex-1"
                  />
                  <Button
                    size="sm"
                    onClick={handleChangePicture}
                    disabled={pictureLoading || !pictureUrl.trim()}
                  >
                    {pictureLoading ? "변경 중..." : "URL 변경"}
                  </Button>
                </div>
                {/* Delete + Sync buttons */}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={handleIconDelete}
                  >
                    <Trash2 className="size-3 mr-1" /> 전콘 삭제
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setShowIconSync(true)}
                  >
                    <RefreshCw className="size-3 mr-1" /> 서버 동기화
                  </Button>
                </div>
                {(pictureMsg || iconMsg) && (
                  <p
                    className={`text-xs ${(pictureMsg || iconMsg).includes("실패") ? "text-red-400" : "text-green-400"}`}
                  >
                    {iconMsg || pictureMsg}
                  </p>
                )}
              </div>
            </div>
          </div>

          {/* OAuth 연동 */}
          <div className="space-y-3 rounded-md border p-3">
            <h2 className="text-sm font-semibold text-muted-foreground flex items-center gap-1">
              <Link2 className="size-4" />
              OAuth 토큰 연동
            </h2>
            <p className="text-xs text-muted-foreground">
              소셜 계정을 연동하여 간편 로그인을 사용할 수 있습니다.
            </p>

            {oauthLoading ? (
              <div className="flex items-center gap-2 text-xs text-muted-foreground py-2">
                <Loader2 className="size-3 animate-spin" />
                연동 정보 로딩 중...
              </div>
            ) : (
              <div className="space-y-2">
                {OAUTH_PROVIDERS.map((provider) => {
                  const isLinked = linkedProviderIds.has(provider.id);
                  const linkedInfo = oauthProviders.find(
                    (p) => p.provider === provider.id,
                  );
                  const isActionLoading = oauthActionLoading === provider.id;

                  return (
                    <div
                      key={provider.id}
                      className="flex items-center justify-between rounded-md border px-3 py-2"
                    >
                      <div className="flex items-center gap-2">
                        <span
                          className="inline-flex items-center justify-center size-6 rounded text-xs font-bold"
                          style={{
                            backgroundColor: provider.color,
                            color: provider.textColor,
                          }}
                        >
                          {provider.name[0]}
                        </span>
                        <div>
                          <span className="text-sm font-medium">
                            {provider.name}
                          </span>
                          {isLinked && linkedInfo && (
                            <p className="text-[10px] text-muted-foreground">
                              연동됨 · {linkedInfo.linkedAt.substring(0, 10)}
                            </p>
                          )}
                        </div>
                      </div>
                      {isLinked ? (
                        <Button
                          size="sm"
                          variant="outline"
                          className="text-xs h-7"
                          onClick={() => handleUnlinkOAuth(provider.id)}
                          disabled={isActionLoading}
                        >
                          {isActionLoading ? (
                            <Loader2 className="size-3 animate-spin" />
                          ) : (
                            <>
                              <Unlink className="size-3 mr-1" />
                              해제
                            </>
                          )}
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          className="text-xs h-7"
                          onClick={() => handleLinkOAuth(provider.id)}
                          disabled={isActionLoading}
                        >
                          {isActionLoading ? (
                            <Loader2 className="size-3 animate-spin" />
                          ) : (
                            <>
                              <Link2 className="size-3 mr-1" />
                              연동
                            </>
                          )}
                        </Button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {oauthMsg && (
              <p
                className={`text-xs ${oauthMsg.includes("실패") ? "text-red-400" : "text-green-400"}`}
              >
                {oauthMsg}
              </p>
            )}
          </div>

          {/* 휴가 모드 */}
          <div className="space-y-2 rounded-md border p-3">
            <h2 className="text-sm font-semibold text-muted-foreground">
              휴가 모드
            </h2>
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

          {/* 비밀번호 변경 */}
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

          {/* 계정 삭제 (탈퇴) */}
          <div className="space-y-3">
            <h2 className="text-sm font-semibold text-red-400 flex items-center gap-1">
              <ShieldAlert className="size-4" />
              계정 삭제 (탈퇴)
            </h2>
            <p className="text-xs text-muted-foreground">
              계정을 삭제하면 모든 데이터가 영구적으로 제거됩니다. 이 작업은
              되돌릴 수 없습니다.
            </p>
            <Button
              variant="destructive"
              onClick={() => setShowDeleteModal(true)}
            >
              계정 삭제
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 전콘 서버 동기화 모달 */}
      {showIconSync && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="relative w-full max-w-sm mx-4 rounded-lg border bg-background p-6 shadow-xl">
            <button
              type="button"
              className="absolute top-3 right-3 text-muted-foreground hover:text-foreground"
              onClick={() => setShowIconSync(false)}
            >
              <X className="size-4" />
            </button>
            <div className="space-y-4">
              <h3 className="text-lg font-bold flex items-center gap-2">
                <RefreshCw className="size-5" />
                전콘 서버 동기화
              </h3>
              <p className="text-sm text-muted-foreground">
                현재 설정된 전콘을 모든 서버의 장수에 동기화합니다. 각 서버에서
                사용 중인 전콘이 모두 현재 전콘으로 변경됩니다.
              </p>
              <div className="flex gap-2">
                <Button
                  className="flex-1"
                  onClick={handleIconSync}
                  disabled={iconSyncLoading}
                >
                  {iconSyncLoading ? (
                    <>
                      <Loader2 className="size-3 animate-spin mr-1" />
                      동기화 중...
                    </>
                  ) : (
                    "동기화 실행"
                  )}
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => setShowIconSync(false)}
                >
                  취소
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 탈퇴 확인 모달 */}
      {showDeleteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="relative w-full max-w-sm mx-4 rounded-lg border border-red-500/30 bg-background p-6 shadow-xl">
            <button
              type="button"
              className="absolute top-3 right-3 text-muted-foreground hover:text-foreground"
              onClick={() => {
                setShowDeleteModal(false);
                setDeletePassword("");
                setDeleteConfirmText("");
                setDeleteError("");
              }}
            >
              <X className="size-4" />
            </button>

            <div className="space-y-4">
              <div className="flex items-center gap-2 text-red-400">
                <AlertTriangle className="size-5" />
                <h3 className="text-lg font-bold">계정 삭제 확인</h3>
              </div>

              <div className="rounded-md border border-red-500/20 bg-red-500/5 p-3 space-y-2">
                <p className="text-xs text-red-300 font-semibold">
                  ⚠️ 주의: 이 작업은 되돌릴 수 없습니다!
                </p>
                <ul className="text-xs text-muted-foreground space-y-1 list-disc list-inside">
                  <li>모든 장수 데이터가 삭제됩니다.</li>
                  <li>게임 기록 및 전적이 모두 사라집니다.</li>
                  <li>연동된 소셜 계정이 모두 해제됩니다.</li>
                  <li>동일 아이디로 재가입이 불가능할 수 있습니다.</li>
                </ul>
              </div>

              <div className="space-y-2">
                <label
                  htmlFor="delete-password-input"
                  className="text-xs text-muted-foreground"
                >
                  비밀번호를 입력하세요
                </label>
                <Input
                  id="delete-password-input"
                  type="password"
                  placeholder="비밀번호"
                  value={deletePassword}
                  onChange={(e) => setDeletePassword(e.target.value)}
                />
              </div>

              <div className="space-y-2">
                <label
                  htmlFor="delete-confirm-input"
                  className="text-xs text-muted-foreground"
                >
                  확인을 위해{" "}
                  <span className="font-bold text-red-400">탈퇴합니다</span>를
                  입력하세요
                </label>
                <Input
                  id="delete-confirm-input"
                  type="text"
                  placeholder="탈퇴합니다"
                  value={deleteConfirmText}
                  onChange={(e) => setDeleteConfirmText(e.target.value)}
                />
              </div>

              {deleteError && (
                <p className="text-xs text-red-400">{deleteError}</p>
              )}

              <div className="flex gap-2">
                <Button
                  variant="destructive"
                  className="flex-1"
                  onClick={handleDeleteAccount}
                  disabled={
                    deleting ||
                    !deletePassword ||
                    deleteConfirmText !== "탈퇴합니다"
                  }
                >
                  {deleting ? (
                    <>
                      <Loader2 className="size-3 animate-spin mr-1" />
                      삭제 중...
                    </>
                  ) : (
                    "영구 삭제"
                  )}
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => {
                    setShowDeleteModal(false);
                    setDeletePassword("");
                    setDeleteConfirmText("");
                    setDeleteError("");
                  }}
                  disabled={deleting}
                >
                  취소
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export default function AccountPage() {
  return (
    <Suspense
      fallback={
        <div className="w-full max-w-md p-8 text-sm text-muted-foreground">
          계정 정보를 불러오는 중...
        </div>
      }
    >
      <AccountPageContent />
    </Suspense>
  );
}
