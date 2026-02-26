"use client";

import { useEffect, useState, useCallback } from "react";
import { Vote, Plus, History, BarChart3 } from "lucide-react";
import Link from "next/link";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Message } from "@/types";
import { voteApi } from "@/lib/gameApi";

/* ── payload shape ── */
interface VotePayload {
  title?: string;
  options?: string[];
  ballots?: Record<string, number>;
  state?: string; // "open" | "closed"
  creatorId?: number;
  creatorName?: string;
  deadline?: string;
  reward?: string;
}

function vp(msg: Message): VotePayload {
  return (msg.payload ?? {}) as VotePayload;
}

function isOpen(v: VotePayload): boolean {
  if (v.state === "closed") return false;
  if (v.deadline && new Date(v.deadline).getTime() <= Date.now()) return false;
  return true;
}

function formatDeadline(iso?: string): string {
  if (!iso) return "-";
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
  } catch {
    return iso;
  }
}

export default function VotePage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const [votes, setVotes] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);

  // create form
  const [showCreate, setShowCreate] = useState(false);
  const [createTitle, setCreateTitle] = useState("");
  const [createOptions, setCreateOptions] = useState(["", ""]);
  const [creating, setCreating] = useState(false);
  const [createDeadline, setCreateDeadline] = useState("");
  const [createReward, setCreateReward] = useState("");
  const [createMaxSelections, setCreateMaxSelections] = useState(1);

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await voteApi.list(currentWorld.id);
      setVotes(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    load();
  }, [load]);

  const handleVote = async (voteId: number, optionIndex: number) => {
    if (!myGeneral) return;
    try {
      await voteApi.cast(voteId, myGeneral.id, optionIndex);
      await load();
    } catch {
      /* ignore */
    }
  };

  const handleClose = async (voteId: number) => {
    try {
      await voteApi.close(voteId);
      await load();
    } catch {
      /* ignore */
    }
  };

  const handleCreate = async () => {
    if (!currentWorld || !myGeneral || !createTitle.trim()) return;
    const opts = createOptions.filter((o) => o.trim());
    if (opts.length < 2) return;
    setCreating(true);
    try {
      await voteApi.create(currentWorld.id, {
        title: createTitle.trim(),
        options: opts,
        creatorId: myGeneral.id,
        ...(createDeadline
          ? { deadline: new Date(createDeadline).toISOString() }
          : {}),
        ...(createReward.trim() ? { reward: createReward.trim() } : {}),
        ...(createMaxSelections > 1
          ? { maxSelections: createMaxSelections }
          : {}),
      });
      setShowCreate(false);
      setCreateTitle("");
      setCreateOptions(["", ""]);
      await load();
    } catch {
      /* ignore */
    } finally {
      setCreating(false);
    }
  };

  const addOption = () => setCreateOptions((o) => [...o, ""]);
  const updateOption = (idx: number, val: string) =>
    setCreateOptions((o) => o.map((v, i) => (i === idx ? val : v)));
  const removeOption = (idx: number) =>
    setCreateOptions((o) =>
      o.length <= 2 ? o : o.filter((_, i) => i !== idx),
    );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  const activeVotes = votes.filter((v) => isOpen(vp(v)));
  const closedVotes = votes.filter((v) => !isOpen(vp(v)));

  // Chief or higher can create
  const canCreate = myGeneral && myGeneral.officerLevel >= 5;

  return (
    <div className="space-y-0 max-w-4xl mx-auto">
      <PageHeader icon={Vote} title="설문 조사" />

      <Tabs defaultValue="active" className="legacy-page-wrap">
        <TabsList className="w-full justify-start border-b border-gray-600">
          <TabsTrigger value="active">
            <BarChart3 className="size-3.5 mr-1" />
            진행중 투표
          </TabsTrigger>
          <TabsTrigger value="history">
            <History className="size-3.5 mr-1" />
            지난 투표
          </TabsTrigger>
        </TabsList>

        {/* ═══ Active ═══ */}
        <TabsContent value="active" className="mt-4 space-y-4 px-2">
          {/* create button */}
          {canCreate && (
            <div className="flex justify-end">
              <Button
                size="sm"
                variant={showCreate ? "outline" : "default"}
                onClick={() => setShowCreate(!showCreate)}
              >
                <Plus className="size-3.5 mr-1" />
                {showCreate ? "취소" : "투표 만들기"}
              </Button>
            </div>
          )}

          {/* create form */}
          {showCreate && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">새 투표 만들기</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    제목
                  </label>
                  <Input
                    value={createTitle}
                    onChange={(e) => setCreateTitle(e.target.value)}
                    placeholder="투표 제목"
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    선택지
                  </label>
                  <div className="space-y-2">
                    {createOptions.map((opt, i) => (
                      <div key={i} className="flex items-center gap-2">
                        <Input
                          value={opt}
                          onChange={(e) => updateOption(i, e.target.value)}
                          placeholder={`선택지 ${i + 1}`}
                          className="text-sm"
                        />
                        {createOptions.length > 2 && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => removeOption(i)}
                            className="h-8 px-2 text-xs text-destructive"
                          >
                            삭제
                          </Button>
                        )}
                      </div>
                    ))}
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={addOption}
                      className="text-xs"
                    >
                      <Plus className="size-3 mr-1" />
                      선택지 추가
                    </Button>
                  </div>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    마감 시간 (선택)
                  </label>
                  <Input
                    type="datetime-local"
                    value={createDeadline}
                    onChange={(e) => setCreateDeadline(e.target.value)}
                    className="text-sm w-56"
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    보상 (선택)
                  </label>
                  <Input
                    value={createReward}
                    onChange={(e) => setCreateReward(e.target.value)}
                    placeholder="추첨 보상 (예: 금 1000)"
                    className="text-sm"
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    다중 선택 제약
                  </label>
                  <div className="flex items-center gap-2">
                    <select
                      value={createMaxSelections}
                      onChange={(e) =>
                        setCreateMaxSelections(Number(e.target.value))
                      }
                      className="h-8 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded"
                    >
                      <option value={1}>단일 선택</option>
                      <option value={2}>최대 2개</option>
                      <option value={3}>최대 3개</option>
                    </select>
                    <span className="text-[10px] text-muted-foreground">
                      {createMaxSelections > 1
                        ? `투표자는 최대 ${createMaxSelections}개 선택 가능`
                        : "하나만 선택 가능"}
                    </span>
                  </div>
                </div>
                <Button
                  onClick={handleCreate}
                  disabled={
                    creating ||
                    !createTitle.trim() ||
                    createOptions.filter((o) => o.trim()).length < 2
                  }
                >
                  {creating ? "생성 중..." : "생성"}
                </Button>
              </CardContent>
            </Card>
          )}

          {activeVotes.length === 0 ? (
            <EmptyState icon={Vote} title="진행중인 투표가 없습니다." />
          ) : (
            <div className="space-y-3">
              {activeVotes.map((vote) => (
                <VoteCard
                  key={vote.id}
                  vote={vote}
                  myGeneralId={myGeneral?.id}
                  isChief={canCreate ?? false}
                  onVote={handleVote}
                  onClose={handleClose}
                />
              ))}
            </div>
          )}
        </TabsContent>

        {/* ═══ History ═══ */}
        <TabsContent value="history" className="mt-4 space-y-4 px-2">
          {closedVotes.length === 0 ? (
            <EmptyState icon={History} title="지난 투표가 없습니다." />
          ) : (
            <div className="space-y-3">
              {closedVotes.map((vote) => (
                <VoteCard
                  key={vote.id}
                  vote={vote}
                  myGeneralId={myGeneral?.id}
                  isChief={false}
                  onVote={handleVote}
                  onClose={handleClose}
                />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ── Vote card ── */
function VoteCard({
  vote,
  myGeneralId,
  isChief,
  onVote,
  onClose,
}: {
  vote: Message;
  myGeneralId?: number;
  isChief: boolean;
  onVote: (voteId: number, idx: number) => void;
  onClose: (voteId: number) => void;
}) {
  const d = vp(vote);
  const title = d.title ?? "(제목 없음)";
  const options = d.options ?? [];
  const ballots = d.ballots ?? {};
  const open = isOpen(d);

  const myVoteIdx =
    myGeneralId != null ? ballots[myGeneralId.toString()] : undefined;

  // count per option
  const counts = new Array(options.length).fill(0) as number[];
  Object.values(ballots).forEach((idx) => {
    if (typeof idx === "number" && idx < counts.length) counts[idx]++;
  });
  const total = counts.reduce((a, b) => a + b, 0);
  const maxCount = Math.max(...counts, 1);

  return (
    <Card>
      <CardContent className="space-y-3 pt-4">
        <div className="flex items-center justify-between gap-2">
          <Link
            href={`/vote/${vote.id}`}
            className="font-semibold text-sm hover:underline"
          >
            {title}
          </Link>
          <div className="flex items-center gap-2">
            {d.deadline && (
              <span className="text-[10px] text-muted-foreground">
                마감: {formatDeadline(d.deadline)}
              </span>
            )}
            <Badge variant={open ? "default" : "outline"}>
              {open ? "진행중" : "종료"}
            </Badge>
          </div>
        </div>

        {d.reward && (
          <div className="flex items-center gap-1.5 bg-amber-400/10 border border-amber-400/20 rounded px-2.5 py-1.5">
            <span className="text-xs font-bold text-amber-400">보상</span>
            <span className="text-xs text-amber-300">{d.reward}</span>
          </div>
        )}

        <p className="text-xs text-muted-foreground">총 {total}표 투표됨</p>

        {/* results with progress bars */}
        <div className="space-y-2">
          {options.map((opt, i) => {
            const pct = total > 0 ? (counts[i] / total) * 100 : 0;
            const isMyVote = myVoteIdx === i;
            const isWinner = !open && counts[i] === maxCount && maxCount > 0;
            return (
              <div key={i} className="space-y-1">
                <div className="flex justify-between text-sm">
                  <span
                    className={
                      isMyVote
                        ? "text-amber-400"
                        : isWinner
                          ? "text-green-400 font-bold"
                          : ""
                    }
                  >
                    {opt}
                    {isMyVote && (
                      <Badge variant="outline" className="ml-2 text-[10px]">
                        내 투표
                      </Badge>
                    )}
                    {isWinner && !open && (
                      <Badge variant="secondary" className="ml-2 text-[10px]">
                        최다
                      </Badge>
                    )}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {counts[i]}표 ({pct.toFixed(0)}%)
                  </span>
                </div>
                <div className="h-2 w-full rounded-full bg-gray-800 overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all ${
                      isMyVote
                        ? "bg-amber-400"
                        : isWinner && !open
                          ? "bg-green-500"
                          : "bg-primary"
                    }`}
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>

        {/* vote buttons */}
        {open && myVoteIdx === undefined && myGeneralId != null && (
          <div className="flex flex-wrap gap-2 pt-1">
            {options.map((opt, i) => (
              <Button
                key={i}
                variant="outline"
                size="sm"
                onClick={() => onVote(vote.id, i)}
                className="text-xs"
              >
                {opt}
              </Button>
            ))}
          </div>
        )}

        {/* close button for chief */}
        {open && isChief && (
          <div className="flex justify-end pt-1">
            <Button
              size="sm"
              variant="destructive"
              onClick={() => onClose(vote.id)}
              className="text-xs"
            >
              투표 종료
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
