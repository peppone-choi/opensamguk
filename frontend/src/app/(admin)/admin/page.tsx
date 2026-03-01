"use client";

import { useEffect, useState, useMemo, useCallback } from "react";
import {
  LayoutDashboard,
  Plus,
  Trash2,
  Globe,
  Play,
  Pause,
  RotateCcw,
  MessageSquarePlus,
  Info,
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
import { adminApi, worldApi, scenarioApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { WorldState, Scenario, AdminDashboard } from "@/types";

export default function AdminDashboardPage() {
  // Gateway-level world list (always available)
  const [worlds, setWorlds] = useState<WorldState[]>([]);
  const [loading, setLoading] = useState(true);

  // Scenarios
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const scenarioMap = useMemo(
    () => new Map(scenarios.map((s) => [s.code, s.title])),
    [scenarios],
  );

  // Per-world dashboard (only when game instance is running)
  const [dashboard, setDashboard] = useState<AdminDashboard | null>(null);
  const [dashboardAvailable, setDashboardAvailable] = useState(false);
  const [notice, setNotice] = useState("");
  const [turnTerm, setTurnTerm] = useState("");
  const [locked, setLocked] = useState(false);
  const [logMessage, setLogMessage] = useState("");

  // Global (gateway) system flags
  const [allowLogin, setAllowLogin] = useState<boolean | null>(null);
  const [allowJoin, setAllowJoin] = useState<boolean | null>(null);
  const [savingSystemFlags, setSavingSystemFlags] = useState(false);

  // Create world form
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newScenario, setNewScenario] = useState("");
  const [newWorldName, setNewWorldName] = useState("");
  const [newTurnTerm, setNewTurnTerm] = useState("300");
  const [newGameVersion, setNewGameVersion] = useState("latest");
  const [creating, setCreating] = useState(false);

  // Reset dialog
  const [resetTarget, setResetTarget] = useState<{
    id: number;
    name: string;
  } | null>(null);
  const [resetScenario, setResetScenario] = useState("");

  const loadWorlds = useCallback(() => {
    worldApi
      .list()
      .then((res) => setWorlds(res.data))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const init = async () => {
      // 1. Load worlds from gateway (always works)
      loadWorlds();

      // 2. Load scenarios
      scenarioApi
        .list()
        .then(({ data }) => {
          setScenarios(data);
          if (data.length > 0) setNewScenario(data[0].code);
        })
        .catch(() => {});

      // 3. Load gateway system flags
      adminApi
        .getSystemFlags()
        .then((res) => {
          setAllowLogin(res.data.allowLogin);
          setAllowJoin(res.data.allowJoin);
        })
        .catch(() => {});

      // 4. Try loading per-world dashboard (may fail if no game instance)
      adminApi
        .getDashboard()
        .then((res) => {
          const d = res.data;
          setDashboard(d);
          setDashboardAvailable(true);
          if (d.currentWorld) {
            setNotice((d.currentWorld.config?.notice as string) ?? "");
            setTurnTerm(String(d.currentWorld.config?.turnTerm ?? ""));
            setLocked(Boolean(d.currentWorld.config?.locked));
          }
        })
        .catch(() => {
          setDashboardAvailable(false);
        })
        .finally(() => {
          setLoading(false);
        });
    };
    init();
  }, [loadWorlds]);

  // ── Handlers ────────────────────────────────────────────────

  const handleCreateWorld = async () => {
    if (!newScenario) {
      toast.error("시나리오를 선택하세요.");
      return;
    }
    setCreating(true);
    try {
      const res = await adminApi.createWorld({
        scenarioCode: newScenario,
        name: newWorldName.trim() || undefined,
        turnTerm: newTurnTerm ? Number(newTurnTerm) : undefined,
        gameVersion: newGameVersion.trim() || undefined,
      });
      toast.success(`월드 생성 완료 (ID: ${res.data.worldId})`);
      setNewWorldName("");
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
    action: "open" | "close",
  ) => {
    const labels = { open: "오픈", close: "폐쇄" };
    try {
      if (action === "open") {
        await adminApi.activateWorld(worldId, {
          gameVersion: newGameVersion.trim() || undefined,
        });
      } else {
        await adminApi.deactivateWorld(worldId);
      }
      toast.success(`월드 #${worldId} ${labels[action]} 완료`);
      loadWorlds();
    } catch {
      toast.error(`${labels[action]} 실패`);
    }
  };

  const handleOpenReset = (worldId: number, worldName: string) => {
    const world = worlds.find((w) => w.id === worldId);
    setResetTarget({ id: worldId, name: worldName });
    setResetScenario(world?.scenarioCode || scenarios[0]?.code || "");
  };

  const handleConfirmReset = async () => {
    if (!resetTarget) return;
    if (
      !confirm(
        `월드 "${resetTarget.name}"을 정말 리셋하시겠습니까? 모든 데이터가 초기화됩니다.`,
      )
    )
      return;
    try {
      await adminApi.resetWorld(
        resetTarget.id,
        resetScenario || undefined,
        newGameVersion.trim() || undefined,
      );
      toast.success(`월드 #${resetTarget.id} 리셋 완료`);
      setResetTarget(null);
      loadWorlds();
    } catch {
      toast.error("리셋 실패");
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

  return (
    <div className="space-y-4">
      <PageHeader icon={LayoutDashboard} title="관리자 대시보드" />

      {/* ── 전역 스위치 (Gateway) ─────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">전역 스위치</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
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
        </CardContent>
      </Card>

      {/* ── 월드 관리 (Gateway) ───────────────────────────── */}
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
          {/* Create Form */}
          {showCreateForm && (
            <div className="p-4 border rounded-md space-y-3 bg-muted/20">
              <h4 className="text-sm font-medium">새 월드 생성</h4>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    시나리오
                  </label>
                  <select
                    value={newScenario}
                    onChange={(e) => setNewScenario(e.target.value)}
                    className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                  >
                    <option value="">시나리오 선택</option>
                    {scenarios.map((s) => (
                      <option key={s.code} value={s.code}>
                        {s.title} ({s.startYear}년)
                      </option>
                    ))}
                  </select>
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    월드 이름
                  </label>
                  <Input
                    value={newWorldName}
                    onChange={(e) => setNewWorldName(e.target.value)}
                    placeholder="선택사항"
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
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    게임 버전
                  </label>
                  <Input
                    value={newGameVersion}
                    onChange={(e) => setNewGameVersion(e.target.value)}
                    placeholder="latest"
                  />
                </div>
              </div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={handleCreateWorld}
                  disabled={creating || !newScenario}
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

          {/* Reset Dialog */}
          {resetTarget && (
            <div className="p-4 border border-destructive/50 rounded-md space-y-3 bg-destructive/5">
              <h4 className="text-sm font-medium flex items-center gap-2">
                <RotateCcw className="size-4" />
                월드 리셋: {resetTarget.name}
              </h4>
              <p className="text-xs text-muted-foreground">
                시나리오를 선택하면 해당 시나리오로 월드가 초기화됩니다. 모든
                진행 상황이 삭제됩니다.
              </p>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    시나리오
                  </label>
                  <select
                    value={resetScenario}
                    onChange={(e) => setResetScenario(e.target.value)}
                    className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                  >
                    {scenarios.map((s) => (
                      <option key={s.code} value={s.code}>
                        {s.title} ({s.startYear}년)
                      </option>
                    ))}
                  </select>
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    게임 버전
                  </label>
                  <Input
                    value={newGameVersion}
                    onChange={(e) => setNewGameVersion(e.target.value)}
                    placeholder="latest"
                  />
                </div>
              </div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={handleConfirmReset}
                  disabled={!resetScenario}
                >
                  리셋 확인
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setResetTarget(null)}
                >
                  취소
                </Button>
              </div>
            </div>
          )}

          {/* World Table */}
          {worlds.length === 0 ? (
            <p className="text-sm text-muted-foreground">월드가 없습니다.</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>시나리오</TableHead>
                  <TableHead>시점</TableHead>
                  <TableHead>게임버전</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>액션</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {worlds.map((w) => {
                  const displayName =
                    w.name || scenarioMap.get(w.scenarioCode) || w.scenarioCode;
                  return (
                    <TableRow key={w.id}>
                      <TableCell>{w.id}</TableCell>
                      <TableCell className="font-medium">
                        {displayName}
                      </TableCell>
                      <TableCell>
                        {scenarioMap.get(w.scenarioCode) || w.scenarioCode}
                      </TableCell>
                      <TableCell>
                        {w.currentYear}년 {w.currentMonth}월
                      </TableCell>
                      <TableCell>
                        <Badge variant="secondary" className="text-xs">
                          {w.gameVersion}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={w.config?.locked ? "destructive" : "outline"}
                        >
                          {w.config?.locked ? "잠금" : "운영중"}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {w.config?.locked ? (
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
                            onClick={() => handleOpenReset(w.id, displayName)}
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
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* ── 게임 설정 (Per-world, requires game instance) ── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">게임 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {dashboardAvailable && dashboard?.currentWorld ? (
            <>
              <div className="grid grid-cols-3 gap-4 text-center">
                <div>
                  <p className="text-xs text-muted-foreground">현재 시점</p>
                  <p className="text-lg font-bold">
                    {dashboard.currentWorld.year}년{" "}
                    {dashboard.currentWorld.month}월
                  </p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">시나리오</p>
                  <p className="text-lg font-bold">
                    {scenarioMap.get(dashboard.currentWorld.scenarioCode) ||
                      dashboard.currentWorld.scenarioCode}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">월드 수</p>
                  <p className="text-lg font-bold">{dashboard.worldCount}</p>
                </div>
              </div>

              <div className="space-y-1">
                <label className="text-sm text-muted-foreground">
                  공지사항
                </label>
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
            </>
          ) : (
            <div className="flex items-center gap-3 text-sm text-muted-foreground py-4">
              <Info className="size-5 shrink-0" />
              <p>
                게임 인스턴스가 실행 중이 아닙니다. 월드를 오픈(활성화)하면
                공지사항, 턴 간격, 서버 잠금 등 상세 설정이 가능합니다.
              </p>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
