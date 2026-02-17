"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { LogOut, Shield } from "lucide-react";
import { Toaster } from "sonner";

export default function LobbyLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const { isAuthenticated, user, initAuth, logout } = useAuthStore();
  const isAdmin = user?.role === "ADMIN";

  useEffect(() => {
    initAuth();
  }, [initAuth]);
  useEffect(() => {
    if (!isAuthenticated) router.replace("/login");
  }, [isAuthenticated, router]);

  if (!isAuthenticated) return null;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="flex items-center justify-between h-14 px-6 border-b border-border bg-card">
        <span className="text-lg font-bold text-amber-400">오픈삼국</span>
        <div className="flex items-center gap-2">
          {isAdmin && (
            <Button variant="ghost" size="sm" asChild className="text-red-400">
              <Link href="/admin">
                <Shield className="size-4 mr-1" />
                관리자
              </Link>
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            className="text-muted-foreground"
            onClick={() => {
              logout();
              router.replace("/login");
            }}
          >
            <LogOut className="size-4 mr-1" />
            로그아웃
          </Button>
        </div>
      </header>
      <main className="max-w-5xl mx-auto p-6">{children}</main>
      <Toaster position="top-right" theme="dark" />
    </div>
  );
}
