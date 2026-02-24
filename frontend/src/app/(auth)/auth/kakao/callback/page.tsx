"use client";

import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import { useAuthStore } from "@/stores/authStore";
import { LoadingState } from "@/components/game/loading-state";

export default function KakaoCallbackPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const loginWithOAuth = useAuthStore((s) => s.loginWithOAuth);
  const registerWithOAuth = useAuthStore((s) => s.registerWithOAuth);

  useEffect(() => {
    const run = async () => {
      const code = searchParams.get("code");
      const mode = searchParams.get("mode");
      if (!code) {
        toast.error("카카오 인증 코드가 없습니다.");
        router.replace("/login");
        return;
      }

      const redirectUri = `${window.location.origin}/auth/kakao/callback${mode ? `?mode=${mode}` : ""}`;

      try {
        if (mode === "register") {
          await registerWithOAuth("kakao", code, redirectUri, "");
        } else {
          await loginWithOAuth("kakao", code, redirectUri);
        }
        toast.success(mode === "register" ? "카카오 가입이 완료되었습니다." : "카카오 로그인 성공");
        router.replace("/lobby");
      } catch {
        toast.error(mode === "register" ? "카카오 가입에 실패했습니다." : "카카오 로그인에 실패했습니다.");
        router.replace(mode === "register" ? "/register" : "/login");
      }
    };

    void run();
  }, [loginWithOAuth, registerWithOAuth, router, searchParams]);

  return <LoadingState message="카카오 인증 처리 중..." />;
}
