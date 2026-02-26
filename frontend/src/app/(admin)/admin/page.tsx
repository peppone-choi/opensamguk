"use client";

import { useEffect, useState } from "react";
import {
  LayoutDashboard,
  Plus,
  Trash2,
  Globe,
  Play,
  Pause,
  RotateCcw,
  MessageSquarePlus,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { AdminDashboard } from "@/types";

export default function AdminDashboardPage() {
  const [data, setData] = useState<AdminDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState("");
  const [turnTerm, setTurnTerm] = useState("");
  const [locked, setLocked] = useState(false);
  const [logMessage, setLogMessage] = useState("");

  // Global (gateway) system flags
  const [allowLogin, setAllowLogin] = useState<boolean | null>(null);
  const [allowJoin, setAllowJoin] = useState<boolean | null>(null);
  const [savingSystemFlags, setSavingSystemFlags] = useState(false);

  // World management
  const [worlds, setWorlds] = useState<
    {
      id: number;
      scenarioCode: string;
      year: number;
      month: number;
      locked: boolean;
    }[]
  >([]);
  const [newScenario, setNewScenario] = useState("");
  const [newTurnTerm, setNewTurnTerm] = useState("300");
  const [creating, setCreating] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);

  const loadWorlds = () => {
    adminApi
      .listWorlds()
      .then((res) => setWorlds(res.data))
      .catch(() => {});
  };

  useEffect(() => {
    loadWorlds();

    adminApi
      .getSystemFlags()
      .then((res) => {
        setAllowLogin(res.data.allowLogin);
        setAllowJoin(res.data.allowJoin);
      })
      .catch(() => {
        // ignore (non-global-admin or older gateway)
      });

    adminApi
      .getDashboard()
      .then((res) => {
        const d = res.data;
        setData(d);
        if (d.currentWorld) {
          setNotice((d.currentWorld.config?.notice as string) ?? "");
          setTurnTerm(String(d.currentWorld.config?.turnTerm ?? ""));
          setLocked(Boolean(d.currentWorld.config?.locked));
        }
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const handleCreateWorld = async () => {
    if (!newScenario.trim()) {
      toast.error("시나리오 코드를 입력하세요.");
      return;
    }
    setCreating(true);
    try {
      const res = await adminApi.createWorld({
        scenarioCode: newScenario.trim(),
        turnTerm: newTurnTerm ? Number(newTurnTerm) : undefined,
      });
      toast.success(`월드 생성 완료 (ID: ${res.data.worldId})`);
      setNewScenario("");
      setShowCreateForm(false);
      loadWorlds();
    } catch {
      toast.error("월드 생성 실패");
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteWorld = async (worldId: number) => {
    if (
      !confirm(
        `월드 #${worldId}를 정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.`,
      )
    )
      return;
    try {
      await adminApi.deleteWorld(worldId);
      toast.success(`월드 #${worldId} 삭제 완료`);
      loadWorlds();
    } catch {
      toast.error("월드 삭제 실패");
    }
  };

  const handleWorldAction = async (
    worldId: number,
    action: "open" | "close" | "reset",
  ) => {
    const labels = { open: "오픈", close: "폐쇄", reset: "리셋" };
    if (action === "reset") {
      if (
        !confirm(
          `월드 #${worldId}를 정말 리셋하시겠습니까? 모든 데이터가 초기화됩니다.`,
        )
      )
        return;
    }
    try {
      if (action === "open") {
        await adminApi.activateWorld(worldId);
      } else if (action === "close") {
        await adminApi.deactivateWorld(worldId);
      } else {
        await adminApi.resetWorld(worldId);
      }
      toast.success(`월드 #${worldId} ${labels[action]} 완료`);
      loadWorlds();
    } catch {
      toast.error(`${labels[action]} 실패`);
    }
  };

  const handleWriteLog = async () => {
    if (!logMessage.trim()) return;
    try {
      await adminApi.writeLog(logMessage.trim());
      toast.success("중원정세 로그가 추가되었습니다.");
      setLogMessage("");
    } catch {
      toast.error("로그 쓰기 실패");
    }
  };

  const handleSaveSystemFlags = async () => {
    if (allowLogin === null || allowJoin === null) return;
    setSavingSystemFlags(true);
    try {
      await adminApi.patchSystemFlags({ allowLogin, allowJoin });
      toast.success("전역 스위치가 저장되었습니다.");
    } catch {
      toast.error("전역 스위치 저장 실패");
    } finally {
      setSavingSystemFlags(false);
    }
  };

  const handleSave = async () => {
    try {
      await adminApi.updateSettings({
        notice,
        turnTerm: turnTerm ? Number(turnTerm) : undefined,
        locked,
      });
      toast.success("설정이 저장되었습니다.");
    } catch {
      toast.error("저장 실패");
    }
  };

  if (loading) return <LoadingState />;

  const world = data?.currentWorld;

  return (
    <div className="space-y-4">
      <PageHeader icon={LayoutDashboard} title="관리자 대시보드" />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader>
            <CardTitle>월드 수</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">{data?.worldCount ?? 0}</span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>현재 시점</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">
              {world ? `${world.year}년 ${world.month}월` : "-"}
            </span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>시나리오</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">
              {world?.scenarioCode ?? "-"}
            </span>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>게임 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <div>
                <p className="text-sm font-medium">전역 스위치</p>
                <p className="text-xs text-muted-foreground">
                  (가입/로그인 허용)
                </p>
              </div>
              <Button
                size="sm"
                variant="outline"
                disabled={
                  savingSystemFlags || allowLogin === null || allowJoin === null
                }
                onClick={handleSaveSystemFlags}
              >
                저장
              </Button>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
              <label className="flex items-center justify-between rounded-md border border-border px-3 py-2 text-sm">
                <span>로그인 허용</span>
                <input
                  type="checkbox"
                  checked={Boolean(allowLogin)}
                  disabled={allowLogin === null}
                  onChange={(e) => setAllowLogin(e.target.checked)}
                />
              </label>
              <label className="flex items-center justify-between rounded-md border border-border px-3 py-2 text-sm">
                <span>가입 허용</span>
                <input
                  type="checkbox"
                  checked={Boolean(allowJoin)}
                  disabled={allowJoin === null}
                  onChange={(e) => setAllowJoin(e.target.checked)}
                />
              </label>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">공지사항</label>
            <Input
              value={notice}
              onChange={(e) => setNotice(e.target.value)}
              placeholder="공지사항 입력"
            />
          </div>
          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">
              중원정세 추가
            </label>
            <div className="flex gap-2">
              <Input
                value={logMessage}
                onChange={(e) => setLogMessage(e.target.value)}
                placeholder="중원정세 메시지 입력"
                onKeyDown={(e) => e.key === "Enter" && handleWriteLog()}
              />
              <Button size="sm" variant="outline" onClick={handleWriteLog}>
                <MessageSquarePlus className="size-4 mr-1" />
                로그쓰기
              </Button>
            </div>
          </div>
          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">
              턴 간격 (초)
            </label>
            <Input
              type="number"
              value={turnTerm}
              onChange={(e) => setTurnTerm(e.target.value)}
              placeholder="300"
            />
          </div>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={locked}
              onChange={(e) => setLocked(e.target.checked)}
              id="locked"
              className="accent-red-400"
            />
            <label htmlFor="locked" className="text-sm">
              서버 잠금
            </label>
          </div>
          <Button
            onClick={handleSave}
            className="bg-red-400 hover:bg-red-500 text-white"
          >
            저장
          </Button>
        </CardContent>
      </Card>

      {/* World Management */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2">
            <Globe className="size-5" />
            월드 관리
          </CardTitle>
          <Button
            size="sm"
            variant="outline"
            onClick={() => setShowCreateForm(!showCreateForm)}
          >
            <Plus className="size-4 mr-1" />새 월드 생성
          </Button>
        </CardHeader>
        <CardContent className="space-y-4">
          {showCreateForm && (
            <div className="p-4 border rounded-md space-y-3 bg-muted/20">
              <h4 className="text-sm font-medium">새 월드 생성</h4>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    시나리오 코드
                  </label>
                  <Input
                    value={newScenario}
                    onChange={(e) => setNewScenario(e.target.value)}
                    placeholder="예: 001_189"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    턴 간격 (초)
                  </label>
                  <Input
                    type="number"
                    value={newTurnTerm}
                    onChange={(e) => setNewTurnTerm(e.target.value)}
                    placeholder="300"
                  />
                </div>
              </div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={handleCreateWorld}
                  disabled={creating}
                >
                  {creating ? "생성 중..." : "생성"}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setShowCreateForm(false)}
                >
                  취소
                </Button>
              </div>
            </div>
          )}

          {worlds.length === 0 ? (
            <p className="text-sm text-muted-foreground">월드가 없습니다.</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>시나리오</TableHead>
                  <TableHead>시점</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>액션</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {worlds.map((w) => (
                  <TableRow key={w.id}>
                    <TableCell>{w.id}</TableCell>
                    <TableCell className="font-medium">
                      {w.scenarioCode}
                    </TableCell>
                    <TableCell>
                      {w.year}년 {w.month}월
                    </TableCell>
                    <TableCell>
                      {w.locked ? (
                        <Badge variant="destructive">잠금</Badge>
                      ) : (
                        <Badge variant="outline">운영중</Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {w.locked ? (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleWorldAction(w.id, "open")}
                          >
                            <Play className="size-3.5 mr-1" />
                            오픈
                          </Button>
                        ) : (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleWorldAction(w.id, "close")}
                          >
                            <Pause className="size-3.5 mr-1" />
                            폐쇄
                          </Button>
                        )}
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => handleWorldAction(w.id, "reset")}
                        >
                          <RotateCcw className="size-3.5 mr-1" />
                          리셋
                        </Button>
                        <Button
                          size="sm"
                          variant="destructive"
                          onClick={() => handleDeleteWorld(w.id)}
                        >
                          <Trash2 className="size-3.5 mr-1" />
                          삭제
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
