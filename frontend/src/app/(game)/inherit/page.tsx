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
import type { InheritanceInfo, City } from "@/types";

/* ── Constants ── */

const BUFF_LEVEL_COSTS = [100, 200, 400, 800, 1600];
const MAX_BUFF_LEVEL = 5;

const BUFF_LIST = [
  { code: "leadership", label: "통솔 +1/레벨", type: "stat" },
  { code: "strength", label: "무력 +1/레벨", type: "stat" },
  { code: "intel", label: "지력 +1/레벨", type: "stat" },
  { code: "politics", label: "정치 +1/레벨", type: "stat" },
  { code: "charm", label: "매력 +1/레벨", type: "stat" },
  { code: "gold", label: "초기 금 +500/레벨", type: "resource" },
  { code: "rice", label: "초기 쌀 +500/레벨", type: "resource" },
  { code: "crew", label: "초기 병력 +200/레벨", type: "resource" },
  { code: "exp", label: "초기 경험 +100/레벨", type: "resource" },
];

const WAR_SPECIALS = [
  "기병", "보병", "궁병", "필살", "회피", "화공", "기습", "저격",
  "매복", "방어", "돌격", "반계", "신산", "귀모", "수군", "연사",
  "공성", "위압", "격노", "분투", "용병", "철벽",
];

const UNIQUE_ITEMS = [
  { code: "unique_sword_1", name: "청룡언월도" },
  { code: "unique_sword_2", name: "사모" },
  { code: "unique_sword_3", name: "방천화극" },
  { code: "unique_book_1", name: "맹덕신서" },
  { code: "unique_book_2", name: "태평요술" },
  { code: "unique_book_3", name: "둔갑천서" },
  { code: "unique_horse_1", name: "적토마" },
  { code: "unique_horse_2", name: "절영" },
  { code: "unique_horse_3", name: "적로" },
  { code: "unique_item_1", name: "옥새" },
  { code: "unique_item_2", name: "의형제술" },
  { code: "unique_item_3", name: "칠성검" },
];

const INHERIT_SPECIAL_COST = 500;
const INHERIT_CITY_COST = 300;
const RANDOM_UNIQUE_COST = 300;
const STAT_RESET_COST = 500;
const CHECK_OWNER_COST = 50;
const UNIQUE_AUCTION_MIN_BID = 500;

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

const TURN_RESET_BASE = 100;
const SPECIAL_WAR_RESET_BASE = 200;

export default function InheritPage() {
  const { currentWorld } = useWorldStore();
  const [info, setInfo] = useState<InheritanceInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedSpecial, setSelectedSpecial] = useState("");
  const [cities, setCities] = useState<City[]>([]);
  const [selectedCity, setSelectedCity] = useState("");

  // Stat reset
  const [statLeadership, setStatLeadership] = useState(0);
  const [statStrength, setStatStrength] = useState(0);
  const [statIntel, setStatIntel] = useState(0);

  // Owner check
  const [ownerQuery, setOwnerQuery] = useState("");
  const [ownerResult, setOwnerResult] = useState<string | null>(null);

  // Unique auction
  const [selectedUnique, setSelectedUnique] = useState("");
  const [auctionBid, setAuctionBid] = useState(UNIQUE_AUCTION_MIN_BID);

  const fetchInfo = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const [infoRes, cityRes] = await Promise.all([
        inheritanceApi.getInfo(currentWorld.id),
        cityApi.listByWorld(currentWorld.id),
      ]);
      setInfo(infoRes.data);
      setCities(cityRes.data);
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    fetchInfo();
  }, [fetchInfo]);

  const handleBuy = async (buffCode: string) => {
    if (!currentWorld || !info) return;
    const currentLevel = info.buffs[buffCode] ?? 0;
    if (currentLevel >= MAX_BUFF_LEVEL) {
      toast.error("최대 레벨에 도달했습니다");
      return;
    }
    const cost = BUFF_LEVEL_COSTS[currentLevel];
    if (info.points < cost) {
      toast.error(`포인트 부족 (필요: ${cost})`);
      return;
    }
    try {
      await inheritanceApi.buy(currentWorld.id, buffCode);
      toast.success("버프 구매 완료");
      fetchInfo();
    } catch {
      toast.error("구매 실패");
    }
  };

  const handleSetSpecial = async () => {
    if (!currentWorld || !info || !selectedSpecial) return;
    if (info.points < INHERIT_SPECIAL_COST) {
      toast.error(`포인트 부족 (필요: ${INHERIT_SPECIAL_COST})`);
      return;
    }
    try {
      await inheritanceApi.setSpecial(currentWorld.id, selectedSpecial);
      toast.success(`전투특기 지정: ${selectedSpecial}`);
      setSelectedSpecial("");
      fetchInfo();
    } catch {
      toast.error("전투특기 지정 실패");
    }
  };

  const handleSetCity = async () => {
    if (!currentWorld || !info || !selectedCity) return;
    if (info.points < INHERIT_CITY_COST) {
      toast.error(`포인트 부족 (필요: ${INHERIT_CITY_COST})`);
      return;
    }
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

  const turnResetCost = fibonacciCost(
    TURN_RESET_BASE,
    info?.turnResetCount ?? 0,
  );
  const specialWarResetCost = fibonacciCost(
    SPECIAL_WAR_RESET_BASE,
    info?.specialWarResetCount ?? 0,
  );

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
    if (info.points < RANDOM_UNIQUE_COST) {
      toast.error(`포인트 부족 (필요: ${RANDOM_UNIQUE_COST})`);
      return;
    }
    if (
      !confirm(`랜덤 유니크 아이템을 획득하시겠습니까? (비용: ${RANDOM_UNIQUE_COST}P)`)
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
      !confirm(
        `전투특기를 재배정하시겠습니까? (비용: ${specialWarResetCost}P)`,
      )
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

  const handleResetStats = async () => {
    if (!currentWorld || !info) return;
    const total = statLeadership + statStrength + statIntel;
    if (total <= 0) {
      toast.error("능력치를 입력해주세요");
      return;
    }
    if (info.points < STAT_RESET_COST) {
      toast.error(`포인트 부족 (필요: ${STAT_RESET_COST})`);
      return;
    }
    if (
      !confirm(
        `능력치를 재분배하시겠습니까?\n통솔: ${statLeadership}, 무력: ${statStrength}, 지력: ${statIntel}\n(비용: ${STAT_RESET_COST}P)`,
      )
    )
      return;
    try {
      await inheritanceApi.resetStats(currentWorld.id, {
        leadership: statLeadership,
        strength: statStrength,
        intel: statIntel,
      });
      toast.success("능력치 재분배 완료");
      setStatLeadership(0);
      setStatStrength(0);
      setStatIntel(0);
      fetchInfo();
    } catch {
      toast.error("능력치 재분배 실패");
    }
  };

  const handleCheckOwner = async () => {
    if (!currentWorld || !ownerQuery.trim()) return;
    if (!info || info.points < CHECK_OWNER_COST) {
      toast.error(`포인트 부족 (필요: ${CHECK_OWNER_COST})`);
      return;
    }
    try {
      const { data } = await inheritanceApi.checkOwner(
        currentWorld.id,
        ownerQuery.trim(),
      );
      if (data.found) {
        setOwnerResult(`'${ownerQuery}' 장수의 소유주: ${data.ownerName}`);
      } else {
        setOwnerResult(`'${ownerQuery}' 장수를 찾을 수 없습니다.`);
      }
      fetchInfo();
    } catch {
      toast.error("소유주 확인 실패");
    }
  };

  const handleAuctionUnique = async () => {
    if (!currentWorld || !info || !selectedUnique) return;
    if (auctionBid < UNIQUE_AUCTION_MIN_BID) {
      toast.error(`최소 입찰금: ${UNIQUE_AUCTION_MIN_BID}P`);
      return;
    }
    if (info.points < auctionBid) {
      toast.error(`포인트 부족 (필요: ${auctionBid})`);
      return;
    }
    const uniqueName =
      UNIQUE_ITEMS.find((u) => u.code === selectedUnique)?.name ??
      selectedUnique;
    if (
      !confirm(
        `'${uniqueName}' 유니크를 ${auctionBid}P로 입찰하시겠습니까?`,
      )
    )
      return;
    try {
      await inheritanceApi.auctionUnique(currentWorld.id, {
        uniqueCode: selectedUnique,
        bidAmount: auctionBid,
      });
      toast.success(`유니크 입찰 완료: ${uniqueName}`);
      setSelectedUnique("");
      setAuctionBid(UNIQUE_AUCTION_MIN_BID);
      fetchInfo();
    } catch {
      toast.error("유니크 입찰 실패");
    }
  };

  if (loading)
    return <div className="p-4 text-muted-foreground">로딩 중...</div>;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Gift} title="유산 포인트" />

      {/* Point Summary with Breakdown */}
      <Card>
        <CardHeader>
          <CardTitle>현재 보유 포인트</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">포인트</span>
            <Badge variant="secondary" className="text-lg px-3 py-1">
              {info?.points ?? 0}
            </Badge>
          </div>
          {/* Point breakdown */}
          {info?.previousPoints != null && (
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

      <Tabs defaultValue="buffs">
        <TabsList className="flex-wrap">
          <TabsTrigger value="buffs">버프 구매</TabsTrigger>
          <TabsTrigger value="specials">특기/도시</TabsTrigger>
          <TabsTrigger value="shop">상점</TabsTrigger>
          <TabsTrigger value="unique">유니크</TabsTrigger>
          <TabsTrigger value="misc">기타</TabsTrigger>
          <TabsTrigger value="log">이력</TabsTrigger>
        </TabsList>

        {/* ── Buff Purchase Tab ── */}
        <TabsContent value="buffs">
          <Card>
            <CardHeader>
              <CardTitle>버프 구매</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {BUFF_LIST.map((buff) => {
                const currentLevel = info?.buffs[buff.code] ?? 0;
                const nextCost =
                  currentLevel < MAX_BUFF_LEVEL
                    ? BUFF_LEVEL_COSTS[currentLevel]
                    : null;
                return (
                  <div
                    key={buff.code}
                    className="flex items-center justify-between py-1"
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-sm">{buff.label}</span>
                      {currentLevel > 0 && (
                        <Badge variant="outline" className="text-xs">
                          Lv.{currentLevel}
                        </Badge>
                      )}
                      {nextCost != null && (
                        <span className="text-xs text-muted-foreground">
                          ({nextCost}P)
                        </span>
                      )}
                      {nextCost == null && (
                        <span className="text-xs text-muted-foreground">
                          (MAX)
                        </span>
                      )}
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={
                        !info || nextCost == null || info.points < nextCost
                      }
                      onClick={() => handleBuy(buff.code)}
                    >
                      구매
                    </Button>
                  </div>
                );
              })}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Specials / City Tab ── */}
        <TabsContent value="specials" className="space-y-4">
          {/* War Special Designation */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Swords className="size-4" />
                전투특기 지정 ({INHERIT_SPECIAL_COST}P)
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
                  {WAR_SPECIALS.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  disabled={
                    !selectedSpecial ||
                    !info ||
                    info.points < INHERIT_SPECIAL_COST
                  }
                  onClick={handleSetSpecial}
                >
                  지정
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Start City Designation */}
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">
                시작 도시 지정 ({INHERIT_CITY_COST}P)
              </CardTitle>
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
                  disabled={
                    !selectedCity || !info || info.points < INHERIT_CITY_COST
                  }
                  onClick={handleSetCity}
                >
                  지정
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Shop Tab (turn reset, special war reset, stat reset) ── */}
        <TabsContent value="shop" className="space-y-4">
          {/* Turn Reset */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <RotateCcw className="size-4" />
                턴 시간 초기화 ({turnResetCost}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                턴 시간을 초기화합니다. 구매 횟수가 늘어날수록 비용이
                증가합니다. (피보나치 증가)
              </p>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">
                  구매 횟수: {info?.turnResetCount ?? 0}회 / 다음 비용:{" "}
                  {turnResetCost}P
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
                전투특기 재배정 ({specialWarResetCost}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                현재 전투특기를 랜덤으로 재배정합니다. 구매 횟수가 늘어날수록
                비용이 증가합니다.
              </p>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">
                  구매 횟수: {info?.specialWarResetCount ?? 0}회 / 다음 비용:{" "}
                  {specialWarResetCost}P
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

          {/* Stat Reset */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <BarChart3 className="size-4" />
                능력치 재분배 ({STAT_RESET_COST}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                통솔/무력/지력 능력치를 재분배합니다. 재분배할 수치를 입력하세요.
                (보너스 스탯 범위 내에서 자유롭게 분배)
              </p>
              <div className="grid grid-cols-3 gap-2">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">통솔</label>
                  <Input
                    type="number"
                    min={0}
                    max={100}
                    value={statLeadership}
                    onChange={(e) =>
                      setStatLeadership(
                        Math.max(0, Math.min(100, Number(e.target.value))),
                      )
                    }
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">무력</label>
                  <Input
                    type="number"
                    min={0}
                    max={100}
                    value={statStrength}
                    onChange={(e) =>
                      setStatStrength(
                        Math.max(0, Math.min(100, Number(e.target.value))),
                      )
                    }
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">지력</label>
                  <Input
                    type="number"
                    min={0}
                    max={100}
                    value={statIntel}
                    onChange={(e) =>
                      setStatIntel(
                        Math.max(0, Math.min(100, Number(e.target.value))),
                      )
                    }
                  />
                </div>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">
                  합계: {statLeadership + statStrength + statIntel}
                </span>
                <Button
                  size="sm"
                  disabled={
                    !info ||
                    info.points < STAT_RESET_COST ||
                    statLeadership + statStrength + statIntel <= 0
                  }
                  onClick={handleResetStats}
                >
                  재분배
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Unique Tab (random unique, unique auction) ── */}
        <TabsContent value="unique" className="space-y-4">
          {/* Random Unique */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Dices className="size-4" />
                랜덤 유니크 획득 ({RANDOM_UNIQUE_COST}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                랜덤으로 유니크 아이템 1개를 획득합니다. 어떤 아이템이 나올지
                알 수 없습니다.
              </p>
              <Button
                size="sm"
                disabled={!info || info.points < RANDOM_UNIQUE_COST}
                onClick={handleBuyRandomUnique}
              >
                랜덤 유니크 획득
              </Button>
            </CardContent>
          </Card>

          {/* Unique Auction */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Crown className="size-4" />
                특정 유니크 입찰 (최소 {UNIQUE_AUCTION_MIN_BID}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                원하는 유니크 아이템에 포인트로 입찰합니다. 가장 높은 금액을
                입찰한 유저가 획득합니다. 낙찰 실패 시 포인트는 환불됩니다.
              </p>
              <div className="space-y-2">
                <select
                  className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                  value={selectedUnique}
                  onChange={(e) => setSelectedUnique(e.target.value)}
                >
                  <option value="">유니크 선택...</option>
                  {UNIQUE_ITEMS.map((u) => (
                    <option key={u.code} value={u.code}>
                      {u.name}
                    </option>
                  ))}
                </select>
                <div className="flex gap-2 items-center">
                  <Input
                    type="number"
                    min={UNIQUE_AUCTION_MIN_BID}
                    value={auctionBid}
                    onChange={(e) =>
                      setAuctionBid(
                        Math.max(
                          UNIQUE_AUCTION_MIN_BID,
                          Number(e.target.value),
                        ),
                      )
                    }
                    className="flex-1"
                    placeholder={`최소 ${UNIQUE_AUCTION_MIN_BID}P`}
                  />
                  <span className="text-sm text-muted-foreground">P</span>
                </div>
                <Button
                  size="sm"
                  disabled={
                    !selectedUnique ||
                    !info ||
                    info.points < auctionBid ||
                    auctionBid < UNIQUE_AUCTION_MIN_BID
                  }
                  onClick={handleAuctionUnique}
                >
                  입찰
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Misc Tab (owner check) ── */}
        <TabsContent value="misc" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Search className="size-4" />
                장수 소유주 확인 ({CHECK_OWNER_COST}P)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm text-muted-foreground">
                특정 장수의 실제 소유주(유저)를 확인합니다.
              </p>
              <div className="flex gap-2">
                <Input
                  value={ownerQuery}
                  onChange={(e) => setOwnerQuery(e.target.value)}
                  placeholder="장수 이름 입력..."
                  className="flex-1"
                />
                <Button
                  size="sm"
                  disabled={
                    !ownerQuery.trim() ||
                    !info ||
                    info.points < CHECK_OWNER_COST
                  }
                  onClick={handleCheckOwner}
                >
                  확인
                </Button>
              </div>
              {ownerResult && (
                <div className="text-sm p-2 rounded bg-muted">{ownerResult}</div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Log Tab ── */}
        <TabsContent value="log">
          {info?.log && info.log.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>포인트 이력</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-1 text-sm max-h-96 overflow-y-auto">
                  {info.log.map((entry, idx) => (
                    <div
                      key={idx}
                      className="flex justify-between text-muted-foreground"
                    >
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground/60">
                          {entry.date}
                        </span>
                        <span>{entry.action}</span>
                      </div>
                      <span
                        className={
                          entry.amount > 0 ? "text-green-400" : "text-red-400"
                        }
                      >
                        {entry.amount > 0 ? `+${entry.amount}` : entry.amount}P
                      </span>
                    </div>
                  ))}
                </div>
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
