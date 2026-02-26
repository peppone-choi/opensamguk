"use client";

import { useEffect, useState, useCallback } from "react";
import {
  Gift,
  Search,
  RotateCcw,
  Dices,
  Swords,
  BarChart3,
  Crown,
  ChevronDown,
  Shield,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { useWorldStore } from "@/stores/worldStore";
import { inheritanceApi, cityApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type {
  InheritanceInfo,
  InheritanceLogEntry,
  InheritBuffType,
  InheritanceActionCost,
  City,
} from "@/types";

/* ── Point breakdown category labels ── */

const POINT_CATEGORY_LABELS: Record<string, string> = {
  previous: "기존 포인트",
  lived_month: "생존",
  max_belong: "최대 임관년 수",
  max_domestic_critical: "최대 연속 내정 성공",
  active_action: "능동 행동 수",
  combat: "전투 횟수",
  sabotage: "계략 성공 횟수",
  unifier: "천통 기여",
  dex: "숙련도",
  tournament: "토너먼트",
  betting: "베팅 당첨",
};

/* ── Combat buff definitions ── */

const COMBAT_BUFF_LIST: {
  code: InheritBuffType;
  label: string;
  info: string;
}[] = [
  {
    code: "warAvoidRatio",
    label: "회피 확률 증가",
    info: "전투 시 회피 확률이 1%p ~ 5%p 증가합니다.",
  },
  {
    code: "warCriticalRatio",
    label: "필살 확률 증가",
    info: "전투 시 필살 확률이 1%p ~ 5%p 증가합니다.",
  },
  {
    code: "warMagicTrialProb",
    label: "계략 시도 확률 증가",
    info: "전투 시 계략을 시도할 확률이 1%p ~ 5%p 증가합니다.",
  },
  {
    code: "warAvoidRatioOppose",
    label: "상대 회피 확률 감소",
    info: "전투 시 상대의 회피 확률이 1%p ~ 5%p 감소합니다.",
  },
  {
    code: "warCriticalRatioOppose",
    label: "상대 필살 확률 감소",
    info: "전투 시 상대의 필살 확률이 1%p ~ 5%p 감소합니다.",
  },
  {
    code: "warMagicTrialProbOppose",
    label: "상대 계략 시도 확률 감소",
    info: "전투 시 상대의 계략 시도 확률이 1%p ~ 5%p 감소합니다.",
  },
  {
    code: "domesticSuccessProb",
    label: "내정 성공 확률 증가",
    info: "내정의 성공 확률이 1%p ~ 5%p 증가합니다.",
  },
  {
    code: "domesticFailProb",
    label: "내정 실패 확률 감소",
    info: "내정의 실패 확률이 1%p ~ 5%p 감소합니다.",
  },
];


/* ── Fallback constants (overridden by server) ── */

const FALLBACK_BUFF_LEVEL_COSTS = [0, 200, 600, 1200, 2000, 3000];
const MAX_BUFF_LEVEL = 5;

// Fibonacci cost for turn reset / special war reset
function fibonacciCost(base: number, count: number): number {
  if (count <= 0) return base;
  let a = base;
  let b = base;
  for (let i = 0; i < count; i++) {
    const next = a + b;
    a = b;
    b = next;
  }
  return b;
}

export default function InheritPage() {
  const { currentWorld } = useWorldStore();
  const [info, setInfo] = useState<InheritanceInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedSpecial, setSelectedSpecial] = useState("");
  const [cities, setCities] = useState<City[]>([]);
  const [selectedCity, setSelectedCity] = useState("");

  // Combat buffs
  const [combatBuffLevels, setCombatBuffLevels] = useState<
    Record<string, number>
  >({});

  // Stat reset
  const [statLeadership, setStatLeadership] = useState(0);
  const [statStrength, setStatStrength] = useState(0);
  const [statIntel, setStatIntel] = useState(0);
  const [bonusStat, setBonusStat] = useState<[number, number, number]>([
    0, 0, 0,
  ]);

  // Owner check (by ID dropdown)
  const [targetOwnerId, setTargetOwnerId] = useState<string>("");
  const [ownerResult, setOwnerResult] = useState<string | null>(null);

  // Unique auction
  const [selectedUnique, setSelectedUnique] = useState("");
  const [auctionBid, setAuctionBid] = useState(500);

  // Logs with pagination
  const [allLogs, setAllLogs] = useState<InheritanceLogEntry[]>([]);
  const [lastLogID, setLastLogID] = useState<number | null>(null);
  const [loadingMoreLogs, setLoadingMoreLogs] = useState(false);

  // Derived costs from server
  const actionCost: InheritanceActionCost = info?.inheritActionCost ?? {
    buff: FALLBACK_BUFF_LEVEL_COSTS,
    resetTurnTime: fibonacciCost(100, info?.turnResetCount ?? 0),
    resetSpecialWar: fibonacciCost(200, info?.specialWarResetCount ?? 0),
    randomUnique: 300,
    nextSpecial: 500,
    minSpecificUnique: 500,
    checkOwner: 50,
    bornStatPoint: 500,
  };

  const fetchInfo = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const [infoRes, cityRes] = await Promise.all([
        inheritanceApi.getInfo(currentWorld.id),
        cityApi.listByWorld(currentWorld.id),
      ]);
      const data = infoRes.data;
      setInfo(data);
      setCities(cityRes.data);

      // Initialize combat buff levels from server
      if (data.inheritBuff) {
        setCombatBuffLevels({ ...data.inheritBuff });
      }

      // Initialize stat reset from currentStat
      if (data.currentStat) {
        setStatLeadership(data.currentStat.leadership);
        setStatStrength(data.currentStat.strength);
        setStatIntel(data.currentStat.intel);
      }

      // Initialize logs
      if (data.log && data.log.length > 0) {
        setAllLogs(data.log);
        const minId = Math.min(...data.log.map((l) => l.id ?? Infinity));
        if (minId !== Infinity) setLastLogID(minId);
      }
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    fetchInfo();
  }, [fetchInfo]);


  /* ── Combat buff buy ── */
  const handleBuyCombatBuff = async (buffCode: InheritBuffType) => {
    if (!currentWorld || !info) return;
    const prevLevel = info.inheritBuff?.[buffCode] ?? 0;
    const targetLevel = combatBuffLevels[buffCode] ?? 0;
    if (targetLevel <= prevLevel) return;

    const cost = actionCost.buff[targetLevel] - actionCost.buff[prevLevel];
    if (info.points < cost) {
      toast.error(`포인트 부족 (필요: ${cost})`);
      return;
    }
    if (
      !confirm(
        `${COMBAT_BUFF_LIST.find((b) => b.code === buffCode)?.label}을(를) ${targetLevel}등급으로 올릴까요? ${cost} 포인트가 소모됩니다.`,
      )
    )
      return;
    try {
      await inheritanceApi.buyInheritBuff(currentWorld.id, {
        type: buffCode,
        level: targetLevel,
      });
      toast.success("버프 구매 완료");
      fetchInfo();
    } catch {
      toast.error("구매 실패");
    }
  };

  /* ── War special ── */
  const handleSetSpecial = async () => {
    if (!currentWorld || !info || !selectedSpecial) return;
    if (info.points < actionCost.nextSpecial) {
      toast.error(`포인트 부족 (필요: ${actionCost.nextSpecial})`);
      return;
    }
    try {
      await inheritanceApi.setSpecial(currentWorld.id, selectedSpecial);
      toast.success(`전투특기 지정 완료`);
      setSelectedSpecial("");
      fetchInfo();
    } catch {
      toast.error("전투특기 지정 실패");
    }
  };

  /* ── City ── */
  const handleSetCity = async () => {
    if (!currentWorld || !info || !selectedCity) return;
    try {
      await inheritanceApi.setCity(currentWorld.id, Number(selectedCity));
      const cityName = cities.find((c) => c.id === Number(selectedCity))?.name;
      toast.success(`시작 도시 지정: ${cityName}`);
      setSelectedCity("");
      fetchInfo();
    } catch {
      toast.error("시작 도시 지정 실패");
    }
  };

  const turnResetCost = actionCost.resetTurnTime;
  const specialWarResetCost = actionCost.resetSpecialWar;

  const handleResetTurn = async () => {
    if (!currentWorld || !info) return;
    if (info.points < turnResetCost) {
      toast.error(`포인트 부족 (필요: ${turnResetCost})`);
      return;
    }
    if (!confirm(`턴 시간을 초기화하시겠습니까? (비용: ${turnResetCost}P)`))
      return;
    try {
      await inheritanceApi.resetTurn(currentWorld.id);
      toast.success("턴 시간 초기화 완료");
      fetchInfo();
    } catch {
      toast.error("턴 시간 초기화 실패");
    }
  };

  const handleBuyRandomUnique = async () => {
    if (!currentWorld || !info) return;
    if (info.points < actionCost.randomUnique) {
      toast.error(`포인트 부족 (필요: ${actionCost.randomUnique})`);
      return;
    }
    if (
      !confirm(
        `랜덤 유니크 아이템을 획득하시겠습니까? (비용: ${actionCost.randomUnique}P)`,
      )
    )
      return;
    try {
      await inheritanceApi.buyRandomUnique(currentWorld.id);
      toast.success("랜덤 유니크 아이템 획득!");
      fetchInfo();
    } catch {
      toast.error("랜덤 유니크 획득 실패");
    }
  };

  const handleResetSpecialWar = async () => {
    if (!currentWorld || !info) return;
    if (info.points < specialWarResetCost) {
      toast.error(`포인트 부족 (필요: ${specialWarResetCost})`);
      return;
    }
    if (
      !confirm(`전투특기를 재배정하시겠습니까? (비용: ${specialWarResetCost}P)`)
    )
      return;
    try {
      await inheritanceApi.resetSpecialWar(currentWorld.id);
      toast.success("전투특기 재배정 완료");
      fetchInfo();
    } catch {
      toast.error("전투특기 재배정 실패");
    }
  };

  /* ── Stat reset with inheritBonusStat ── */
  const bonusStatTotal = bonusStat[0] + bonusStat[1] + bonusStat[2];
  const requiredResetStatPoint =
    bonusStatTotal > 0 ? actionCost.bornStatPoint : 0;

  const handleResetStats = async () => {
    if (!currentWorld || !info) return;
    if (requiredResetStatPoint > 0 && info.points < requiredResetStatPoint) {
      toast.error(`포인트 부족 (필요: ${requiredResetStatPoint})`);
      return;
    }
    if (
      !confirm(
        `능력치를 초기화 하시겠습니까? 시즌마다 한번만 가능합니다. 필요 포인트: ${requiredResetStatPoint}`,
      )
    )
      return;
    try {
      const payload: {
        leadership: number;
        strength: number;
        intel: number;
        inheritBonusStat?: [number, number, number];
      } = {
        leadership: statLeadership,
        strength: statStrength,
        intel: statIntel,
      };
      if (bonusStatTotal > 0) {
        payload.inheritBonusStat = bonusStat;
      }
      await inheritanceApi.resetStats(currentWorld.id, payload);
      toast.success("능력치 초기화 완료");
      fetchInfo();
    } catch {
      toast.error("능력치 초기화 실패");
    }
  };

  /* ── Owner check by ID ── */
  const handleCheckOwner = async () => {
    if (!currentWorld || !targetOwnerId) return;
    if (!info || info.points < actionCost.checkOwner) {
      toast.error(`포인트 부족 (필요: ${actionCost.checkOwner})`);
      return;
    }
    const targetName =
      info.availableTargetGeneral?.[Number(targetOwnerId)] ?? targetOwnerId;
    if (
      !confirm(
        `${targetName}의 소유자를 찾겠습니까? 대상에게도 알림이 전송됩니다.`,
      )
    )
      return;
    try {
      const { data } = await inheritanceApi.checkOwner(
        currentWorld.id,
        Number(targetOwnerId),
      );
      if (data.found) {
        setOwnerResult(`'${targetName}' 장수의 소유주: ${data.ownerName}`);
      } else {
        setOwnerResult(`'${targetName}' 장수를 찾을 수 없습니다.`);
      }
      fetchInfo();
    } catch {
      toast.error("소유주 확인 실패");
    }
  };

  /* ── Unique auction ── */
  const availableUnique = info?.availableUnique ?? {};
  const minBid = actionCost.minSpecificUnique;

  const handleAuctionUnique = async () => {
    if (!currentWorld || !info || !selectedUnique) return;
    if (auctionBid < minBid) {
      toast.error(`최소 입찰금: ${minBid}P`);
      return;
    }
    if (info.points < auctionBid) {
      toast.error(`포인트 부족 (필요: ${auctionBid})`);
      return;
    }
    const uniqueName = availableUnique[selectedUnique]?.title ?? selectedUnique;
    if (!confirm(`'${uniqueName}'을(를) ${auctionBid}P로 입찰하시겠습니까?`))
      return;
    try {
      await inheritanceApi.auctionUnique(currentWorld.id, {
        uniqueCode: selectedUnique,
        bidAmount: auctionBid,
      });
      toast.success(`유니크 입찰 완료: ${uniqueName}`);
      setSelectedUnique("");
      setAuctionBid(minBid);
      fetchInfo();
    } catch {
      toast.error("유니크 입찰 실패");
    }
  };

  /* ── Load more logs ── */
  const handleLoadMoreLogs = async () => {
    if (!currentWorld || lastLogID == null) return;
    setLoadingMoreLogs(true);
    try {
      const { data } = await inheritanceApi.getMoreLog(
        currentWorld.id,
        lastLogID,
      );
      if (data.log && data.log.length > 0) {
        setAllLogs((prev) => [...prev, ...data.log]);
        const minId = Math.min(...data.log.map((l) => l.id ?? Infinity));
        if (minId !== Infinity) setLastLogID(minId);
      } else {
        toast.info("더 이상 이력이 없습니다.");
      }
    } catch {
      toast.error("이력 로드 실패");
    } finally {
      setLoadingMoreLogs(false);
    }
  };

  // Dynamic lists from server
  const availableSpecialWar = info?.availableSpecialWar ?? {};
  const availableTargetGeneral = info?.availableTargetGeneral ?? {};
  const maxBuff = info?.maxInheritBuff ?? MAX_BUFF_LEVEL;
  const currentStat = info?.currentStat;

  if (loading)
    return <div className="p-4 text-muted-foreground">로딩 중...</div>;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Gift} title="유산 포인트" />

      {/* Point Summary with Detailed Breakdown */}
      <Card>
        <CardHeader>
          <CardTitle>현재 보유 포인트</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">총 포인트</span>
            <Badge variant="secondary" className="text-lg px-3 py-1">
              {info?.points ?? 0}
            </Badge>
          </div>

          {/* Detailed point breakdown by category */}
          {info?.pointBreakdown && (
            <div className="text-xs text-muted-foreground space-y-1 border-t border-gray-800 pt-2">
              {Object.entries(info.pointBreakdown).map(([key, value]) => {
                if (value === 0 && key !== "previous") return null;
                return (
                  <div key={key} className="flex justify-between">
                    <span>{POINT_CATEGORY_LABELS[key] ?? key}</span>
                    <span
                      className={
                        key === "previous"
                          ? ""
                          : value > 0
                            ? "text-green-400"
                            : "text-red-400"
                      }
                    >
                      {key === "previous"
                        ? `${Math.floor(value)}P`
                        : value > 0
                          ? `+${Math.floor(value)}P`
                          : `${Math.floor(value)}P`}
                    </span>
                  </div>
                );
              })}
            </div>
          )}

          {/* Fallback: old-style pointSources */}
          {!info?.pointBreakdown && info?.previousPoints != null && (
            <div className="text-xs text-muted-foreground space-y-1 border-t border-gray-800 pt-2">
              <div className="flex justify-between">
                <span>이전 잔여 포인트</span>
                <span>{info.previousPoints}P</span>
              </div>
              {info.newPoints != null && (
                <div className="flex justify-between text-green-400">
                  <span>신규 획득</span>
                  <span>+{info.newPoints}P</span>
                </div>
              )}
              {info.pointSources?.map((src, i) => (
                <div key={i} className="flex justify-between">
                  <span className="pl-2 text-muted-foreground/70">
                    └ {src.label}
                  </span>
                  <span
                    className={
                      src.amount > 0 ? "text-green-400" : "text-red-400"
                    }
                  >
                    {src.amount > 0 ? `+${src.amount}` : src.amount}P
                  </span>
                </div>
              ))}
            </div>
          )}

          <p className="text-sm text-muted-foreground">
            이전 게임에서 획득한 포인트로 새 장수에게 보너스를 줄 수 있습니다.
          </p>
        </CardContent>
      </Card>

      <Tabs defaultValue="combat-buffs">
        <TabsList className="flex-wrap">
          <TabsTrigger value="combat-buffs">전투 버프</TabsTrigger>
          <TabsTrigger value="specials">특기/도시</TabsTrigger>
          <TabsTrigger value="shop">상점</TabsTrigger>
          <TabsTrigger value="unique">유니크</TabsTrigger>
          <TabsTrigger value="misc">기타</TabsTrigger>
          <TabsTrigger value="log">이력</TabsTrigger>
        </TabsList>


        {/* ── Combat Buff Tab (8 buffs with granular level control) ── */}
        <TabsContent value="combat-buffs">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Shield className="size-4" />
                전투/내정 버프
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-xs text-muted-foreground">
                전투 및 내정에 영향을 주는 숨겨진 버프입니다. 레벨을 선택한 후
                구입하세요.
              </p>
              {COMBAT_BUFF_LIST.map((buff) => {
                const prevLevel = info?.inheritBuff?.[buff.code] ?? 0;
                const currentSelected =
                  combatBuffLevels[buff.code] ?? prevLevel;
                const cost =
                  currentSelected > prevLevel
                    ? actionCost.buff[currentSelected] -
                      actionCost.buff[prevLevel]
                    : 0;
                return (
                  <div
                    key={buff.code}
                    className="border border-gray-800 rounded-md p-3 space-y-2"
                  >
                    <div className="flex items-center justify-between">
                      <div>
                        <span className="text-sm font-medium">
                          {buff.label}
                        </span>
                        {prevLevel > 0 && (
                          <Badge variant="outline" className="text-xs ml-2">
                            현재 Lv.{prevLevel}
                          </Badge>
                        )}
                      </div>
                      <Button
                        size="sm"
                        disabled={
                          !info ||
                          currentSelected <= prevLevel ||
                          info.points < cost
                        }
                        onClick={() => handleBuyCombatBuff(buff.code)}
                      >
                        구입 ({cost}P)
                      </Button>
                    </div>
                    <p className="text-xs text-muted-foreground">{buff.info}</p>
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-muted-foreground w-16">
                        목표 레벨:
                      </span>
                      <Input
                        type="number"
                        min={prevLevel}
                        max={maxBuff}
                        value={currentSelected}
                        onChange={(e) => {
                          const val = Math.max(
                            prevLevel,
                            Math.min(maxBuff, Number(e.target.value)),
                          );
                          setCombatBuffLevels((prev) => ({
                            ...prev,
                            [buff.code]: val,
                          }));
                        }}
                        className="w-20"
                      />
                      <Button
                        size="sm"
                        variant="ghost"
                        className="text-xs"
                        onClick={() =>
                          setCombatBuffLevels((prev) => ({
                            ...prev,
                            [buff.code]: prevLevel,
                          }))
                        }
                      >
                        리셋
                      </Button>
                    </div>
                  </div>
                );
              })}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Specials / City Tab ── */}
        <TabsContent value="specials" className="space-y-4">
          {/* War Special Designation (dynamic from server) */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Swords className="size-4" />
                전투특기 지정 ({actionCost.nextSpecial}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                다음에 배정받을 전투특기를 지정합니다.
              </p>
              <div className="flex gap-2">
                <select
                  className="flex-1 rounded-md border bg-background px-3 py-2 text-sm"
                  value={selectedSpecial}
                  onChange={(e) => setSelectedSpecial(e.target.value)}
                >
                  <option value="">선택...</option>
                  {Object.entries(availableSpecialWar).map(([key, val]) => (
                    <option key={key} value={key}>
                      {val.title}
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  disabled={
                    !selectedSpecial ||
                    !info ||
                    info.points < actionCost.nextSpecial
                  }
                  onClick={handleSetSpecial}
                >
                  지정
                </Button>
              </div>
              {selectedSpecial && availableSpecialWar[selectedSpecial] && (
                <p
                  className="text-xs text-muted-foreground"
                  dangerouslySetInnerHTML={{
                    __html: availableSpecialWar[selectedSpecial].info,
                  }}
                />
              )}
            </CardContent>
          </Card>

          {/* Start City Designation */}
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">시작 도시 지정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                다음에 시작할 도시를 지정합니다.
              </p>
              <div className="flex gap-2">
                <select
                  className="flex-1 rounded-md border bg-background px-3 py-2 text-sm"
                  value={selectedCity}
                  onChange={(e) => setSelectedCity(e.target.value)}
                >
                  <option value="">선택...</option>
                  {cities.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  disabled={!selectedCity}
                  onClick={handleSetCity}
                >
                  지정
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Shop Tab ── */}
        <TabsContent value="shop" className="space-y-4">
          {/* Turn Reset */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <RotateCcw className="size-4" />
                랜덤 턴 초기화 ({turnResetCost}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                다다음턴부터 시간이 랜덤하게 바뀝니다. (필요 포인트가
                피보나치식으로 증가합니다)
              </p>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">
                  구매 횟수: {info?.turnResetCount ?? 0}회
                </span>
                <Button
                  size="sm"
                  disabled={!info || info.points < turnResetCost}
                  onClick={handleResetTurn}
                >
                  초기화
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Special War Reset */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Swords className="size-4" />
                즉시 전투특기 초기화 ({specialWarResetCost}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                즉시 전투 특기를 초기화합니다. (필요 포인트가 피보나치식으로
                증가합니다)
              </p>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">
                  구매 횟수: {info?.specialWarResetCount ?? 0}회
                </span>
                <Button
                  size="sm"
                  disabled={!info || info.points < specialWarResetCost}
                  onClick={handleResetSpecialWar}
                >
                  재배정
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Stat Reset with inheritBonusStat */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <BarChart3 className="size-4" />
                능력치 초기화 ({requiredResetStatPoint}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                시즌 당 1회에 한해 능력치를 초기화합니다.
              </p>
              <div className="space-y-2">
                <p className="text-xs font-medium">기본 능력치</p>
                <div className="grid grid-cols-3 gap-2">
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      통솔
                    </label>
                    <Input
                      type="number"
                      min={currentStat?.statMin ?? 0}
                      max={currentStat?.statMax ?? 100}
                      value={statLeadership}
                      onChange={(e) =>
                        setStatLeadership(Number(e.target.value))
                      }
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      무력
                    </label>
                    <Input
                      type="number"
                      min={currentStat?.statMin ?? 0}
                      max={currentStat?.statMax ?? 100}
                      value={statStrength}
                      onChange={(e) => setStatStrength(Number(e.target.value))}
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      지력
                    </label>
                    <Input
                      type="number"
                      min={currentStat?.statMin ?? 0}
                      max={currentStat?.statMax ?? 100}
                      value={statIntel}
                      onChange={(e) => setStatIntel(Number(e.target.value))}
                    />
                  </div>
                </div>
                <p className="text-xs font-medium mt-2">추가 능력치 (보너스)</p>
                <div className="grid grid-cols-3 gap-2">
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      통솔
                    </label>
                    <Input
                      type="number"
                      min={0}
                      max={5}
                      value={bonusStat[0]}
                      onChange={(e) => {
                        const v = Math.max(
                          0,
                          Math.min(5, Number(e.target.value)),
                        );
                        setBonusStat((prev) => [v, prev[1], prev[2]]);
                      }}
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      무력
                    </label>
                    <Input
                      type="number"
                      min={0}
                      max={5}
                      value={bonusStat[1]}
                      onChange={(e) => {
                        const v = Math.max(
                          0,
                          Math.min(5, Number(e.target.value)),
                        );
                        setBonusStat((prev) => [prev[0], v, prev[2]]);
                      }}
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs text-muted-foreground">
                      지력
                    </label>
                    <Input
                      type="number"
                      min={0}
                      max={5}
                      value={bonusStat[2]}
                      onChange={(e) => {
                        const v = Math.max(
                          0,
                          Math.min(5, Number(e.target.value)),
                        );
                        setBonusStat((prev) => [prev[0], prev[1], v]);
                      }}
                    />
                  </div>
                </div>
                <div className="flex items-center justify-between pt-1">
                  <span className="text-xs text-muted-foreground">
                    필요 포인트: {requiredResetStatPoint}P
                  </span>
                  <Button size="sm" onClick={handleResetStats}>
                    능력치 초기화
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Unique Tab ── */}
        <TabsContent value="unique" className="space-y-4">
          {/* Random Unique */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Dices className="size-4" />
                랜덤 유니크 획득 ({actionCost.randomUnique}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                다음 턴에 랜덤 유니크를 얻습니다.
              </p>
              <Button
                size="sm"
                disabled={!info || info.points < actionCost.randomUnique}
                onClick={handleBuyRandomUnique}
              >
                랜덤 유니크 획득
              </Button>
            </CardContent>
          </Card>

          {/* Unique Auction (dynamic from server) */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Crown className="size-4" />
                특정 유니크 입찰 (최소 {minBid}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                얻고자 하는 유니크 아이템으로 경매를 시작합니다. 24턴 동안
                진행됩니다.
              </p>
              <div className="space-y-2">
                <select
                  className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                  value={selectedUnique}
                  onChange={(e) => setSelectedUnique(e.target.value)}
                >
                  <option value="">유니크 선택...</option>
                  {Object.entries(availableUnique).map(([key, val]) => (
                    <option key={key} value={key}>
                      {val.title}
                    </option>
                  ))}
                </select>
                {selectedUnique && availableUnique[selectedUnique] && (
                  <p
                    className="text-xs text-muted-foreground"
                    dangerouslySetInnerHTML={{
                      __html: availableUnique[selectedUnique].info,
                    }}
                  />
                )}
                <div className="flex gap-2 items-center">
                  <Input
                    type="number"
                    min={minBid}
                    value={auctionBid}
                    onChange={(e) =>
                      setAuctionBid(Math.max(minBid, Number(e.target.value)))
                    }
                    className="flex-1"
                    placeholder={`최소 ${minBid}P`}
                  />
                  <span className="text-sm text-muted-foreground">P</span>
                </div>
                <Button
                  size="sm"
                  disabled={
                    !selectedUnique ||
                    !info ||
                    info.points < auctionBid ||
                    auctionBid < minBid
                  }
                  onClick={handleAuctionUnique}
                >
                  경매 시작
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Misc Tab (owner check by ID) ── */}
        <TabsContent value="misc" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Search className="size-4" />
                장수 소유주 확인 ({actionCost.checkOwner}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                특정 장수의 실제 소유주(유저)를 확인합니다. 대상에게도 알림이
                전송됩니다.
              </p>
              <div className="flex gap-2">
                <select
                  className="flex-1 rounded-md border bg-background px-3 py-2 text-sm"
                  value={targetOwnerId}
                  onChange={(e) => setTargetOwnerId(e.target.value)}
                >
                  <option value="">장수 선택...</option>
                  {Object.entries(availableTargetGeneral).map(([id, name]) => (
                    <option key={id} value={id}>
                      {name}
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  disabled={
                    !targetOwnerId ||
                    !info ||
                    info.points < actionCost.checkOwner
                  }
                  onClick={handleCheckOwner}
                >
                  소유자 찾기
                </Button>
              </div>
              {ownerResult && (
                <div className="text-sm p-2 rounded bg-muted">
                  {ownerResult}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Log Tab with pagination ── */}
        <TabsContent value="log">
          {allLogs.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>포인트 변경 내역</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                <div className="space-y-1 text-sm max-h-96 overflow-y-auto">
                  {allLogs.map((entry, idx) => (
                    <div
                      key={entry.id ?? idx}
                      className="flex justify-between text-muted-foreground"
                    >
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground/60">
                          [{entry.date}]
                        </span>
                        <span>{entry.text ?? entry.action}</span>
                      </div>
                      {entry.amount !== undefined && (
                        <span
                          className={
                            entry.amount > 0 ? "text-green-400" : "text-red-400"
                          }
                        >
                          {entry.amount > 0 ? `+${entry.amount}` : entry.amount}
                          P
                        </span>
                      )}
                    </div>
                  ))}
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full"
                  disabled={loadingMoreLogs}
                  onClick={handleLoadMoreLogs}
                >
                  <ChevronDown className="size-3 mr-1" />
                  {loadingMoreLogs ? "로딩 중..." : "더 가져오기"}
                </Button>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="py-8 text-center text-muted-foreground text-sm">
                포인트 이력이 없습니다.
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
