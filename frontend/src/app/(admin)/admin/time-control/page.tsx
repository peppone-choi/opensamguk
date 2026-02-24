"use client";

import { useEffect, useState, useCallback } from "react";
import { Clock, Coins, Wheat, Timer, Gavel, AlertTriangle, Lock, Unlock } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";

const TURN_PRESETS = [1, 2, 5, 10, 20, 30, 60, 120];

export default function AdminTimeControlPage() {
  const [loading, setLoading] = useState(true);
  const [year, setYear] = useState("");
  const [month, setMonth] = useState("");
  const [startYear, setStartYear] = useState("");
  const [locked, setLocked] = useState(false);
  const [turnTerm, setTurnTerm] = useState("");
  const [customTurnTerm, setCustomTurnTerm] = useState("");
  const [lastTurnTime, setLastTurnTime] = useState("");

  // Gold/Rice distribution
  const [goldAmount, setGoldAmount] = useState("");
  const [riceAmount, setRiceAmount] = useState("");
  const [distributeTarget, setDistributeTarget] = useState<"all" | "nations">("all");

  // Auction time
  const [auctionSyncEnabled, setAuctionSyncEnabled] = useState(false);
  const [auctionCloseMinutes, setAuctionCloseMinutes] = useState("60");

  useEffect(() => {
    adminApi
      .getDashboard()
      .then((res) => {
        const w = res.data.currentWorld;
        if (w) {
          setYear(String(w.year));
          setMonth(String(w.month));
          setLocked(Boolean(w.config?.locked));
          setTurnTerm(String(w.config?.turnTerm ?? ""));
          setStartYear(String(w.config?.startyear ?? ""));
          setLastTurnTime(String(w.config?.turntime ?? ""));
          setAuctionSyncEnabled(Boolean(w.config?.auctionSync));
          setAuctionCloseMinutes(String(w.config?.auctionCloseMinutes ?? 60));
        }
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const handleTimeSubmit = useCallback(async () => {
    try {
      await adminApi.timeControl({
        year: year ? Number(year) : undefined,
        month: month ? Number(month) : undefined,
        startYear: startYear ? Number(startYear) : undefined,
        locked,
      });
      toast.success("시간 설정이 변경되었습니다.");
    } catch {
      toast.error("변경 실패");
    }
  }, [year, month, startYear, locked]);

  const handleTurnTermChange = useCallback(async (minutes: number) => {
    try {
      await adminApi.timeControl({ turnTerm: minutes });
      setTurnTerm(String(minutes));
      toast.success(`턴 시간이 ${minutes}분으로 변경되었습니다.`);
    } catch {
      toast.error("턴 시간 변경 실패");
    }
  }, []);

  const handleCustomTurnTerm = useCallback(async () => {
    const minutes = Number(customTurnTerm);
    if (!minutes || minutes < 1 || minutes > 1440) {
      toast.error("1~1440분 사이의 값을 입력하세요.");
      return;
    }
    await handleTurnTermChange(minutes);
    setCustomTurnTerm("");
  }, [customTurnTerm, handleTurnTermChange]);

  const handleDistribute = useCallback(async () => {
    const gold = Number(goldAmount) || 0;
    const rice = Number(riceAmount) || 0;
    if (gold === 0 && rice === 0) {
      toast.error("금 또는 쌀 수량을 입력하세요.");
      return;
    }
    try {
      await adminApi.timeControl({
        distribute: { gold, rice, target: distributeTarget },
      });
      toast.success(`금 ${gold.toLocaleString()}, 쌀 ${rice.toLocaleString()} 지급 완료 (${distributeTarget === "all" ? "전체 장수" : "국가별"})`);
      setGoldAmount("");
      setRiceAmount("");
    } catch {
      toast.error("지급 실패");
    }
  }, [goldAmount, riceAmount, distributeTarget]);

  const handleAuctionSync = useCallback(async () => {
    try {
      await adminApi.timeControl({
        auctionSync: auctionSyncEnabled,
        auctionCloseMinutes: Number(auctionCloseMinutes) || 60,
      });
      toast.success("경매 시간 설정이 변경되었습니다.");
    } catch {
      toast.error("경매 시간 설정 실패");
    }
  }, [auctionSyncEnabled, auctionCloseMinutes]);

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4 max-w-2xl">
      <PageHeader icon={Clock} title="시간 제어" />

      {/* Game Time */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Clock className="size-4" />
            게임 시간
          </CardTitle>
          {lastTurnTime && (
            <CardDescription>최근 갱신: {lastTurnTime}</CardDescription>
          )}
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">시작 년도</label>
              <Input
                type="number"
                value={startYear}
                onChange={(e) => setStartYear(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">현재 년</label>
              <Input
                type="number"
                value={year}
                onChange={(e) => setYear(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">현재 월</label>
              <Input
                type="number"
                value={month}
                onChange={(e) => setMonth(e.target.value)}
                min={1}
                max={12}
              />
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setLocked(!locked)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors ${
                locked
                  ? "bg-red-500/20 text-red-400 border border-red-500/40"
                  : "bg-green-500/20 text-green-400 border border-green-500/40"
              }`}
            >
              {locked ? <Lock className="size-3.5" /> : <Unlock className="size-3.5" />}
              {locked ? "서버 잠금됨" : "서버 열림"}
            </button>
          </div>
          <Button onClick={handleTimeSubmit} className="bg-red-400 hover:bg-red-500 text-white">
            시간 설정 적용
          </Button>
        </CardContent>
      </Card>

      {/* Turn Term (minute-level) */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Timer className="size-4" />
            턴 시간 조정
          </CardTitle>
          <CardDescription>
            현재 턴 시간: <Badge variant="secondary">{turnTerm}분</Badge>
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Preset buttons */}
          <div className="flex flex-wrap gap-2">
            {TURN_PRESETS.map((min) => (
              <Button
                key={min}
                variant={turnTerm === String(min) ? "default" : "outline"}
                size="sm"
                onClick={() => handleTurnTermChange(min)}
              >
                {min}분턴
              </Button>
            ))}
          </div>

          {/* Custom minute input */}
          <div className="flex items-center gap-2">
            <Input
              type="number"
              placeholder="분 단위 직접 입력 (1~1440)"
              value={customTurnTerm}
              onChange={(e) => setCustomTurnTerm(e.target.value)}
              min={1}
              max={1440}
              className="w-56"
            />
            <Button variant="outline" size="sm" onClick={handleCustomTurnTerm}>
              적용
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Gold/Rice Distribution */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Coins className="size-4 text-amber-400" />
            금쌀 지급
          </CardTitle>
          <CardDescription>장수 또는 국가에 금/쌀을 일괄 지급합니다.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground flex items-center gap-1">
                <Coins className="size-3 text-amber-400" /> 금
              </label>
              <Input
                type="number"
                placeholder="0"
                value={goldAmount}
                onChange={(e) => setGoldAmount(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground flex items-center gap-1">
                <Wheat className="size-3 text-green-400" /> 쌀
              </label>
              <Input
                type="number"
                placeholder="0"
                value={riceAmount}
                onChange={(e) => setRiceAmount(e.target.value)}
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex border border-gray-600 rounded-md overflow-hidden">
              {(["all", "nations"] as const).map((target) => (
                <button
                  type="button"
                  key={target}
                  onClick={() => setDistributeTarget(target)}
                  className={`px-3 py-1.5 text-xs transition-colors ${
                    distributeTarget === target
                      ? "bg-[#141c65] text-white"
                      : "text-gray-400 hover:text-white"
                  }`}
                >
                  {target === "all" ? "전체 장수" : "국가별"}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button onClick={handleDistribute} variant="outline" className="border-amber-500/40 text-amber-400 hover:bg-amber-500/10">
              <Coins className="size-4 mr-1" /> 지급 실행
            </Button>
            <span className="text-xs text-muted-foreground flex items-center gap-1">
              <AlertTriangle className="size-3" /> 실행 후 되돌릴 수 없습니다
            </span>
          </div>
        </CardContent>
      </Card>

      {/* Auction Time Sync */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Gavel className="size-4 text-purple-400" />
            경매 시간 동기
          </CardTitle>
          <CardDescription>경매 마감 시간을 턴 시간과 동기화합니다.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setAuctionSyncEnabled(!auctionSyncEnabled)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors ${
                auctionSyncEnabled
                  ? "bg-purple-500/20 text-purple-400 border border-purple-500/40"
                  : "bg-muted text-muted-foreground border border-muted"
              }`}
            >
              {auctionSyncEnabled ? "동기화 활성" : "동기화 비활성"}
            </button>
          </div>
          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">경매 마감 시간 (분)</label>
            <Input
              type="number"
              value={auctionCloseMinutes}
              onChange={(e) => setAuctionCloseMinutes(e.target.value)}
              min={1}
              max={10080}
              className="w-40"
            />
            <p className="text-xs text-muted-foreground">경매 등록 후 지정한 분 후에 마감됩니다.</p>
          </div>
          <Button onClick={handleAuctionSync} variant="outline" className="border-purple-500/40 text-purple-400 hover:bg-purple-500/10">
            <Gavel className="size-4 mr-1" /> 경매 설정 적용
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
