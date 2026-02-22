"use client";

import { useEffect } from "react";
import { useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { useAuthStore } from "@/stores/authStore";
import { Toaster } from "sonner";
import { Button } from "@/components/ui/button";
import {
  LayoutDashboard,
  Users,
  BarChart3,
  ScrollText,
  Handshake,
  Clock,
  UserCog,
  Container,
  LogOut,
} from "lucide-react";

const adminNav = [
  { href: "/admin", label: "대시보드", icon: LayoutDashboard },
  { href: "/admin/members", label: "장수 관리", icon: Users },
  { href: "/admin/statistics", label: "통계", icon: BarChart3 },
  { href: "/admin/logs", label: "로그", icon: ScrollText },
  { href: "/admin/diplomacy", label: "외교", icon: Handshake },
  { href: "/admin/time-control", label: "시간 제어", icon: Clock },
  { href: "/admin/users", label: "유저 관리", icon: UserCog },
  { href: "/admin/game-versions", label: "게임 버전", icon: Container },
];

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, initAuth, logout } = useAuthStore();

  useEffect(() => {
    initAuth();
  }, [initAuth]);
  useEffect(() => {
    if (!isAuthenticated) router.replace("/login");
  }, [isAuthenticated, router]);

  if (!isAuthenticated) return null;

  return (
    <div className="flex h-screen bg-background text-foreground">
      <aside className="w-48 border-r border-border bg-card">
        <div className="h-14 px-4 flex items-center border-b border-border">
          <Link href="/admin" className="text-lg font-bold text-red-400">
            관리자
          </Link>
        </div>
        <nav className="p-2 space-y-0.5">
          {adminNav.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Button
                key={item.href}
                variant="ghost"
                size="sm"
                asChild
                className={`w-full justify-start gap-2 ${active ? "bg-red-400/10 text-red-400" : "text-muted-foreground"}`}
              >
                <Link href={item.href}>
                  <Icon className="size-4" />
                  {item.label}
                </Link>
              </Button>
            );
          })}
          <Button
            variant="ghost"
            size="sm"
            asChild
            className="w-full justify-start gap-2 text-muted-foreground"
          >
            <Link href="/">
              <LogOut className="size-4" />
              게임으로
            </Link>
          </Button>
        </nav>
      </aside>
      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-14 px-4 flex items-center border-b border-border bg-card">
          <span className="text-sm text-muted-foreground">관리자 패널</span>
          <Button
            variant="ghost"
            size="sm"
            className="ml-auto text-muted-foreground"
            onClick={() => {
              logout();
              router.replace("/login");
            }}
          >
            <LogOut className="size-4" />
          </Button>
        </header>
        <main className="flex-1 overflow-y-auto p-4">{children}</main>
      </div>
      <Toaster position="top-right" theme="dark" />
    </div>
  );
}
