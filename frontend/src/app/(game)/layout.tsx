"use client";

import { useEffect, useMemo } from "react";
import { useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { Toaster } from "sonner";
import { useAuthStore } from "@/stores/authStore";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useWebSocket } from "@/hooks/useWebSocket";
import { TurnTimer } from "@/components/game/turn-timer";
import { Button } from "@/components/ui/button";
import { ResourceDisplay } from "@/components/game/resource-display";
import { GeneralPortrait } from "@/components/game/general-portrait";

type NavRequire = "nation" | "secret" | "government";

interface NavItem {
  href: string;
  label: string;
  require?: NavRequire;
}

interface NavSection {
  label: string;
  items: NavItem[];
}

const navSections: NavSection[] = [
  {
    label: "핵심",
    items: [
      { href: "/map", label: "세계지도" },
      { href: "/general", label: "내 장수" },
      { href: "/city", label: "현재도시" },
    ],
  },
  {
    label: "세력",
    items: [
      { href: "/board", label: "회의실", require: "nation" },
      { href: "/troop", label: "부대편성", require: "nation" },
      { href: "/superior", label: "상급자", require: "nation" },
      { href: "/personnel", label: "인사부", require: "nation" },
      { href: "/nation", label: "세력정보", require: "nation" },
      { href: "/nation-cities", label: "세력도시", require: "nation" },
      { href: "/nation-generals", label: "세력장수", require: "nation" },
      { href: "/diplomacy", label: "외교부", require: "secret" },
      { href: "/internal-affairs", label: "내무부", require: "secret" },
      { href: "/chief", label: "사령부", require: "secret" },
      { href: "/npc-control", label: "NPC정책", require: "secret" },
      { href: "/spy", label: "암행부", require: "secret" },
      { href: "/battle", label: "감찰부", require: "secret" },
    ],
  },
  {
    label: "정보",
    items: [
      { href: "/nations", label: "세력일람" },
      { href: "/generals", label: "장수일람" },
      { href: "/best-generals", label: "명장일람" },
      { href: "/hall-of-fame", label: "명예의전당" },
      { href: "/emperor", label: "황제현황" },
      { href: "/npc-list", label: "NPC일람" },
      { href: "/battle-center", label: "전투기록" },
      { href: "/history", label: "연감" },
      { href: "/traffic", label: "접속현황" },
    ],
  },
  {
    label: "기타",
    items: [
      { href: "/messages", label: "서신" },
      { href: "/inherit", label: "유산관리" },
      { href: "/auction", label: "경매장" },
      { href: "/betting", label: "베팅장" },
      { href: "/tournament", label: "토너먼트" },
      { href: "/my-page", label: "내정보&설정" },
      { href: "/vote", label: "투표" },
      { href: "/battle-simulator", label: "전투시뮬" },
    ],
  },
];

export default function GameLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, initAuth, logout } = useAuthStore();
  const { currentWorld } = useWorldStore();
  const {
    myGeneral,
    loading: generalLoading,
    fetchMyGeneral,
  } = useGeneralStore();

  useEffect(() => {
    initAuth();
  }, [initAuth]);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace("/login");
    }
  }, [isAuthenticated, router]);

  useEffect(() => {
    if (currentWorld) {
      fetchMyGeneral(currentWorld.id);
    }
  }, [currentWorld, fetchMyGeneral]);

  useEffect(() => {
    if (!isAuthenticated) return;
    if (generalLoading) return;

    if (!currentWorld || myGeneral === null) {
      router.replace("/lobby");
    }
  }, [isAuthenticated, currentWorld, myGeneral, generalLoading, router]);

  const { enabled: wsEnabled, toggleRealtime } = useWebSocket();

  const officerLevel = myGeneral?.officerLevel ?? 0;
  const inNation = officerLevel >= 1;
  const showSecret = inNation && officerLevel >= 2;
  const isGovernment = inNation && officerLevel >= 5;

  const navItems = useMemo(() => {
    const items: NavItem[] = [];

    for (const section of navSections) {
      for (const item of section.items) {
        if (item.require === "nation" && !inNation) continue;
        if (item.require === "secret" && !showSecret) continue;
        if (item.require === "government" && !isGovernment) continue;
        items.push(item);
      }
    }

    return items;
  }, [inNation, showSecret, isGovernment]);

  const guardChecked = Boolean(
    isAuthenticated && !generalLoading && currentWorld && myGeneral !== null,
  );

  if (!isAuthenticated || !guardChecked) return null;

  return (
    <div className="min-h-screen legacy-bg0 text-white">
      <div className="legacy-page-wrap px-1 pb-2">
        <div className="mb-[1px] border border-gray-600 bg-[#0b0b0b] px-2 py-1">
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
            <div className="flex items-center gap-2">
              {currentWorld && (
                <>
                  <span>
                    {currentWorld.currentYear}년 {currentWorld.currentMonth}월
                  </span>
                  <TurnTimer />
                </>
              )}
              <button
                type="button"
                onClick={toggleRealtime}
                className={`border px-1 py-0 text-[10px] ${
                  wsEnabled
                    ? "border-[#006a33] bg-[#00331a] text-[#7cff91]"
                    : "border-gray-600 bg-[#111] text-gray-300"
                }`}
              >
                {wsEnabled ? "실시간 ON" : "실시간 OFF"}
              </button>
            </div>

            {myGeneral && (
              <div className="flex items-center gap-2">
                <GeneralPortrait
                  picture={myGeneral.picture}
                  name={myGeneral.name}
                  size="sm"
                />
                <span className="font-bold text-yellow-300">
                  {myGeneral.name}
                </span>
                <ResourceDisplay
                  gold={myGeneral.gold}
                  rice={myGeneral.rice}
                  crew={myGeneral.crew}
                />
              </div>
            )}

            <div className="flex items-center gap-1">
              <Button variant="outline" size="sm" asChild>
                <Link href="/lobby">로비로</Link>
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  logout();
                  router.replace("/login");
                }}
              >
                로그아웃
              </Button>
            </div>
          </div>
        </div>

        <div className="mb-[1px] grid grid-cols-4 gap-[1px] bg-gray-600 lg:grid-cols-10">
          {navItems.map((item) => {
            const active = pathname === item.href;
            return (
              <Button
                key={`${item.href}-${item.label}`}
                variant="outline"
                size="sm"
                asChild
                className={`h-7 border-0 px-1 text-[11px] font-bold ${
                  active
                    ? "bg-[#141c65] text-white"
                    : "bg-[#00582c] text-white hover:bg-[#006a33]"
                }`}
              >
                <Link href={item.href}>{item.label}</Link>
              </Button>
            );
          })}
        </div>

        <main>{children}</main>
      </div>

      <Toaster position="top-right" theme="dark" />
    </div>
  );
}
