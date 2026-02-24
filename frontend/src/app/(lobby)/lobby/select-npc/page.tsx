"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AxiosError } from "axios";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { npcTokenApi } from "@/lib/gameApi";
import type { NpcCard, NpcTokenResponse } from "@/types";
import { Bot, ArrowLeft, RefreshCw, Timer, List } from "lucide-react";
import { toast } from "sonner";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { generalApi, nationApi } from "@/lib/gameApi";
import type { General, Nation } from "@/types";

export default function LobbySelectNpcPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { fetchMyGeneral } = useGeneralStore();
  const [token, setToken] = useState<NpcTokenResponse | null>(null);
  const [keepIds, setKeepIds] = useState<number[]>([]);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selectingId, setSelectingId] = useState<number | null>(null);

  // Full general list view - legacy parity from select_npc.php "장수 목록 보기"
  const [showGeneralList, setShowGeneralList] = useState(false);
  const [generalList, setGeneralList] = useState<General[]>([]);
  const [generalListLoading, setGeneralListLoading] = useState(false);
  const [nationMap, setNationMap] = useState<Record<number, Nation>>({});
  const [generalListLimit, setGeneralListLimit] = useState(30);

  const formatSeconds = (seconds: number) => {
    const safe = Math.max(0, seconds);
    const minutes = Math.floor(safe / 60)
      .toString()
      .padStart(2, "0");
    const remain = (safe % 60).toString().padStart(2, "0");
    return `${minutes}:${remain}`;
  };

  const validRemaining = token
    ? Math.max(
        0,
        Math.ceil((new Date(token.validUntil).getTime() - nowMs) / 1000),
      )
    : 0;
  const refreshRemaining = token
    ? Math.max(
        0,
        Math.ceil((new Date(token.pickMoreAfter).getTime() - nowMs) / 1000),
      )
    : 0;
  const tokenExpired = !!token && validRemaining === 0;
  const refreshDisabled =
    !token ||
    refreshing ||
    selectingId !== null ||
    (!tokenExpired && refreshRemaining > 0);

  useEffect(() => {
    const interval = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(interval);
  }, []);

  const handleLoadGeneralList = async () => {
    if (!currentWorld) return;
    setGeneralListLoading(true);
    try {
      const [genRes, natRes] = await Promise.all([
        generalApi.listByWorld(currentWorld.id),
        nationApi.listByWorld(currentWorld.id),
      ]);
      setGeneralList(genRes.data);
      const nm: Record<number, Nation> = {};
      for (const n of natRes.data) nm[n.id] = n;
      setNationMap(nm);
      setShowGeneralList(true);
    } catch {
      // ignore
    } finally {
      setGeneralListLoading(false);
    }
  };

  useEffect(() => {
    if (!currentWorld) {
      setLoading(false);
      return;
    }
    setLoading(true);
    npcTokenApi
      .generate(currentWorld.id)
      .then(({ data }) => {
        setToken(data);
        setKeepIds([]);
      })
      .catch(() => {
        toast.error("NPC 카드 생성에 실패했습니다.");
      })
      .finally(() => setLoading(false));
  }, [currentWorld]);

  const handleKeepToggle = (npcId: number, checked: boolean) => {
    if (checked) {
      if (keepIds.includes(npcId)) return;
      if (keepIds.length >= 3) {
        toast.error("보존 카드는 최대 3장입니다.");
        return;
      }
      setKeepIds((prev) => [...prev, npcId]);
      return;
    }
    setKeepIds((prev) => prev.filter((id) => id !== npcId));
  };

  const handleRefresh = async () => {
    if (!currentWorld || !token || refreshDisabled) return;
    setRefreshing(true);
    try {
      const { data } = tokenExpired
        ? await npcTokenApi.generate(currentWorld.id)
        : await npcTokenApi.refresh(currentWorld.id, token.nonce, keepIds);
      setToken(data);
      setKeepIds((prev) =>
        prev.filter((id) => data.npcs.some((n) => n.id === id)),
      );
      toast.success(
        tokenExpired
          ? "새 NPC 카드를 생성했습니다."
          : "NPC 카드를 다시 뽑았습니다.",
      );
    } catch (error) {
      const message =
        error instanceof AxiosError
          ? (error.response?.data?.message as string | undefined)
          : undefined;
      toast.error(message ?? "다시 뽑기에 실패했습니다.");
    } finally {
      setRefreshing(false);
    }
  };

  const handleSelect = async (npc: NpcCard) => {
    if (!currentWorld || !token) return;
    if (tokenExpired) {
      toast.error("토큰이 만료되었습니다. 다시 뽑아주세요.");
      return;
    }
    if (!confirm(`${npc.name} 장수를 선택하시겠습니까?`)) return;

    setSelectingId(npc.id);
    try {
      await npcTokenApi.select(currentWorld.id, token.nonce, npc.id);
      await fetchMyGeneral(currentWorld.id);
      toast.success("NPC 장수를 선택했습니다.");
      router.replace("/lobby");
    } catch {
      toast.error("NPC 선택에 실패했습니다.");
    } finally {
      setSelectingId(null);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-6xl mx-auto">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => router.push("/lobby")}
        className="mb-2"
      >
        <ArrowLeft className="size-4 mr-1" /> 로비로 돌아가기
      </Button>

      <PageHeader icon={Bot} title="NPC 인수" />
      <p className="text-sm text-muted-foreground">
        카드 5장을 확인하고 1명을 선택하세요. 보존은 최대 3장까지 가능합니다.
      </p>

      {!token || token.npcs.length === 0 ? (
        <EmptyState icon={Bot} title="선택 가능한 NPC 카드가 없습니다." />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3 rounded-lg border p-3 bg-card">
            <div className="flex items-center gap-2 text-sm">
              <Timer className="size-4" />
              <span className="text-muted-foreground">유효 시간</span>
              <span
                className={
                  validRemaining < 30
                    ? "font-semibold text-red-500"
                    : "font-semibold"
                }
              >
                {formatSeconds(validRemaining)}
              </span>
            </div>
            <div className="text-sm text-muted-foreground">
              보존 {keepIds.length}/3
            </div>
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="ml-auto"
              disabled={refreshDisabled}
              onClick={handleRefresh}
            >
              <RefreshCw
                className={`size-4 mr-1 ${refreshing ? "animate-spin" : ""}`}
              />
              {refreshRemaining > 0
                ? `다시 뽑기 (${formatSeconds(refreshRemaining)})`
                : "다시 뽑기"}
            </Button>
          </div>

          <div className="grid gap-3 grid-cols-2 lg:grid-cols-5">
            {token.npcs.map((npc) => {
              const kept = keepIds.includes(npc.id);
              const keepLocked = !kept && keepIds.length >= 3;
              return (
                <Card key={npc.id}>
                  <CardContent className="space-y-3">
                    <div className="flex items-center gap-3">
                      <GeneralPortrait
                        picture={npc.picture}
                        name={npc.name}
                        size="md"
                      />
                      <div className="min-w-0">
                        <p className="font-semibold truncate">{npc.name}</p>
                        <Badge
                          variant="secondary"
                          className="text-xs"
                          style={{
                            backgroundColor: npc.nationColor,
                            color: "white",
                          }}
                        >
                          {npc.nationName}
                        </Badge>
                      </div>
                    </div>

                    <div className="space-y-1 text-xs">
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">통솔</span>
                        <span>{npc.leadership}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">무력</span>
                        <span>{npc.strength}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">지력</span>
                        <span>{npc.intel}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">정치</span>
                        <span>{npc.politics}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">매력</span>
                        <span>{npc.charm}</span>
                      </div>
                    </div>

                    <div className="space-y-1 text-xs">
                      <p title={npc.personalityInfo ?? ""}>
                        <span className="text-muted-foreground">성격</span>{" "}
                        <span className="cursor-help underline decoration-dotted">{npc.personality}</span>
                      </p>
                      <p title={npc.specialInfo ?? ""}>
                        <span className="text-muted-foreground">특기</span>{" "}
                        <span className="cursor-help underline decoration-dotted">{npc.special}</span>
                      </p>
                      {npc.special2 && npc.special2 !== "None" && (
                        <p title={npc.special2Info ?? ""}>
                          <span className="text-muted-foreground">특기2</span>{" "}
                          <span className="cursor-help underline decoration-dotted">{npc.special2}</span>
                        </p>
                      )}
                    </div>

                    {/* Dex (숙련도) summary if available */}
                    {npc.dex && (
                      <div className="space-y-0.5 text-[10px] text-muted-foreground">
                        {["보병", "궁병", "기병", "귀병", "차병"].map((name, i) => (
                          <div key={name} className="flex justify-between">
                            <span>{name}</span>
                            <span className="text-foreground">{Math.floor(npc.dex![i] / 1000)}K</span>
                          </div>
                        ))}
                      </div>
                    )}

                    <label className="flex items-center gap-2 text-xs text-muted-foreground">
                      <input
                        type="checkbox"
                        checked={kept}
                        disabled={
                          keepLocked || tokenExpired || selectingId !== null
                        }
                        onChange={(event) =>
                          handleKeepToggle(npc.id, event.target.checked)
                        }
                      />
                      보존
                      {npc.keepCount != null && npc.keepCount > 0 && (
                        <span className="text-yellow-400">보관({npc.keepCount}회)</span>
                      )}
                    </label>

                    <Button
                      className="w-full"
                      onClick={() => handleSelect(npc)}
                      disabled={
                        tokenExpired || selectingId !== null || refreshing
                      }
                    >
                      {selectingId === npc.id ? "선택 중..." : "선택하기"}
                    </Button>
                  </CardContent>
                </Card>
              );
            })}
          </div>

          {tokenExpired ? (
            <p className="text-sm text-red-500">
              토큰 유효 시간이 만료되었습니다. 다시 뽑기를 눌러 새 카드를
              생성하세요.
            </p>
          ) : null}
        </>
      )}

      {/* Full General List - legacy parity from select_npc.php "장수 목록 보기" */}
      <div className="flex gap-2 mt-4">
        <Button
          variant="outline"
          size="sm"
          onClick={handleLoadGeneralList}
          disabled={generalListLoading}
        >
          <List className="size-4 mr-1" />
          {generalListLoading ? "로딩 중..." : "장수 목록 보기"}
        </Button>
        {showGeneralList && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowGeneralList(false)}
          >
            목록 닫기
          </Button>
        )}
      </div>

      {showGeneralList && generalList.length > 0 && (
        <div className="overflow-x-auto border rounded">
          <table className="w-full text-xs">
            <thead className="bg-muted">
              <tr>
                <th className="px-2 py-1.5 text-left">얼굴</th>
                <th className="px-2 py-1.5 text-left">이름</th>
                <th className="px-2 py-1.5 text-center">연령</th>
                <th className="px-2 py-1.5 text-left">국가</th>
                <th className="px-2 py-1.5 text-center">레벨</th>
                <th className="px-2 py-1.5 text-center">통솔</th>
                <th className="px-2 py-1.5 text-center">무력</th>
                <th className="px-2 py-1.5 text-center">지력</th>
                <th className="px-2 py-1.5 text-center">정치</th>
                <th className="px-2 py-1.5 text-center">매력</th>
                <th className="px-2 py-1.5 text-center">경험</th>
                <th className="px-2 py-1.5 text-center">NPC</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {generalList.slice(0, generalListLimit).map((g) => {
                const nat = nationMap[g.nationId];
                return (
                  <tr key={g.id} className="hover:bg-muted/50">
                    <td className="px-2 py-1">
                      <GeneralPortrait
                        picture={g.picture}
                        name={g.name}
                        size="sm"
                      />
                    </td>
                    <td className="px-2 py-1 font-medium">{g.name}</td>
                    <td className="px-2 py-1 text-center">{g.age}</td>
                    <td className="px-2 py-1">
                      {nat ? (
                        <span style={{ color: nat.color }}>{nat.name}</span>
                      ) : (
                        <span className="text-muted-foreground">재야</span>
                      )}
                    </td>
                    <td className="px-2 py-1 text-center">{g.expLevel}</td>
                    <td className="px-2 py-1 text-center">{g.leadership}</td>
                    <td className="px-2 py-1 text-center">{g.strength}</td>
                    <td className="px-2 py-1 text-center">{g.intel}</td>
                    <td className="px-2 py-1 text-center">{g.politics}</td>
                    <td className="px-2 py-1 text-center">{g.charm}</td>
                    <td className="px-2 py-1 text-center">{g.experience}</td>
                    <td className="px-2 py-1 text-center">
                      {g.npcState > 0 ? (
                        <Badge variant="secondary" className="text-[10px]">
                          NPC
                        </Badge>
                      ) : (
                        "유저"
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {generalListLimit < generalList.length && (
            <div className="p-2 text-center border-t">
              <Button
                variant="ghost"
                size="sm"
                onClick={() =>
                  setGeneralListLimit((prev) => prev + 30)
                }
              >
                장수 더 보기 ({generalList.length - generalListLimit}명 남음)
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
