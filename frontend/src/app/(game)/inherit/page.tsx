"use client";

import { useEffect, useState, useCallback } from "react";
import { Gift } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useWorldStore } from "@/stores/worldStore";
import { inheritanceApi, cityApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { InheritanceInfo, City } from "@/types";

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
  "기병",
  "보병",
  "궁병",
  "필살",
  "회피",
  "화공",
  "기습",
  "저격",
  "매복",
  "방어",
  "돌격",
  "반계",
  "신산",
  "귀모",
  "수군",
  "연사",
  "공성",
  "위압",
  "격노",
  "분투",
  "용병",
  "철벽",
];

const INHERIT_SPECIAL_COST = 500;
const INHERIT_CITY_COST = 300;

export default function InheritPage() {
  const { currentWorld } = useWorldStore();
  const [info, setInfo] = useState<InheritanceInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedSpecial, setSelectedSpecial] = useState("");
  const [cities, setCities] = useState<City[]>([]);
  const [selectedCity, setSelectedCity] = useState("");

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

  if (loading)
    return <div className="p-4 text-muted-foreground">로딩 중...</div>;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Gift} title="유산 포인트" />

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
          <p className="text-sm text-muted-foreground">
            이전 게임에서 획득한 포인트로 새 장수에게 보너스를 줄 수 있습니다.
          </p>
        </CardContent>
      </Card>

      {/* Buff Purchase */}
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
                    <span className="text-xs text-muted-foreground">(MAX)</span>
                  )}
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!info || nextCost == null || info.points < nextCost}
                  onClick={() => handleBuy(buff.code)}
                >
                  구매
                </Button>
              </div>
            );
          })}
        </CardContent>
      </Card>

      {/* War Special Designation */}
      <Card>
        <CardHeader>
          <CardTitle>전투특기 지정 ({INHERIT_SPECIAL_COST}P)</CardTitle>
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
                !selectedSpecial || !info || info.points < INHERIT_SPECIAL_COST
              }
              onClick={handleSetSpecial}
            >
              지정
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* City Designation */}
      <Card>
        <CardHeader>
          <CardTitle>시작 도시 지정 ({INHERIT_CITY_COST}P)</CardTitle>
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

      {/* Point Log */}
      {info?.log && info.log.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>포인트 이력</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-1 text-sm">
              {info.log.map((entry, idx) => (
                <div
                  key={idx}
                  className="flex justify-between text-muted-foreground"
                >
                  <span>{entry.action}</span>
                  <span>
                    {entry.amount > 0 ? `+${entry.amount}` : entry.amount}P
                  </span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
