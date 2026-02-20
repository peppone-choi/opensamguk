"use client";

import { useEffect, useState, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { Vote, ArrowLeft } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import type { Message, VoteComment } from "@/types";
import { voteApi } from "@/lib/gameApi";

interface VotePayload {
  title?: string;
  options?: string[];
  ballots?: Record<string, number>;
  state?: string;
  creatorId?: number;
  deadline?: string;
  reward?: string;
}

function pickLotteryWinner(
  voteId: number,
  ballots: Record<string, number>,
): number | null {
  const voters = Object.keys(ballots)
    .map((id) => Number(id))
    .filter((id) => Number.isFinite(id) && id > 0)
    .sort((a, b) => a - b);
  if (voters.length === 0) return null;

  const seed = String(voteId)
    .split("")
    .reduce((acc, ch) => acc + ch.charCodeAt(0), 0);
  const idx = seed % voters.length;
  return voters[idx];
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

export default function VoteDetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const voteId = Number(params.id);
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const [vote, setVote] = useState<Message | null>(null);
  const [loading, setLoading] = useState(true);
  const [comments, setComments] = useState<VoteComment[]>([]);
  const [commentInput, setCommentInput] = useState("");

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const [{ data }, { data: commentData }] = await Promise.all([
        voteApi.list(currentWorld.id),
        voteApi.listComments(voteId),
      ]);
      const found = data.find((v) => v.id === voteId) ?? null;
      setVote(found);
      setComments(commentData);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld, voteId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleVote = async (optionIndex: number) => {
    if (!myGeneral) return;
    try {
      await voteApi.cast(voteId, myGeneral.id, optionIndex);
      await load();
    } catch {
      /* ignore */
    }
  };

  const handleClose = async () => {
    try {
      await voteApi.close(voteId);
      await load();
    } catch {
      /* ignore */
    }
  };

  const handleAddComment = async () => {
    if (!myGeneral || !commentInput.trim()) return;
    try {
      const { data } = await voteApi.createComment(
        voteId,
        myGeneral.id,
        commentInput.trim(),
      );
      setComments((prev) => [...prev, data]);
      setCommentInput("");
    } catch {
      /* ignore */
    }
  };

  const handleDeleteComment = async (id: number) => {
    if (!myGeneral) return;
    try {
      await voteApi.deleteComment(voteId, id, myGeneral.id);
      setComments((prev) => prev.filter((comment) => comment.id !== id));
    } catch {
      /* ignore */
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (!vote)
    return (
      <div className="space-y-4 max-w-3xl mx-auto">
        <PageHeader icon={Vote} title="투표 상세" />
        <EmptyState icon={Vote} title="투표를 찾을 수 없습니다." />
      </div>
    );

  const d = vp(vote);
  const title = d.title ?? "(제목 없음)";
  const options = d.options ?? [];
  const ballots = d.ballots ?? {};
  const open = isOpen(d);
  const myVoteIdx =
    myGeneral != null ? ballots[myGeneral.id.toString()] : undefined;

  const counts = new Array(options.length).fill(0) as number[];
  Object.values(ballots).forEach((idx) => {
    if (typeof idx === "number" && idx < counts.length) counts[idx]++;
  });
  const total = counts.reduce((a, b) => a + b, 0);
  const maxCount = Math.max(...counts, 1);
  const canClose = myGeneral && myGeneral.officerLevel >= 5;
  const lotteryWinner = !open ? pickLotteryWinner(vote.id, ballots) : null;

  return (
    <div className="space-y-0 max-w-3xl mx-auto">
      <PageHeader icon={Vote} title="투표 상세" />

      <div className="legacy-page-wrap space-y-4 px-2 mt-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => router.push("/vote")}
          className="text-xs"
        >
          <ArrowLeft className="size-3 mr-1" />
          목록으로
        </Button>

        <Card>
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="text-base">{title}</CardTitle>
              <Badge variant={open ? "default" : "outline"}>
                {open ? "진행중" : "종료"}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-4 text-xs text-muted-foreground">
              <span>총 {total}표 투표됨</span>
              {d.deadline && <span>마감: {formatDeadline(d.deadline)}</span>}
            </div>

            {d.reward && (
              <p className="text-xs text-amber-400">보상: {d.reward}</p>
            )}

            {!open && d.reward && lotteryWinner && (
              <div className="rounded border border-amber-400/30 bg-amber-400/10 p-2 text-xs text-amber-300">
                추첨 당첨자: 장수 #{lotteryWinner}
              </div>
            )}

            {/* results */}
            <div className="space-y-3">
              {options.map((opt, i) => {
                const pct = total > 0 ? (counts[i] / total) * 100 : 0;
                const isMyVote = myVoteIdx === i;
                const isWinner =
                  !open && counts[i] === maxCount && maxCount > 0;
                return (
                  <div key={`${opt}-${counts[i]}`} className="space-y-1">
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
                          <Badge
                            variant="secondary"
                            className="ml-2 text-[10px]"
                          >
                            최다
                          </Badge>
                        )}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {counts[i]}표 ({pct.toFixed(0)}%)
                      </span>
                    </div>
                    <div className="h-3 w-full rounded-full bg-gray-800 overflow-hidden">
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
            {open && myVoteIdx === undefined && myGeneral && (
              <div className="flex flex-wrap gap-2 pt-2">
                {options.map((opt, i) => (
                  <Button
                    key={`${opt}-vote`}
                    variant="outline"
                    size="sm"
                    onClick={() => handleVote(i)}
                  >
                    {opt}
                  </Button>
                ))}
              </div>
            )}

            {/* close button */}
            {open && canClose && (
              <div className="flex justify-end pt-2">
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={handleClose}
                  className="text-xs"
                >
                  투표 종료
                </Button>
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">투표 댓글</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {myGeneral && (
              <div className="flex gap-2">
                <Input
                  value={commentInput}
                  onChange={(e) => setCommentInput(e.target.value)}
                  placeholder="댓글을 입력하세요"
                />
                <Button size="sm" onClick={handleAddComment}>
                  등록
                </Button>
              </div>
            )}
            {comments.length === 0 ? (
              <p className="text-xs text-muted-foreground">댓글이 없습니다.</p>
            ) : (
              <div className="space-y-2">
                {comments.map((comment) => (
                  <div key={comment.id} className="rounded border p-2 text-xs">
                    <div className="mb-1 flex items-center justify-between text-muted-foreground">
                      <span>장수 #{comment.authorGeneralId}</span>
                      <span>{formatDeadline(comment.createdAt)}</span>
                    </div>
                    <p>{comment.content}</p>
                    {myGeneral?.id === comment.authorGeneralId && (
                      <div className="mt-2 flex justify-end">
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-6 px-2 text-[11px]"
                          onClick={() => handleDeleteComment(comment.id)}
                        >
                          삭제
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
