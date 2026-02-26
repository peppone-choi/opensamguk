"use client";

import { useEffect, useState, useCallback } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";
import { useAuthStore } from "@/stores/authStore";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ServerStatusCard } from "@/components/auth/server-status-card";
import { ShieldCheck, X, Map } from "lucide-react";
import { sha512 } from "js-sha512";
import api from "@/lib/api";

const loginSchema = z.object({
  loginId: z.string().min(3, "아이디는 3자 이상이어야 합니다"),
  password: z.string().min(6, "비밀번호는 6자 이상이어야 합니다"),
});

type LoginForm = z.infer<typeof loginSchema>;

/* ── Kakao OAuth helpers ── */
const KAKAO_CLIENT_ID = process.env.NEXT_PUBLIC_KAKAO_CLIENT_ID ?? "";
const KAKAO_REDIRECT_URI =
  typeof window !== "undefined"
    ? `${window.location.origin}/auth/kakao/callback`
    : "";

function startKakaoLogin() {
  if (!KAKAO_CLIENT_ID) {
    toast.error("카카오 로그인이 아직 설정되지 않았습니다.");
    return;
  }
  const url =
    `https://kauth.kakao.com/oauth/authorize` +
    `?client_id=${KAKAO_CLIENT_ID}` +
    `&redirect_uri=${encodeURIComponent(KAKAO_REDIRECT_URI)}` +
    `&response_type=code`;
  window.location.href = url;
}

/* ── OTP Modal ── */
function OtpModal({
  open,
  onClose,
  onSubmit,
  loading,
  validUntil,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (code: string) => void;
  loading: boolean;
  validUntil?: string;
}) {
  const [code, setCode] = useState("");

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <Card className="w-full max-w-sm p-6 relative">
        <button
          type="button"
          className="absolute top-3 right-3 text-muted-foreground hover:text-foreground"
          onClick={onClose}
        >
          <X className="size-4" />
        </button>
        <div className="flex items-center gap-2 mb-4">
          <ShieldCheck className="size-5 text-primary" />
          <h3 className="text-lg font-bold">2차 인증 (OTP)</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4">
          등록된 OTP 앱에서 생성된 6자리 인증 코드를 입력해주세요.
        </p>
        {validUntil && (
          <p className="text-xs text-cyan-400 mb-3">
            {validUntil}까지 유효합니다
          </p>
        )}
        <div className="space-y-3">
          <Input
            type="text"
            inputMode="numeric"
            maxLength={6}
            placeholder="000000"
            value={code}
            onChange={(e) =>
              setCode(e.target.value.replace(/\D/g, "").slice(0, 6))
            }
            className="text-center text-2xl tracking-[0.5em] font-mono"
            autoFocus
            onKeyDown={(e) => {
              if (e.key === "Enter" && code.length === 6) {
                onSubmit(code);
              }
            }}
          />
          <Button
            className="w-full"
            disabled={code.length !== 6 || loading}
            onClick={() => onSubmit(code)}
          >
            {loading ? "인증 중..." : "인증 확인"}
          </Button>
        </div>
      </Card>
    </div>
  );
}

export default function LoginPage() {
  const router = useRouter();
  const login = useAuthStore((s) => s.login);
  const loginWithToken = useAuthStore((s) => s.loginWithToken);
  const loginWithOtp = useAuthStore((s) => s.loginWithOtp);
  const registerUser = useAuthStore((s) => s.register);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const isInitialized = useAuthStore((s) => s.isInitialized);
  const [registerMode, setRegisterMode] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [quickRegistering, setQuickRegistering] = useState(false);
  const [autoLogging, setAutoLogging] = useState(false);

  // OTP state
  const [otpOpen, setOtpOpen] = useState(false);
  const [otpLoading, setOtpLoading] = useState(false);
  const [otpValidUntil, setOtpValidUntil] = useState("");
  const [pendingLoginId, setPendingLoginId] = useState("");
  const [pendingPassword, setPendingPassword] = useState("");

  // Server map
  const [showServerMap, setShowServerMap] = useState(false);
  const serverMapUrl = process.env.NEXT_PUBLIC_SERVER_MAP_URL ?? "";

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
  });

  // Auto-redirect if already authenticated
  useEffect(() => {
    if (isInitialized && isAuthenticated) {
      router.replace("/lobby");
    }
  }, [isInitialized, isAuthenticated, router]);

  // Auto-login from stored token with nonce challenge (legacy parity: ReqNonce → sha512(token+nonce) → LoginByToken)
  const attemptAutoLogin = useCallback(async () => {
    if (!isInitialized || isAuthenticated) return;
    const LOGIN_TOKEN_KEY = "sammo_login_token";
    const raw =
      typeof window !== "undefined"
        ? localStorage.getItem(LOGIN_TOKEN_KEY)
        : null;
    if (!raw) return;

    let tokenId: number;
    let token: string;
    try {
      const parsed = JSON.parse(raw);
      // Format: [version, [tokenId, token], timestamp]
      if (!Array.isArray(parsed) || parsed[0] !== 1) {
        localStorage.removeItem(LOGIN_TOKEN_KEY);
        return;
      }
      [tokenId, token] = parsed[1];
    } catch {
      localStorage.removeItem(LOGIN_TOKEN_KEY);
      return;
    }

    setAutoLogging(true);
    try {
      // Step 1: Request nonce from server
      const { data: nonceData } = await api.post<{
        result: boolean;
        loginNonce?: string;
      }>("/auth/nonce");
      if (!nonceData.result || !nonceData.loginNonce) {
        localStorage.removeItem(LOGIN_TOKEN_KEY);
        return;
      }

      // Step 2: Hash token with nonce
      const hashedToken = sha512(token + nonceData.loginNonce);

      // Step 3: Login by token
      const { data: loginData } = await api.post<{
        result: boolean;
        nextToken?: [number, string];
        reason?: string;
        silent?: boolean;
      }>("/auth/login-by-token", {
        hashedToken,
        token_id: tokenId,
      });

      if (!loginData.result) {
        if (!loginData.silent) {
          console.error(loginData.reason);
        }
        localStorage.removeItem(LOGIN_TOKEN_KEY);
        return;
      }

      // Store next token for future auto-login
      if (loginData.nextToken) {
        localStorage.setItem(
          LOGIN_TOKEN_KEY,
          JSON.stringify([1, loginData.nextToken, Date.now()]),
        );
      }

      // Also try the store's loginWithToken for session setup
      if (loginWithToken) {
        await loginWithToken(token);
      }
      router.push("/lobby");
    } catch {
      localStorage.removeItem(LOGIN_TOKEN_KEY);
    } finally {
      setAutoLogging(false);
    }
  }, [isInitialized, isAuthenticated, loginWithToken, router]);

  useEffect(() => {
    attemptAutoLogin();
  }, [attemptAutoLogin]);

  const onSubmit = async (data: LoginForm) => {
    try {
      const result = await login(data.loginId, data.password);
      // If server signals OTP is required
      if (
        result &&
        typeof result === "object" &&
        "otpRequired" in result &&
        result.otpRequired
      ) {
        setPendingLoginId(data.loginId);
        setPendingPassword(data.password);
        setOtpOpen(true);
        return;
      }
      router.push("/lobby");
    } catch (err: unknown) {
      const errObj = err as {
        response?: { data?: { message?: string; otpRequired?: boolean } };
      };
      if (errObj?.response?.data?.otpRequired) {
        setPendingLoginId(data.loginId);
        setPendingPassword(data.password);
        setOtpOpen(true);
        return;
      }
      const message =
        errObj?.response?.data?.message || "로그인에 실패했습니다";
      toast.error(message);
    }
  };

  const handleOtpSubmit = async (otpCode: string) => {
    if (!loginWithOtp) {
      toast.error("OTP 인증이 지원되지 않습니다.");
      return;
    }
    setOtpLoading(true);
    try {
      const result = await loginWithOtp(
        pendingLoginId,
        pendingPassword,
        otpCode,
      );
      // Legacy parity: show validUntil from OTP response
      const validUntil = (result as { validUntil?: string } | undefined)
        ?.validUntil;
      if (validUntil) {
        toast.success(`로그인되었습니다. ${validUntil}까지 유효합니다.`);
        setOtpValidUntil(validUntil);
      }
      setOtpOpen(false);
      router.push("/lobby");
    } catch (err: unknown) {
      const errData = (
        err as {
          response?: {
            data?: { message?: string; reason?: string; reset?: boolean };
          };
        }
      )?.response?.data;
      const message =
        errData?.message || errData?.reason || "OTP 인증에 실패했습니다";
      toast.error(message);
      // Legacy parity: if reset flag, close OTP modal
      if (errData?.reset) {
        setOtpOpen(false);
      }
    } finally {
      setOtpLoading(false);
    }
  };

  // Legacy parity: "가입 & 로그인" combined button from core2026 HomeView
  const handleQuickRegister = async () => {
    const values = getValues();
    if (!values.loginId || values.loginId.length < 3) {
      toast.error("아이디는 3자 이상이어야 합니다");
      return;
    }
    if (!values.password || values.password.length < 6) {
      toast.error("비밀번호는 6자 이상이어야 합니다");
      return;
    }
    if (registerMode) {
      if (!displayName || displayName.length < 2) {
        toast.error("닉네임은 2자 이상이어야 합니다");
        return;
      }
      setQuickRegistering(true);
      try {
        await registerUser(values.loginId, displayName, values.password);
        router.push("/lobby");
      } catch (err: unknown) {
        const message =
          (err as { response?: { data?: { message?: string } } })?.response
            ?.data?.message || "가입에 실패했습니다";
        toast.error(message);
      } finally {
        setQuickRegistering(false);
      }
    } else {
      setRegisterMode(true);
    }
  };

  return (
    <>
      <Card className="w-full max-w-md p-8">
        <CardHeader className="px-0 pt-0">
          <CardTitle className="text-center text-2xl">
            삼국지 모의전투 HiDCHe
          </CardTitle>
        </CardHeader>
        <CardContent className="px-0 pb-0">
          {autoLogging && (
            <div className="mb-4 text-center text-sm text-muted-foreground">
              자동 로그인 중...
            </div>
          )}
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div>
              <label
                htmlFor="login-id"
                className="mb-1 block text-sm text-muted-foreground"
              >
                계정명
              </label>
              <Input
                id="login-id"
                {...register("loginId")}
                placeholder="계정명"
              />
              {errors.loginId && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.loginId.message}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="login-password"
                className="mb-1 block text-sm text-muted-foreground"
              >
                비밀번호
              </label>
              <Input
                id="login-password"
                type="password"
                {...register("password")}
                placeholder="비밀번호"
              />
              {errors.password && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.password.message}
                </p>
              )}
            </div>
            {registerMode && (
              <div>
                <label
                  htmlFor="quick-display-name"
                  className="mb-1 block text-sm text-muted-foreground"
                >
                  닉네임
                </label>
                <Input
                  id="quick-display-name"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="닉네임을 입력하세요"
                />
              </div>
            )}
            <div className="flex gap-2 pt-1">
              <Button
                type="button"
                variant="secondary"
                disabled={isSubmitting || quickRegistering}
                className="flex-1"
                onClick={handleQuickRegister}
              >
                {quickRegistering
                  ? "가입 중..."
                  : registerMode
                    ? "가입 확인"
                    : "가입 & 로그인"}
              </Button>
              <Button
                type="submit"
                disabled={isSubmitting || quickRegistering}
                className="flex-[2]"
              >
                {isSubmitting ? "로그인 중..." : "로그인"}
              </Button>
            </div>
          </form>

          {/* Kakao OAuth login (legacy parity: oauth_kakao/) */}
          <div className="mt-4">
            <div className="relative flex items-center justify-center my-3">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-muted" />
              </div>
              <span className="relative bg-card px-2 text-xs text-muted-foreground">
                또는
              </span>
            </div>
            <Button
              type="button"
              variant="outline"
              className="w-full bg-[#FEE500] hover:bg-[#FDD800] text-[#191919] border-[#FEE500] hover:border-[#FDD800] font-medium"
              onClick={startKakaoLogin}
            >
              <svg
                className="mr-2 size-4"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <path d="M12 3C6.477 3 2 6.463 2 10.691c0 2.726 1.818 5.122 4.558 6.48-.152.543-.98 3.503-.998 3.712 0 0-.02.166.088.229.108.063.234.03.234.03.308-.043 3.57-2.33 4.132-2.724.643.09 1.307.137 1.986.137 5.523 0 10-3.463 10-7.864C22 6.463 17.523 3 12 3" />
              </svg>
              카카오 로그인
            </Button>
          </div>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            계정이 없으신가요?{" "}
            <Link href="/register" className="text-primary hover:underline">
              회원가입
            </Link>
          </p>
        </CardContent>
      </Card>
      <ServerStatusCard />

      {/* Running Server Map (legacy parity: running_map iframe) */}
      {serverMapUrl && (
        <Card className="w-full max-w-md mt-4">
          <CardContent className="p-3">
            <button
              type="button"
              className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground w-full"
              onClick={() => setShowServerMap(!showServerMap)}
            >
              <Map className="size-4" />
              서버 근황 지도 {showServerMap ? "▲" : "▼"}
            </button>
            {showServerMap && (
              <iframe
                src={serverMapUrl}
                className="w-full mt-2 border-0 rounded"
                style={{ height: 500 }}
                title="서버 근황 지도"
                onLoad={(e) => {
                  try {
                    const iframe = e.target as HTMLIFrameElement;
                    const scrollHeight =
                      iframe.contentWindow?.document.body.scrollHeight;
                    if (scrollHeight) iframe.style.height = `${scrollHeight}px`;
                  } catch {
                    /* cross-origin */
                  }
                }}
              />
            )}
          </CardContent>
        </Card>
      )}

      {/* OTP 2차 인증 모달 */}
      <OtpModal
        open={otpOpen}
        onClose={() => setOtpOpen(false)}
        onSubmit={handleOtpSubmit}
        loading={otpLoading}
        validUntil={otpValidUntil}
      />
    </>
  );
}
