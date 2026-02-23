"use client";

import { useEffect, useState } from "react";
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

const loginSchema = z.object({
  loginId: z.string().min(3, "아이디는 3자 이상이어야 합니다"),
  password: z.string().min(4, "비밀번호는 4자 이상이어야 합니다"),
});

type LoginForm = z.infer<typeof loginSchema>;

export default function LoginPage() {
  const router = useRouter();
  const login = useAuthStore((s) => s.login);
  const registerUser = useAuthStore((s) => s.register);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const isInitialized = useAuthStore((s) => s.isInitialized);
  const [registerMode, setRegisterMode] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [quickRegistering, setQuickRegistering] = useState(false);

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
  });

  // Auto-redirect if already authenticated (legacy parity: index.php checks session)
  useEffect(() => {
    if (isInitialized && isAuthenticated) {
      router.replace("/lobby");
    }
  }, [isInitialized, isAuthenticated, router]);

  const onSubmit = async (data: LoginForm) => {
    try {
      await login(data.loginId, data.password);
      router.push("/lobby");
    } catch (err: unknown) {
      const message =
        (err as { response?: { data?: { message?: string } } })?.response?.data
          ?.message || "로그인에 실패했습니다";
      toast.error(message);
    }
  };

  // Legacy parity: "가입 & 로그인" combined button from core2026 HomeView
  const handleQuickRegister = async () => {
    const values = getValues();
    if (!values.loginId || values.loginId.length < 3) {
      toast.error("아이디는 3자 이상이어야 합니다");
      return;
    }
    if (!values.password || values.password.length < 4) {
      toast.error("비밀번호는 4자 이상이어야 합니다");
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
          <p className="mt-4 text-center text-sm text-muted-foreground">
            계정이 없으신가요?{" "}
            <Link href="/register" className="text-primary hover:underline">
              회원가입
            </Link>
          </p>
        </CardContent>
      </Card>
      <ServerStatusCard />
    </>
  );
}
