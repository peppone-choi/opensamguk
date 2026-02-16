"use client";

import { useState, useRef, useEffect } from "react";
import Link from "next/link";
import { useGeneralStore } from "@/stores/generalStore";

interface GameBottomBarProps {
  onRefresh?: () => void;
}

/* ── Legacy MainControlDropdown parity: 국가 메뉴 items ── */
type NavRequire = "nation" | "secret" | "government";
interface NavItem {
  href: string;
  label: string;
  require?: NavRequire;
}

const NATION_MENU: NavItem[] = [
  { href: "/board", label: "회의실", require: "nation" },
  { href: "/board?secret=true", label: "기밀실", require: "government" },
  { href: "/troop", label: "부대편성", require: "nation" },
  { href: "/diplomacy", label: "외교부", require: "secret" },
  { href: "/personnel", label: "인사부", require: "nation" },
  { href: "/internal-affairs", label: "내무부", require: "secret" },
  { href: "/chief", label: "사령부", require: "secret" },
  { href: "/npc-control", label: "NPC정책", require: "secret" },
  { href: "/spy", label: "암행부", require: "secret" },
  { href: "/tournament", label: "토너먼트" },
  { href: "/nation", label: "세력정보", require: "nation" },
  { href: "/nation-cities", label: "세력도시", require: "nation" },
  { href: "/nation-generals", label: "세력장수", require: "nation" },
  { href: "/diplomacy", label: "중원정보" },
  { href: "/city", label: "현재도시" },
  { href: "/battle", label: "감찰부", require: "secret" },
  { href: "/inherit", label: "유산관리" },
  { href: "/my-page", label: "내정보&설정" },
  { href: "/auction", label: "경매장" },
  { href: "/betting", label: "베팅장" },
];

/* ── Legacy GlobalMenuDropdown parity: 외부 메뉴 items ── */
const GLOBAL_MENU: NavItem[] = [
  { href: "/nations", label: "세력일람" },
  { href: "/generals", label: "장수일람" },
  { href: "/best-generals", label: "명장일람" },
  { href: "/hall-of-fame", label: "명예의전당" },
  { href: "/emperor", label: "황제현황" },
  { href: "/history", label: "연감" },
  { href: "/traffic", label: "접속현황" },
  { href: "/messages", label: "서신" },
  { href: "/vote", label: "투표" },
];

/* ── Legacy 빠른 이동: scroll-to-section items ── */
interface QuickNavItem {
  label: string;
  selector?: string;
  header?: boolean;
  divider?: boolean;
  lobby?: boolean;
}

const QUICK_NAV: QuickNavItem[] = [
  { label: "국가 정보", header: true },
  { label: "", divider: true },
  { label: "방침", selector: ".nationNotice" },
  { label: "명령", selector: ".reservedCommandZone" },
  { label: "국가", selector: ".nationInfo" },
  { label: "장수", selector: ".generalInfo" },
  { label: "도시", selector: ".cityInfo" },
  { label: "동향 정보", header: true },
  { label: "", divider: true },
  { label: "지도", selector: ".mapView" },
  { label: "동향", selector: ".PublicRecord" },
  { label: "개인", selector: ".GeneralLog" },
  { label: "정세", selector: ".WorldHistory" },
  { label: "", divider: true },
  { label: "메시지", header: true },
  { label: "", divider: true },
  { label: "전체", selector: ".PublicTalk" },
  { label: "국가", selector: ".NationalTalk" },
  { label: "개인", selector: ".PrivateTalk" },
  { label: "외교", selector: ".DiplomacyTalk" },
  { label: "로비로", lobby: true },
];

function scrollToSelector(selector: string) {
  const el = document.querySelector(selector);
  if (el) {
    el.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}

export function GameBottomBar({ onRefresh }: GameBottomBarProps) {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const barRef = useRef<HTMLDivElement>(null);

  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const officerLevel = myGeneral?.officerLevel ?? 0;
  const inNation = officerLevel >= 1;
  const showSecret = inNation && officerLevel >= 2;
  const isGovernment = inNation && officerLevel >= 5;

  function isVisible(item: NavItem): boolean {
    if (!item.require) return true;
    if (item.require === "nation") return inNation;
    if (item.require === "secret") return showSecret;
    if (item.require === "government") return isGovernment;
    return true;
  }

  // Close menu on outside click
  useEffect(() => {
    if (!openMenu) return;
    function handleClick(e: MouseEvent) {
      if (barRef.current && !barRef.current.contains(e.target as Node)) {
        setOpenMenu(null);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [openMenu]);

  function toggle(menu: string) {
    setOpenMenu((prev) => (prev === menu ? null : menu));
  }

  const filteredNation = NATION_MENU.filter(isVisible);

  return (
    <div
      ref={barRef}
      className="fixed bottom-0 left-0 right-0 z-40 lg:hidden"
    >
      {/* ── Dropup panels ── */}
      {openMenu === "global" && (
        <DropupPanel columns={3}>
          {GLOBAL_MENU.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="block px-3 py-1.5 text-sm hover:bg-muted/50"
              onClick={() => setOpenMenu(null)}
            >
              {item.label}
            </Link>
          ))}
        </DropupPanel>
      )}

      {openMenu === "nation" && (
        <DropupPanel columns={3}>
          {filteredNation.map((item, i) => (
            <Link
              key={`${item.href}-${i}`}
              href={item.href}
              className="block px-3 py-1.5 text-sm hover:bg-muted/50"
              onClick={() => setOpenMenu(null)}
            >
              {item.label}
            </Link>
          ))}
        </DropupPanel>
      )}

      {openMenu === "quick" && (
        <DropupPanel columns={3}>
          {QUICK_NAV.map((item, i) => {
            if (item.divider) {
              return (
                <div
                  key={`d-${i}`}
                  className="col-span-3 border-t border-gray-600 my-0.5"
                />
              );
            }
            if (item.header) {
              return (
                <div
                  key={`h-${i}`}
                  className="col-span-3 px-3 py-1 text-xs text-muted-foreground font-bold"
                >
                  {item.label}
                </div>
              );
            }
            if (item.lobby) {
              return (
                <button
                  key="lobby"
                  type="button"
                  className="col-span-3 mx-2 my-1 px-3 py-1.5 text-sm text-center bg-muted/50 hover:bg-muted rounded"
                  onClick={() => {
                    setOpenMenu(null);
                    window.location.href = "/lobby";
                  }}
                >
                  로비로
                </button>
              );
            }
            return (
              <button
                key={`q-${i}`}
                type="button"
                className="block w-full text-left px-3 py-1.5 text-sm hover:bg-muted/50"
                onClick={() => {
                  if (item.selector) scrollToSelector(item.selector);
                  setOpenMenu(null);
                }}
              >
                {item.label}
              </button>
            );
          })}
        </DropupPanel>
      )}

      {/* ── Bottom bar buttons ── */}
      <div className="border-t border-border bg-card flex">
        <BottomBtn
          label="외부 메뉴"
          active={openMenu === "global"}
          onClick={() => toggle("global")}
        />
        <BottomBtn
          label="국가 메뉴"
          active={openMenu === "nation"}
          onClick={() => toggle("nation")}
          className="bg-[#00582c]"
        />
        <BottomBtn
          label="빠른 이동"
          active={openMenu === "quick"}
          onClick={() => toggle("quick")}
        />
        <button
          type="button"
          className="flex-1 border-l border-gray-600 bg-[#00582c] py-2.5 text-center text-sm font-bold text-white hover:bg-[#006a33]"
          onClick={() => {
            setOpenMenu(null);
            onRefresh?.();
          }}
        >
          갱신
        </button>
      </div>
    </div>
  );
}

/* ── Sub-components ── */

function DropupPanel({
  columns,
  children,
}: {
  columns: number;
  children: React.ReactNode;
}) {
  return (
    <div
      className="border-t border-gray-600 bg-[#111] overflow-y-auto"
      style={{
        maxHeight: "calc(100vh - 50px)",
        display: "grid",
        gridTemplateColumns: `repeat(${columns}, 1fr)`,
      }}
    >
      {children}
    </div>
  );
}

function BottomBtn({
  label,
  active,
  onClick,
  className,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
  className?: string;
}) {
  return (
    <button
      type="button"
      className={`flex-1 border-l border-gray-600 py-2.5 text-center text-sm font-bold ${
        active ? "bg-[#141c65] text-white" : "bg-[#111] text-white hover:bg-[#1c1c1c]"
      } ${className ?? ""}`}
      onClick={onClick}
    >
      {label}
    </button>
  );
}
