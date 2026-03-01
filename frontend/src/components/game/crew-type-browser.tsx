"use client";

import { useState, useMemo } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { cn } from "@/lib/utils";

// ── Crew type data mirroring backend CrewType.kt ──

interface CrewTypeData {
  code: number;
  name: string;
  armType: ArmType;
  attack: number;
  defence: number;
  speed: number;
  avoid: number;
  cost: number;
  riceCost: number;
  magicCoef: number;
  info: string[];
}

type ArmType = "보병" | "궁병" | "기병" | "귀병" | "차병";

const ARM_TYPES: ArmType[] = ["보병", "궁병", "기병", "귀병", "차병"];

const CREW_TYPES: CrewTypeData[] = [
  // 보병
  {
    code: 1100,
    name: "보병",
    armType: "보병",
    attack: 100,
    defence: 150,
    speed: 7,
    avoid: 10,
    cost: 9,
    riceCost: 9,
    magicCoef: 0,
    info: ["기본 보병", "궁병에 강함, 기병에 약함"],
  },
  {
    code: 1101,
    name: "청주병",
    armType: "보병",
    attack: 100,
    defence: 200,
    speed: 7,
    avoid: 10,
    cost: 10,
    riceCost: 11,
    magicCoef: 0,
    info: ["높은 방어력"],
  },
  {
    code: 1102,
    name: "수병",
    armType: "보병",
    attack: 150,
    defence: 150,
    speed: 7,
    avoid: 10,
    cost: 11,
    riceCost: 10,
    magicCoef: 0,
    info: ["높은 공격력"],
  },
  {
    code: 1103,
    name: "자객병",
    armType: "보병",
    attack: 100,
    defence: 150,
    speed: 8,
    avoid: 20,
    cost: 10,
    riceCost: 10,
    magicCoef: 0,
    info: ["높은 회피, 빠른 기동"],
  },
  {
    code: 1104,
    name: "근위병",
    armType: "보병",
    attack: 150,
    defence: 200,
    speed: 7,
    avoid: 10,
    cost: 12,
    riceCost: 12,
    magicCoef: 0,
    info: ["균형잡힌 정예병"],
  },
  {
    code: 1105,
    name: "등갑병",
    armType: "보병",
    attack: 100,
    defence: 225,
    speed: 7,
    avoid: 5,
    cost: 13,
    riceCost: 10,
    magicCoef: 0,
    info: ["최고 방어력"],
  },
  {
    code: 1106,
    name: "백이병",
    armType: "보병",
    attack: 175,
    defence: 175,
    speed: 7,
    avoid: 5,
    cost: 13,
    riceCost: 11,
    magicCoef: 0,
    info: ["균형잡힌 공방"],
  },
  // 궁병
  {
    code: 1200,
    name: "궁병",
    armType: "궁병",
    attack: 100,
    defence: 100,
    speed: 7,
    avoid: 10,
    cost: 10,
    riceCost: 10,
    magicCoef: 0,
    info: ["기본 궁병", "기병에 강함, 보병에 약함", "선제사격"],
  },
  {
    code: 1201,
    name: "궁기병",
    armType: "궁병",
    attack: 100,
    defence: 100,
    speed: 8,
    avoid: 20,
    cost: 11,
    riceCost: 12,
    magicCoef: 0,
    info: ["높은 회피, 빠른 기동", "선제사격"],
  },
  {
    code: 1202,
    name: "연노병",
    armType: "궁병",
    attack: 150,
    defence: 100,
    speed: 8,
    avoid: 10,
    cost: 12,
    riceCost: 11,
    magicCoef: 0,
    info: ["높은 공격력", "선제사격"],
  },
  {
    code: 1203,
    name: "강궁병",
    armType: "궁병",
    attack: 150,
    defence: 150,
    speed: 7,
    avoid: 10,
    cost: 13,
    riceCost: 13,
    magicCoef: 0,
    info: ["균형잡힌 정예 궁병", "선제사격"],
  },
  {
    code: 1204,
    name: "석궁병",
    armType: "궁병",
    attack: 200,
    defence: 100,
    speed: 7,
    avoid: 10,
    cost: 13,
    riceCost: 13,
    magicCoef: 0,
    info: ["최고 공격력 궁병", "선제사격"],
  },
  // 기병
  {
    code: 1300,
    name: "기병",
    armType: "기병",
    attack: 150,
    defence: 100,
    speed: 7,
    avoid: 5,
    cost: 11,
    riceCost: 11,
    magicCoef: 0,
    info: ["기본 기병", "보병에 강함, 궁병에 약함"],
  },
  {
    code: 1301,
    name: "백마병",
    armType: "기병",
    attack: 200,
    defence: 100,
    speed: 7,
    avoid: 5,
    cost: 12,
    riceCost: 13,
    magicCoef: 0,
    info: ["높은 공격력"],
  },
  {
    code: 1302,
    name: "중장기병",
    armType: "기병",
    attack: 150,
    defence: 150,
    speed: 7,
    avoid: 5,
    cost: 13,
    riceCost: 12,
    magicCoef: 0,
    info: ["높은 방어력"],
  },
  {
    code: 1303,
    name: "돌격기병",
    armType: "기병",
    attack: 200,
    defence: 100,
    speed: 8,
    avoid: 5,
    cost: 13,
    riceCost: 11,
    magicCoef: 0,
    info: ["빠른 기동, 높은 공격력"],
  },
  {
    code: 1304,
    name: "철기병",
    armType: "기병",
    attack: 100,
    defence: 250,
    speed: 7,
    avoid: 5,
    cost: 11,
    riceCost: 13,
    magicCoef: 0,
    info: ["최고 방어력 기병"],
  },
  {
    code: 1305,
    name: "수렵기병",
    armType: "기병",
    attack: 150,
    defence: 100,
    speed: 8,
    avoid: 15,
    cost: 12,
    riceCost: 12,
    magicCoef: 0,
    info: ["높은 회피, 빠른 기동"],
  },
  {
    code: 1306,
    name: "맹수병",
    armType: "기병",
    attack: 250,
    defence: 175,
    speed: 6,
    avoid: 0,
    cost: 16,
    riceCost: 16,
    magicCoef: 0,
    info: ["최강 공격력, 느린 기동"],
  },
  {
    code: 1307,
    name: "호표기병",
    armType: "기병",
    attack: 200,
    defence: 150,
    speed: 7,
    avoid: 5,
    cost: 14,
    riceCost: 14,
    magicCoef: 0,
    info: ["정예 기병"],
  },
  // 귀병
  {
    code: 1400,
    name: "귀병",
    armType: "귀병",
    attack: 80,
    defence: 80,
    speed: 7,
    avoid: 5,
    cost: 9,
    riceCost: 9,
    magicCoef: 0.5,
    info: ["기본 귀병", "마법 계수 0.5"],
  },
  {
    code: 1401,
    name: "신귀병",
    armType: "귀병",
    attack: 80,
    defence: 80,
    speed: 7,
    avoid: 20,
    cost: 10,
    riceCost: 10,
    magicCoef: 0.6,
    info: ["높은 회피", "마법 계수 0.6"],
  },
  {
    code: 1402,
    name: "백귀병",
    armType: "귀병",
    attack: 80,
    defence: 130,
    speed: 7,
    avoid: 5,
    cost: 9,
    riceCost: 11,
    magicCoef: 0.6,
    info: ["방어형 귀병", "마법 계수 0.6"],
  },
  {
    code: 1403,
    name: "흑귀병",
    armType: "귀병",
    attack: 130,
    defence: 80,
    speed: 7,
    avoid: 5,
    cost: 11,
    riceCost: 9,
    magicCoef: 0.6,
    info: ["공격형 귀병", "마법 계수 0.6"],
  },
  {
    code: 1404,
    name: "악귀병",
    armType: "귀병",
    attack: 130,
    defence: 130,
    speed: 7,
    avoid: 0,
    cost: 12,
    riceCost: 12,
    magicCoef: 0.6,
    info: ["균형잡힌 정예 귀병", "마법 계수 0.6"],
  },
  {
    code: 1405,
    name: "남귀병",
    armType: "귀병",
    attack: 60,
    defence: 60,
    speed: 7,
    avoid: 10,
    cost: 8,
    riceCost: 8,
    magicCoef: 0.8,
    info: ["최고 마법 계수", "마법 계수 0.8"],
  },
  {
    code: 1406,
    name: "황귀병",
    armType: "귀병",
    attack: 110,
    defence: 110,
    speed: 7,
    avoid: 0,
    cost: 13,
    riceCost: 10,
    magicCoef: 0.8,
    info: ["강력한 마법형", "마법 계수 0.8"],
  },
  {
    code: 1407,
    name: "천귀병",
    armType: "귀병",
    attack: 80,
    defence: 130,
    speed: 7,
    avoid: 15,
    cost: 11,
    riceCost: 12,
    magicCoef: 0.6,
    info: ["방어+회피형", "마법 계수 0.6"],
  },
  {
    code: 1408,
    name: "마귀병",
    armType: "귀병",
    attack: 130,
    defence: 80,
    speed: 7,
    avoid: 15,
    cost: 12,
    riceCost: 11,
    magicCoef: 0.6,
    info: ["공격+회피형", "마법 계수 0.6"],
  },
  // 차병
  {
    code: 1500,
    name: "정란",
    armType: "차병",
    attack: 100,
    defence: 100,
    speed: 6,
    avoid: 0,
    cost: 14,
    riceCost: 5,
    magicCoef: 0,
    info: ["기본 공성 병기", "성벽에 강함", "선제사격"],
  },
  {
    code: 1501,
    name: "충차",
    armType: "차병",
    attack: 150,
    defence: 100,
    speed: 6,
    avoid: 0,
    cost: 18,
    riceCost: 5,
    magicCoef: 0,
    info: ["성벽 특화 공성", "성벽에 매우 강함"],
  },
  {
    code: 1502,
    name: "벽력거",
    armType: "차병",
    attack: 150,
    defence: 100,
    speed: 6,
    avoid: 5,
    cost: 20,
    riceCost: 5,
    magicCoef: 0,
    info: ["최강 공성 병기", "성벽에 강함", "선제사격"],
  },
  {
    code: 1503,
    name: "목우",
    armType: "차병",
    attack: 50,
    defence: 200,
    speed: 5,
    avoid: 0,
    cost: 15,
    riceCost: 5,
    magicCoef: 0,
    info: ["방어형 공성", "저지 능력"],
  },
];

// ── Stat bar component ──

function StatBar({
  value,
  max,
  color,
}: {
  value: number;
  max: number;
  color: string;
}) {
  const pct = Math.min(100, (value / max) * 100);
  return (
    <div className="h-1.5 w-full bg-zinc-800 rounded-full overflow-hidden">
      <div
        className={cn("h-full rounded-full", color)}
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

// ── Main component ──

interface CrewTypeBrowserProps {
  commandName: string; // 징병 or 모병
  onSubmit: (crewTypeCode: number, amount: number) => void;
}

export function CrewTypeBrowser({
  commandName,
  onSubmit,
}: CrewTypeBrowserProps) {
  const { myGeneral } = useGeneralStore();
  const { cities } = useGameStore();

  const [selectedArm, setSelectedArm] = useState<ArmType | "전체">("전체");
  const [selectedCrew, setSelectedCrew] = useState<CrewTypeData | null>(null);
  const [amount, setAmount] = useState(0);
  const [showUnavailable, setShowUnavailable] = useState(false);

  // Derive city tech level
  const myCity = useMemo(() => {
    if (!myGeneral) return null;
    return cities.find((c) => c.id === myGeneral.cityId) ?? null;
  }, [cities, myGeneral]);

  const techLevel =
    typeof myCity?.meta?.tech === "number" ? (myCity.meta.tech as number) : 0;
  const leadership = myGeneral?.leadership ?? 100;
  const currentCrew = myGeneral?.crew ?? 0;
  const currentCrewType = myGeneral?.crewType ?? 0;
  const gold = myGeneral?.gold ?? 0;
  const goldCoeff = commandName === "모병" ? 2 : 1;

  // Simple tech availability: base types always available, others need tech
  // For now, approximate: code % 100 === 0 → base (no tech needed), others need progressively higher tech
  const isAvailable = (ct: CrewTypeData) => {
    const subCode = ct.code % 100;
    if (subCode === 0) return true;
    // Each subsequent crew type needs ~tech level * 200
    // This is approximate - real logic would come from backend
    return techLevel >= subCode * 200;
  };

  const filteredCrews = useMemo(() => {
    let list = CREW_TYPES;
    if (selectedArm !== "전체") {
      list = list.filter((c) => c.armType === selectedArm);
    }
    if (!showUnavailable) {
      list = list.filter((c) => isAvailable(c));
    }
    return list;
  }, [selectedArm, showUnavailable, techLevel]);

  const beHalf = () => setAmount(Math.ceil(leadership * 0.5));
  const beFilled = (ct?: CrewTypeData) => {
    const target = ct ?? selectedCrew;
    if (target && target.code === currentCrewType) {
      setAmount(Math.max(1, leadership - Math.floor(currentCrew / 100)));
    } else {
      setAmount(leadership);
    }
  };
  const beFull = () => setAmount(Math.floor(leadership * 1.2));

  const selectCrew = (ct: CrewTypeData) => {
    setSelectedCrew(ct);
    // Auto-fill amount
    if (ct.code === currentCrewType) {
      setAmount(Math.max(1, leadership - Math.floor(currentCrew / 100)));
    } else {
      setAmount(leadership);
    }
  };

  const totalCost = selectedCrew
    ? Math.ceil(amount * selectedCrew.cost * goldCoeff)
    : 0;
  const totalRice = selectedCrew
    ? Math.ceil(amount * selectedCrew.riceCost)
    : 0;

  const handleSubmit = () => {
    if (!selectedCrew) return;
    onSubmit(selectedCrew.code, amount * 100);
  };

  return (
    <div className="space-y-3">
      {/* Header info */}
      <div className="rounded-md bg-amber-900/20 border border-amber-800/40 px-3 py-2 text-xs text-amber-200/90">
        {commandName === "모병"
          ? "모병은 가격 2배의 자금이 소요됩니다. 훈련·사기가 높습니다."
          : "징병은 저렴하지만 훈련·사기가 낮습니다. 도시 인구가 감소합니다."}
      </div>

      {/* Status bar */}
      <div className="grid grid-cols-3 gap-1 text-[10px] text-muted-foreground bg-zinc-900/50 rounded-md px-2 py-1.5">
        <div>
          통솔: <span className="text-amber-300 font-mono">{leadership}</span>
        </div>
        <div>
          병사:{" "}
          <span className="text-amber-300 font-mono">
            {currentCrew.toLocaleString()}
          </span>
        </div>
        <div>
          자금:{" "}
          <span className="text-amber-300 font-mono">
            {gold.toLocaleString()}
          </span>
        </div>
      </div>

      {/* Arm type filter tabs */}
      <Tabs
        value={selectedArm}
        onValueChange={(v) => setSelectedArm(v as ArmType | "전체")}
      >
        <TabsList className="w-full h-7">
          <TabsTrigger value="전체" className="text-[10px] flex-1">
            전체
          </TabsTrigger>
          {ARM_TYPES.map((arm) => (
            <TabsTrigger key={arm} value={arm} className="text-[10px] flex-1">
              {arm}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/* Toggle unavailable */}
      <label className="flex items-center gap-1.5 text-[10px] text-muted-foreground cursor-pointer">
        <input
          type="checkbox"
          checked={showUnavailable}
          onChange={(e) => setShowUnavailable(e.target.checked)}
          className="accent-amber-400"
        />
        사용 불가 병종도 표시
      </label>

      {/* Crew type grid */}
      <div className="max-h-[280px] overflow-y-auto space-y-1 pr-1">
        {filteredCrews.map((ct) => {
          const available = isAvailable(ct);
          const isSelected = selectedCrew?.code === ct.code;
          return (
            <TooltipProvider key={ct.code} delayDuration={300}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    onClick={() => available && selectCrew(ct)}
                    className={cn(
                      "w-full text-left rounded-md border px-2 py-1.5 transition-colors",
                      isSelected
                        ? "border-amber-500 bg-amber-900/30"
                        : available
                          ? "border-zinc-700 bg-zinc-900/50 hover:border-zinc-500"
                          : "border-red-900/50 bg-red-950/20 opacity-50 cursor-not-allowed",
                    )}
                  >
                    <div className="flex items-center gap-2">
                      {/* Availability dot */}
                      <div
                        className={cn(
                          "w-2 h-2 rounded-full shrink-0",
                          available ? "bg-green-500" : "bg-red-500",
                        )}
                      />

                      {/* Name */}
                      <span className="text-xs font-medium w-14 shrink-0">
                        {ct.name}
                      </span>

                      {/* Stats mini grid */}
                      <div className="flex-1 grid grid-cols-6 gap-x-1.5 text-[9px] text-muted-foreground">
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">공</div>
                          <div className="font-mono text-amber-300">
                            {ct.attack}
                          </div>
                        </div>
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">방</div>
                          <div className="font-mono text-blue-300">
                            {ct.defence}
                          </div>
                        </div>
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">속</div>
                          <div className="font-mono">{ct.speed}</div>
                        </div>
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">회</div>
                          <div className="font-mono">{ct.avoid}</div>
                        </div>
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">금</div>
                          <div className="font-mono text-yellow-300">
                            {ct.cost}
                          </div>
                        </div>
                        <div className="text-center">
                          <div className="text-[8px] opacity-60">쌀</div>
                          <div className="font-mono text-green-300">
                            {ct.riceCost}
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Stat bars */}
                    <div className="mt-1 grid grid-cols-2 gap-x-2 gap-y-0.5">
                      <StatBar
                        value={ct.attack}
                        max={250}
                        color="bg-amber-500"
                      />
                      <StatBar
                        value={ct.defence}
                        max={250}
                        color="bg-blue-500"
                      />
                    </div>
                  </button>
                </TooltipTrigger>
                <TooltipContent side="right" className="max-w-[200px]">
                  <p className="font-bold text-xs mb-1">{ct.name}</p>
                  {ct.info.map((line, i) => (
                    <p key={i} className="text-[10px] text-muted-foreground">
                      {line}
                    </p>
                  ))}
                  {ct.magicCoef > 0 && (
                    <p className="text-[10px] text-purple-300 mt-1">
                      마법 계수: {ct.magicCoef}
                    </p>
                  )}
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          );
        })}
      </div>

      {/* Selected crew detail + amount controls */}
      {selectedCrew && (
        <div className="rounded-md border border-zinc-700 bg-zinc-900/50 p-2 space-y-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-[10px]">
                {selectedCrew.armType}
              </Badge>
              <span className="text-sm font-bold">{selectedCrew.name}</span>
            </div>
            <div className="flex gap-1">
              <Button
                variant="outline"
                size="sm"
                className="h-6 text-[10px] px-2"
                onClick={beHalf}
              >
                절반
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-6 text-[10px] px-2"
                onClick={() => beFilled()}
              >
                채우기
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-6 text-[10px] px-2"
                onClick={beFull}
              >
                가득
              </Button>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Input
              type="number"
              value={amount || ""}
              onChange={(e) => setAmount(Number(e.target.value) || 0)}
              className="text-xs h-7 flex-1"
              min={1}
            />
            <span className="text-[10px] text-muted-foreground shrink-0">
              ×100명
            </span>
          </div>

          {/* Cost preview */}
          <div className="flex justify-between text-[10px]">
            <span className="text-muted-foreground">
              비용:{" "}
              <span className="text-yellow-300 font-mono">
                {totalCost.toLocaleString()}
              </span>
              금{" / "}
              <span className="text-green-300 font-mono">
                {totalRice.toLocaleString()}
              </span>
              쌀
            </span>
            {totalCost > gold && (
              <span className="text-red-400">자금 부족!</span>
            )}
          </div>

          <Button
            size="sm"
            onClick={handleSubmit}
            disabled={amount <= 0}
            className="w-full"
          >
            {commandName} ({selectedCrew.name} {(amount * 100).toLocaleString()}
            명)
          </Button>
        </div>
      )}
    </div>
  );
}

export { CREW_TYPES };
export type { CrewTypeData, ArmType };
