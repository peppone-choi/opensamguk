"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";
import { Toaster } from "sonner";

export default function LobbyLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { isAuthenticated, initAuth, logout } = useAuthStore();

  useEffect(() => { initAuth(); }, [initAuth]);
  useEffect(() => {
    if (!isAuthenticated) router.replace("/login");
  }, [isAuthenticated, router]);

  if (!isAuthenticated) return null;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="flex items-center justify-between h-14 px-6 border-b border-border bg-card">
        <span className="text-lg font-bold text-amber-400">오픈삼국</span>
        <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={() => { logout(); router.replace("/login"); }}>
          <LogOut className="size-4 mr-1" />
          로그아웃
        </Button>
      </header>
      <main className="max-w-5xl mx-auto p-6">{children}</main>
      <Toaster position="top-right" theme="dark" />
    </div>
  );
}
