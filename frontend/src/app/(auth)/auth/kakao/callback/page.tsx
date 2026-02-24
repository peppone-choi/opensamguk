"use client";

import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import { useAuthStore } from "@/stores/authStore";
import { accountApi } from "@/lib/gameApi";
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
      const provider = searchParams.get("provider") ?? "kakao";

      if (!code) {
        toast.error("카카오 인증 코드가 없습니다.");
        router.replace(mode === "link" ? "/account" : "/login");
        return;
      }

      const callbackQuery = new URLSearchParams();
      if (mode) callbackQuery.set("mode", mode);
      if (provider) callbackQuery.set("provider", provider);
      const redirectUri = `${window.location.origin}/auth/kakao/callback${callbackQuery.toString() ? `?${callbackQuery.toString()}` : ""}`;

      try {
        if (mode === "register") {
          await registerWithOAuth(provider, code, redirectUri, "");
          toast.success("카카오 가입이 완료되었습니다.");
          router.replace("/lobby");
          return;
        }

        if (mode === "link") {
          await accountApi.completeOAuthLink(provider, code, redirectUri);
          toast.success("카카오 계정 연동이 완료되었습니다.");
          router.replace(`/account?oauth=linked&provider=${encodeURIComponent(provider)}`);
          return;
        }

        await loginWithOAuth(provider, code, redirectUri);
        toast.success("카카오 로그인 성공");
        router.replace("/lobby");
      } catch {
        if (mode === "register") {
          toast.error("카카오 가입에 실패했습니다.");
          router.replace("/register");
          return;
        }

        if (mode === "link") {
          toast.error("카카오 계정 연동에 실패했습니다.");
          router.replace(`/account?oauth=link_failed&provider=${encodeURIComponent(provider)}`);
          return;
        }

        toast.error("카카오 로그인에 실패했습니다.");
        router.replace("/login");
      }
    };

    void run();
  }, [loginWithOAuth, registerWithOAuth, router, searchParams]);

  return <LoadingState message="카카오 인증 처리 중..." />;
}
