"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useSearchParams } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { boardApi, messageApi } from "@/lib/gameApi";
import type { BoardComment, Message } from "@/types";
import {
  MessageSquare,
  Lock,
  Trash2,
  ChevronDown,
  ChevronUp,
  Send,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { formatLog } from "@/lib/formatLog";

const PAGE_SIZE = 20;

export default function BoardPage() {
  const searchParams = useSearchParams();
  const isSecretParam = searchParams.get("secret") === "true";

  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const { generals, nations, loadAll } = useGameStore();

  const [tab, setTab] = useState(isSecretParam ? "secret" : "public");
  const [publicPosts, setPublicPosts] = useState<Message[]>([]);
  const [secretPosts, setSecretPosts] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [content, setContent] = useState("");
  const [sending, setSending] = useState(false);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [commentsByPost, setCommentsByPost] = useState<
    Record<number, BoardComment[]>
  >({});
  const [commentLoadingByPost, setCommentLoadingByPost] = useState<
    Record<number, boolean>
  >({});

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const canAccessSecret = (myGeneral?.officerLevel ?? 0) >= 2;
  const isSecret = tab === "secret";
  const posts = isSecret ? secretPosts : publicPosts;
  const visiblePosts = posts.slice(0, visibleCount);
  const hasMore = visibleCount < posts.length;

  // Load game data (generals/nations) for name lookups
  useEffect(() => {
    if (currentWorld && generals.length === 0) {
      loadAll(currentWorld.id);
    }
  }, [currentWorld, generals.length, loadAll]);

  const loadPublic = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await messageApi.getBoard(currentWorld.id);
      setPublicPosts(data);
    } catch {
      /* ignore */
    }
  }, [currentWorld]);

  const loadSecret = useCallback(async () => {
    if (!currentWorld || !myGeneral?.nationId || !canAccessSecret) return;
    try {
      const { data } = await messageApi.getSecretBoard(
        currentWorld.id,
        myGeneral.nationId,
      );
      setSecretPosts(data);
    } catch {
      /* ignore */
    }
  }, [currentWorld, myGeneral?.nationId, canAccessSecret]);

  const loadPosts = useCallback(async () => {
    setLoading(true);
    try {
      await Promise.all([loadPublic(), loadSecret()]);
    } finally {
      setLoading(false);
    }
  }, [loadPublic, loadSecret]);

  useEffect(() => {
    loadPosts();
  }, [loadPosts]);

  // Auto-refresh every 10s
  useEffect(() => {
    const interval = setInterval(() => {
      loadPublic();
      loadSecret();
    }, 10000);
    return () => clearInterval(interval);
  }, [loadPublic, loadSecret]);

  const handlePost = async () => {
    if (!currentWorld || !myGeneral || !content.trim()) return;
    setSending(true);
    try {
      if (isSecret) {
        await messageApi.postSecretBoard(
          currentWorld.id,
          myGeneral.id,
          myGeneral.nationId,
          content.trim(),
        );
        await loadSecret();
      } else {
        await messageApi.postBoard(
          currentWorld.id,
          myGeneral.id,
          content.trim(),
        );
        await loadPublic();
      }
      setContent("");
    } catch {
      /* ignore */
    } finally {
      setSending(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await messageApi.delete(id);
      if (isSecret) await loadSecret();
      else await loadPublic();
    } catch {
      /* ignore */
    }
  };

  const loadComments = useCallback(async (postId: number) => {
    setCommentLoadingByPost((prev) => ({ ...prev, [postId]: true }));
    try {
      const { data } = await boardApi.getComments(postId);
      setCommentsByPost((prev) => ({ ...prev, [postId]: data }));
    } catch {
      setCommentsByPost((prev) => ({ ...prev, [postId]: [] }));
    } finally {
      setCommentLoadingByPost((prev) => ({ ...prev, [postId]: false }));
    }
  }, []);

  const handleCreateComment = useCallback(
    async (postId: number, comment: string) => {
      if (!myGeneral || !comment.trim()) return;
      await boardApi.createComment(postId, myGeneral.id, comment.trim());
      await loadComments(postId);
    },
    [myGeneral, loadComments],
  );

  const handleDeleteComment = useCallback(
    async (postId: number, commentId: number) => {
      if (!myGeneral) return;
      await boardApi.deleteComment(postId, commentId, myGeneral.id);
      await loadComments(postId);
    },
    [myGeneral, loadComments],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader
        icon={isSecret ? Lock : MessageSquare}
        title={isSecret ? "기밀실" : "회의실"}
        description={
          isSecret
            ? "아국 관료만 열람할 수 있는 비밀 게시판입니다."
            : "모든 세력이 볼 수 있는 공개 게시판입니다."
        }
      />

      <Tabs
        value={tab}
        onValueChange={(nextTab) => {
          setTab(nextTab);
          setVisibleCount(PAGE_SIZE);
          setExpandedId(null);
        }}
      >
        <TabsList>
          <TabsTrigger value="public">회의실</TabsTrigger>
          <TabsTrigger value="secret" disabled={!canAccessSecret}>
            <Lock className="size-3 mr-1" />
            기밀실
          </TabsTrigger>
        </TabsList>

        <TabsContent value={tab} className="mt-4 space-y-4">
          {/* Compose form */}
          {myGeneral && (!isSecret || canAccessSecret) && (
            <Card>
              <CardContent className="space-y-2">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <GeneralPortrait
                    picture={myGeneral.picture}
                    name={myGeneral.name}
                    size="sm"
                  />
                  <span className="font-medium text-white">
                    {myGeneral.name}
                  </span>
                  {isSecret && (
                    <Badge variant="destructive" className="text-[10px]">
                      기밀
                    </Badge>
                  )}
                </div>
                <Textarea
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  placeholder={
                    isSecret
                      ? "기밀 내용을 입력하세요..."
                      : "내용을 입력하세요..."
                  }
                  className="resize-none h-20 text-xs"
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                      handlePost();
                    }
                  }}
                />
                <div className="flex justify-end">
                  <Button
                    size="sm"
                    onClick={handlePost}
                    disabled={sending || !content.trim()}
                  >
                    {sending ? "작성 중..." : "작성"}
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Post list */}
          {loading ? (
            <LoadingState />
          ) : posts.length === 0 ? (
            <EmptyState
              icon={isSecret ? Lock : MessageSquare}
              title={
                isSecret ? "기밀실에 글이 없습니다." : "회의실에 글이 없습니다."
              }
            />
          ) : (
            <div className="space-y-0">
              {/* Table header */}
              <div
                className="grid text-[11px] font-bold text-muted-foreground border-b border-gray-600 pb-1 px-1"
                style={{ gridTemplateColumns: "36px 1fr 80px 72px" }}
              >
                <span className="text-center">#</span>
                <span>내용</span>
                <span className="text-center">작성자</span>
                <span className="text-center">시간</span>
              </div>

              {visiblePosts.map((post, idx) => (
                <BoardRow
                  key={post.id}
                  post={post}
                  idx={posts.length - idx}
                  isSecret={isSecret}
                  isExpanded={expandedId === post.id}
                  isMine={post.srcId === myGeneral?.id}
                  sender={
                    post.srcId ? (generalMap.get(post.srcId) ?? null) : null
                  }
                  senderNation={
                    post.srcId
                      ? (() => {
                          const g = generalMap.get(post.srcId!);
                          return g ? (nationMap.get(g.nationId) ?? null) : null;
                        })()
                      : null
                  }
                  onToggle={() =>
                    setExpandedId((prev) => {
                      const next = prev === post.id ? null : post.id;
                      if (next === post.id) {
                        loadComments(post.id);
                      }
                      return next;
                    })
                  }
                  onDelete={() => handleDelete(post.id)}
                  comments={commentsByPost[post.id] ?? []}
                  commentsLoading={commentLoadingByPost[post.id] ?? false}
                  myGeneralId={myGeneral?.id ?? null}
                  getGeneralName={(id) => generalMap.get(id)?.name ?? `#${id}`}
                  onCreateComment={(comment) =>
                    handleCreateComment(post.id, comment)
                  }
                  onDeleteComment={(commentId) =>
                    handleDeleteComment(post.id, commentId)
                  }
                />
              ))}

              {/* Load more */}
              {hasMore && (
                <div className="pt-3 text-center">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setVisibleCount((c) => c + PAGE_SIZE)}
                  >
                    더 보기 ({posts.length - visibleCount}건 남음)
                  </Button>
                </div>
              )}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ---- Board row sub-component ---- */

interface BoardRowProps {
  post: Message;
  idx: number;
  isSecret: boolean;
  isExpanded: boolean;
  isMine: boolean;
  sender: { name: string; picture: string } | null;
  senderNation: { name: string; color: string } | null;
  comments: BoardComment[];
  commentsLoading: boolean;
  myGeneralId: number | null;
  getGeneralName: (generalId: number) => string;
  onCreateComment: (content: string) => Promise<void>;
  onDeleteComment: (commentId: number) => Promise<void>;
  onToggle: () => void;
  onDelete: () => void;
}

function BoardRow({
  post,
  idx,
  isSecret,
  isExpanded,
  isMine,
  sender,
  senderNation,
  comments,
  commentsLoading,
  myGeneralId,
  getGeneralName,
  onCreateComment,
  onDeleteComment,
  onToggle,
  onDelete,
}: BoardRowProps) {
  const [commentInput, setCommentInput] = useState("");
  const [commentSubmitting, setCommentSubmitting] = useState(false);
  const postContent = (post.payload.content as string) ?? "";
  const preview =
    postContent.length > 50 ? postContent.slice(0, 50) + "..." : postContent;
  const sentDate = new Date(post.sentAt);

  return (
    <div className="border-b border-gray-600/30">
      {/* Summary row */}
      <button
        type="button"
        className="grid w-full items-center text-left text-xs hover:bg-white/5 py-1.5 px-1"
        style={{ gridTemplateColumns: "36px 1fr 80px 72px" }}
        onClick={onToggle}
      >
        <span className="text-center text-muted-foreground">{idx}</span>
        <span className="truncate flex items-center gap-1 min-w-0">
          {isSecret && <Lock className="size-3 text-red-400 shrink-0" />}
          {!isSecret && senderNation && (
            <NationBadge name={senderNation.name} color={senderNation.color} />
          )}
          <span className="truncate">{formatLog(preview)}</span>
          {isExpanded ? (
            <ChevronUp className="size-3 shrink-0 text-muted-foreground ml-auto" />
          ) : (
            <ChevronDown className="size-3 shrink-0 text-muted-foreground ml-auto" />
          )}
        </span>
        <span className="text-center truncate">
          {sender?.name ?? `#${post.srcId ?? "?"}`}
        </span>
        <span className="text-center text-muted-foreground text-[10px]">
          {sentDate.getMonth() + 1}/{sentDate.getDate()}{" "}
          {sentDate.getHours().toString().padStart(2, "0")}:
          {sentDate.getMinutes().toString().padStart(2, "0")}
        </span>
      </button>

      {/* Expanded detail */}
      {isExpanded && (
        <div className="px-2 pb-3 pt-1">
          <Card>
            <CardContent className="space-y-2">
              <div className="flex items-center gap-2">
                <GeneralPortrait
                  picture={sender?.picture}
                  name={sender?.name ?? "?"}
                  size="sm"
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="font-medium text-xs">
                      {sender?.name ?? `#${post.srcId ?? "시스템"}`}
                    </span>
                    {!isSecret && senderNation && (
                      <NationBadge
                        name={senderNation.name}
                        color={senderNation.color}
                      />
                    )}
                    {isSecret && (
                      <Badge variant="destructive" className="text-[10px]">
                        기밀
                      </Badge>
                    )}
                  </div>
                  <span className="text-[10px] text-muted-foreground">
                    {sentDate.toLocaleString("ko-KR")}
                  </span>
                </div>
                {isMine && (
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    className="text-muted-foreground hover:text-red-400"
                    onClick={(e) => {
                      e.stopPropagation();
                      onDelete();
                    }}
                  >
                    <Trash2 className="size-3.5" />
                  </Button>
                )}
              </div>
              <p className="text-xs whitespace-pre-wrap leading-relaxed">
                {formatLog(postContent)}
              </p>

              {/* Comments section */}
              <div className="mt-3 pt-3 border-t border-gray-600/30 space-y-2">
                <span className="text-[10px] font-bold text-muted-foreground">
                  댓글 {comments.length > 0 ? `(${comments.length})` : ""}
                </span>
                {comments.length > 0 ? (
                  <div className="space-y-1.5">
                    {comments.map((c) => (
                      <div
                        key={c.id}
                        className="flex gap-2 text-[11px] bg-white/5 rounded px-2 py-1.5"
                      >
                        <span className="font-medium shrink-0">
                          {getGeneralName(c.authorGeneralId)}
                        </span>
                        <span className="flex-1 text-muted-foreground">
                          {c.content}
                        </span>
                        <span className="text-[10px] text-muted-foreground shrink-0">
                          {new Date(c.createdAt).toLocaleString("ko-KR")}
                        </span>
                        {myGeneralId === c.authorGeneralId && (
                          <Button
                            size="icon-sm"
                            variant="ghost"
                            className="h-5 w-5 text-muted-foreground hover:text-red-400"
                            onClick={async () => {
                              await onDeleteComment(c.id);
                            }}
                          >
                            <Trash2 className="size-3" />
                          </Button>
                        )}
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-[10px] text-muted-foreground">
                    댓글이 없습니다.
                  </p>
                )}
                <div className="space-y-1.5 pt-1">
                  <Textarea
                    placeholder="댓글 입력..."
                    className="text-xs h-16 resize-none"
                    value={commentInput}
                    onChange={(e) => setCommentInput(e.target.value)}
                  />
                  <div className="flex justify-end">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={!commentInput.trim() || commentSubmitting}
                      onClick={async () => {
                        if (!commentInput.trim()) return;
                        setCommentSubmitting(true);
                        try {
                          await onCreateComment(commentInput);
                          setCommentInput("");
                        } finally {
                          setCommentSubmitting(false);
                        }
                      }}
                    >
                      <Send className="size-3.5 mr-1" />
                      {commentSubmitting ? "등록 중..." : "댓글 등록"}
                    </Button>
                  </div>
                </div>
                {commentsLoading && (
                  <div className="text-[10px] text-muted-foreground">
                    댓글 불러오는 중...
                  </div>
                )}
              </div>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}
