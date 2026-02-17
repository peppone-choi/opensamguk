"use client";

import Link from "next/link";
import { Button } from "@/components/ui/button";
import { useGeneralStore } from "@/stores/generalStore";

// Legacy MainControlBar parity (20 buttons)
// Access: "nation" = officerLevel >= 1, "secret" = officerLevel >= 2, "government" = officerLevel >= 5
type CtrlRequire = "nation" | "secret" | "government";

interface CtrlItem {
  href: string;
  label: string;
  require?: CtrlRequire;
}

const CONTROLS: CtrlItem[] = [
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
  { href: "/history", label: "연감" },
  { href: "/dynasty", label: "왕조일람" },
];

export function MainControlBar() {
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const officerLevel = myGeneral?.officerLevel ?? 0;
  const inNation = officerLevel >= 1;
  const showSecret = inNation && officerLevel >= 2;
  const isGovernment = inNation && officerLevel >= 5;

  function isDisabled(item: CtrlItem): boolean {
    if (!item.require) return false;
    if (item.require === "nation") return !inNation;
    if (item.require === "secret") return !showSecret;
    if (item.require === "government") return !isGovernment;
    return false;
  }

  return (
    <div className="grid grid-cols-4 gap-[1px] bg-gray-600 lg:grid-cols-10">
      {CONTROLS.map((item, idx) => {
        const disabled = isDisabled(item);
        return (
          <Button
            key={`${item.href}-${idx}`}
            variant="outline"
            size="sm"
            asChild={!disabled}
            disabled={disabled}
            className={`h-7 border-0 bg-[#00582c] px-1 text-[11px] leading-none text-white hover:bg-[#006a33] ${
              disabled ? "opacity-40 pointer-events-none" : ""
            }`}
          >
            {disabled ? (
              <span className="truncate text-center">{item.label}</span>
            ) : (
              <Link href={item.href}>
                <span className="truncate text-center">{item.label}</span>
              </Link>
            )}
          </Button>
        );
      })}
    </div>
  );
}
