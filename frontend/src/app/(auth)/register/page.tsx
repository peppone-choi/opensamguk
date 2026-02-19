"use client";

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

export default function RegisterPage() {
  const router = useRouter();
  const registerUser = useAuthStore((s) => s.register);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
  });

  const onSubmit = async (data: RegisterForm) => {
    try {
      await registerUser(data.loginId, data.displayName, data.password);
      router.push("/lobby");
    } catch (err: unknown) {
      const message =
        (err as { response?: { data?: { message?: string } } })?.response?.data
          ?.message || "회원가입에 실패했습니다";
      toast.error(message);
    }
  };

  return (
    <Card className="w-full max-w-md p-8">
      <CardHeader className="px-0 pt-0">
        <CardTitle className="text-center text-2xl">오픈삼국 회원가입</CardTitle>
      </CardHeader>
      <CardContent className="px-0 pb-0">
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
          <Button type="submit" disabled={isSubmitting} className="w-full">
            {isSubmitting ? "가입 중..." : "회원가입"}
          </Button>
        </form>
        <p className="mt-4 text-center text-sm text-muted-foreground">
          이미 계정이 있으신가요?{" "}
          <Link href="/login" className="text-primary hover:underline">
            로그인
          </Link>
        </p>
      </CardContent>
    </Card>
  );
}
