"use client";

import { useState } from "react";
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
import { X, FileText, Shield } from "lucide-react";

const registerSchema = z
  .object({
    loginId: z.string().min(3, "아이디는 3자 이상이어야 합니다"),
    displayName: z.string().min(2, "닉네임은 2자 이상이어야 합니다"),
    password: z.string().min(4, "비밀번호는 4자 이상이어야 합니다"),
    confirmPassword: z.string(),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "비밀번호가 일치하지 않습니다",
    path: ["confirmPassword"],
  });

type RegisterForm = z.infer<typeof registerSchema>;

/* ── Terms content ── */
const TERMS_CONTENT = `제1조 (목적)
본 약관은 삼국지 모의전투 HiDCHe(이하 "서비스")의 이용과 관련하여 서비스와 회원 간의 권리, 의무 및 책임사항을 규정함을 목적으로 합니다.

제2조 (회원가입)
1. 회원가입은 이용자가 본 약관에 동의한 후 회원가입 양식에 따라 회원정보를 기입하여 신청합니다.
2. 서비스는 제1항의 회원가입 신청에 대하여 특별한 사유가 없는 한 승낙합니다.

제3조 (서비스 이용)
1. 서비스는 회원에게 웹 기반 삼국지 전략 시뮬레이션 게임을 제공합니다.
2. 서비스 이용 시간은 서비스 정책에 따릅니다.

제4조 (회원의 의무)
1. 회원은 다른 회원의 게임 경험을 해치는 행위를 해서는 안 됩니다.
2. 타인의 정보를 도용하여 회원가입하거나 서비스를 이용해서는 안 됩니다.
3. 버그나 취약점을 악용해서는 안 됩니다.

제5조 (서비스 중단)
서비스는 시스템 점검, 교체 및 고장, 통신 두절 등의 사유가 발생한 경우 일시적으로 서비스 제공을 중단할 수 있습니다.

제6조 (면책)
1. 서비스는 무료로 제공되며, 서비스 이용과 관련하여 발생하는 손해에 대해 책임을 지지 않습니다.
2. 서비스는 회원이 게시한 정보의 신뢰성, 정확성에 대해 책임을 지지 않습니다.`;

const PRIVACY_CONTENT = `1. 수집하는 개인정보 항목
- 회원가입 시: 아이디, 닉네임, 비밀번호(암호화 저장)
- 서비스 이용 시: 접속 IP, 접속 일시, 게임 내 활동 로그
- OAuth 가입 시: OAuth 제공자가 전달하는 이메일, 프로필 정보

2. 개인정보의 수집 및 이용 목적
- 회원 식별 및 서비스 제공
- 부정 이용 방지 및 게임 운영
- 서비스 개선을 위한 통계 분석

3. 개인정보의 보유 및 이용 기간
- 회원 탈퇴 시까지 보유하며, 탈퇴 후 지체 없이 파기합니다.
- 관련 법령에 따라 보존이 필요한 경우 해당 기간 동안 보관합니다.

4. 개인정보의 파기
- 보유 기간이 경과하거나 처리 목적이 달성된 경우 지체 없이 파기합니다.

5. 개인정보의 제3자 제공
- 회원의 개인정보를 제3자에게 제공하지 않습니다.
- 단, 법령에 의한 경우는 예외로 합니다.

6. 이용자의 권리
- 회원은 언제든지 자신의 개인정보를 조회하거나 수정할 수 있습니다.
- 회원은 언제든지 회원 탈퇴를 요청할 수 있습니다.`;

/* ── Agreement Modal ── */
function AgreementModal({
  open,
  title,
  icon,
  content,
  onClose,
}: {
  open: boolean;
  title: string;
  icon: React.ReactNode;
  content: string;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <Card className="w-full max-w-lg max-h-[80vh] flex flex-col relative">
        <button
          type="button"
          className="absolute top-3 right-3 text-muted-foreground hover:text-foreground z-10"
          onClick={onClose}
        >
          <X className="size-4" />
        </button>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            {icon}
            {title}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex-1 overflow-y-auto">
          <pre className="text-xs text-muted-foreground whitespace-pre-wrap leading-relaxed font-sans">
            {content}
          </pre>
        </CardContent>
        <div className="p-4 border-t">
          <Button onClick={onClose} className="w-full">
            확인
          </Button>
        </div>
      </Card>
    </div>
  );
}

/* ── Kakao OAuth ── */
const KAKAO_CLIENT_ID = process.env.NEXT_PUBLIC_KAKAO_CLIENT_ID ?? "";

function getKakaoRegisterUrl() {
  if (!KAKAO_CLIENT_ID) return "";
  const redirectUri =
    typeof window !== "undefined"
      ? `${window.location.origin}/auth/kakao/callback?mode=register`
      : "";
  return (
    `https://kauth.kakao.com/oauth/authorize` +
    `?client_id=${KAKAO_CLIENT_ID}` +
    `&redirect_uri=${encodeURIComponent(redirectUri)}` +
    `&response_type=code`
  );
}

export default function RegisterPage() {
  const router = useRouter();
  const registerUser = useAuthStore((s) => s.register);

  // Agreement state
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [showTerms, setShowTerms] = useState(false);
  const [showPrivacy, setShowPrivacy] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
  });

  const allAgreed = agreeTerms && agreePrivacy;

  const handleAgreeAll = () => {
    const next = !(agreeTerms && agreePrivacy);
    setAgreeTerms(next);
    setAgreePrivacy(next);
  };

  const onSubmit = async (data: RegisterForm) => {
    if (!allAgreed) {
      toast.error("이용약관과 개인정보 처리방침에 동의해주세요.");
      return;
    }
    try {
      await registerUser(data.loginId, data.displayName, data.password, {
        terms: agreeTerms,
        privacy: agreePrivacy,
      });
      router.push("/lobby");
    } catch (err: unknown) {
      const message =
        (err as { response?: { data?: { message?: string } } })?.response?.data
          ?.message || "회원가입에 실패했습니다";
      toast.error(message);
    }
  };

  const handleKakaoRegister = () => {
    if (!allAgreed) {
      toast.error("이용약관과 개인정보 처리방침에 동의해주세요.");
      return;
    }
    const url = getKakaoRegisterUrl();
    if (!url) {
      toast.error("카카오 회원가입이 아직 설정되지 않았습니다.");
      return;
    }
    window.location.href = url;
  };

  return (
    <>
      <Card className="w-full max-w-md p-8">
        <CardHeader className="px-0 pt-0">
          <CardTitle className="text-center text-2xl">
            오픈삼국 회원가입
          </CardTitle>
        </CardHeader>
        <CardContent className="px-0 pb-0">
          {/* Agreement checkboxes */}
          <div className="mb-5 space-y-2 border border-input rounded-md p-3">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={agreeTerms && agreePrivacy}
                onChange={handleAgreeAll}
                className="size-4 rounded accent-primary"
              />
              <span className="text-sm font-medium">전체 동의</span>
            </label>
            <div className="border-t border-input my-1" />
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={agreeTerms}
                onChange={() => setAgreeTerms(!agreeTerms)}
                className="size-4 rounded accent-primary"
              />
              <span className="text-sm flex-1">
                이용약관 동의{" "}
                <span className="text-destructive text-xs">(필수)</span>
              </span>
              <button
                type="button"
                className="text-xs text-primary hover:underline"
                onClick={() => setShowTerms(true)}
              >
                보기
              </button>
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={agreePrivacy}
                onChange={() => setAgreePrivacy(!agreePrivacy)}
                className="size-4 rounded accent-primary"
              />
              <span className="text-sm flex-1">
                개인정보 처리방침 동의{" "}
                <span className="text-destructive text-xs">(필수)</span>
              </span>
              <button
                type="button"
                className="text-xs text-primary hover:underline"
                onClick={() => setShowPrivacy(true)}
              >
                보기
              </button>
            </label>
          </div>

          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div>
              <label
                htmlFor="register-login-id"
                className="mb-1 block text-sm text-muted-foreground"
              >
                아이디
              </label>
              <Input
                id="register-login-id"
                {...register("loginId")}
                placeholder="아이디를 입력하세요"
              />
              {errors.loginId && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.loginId.message}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="register-display-name"
                className="mb-1 block text-sm text-muted-foreground"
              >
                닉네임
              </label>
              <Input
                id="register-display-name"
                {...register("displayName")}
                placeholder="닉네임을 입력하세요"
              />
              {errors.displayName && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.displayName.message}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="register-password"
                className="mb-1 block text-sm text-muted-foreground"
              >
                비밀번호
              </label>
              <Input
                id="register-password"
                type="password"
                {...register("password")}
                placeholder="비밀번호를 입력하세요"
              />
              {errors.password && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.password.message}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="register-confirm-password"
                className="mb-1 block text-sm text-muted-foreground"
              >
                비밀번호 확인
              </label>
              <Input
                id="register-confirm-password"
                type="password"
                {...register("confirmPassword")}
                placeholder="비밀번호를 다시 입력하세요"
              />
              {errors.confirmPassword && (
                <p className="mt-1 text-sm text-destructive">
                  {errors.confirmPassword.message}
                </p>
              )}
            </div>
            <Button
              type="submit"
              disabled={isSubmitting || !allAgreed}
              className="w-full"
            >
              {isSubmitting ? "가입 중..." : "회원가입"}
            </Button>
          </form>

          {/* OAuth register */}
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
              onClick={handleKakaoRegister}
              disabled={!allAgreed}
            >
              <svg
                className="mr-2 size-4"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <path d="M12 3C6.477 3 2 6.463 2 10.691c0 2.726 1.818 5.122 4.558 6.48-.152.543-.98 3.503-.998 3.712 0 0-.02.166.088.229.108.063.234.03.234.03.308-.043 3.57-2.33 4.132-2.724.643.09 1.307.137 1.986.137 5.523 0 10-3.463 10-7.864C22 6.463 17.523 3 12 3" />
              </svg>
              카카오로 가입하기
            </Button>
            {!allAgreed && (
              <p className="mt-1 text-[11px] text-muted-foreground text-center">
                약관 동의 후 카카오 가입이 가능합니다.
              </p>
            )}
          </div>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            이미 계정이 있으신가요?{" "}
            <Link href="/login" className="text-primary hover:underline">
              로그인
            </Link>
          </p>
        </CardContent>
      </Card>

      {/* Agreement modals */}
      <AgreementModal
        open={showTerms}
        title="이용약관"
        icon={<FileText className="size-5" />}
        content={TERMS_CONTENT}
        onClose={() => setShowTerms(false)}
      />
      <AgreementModal
        open={showPrivacy}
        title="개인정보 처리방침"
        icon={<Shield className="size-5" />}
        content={PRIVACY_CONTENT}
        onClose={() => setShowPrivacy(false)}
      />
    </>
  );
}
