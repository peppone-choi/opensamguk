"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import {
  Trophy,
  Medal,
  Crown,
  Swords,
  Shield,
  Brain,
  Flame,
  Users,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { tournamentApi, frontApi } from "@/lib/gameApi";
import type {
  TournamentInfo,
  TournamentBracketMatch,
  GlobalInfo,
} from "@/types";

/* ── Constants ── */

const STATE_LABELS: Record<number, string> = {
  0: "대기",
  1: "모집중",
  2: "진행중",
  3: "종료",
};

const STATE_COLORS: Record<
  number,
  "secondary" | "default" | "destructive" | "outline"
> = {
  0: "outline",
  1: "default",
  2: "destructive",
  3: "secondary",
};

const TOURNAMENT_TYPES: {
  code: number;
  name: string;
  stat: string;
  statKey: string;
  icon: typeof Trophy;
}[] = [
  { code: 0, name: "전력전", stat: "종합", statKey: "total", icon: Flame },
  {
    code: 1,
    name: "통솔전",
    stat: "통솔",
    statKey: "leadership",
    icon: Shield,
  },
  { code: 2, name: "일기토", stat: "무력", statKey: "strength", icon: Swords },
  { code: 3, name: "설전", stat: "지력", statKey: "intel", icon: Brain },
];

const ROUND_NAMES: Record<number, string> = {
  1: "16강",
  2: "8강",
  3: "4강",
  4: "결승",
};

/* ── Page ── */

export default function TournamentPage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals, nations, loadAll } = useGameStore();
  const [info, setInfo] = useState<TournamentInfo | null>(null);
  const [globalInfo, setGlobalInfo] = useState<GlobalInfo | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      loadAll(currentWorld.id);
      const [tRes, fRes] = await Promise.all([
        tournamentApi.getInfo(currentWorld.id),
        frontApi.getInfo(currentWorld.id),
      ]);
      setInfo(tRes.data);
      setGlobalInfo(fRes.data.global);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld, loadAll]);

  useEffect(() => {
    load();
  }, [load]);

  const handleRegister = async () => {
    if (!currentWorld || !myGeneral) return;
    try {
      await tournamentApi.register(currentWorld.id, myGeneral.id);
      await load();
    } catch {
      /* ignore */
    }
  };

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );
  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  const isRegistered = myGeneral && info?.participants.includes(myGeneral.id);
  const tournamentType =
    TOURNAMENT_TYPES.find(
      (t) => t.code === (globalInfo?.tournamentType ?? 0),
    ) ?? TOURNAMENT_TYPES[0];

  // Group bracket matches by round
  const roundsMap = new Map<number, TournamentBracketMatch[]>();
  if (info) {
    for (const m of info.bracket) {
      const arr = roundsMap.get(m.round) ?? [];
      arr.push(m);
      roundsMap.set(m.round, arr);
    }
  }
  const rounds = Array.from(roundsMap.entries()).sort(([a], [b]) => a - b);

  const TypeIcon = tournamentType.icon;

  return (
    <div className="space-y-0">
      <PageHeader icon={Trophy} title="토너먼트" />

      <div className="legacy-page-wrap space-y-2 py-2">
        {/* ── Status & Type ── */}
        <Card>
          <CardContent className="py-3 px-4">
            <div className="flex items-center justify-between flex-wrap gap-2">
              <div className="flex items-center gap-2">
                <TypeIcon className="size-5 text-cyan-400" />
                <span className="text-sm font-bold text-cyan-400">
                  {tournamentType.name}
                </span>
                <Badge variant={STATE_COLORS[info?.state ?? 0]}>
                  {STATE_LABELS[info?.state ?? 0] ?? "알 수 없음"}
                </Badge>
              </div>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Users className="size-3.5" />
                <span>참가자 {info?.participants.length ?? 0}명</span>
              </div>
            </div>

            {/* Registration */}
            {info?.state === 1 && myGeneral && !isRegistered && (
              <div className="mt-3 flex items-center gap-2">
                <Button size="sm" onClick={handleRegister}>
                  참가 등록
                </Button>
                <span className="text-xs text-muted-foreground">
                  참가비가 소모됩니다
                </span>
              </div>
            )}
            {isRegistered && (
              <div className="mt-2">
                <Badge
                  variant="outline"
                  className="text-xs text-amber-400 border-amber-400/50"
                >
                  등록 완료
                </Badge>
              </div>
            )}
          </CardContent>
        </Card>

        {/* ── Tournament Type Info ── */}
        <Card>
          <CardHeader className="py-2 px-4">
            <CardTitle className="text-sm">토너먼트 종류</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-3">
            <div className="grid grid-cols-4 gap-1.5">
              {TOURNAMENT_TYPES.map((t) => {
                const Icon = t.icon;
                const isActive = t.code === tournamentType.code;
                return (
                  <div
                    key={t.code}
                    className={`flex flex-col items-center gap-1 rounded border px-2 py-2 text-center ${
                      isActive
                        ? "border-cyan-500/50 bg-cyan-950/30"
                        : "border-gray-700 bg-[#0a0a0a]"
                    }`}
                  >
                    <Icon
                      className={`size-4 ${isActive ? "text-cyan-400" : "text-gray-500"}`}
                    />
                    <span
                      className={`text-xs font-medium ${
                        isActive ? "text-cyan-400" : "text-gray-500"
                      }`}
                    >
                      {t.name}
                    </span>
                    <span className="text-[10px] text-muted-foreground">
                      {t.stat} 기준
                    </span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>

        {/* ── Bracket Visualization ── */}
        {info && info.bracket.length > 0 ? (
          <Card>
            <CardHeader className="py-2 px-4">
              <CardTitle className="text-sm flex items-center gap-2">
                <Trophy className="size-4 text-amber-400" />
                대진표
              </CardTitle>
            </CardHeader>
            <CardContent className="px-2 pb-3">
              <div className="overflow-x-auto">
                <div className="flex gap-0 min-w-[600px]">
                  {rounds.map(([round, matches]) => (
                    <div key={round} className="flex flex-col flex-1 min-w-0">
                      {/* Round header */}
                      <div className="text-center mb-2">
                        <Badge
                          variant={
                            round === rounds.length ? "default" : "outline"
                          }
                          className="text-[10px]"
                        >
                          {ROUND_NAMES[round] ?? `R${round}`}
                        </Badge>
                      </div>
                      {/* Matches */}
                      <div className="flex flex-col justify-around flex-1 gap-1 px-0.5">
                        {matches
                          .sort((a, b) => a.match - b.match)
                          .map((match) => {
                            const p1 = generalMap.get(match.p1);
                            const p2 = generalMap.get(match.p2);
                            const p1Nation = p1
                              ? nationMap.get(p1.nationId)
                              : null;
                            const p2Nation = p2
                              ? nationMap.get(p2.nationId)
                              : null;
                            const isP1Winner =
                              match.winner === match.p1 && match.winner;
                            const isP2Winner =
                              match.winner === match.p2 && match.winner;

                            return (
                              <div
                                key={`${round}-${match.match}`}
                                className="border border-gray-700 rounded text-[11px] bg-[#0a0a0a] overflow-hidden"
                              >
                                {/* Player 1 */}
                                <div
                                  className={`flex items-center gap-1.5 px-2 py-1 ${
                                    isP1Winner ? "bg-amber-950/40" : ""
                                  }`}
                                >
                                  {p1Nation && (
                                    <span
                                      className="inline-block size-1.5 rounded-full shrink-0"
                                      style={{
                                        backgroundColor: p1Nation.color,
                                      }}
                                    />
                                  )}
                                  <span
                                    className={`truncate ${
                                      isP1Winner
                                        ? "text-amber-400 font-bold"
                                        : match.winner && !isP1Winner
                                          ? "text-gray-600"
                                          : "text-gray-300"
                                    }`}
                                  >
                                    {p1?.name ??
                                      (match.p1 ? `#${match.p1}` : "-")}
                                  </span>
                                  {isP1Winner && (
                                    <Medal className="size-3 text-amber-400 shrink-0 ml-auto" />
                                  )}
                                </div>
                                {/* Divider */}
                                <div className="border-t border-gray-700/50" />
                                {/* Player 2 */}
                                <div
                                  className={`flex items-center gap-1.5 px-2 py-1 ${
                                    isP2Winner ? "bg-amber-950/40" : ""
                                  }`}
                                >
                                  {p2Nation && (
                                    <span
                                      className="inline-block size-1.5 rounded-full shrink-0"
                                      style={{
                                        backgroundColor: p2Nation.color,
                                      }}
                                    />
                                  )}
                                  <span
                                    className={`truncate ${
                                      isP2Winner
                                        ? "text-amber-400 font-bold"
                                        : match.winner && !isP2Winner
                                          ? "text-gray-600"
                                          : "text-gray-300"
                                    }`}
                                  >
                                    {p2?.name ??
                                      (match.p2 ? `#${match.p2}` : "-")}
                                  </span>
                                  {isP2Winner && (
                                    <Medal className="size-3 text-amber-400 shrink-0 ml-auto" />
                                  )}
                                </div>
                              </div>
                            );
                          })}
                      </div>
                    </div>
                  ))}

                  {/* Champion column */}
                  {(() => {
                    const finalRound = rounds[rounds.length - 1];
                    if (!finalRound) return null;
                    const finalMatch = finalRound[1][0];
                    if (!finalMatch?.winner) return null;
                    const champion = generalMap.get(finalMatch.winner);
                    const champNation = champion
                      ? nationMap.get(champion.nationId)
                      : null;
                    return (
                      <div className="flex flex-col items-center justify-center min-w-[80px] px-1">
                        <Crown className="size-5 text-amber-400 mb-1" />
                        <div className="text-center">
                          <GeneralPortrait
                            picture={champion?.picture}
                            name={champion?.name ?? "???"}
                            size="md"
                            className="mx-auto mb-1"
                          />
                          <div className="text-xs font-bold text-amber-400">
                            {champion?.name ?? "???"}
                          </div>
                          {champNation && (
                            <NationBadge
                              name={champNation.name}
                              color={champNation.color}
                            />
                          )}
                        </div>
                      </div>
                    );
                  })()}
                </div>
              </div>
            </CardContent>
          </Card>
        ) : (
          <EmptyState
            icon={Trophy}
            title="대진표가 아직 생성되지 않았습니다."
          />
        )}

        {/* ── Participants ── */}
        {info && info.participants.length > 0 && (
          <Card>
            <CardHeader className="py-2 px-4">
              <CardTitle className="text-sm flex items-center gap-2">
                <Users className="size-4" />
                참가자 ({info.participants.length}명)
              </CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-3">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="text-xs">장수</TableHead>
                    <TableHead className="text-xs">국가</TableHead>
                    <TableHead className="text-xs text-right">
                      {tournamentType.stat}
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {info.participants
                    .map((pid) => {
                      const gen = generalMap.get(pid);
                      const nat = gen ? nationMap.get(gen.nationId) : null;
                      let statVal = 0;
                      if (gen) {
                        switch (tournamentType.statKey) {
                          case "leadership":
                            statVal = gen.leadership;
                            break;
                          case "strength":
                            statVal = gen.strength;
                            break;
                          case "intel":
                            statVal = gen.intel;
                            break;
                          case "total":
                            statVal = gen.leadership + gen.strength + gen.intel;
                            break;
                        }
                      }
                      return { pid, gen, nat, statVal };
                    })
                    .sort((a, b) => b.statVal - a.statVal)
                    .map(({ pid, gen, nat, statVal }) => (
                      <TableRow key={pid}>
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <GeneralPortrait
                              picture={gen?.picture}
                              name={gen?.name ?? `#${pid}`}
                              size="sm"
                            />
                            <span className="text-xs">
                              {gen?.name ?? `#${pid}`}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell>
                          {nat ? (
                            <NationBadge name={nat.name} color={nat.color} />
                          ) : (
                            <span className="text-xs text-muted-foreground">
                              재야
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="text-right text-xs font-mono">
                          {statVal}
                        </TableCell>
                      </TableRow>
                    ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        )}

        {/* ── Tournament Rules ── */}
        <Card>
          <CardHeader className="py-2 px-4">
            <CardTitle className="text-sm">토너먼트 안내</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-3 text-xs text-muted-foreground space-y-1">
            <p>예선은 홈&어웨이 풀리그로 진행됩니다. (총 14경기)</p>
            <p>상위 4명이 본선에 진출하며 조추첨을 통해 조가 배정됩니다.</p>
            <p>
              본선은 개인당 3경기를 치르며 승점(승3, 무1, 패0)에 따라 순위를
              매깁니다.
            </p>
            <p>각 조 1, 2위는 16강에 배정됩니다.</p>
            <p>16강부터는 1경기 토너먼트로 진행됩니다.</p>
            <p>성적에 따라 금과 명성이 포상으로 주어집니다.</p>
            <div className="mt-2 pt-2 border-t border-gray-800">
              <p className="text-amber-400/80">
                16강자 100금, 8강자 300금, 4강자 600금, 준우승자 1200금, 우승자
                2000금
              </p>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
