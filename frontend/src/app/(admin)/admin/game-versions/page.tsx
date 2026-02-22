"use client";

import { useEffect, useState, useCallback } from "react";
import { Container, Play, Square, Trash2, RefreshCw } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { gameVersionApi } from "@/lib/gameApi";
import { toast } from "sonner";

interface GameInstanceStatus {
  commitSha: string;
  gameVersion: string;
  jarPath: string;
  port: number;
  worldIds: number[];
  alive: boolean;
  pid: number;
  baseUrl: string;
  containerId: string | null;
  imageTag: string | null;
}

export default function AdminGameVersionsPage() {
  const [loading, setLoading] = useState(true);
  const [instances, setInstances] = useState<GameInstanceStatus[]>([]);
  const [gameVersion, setGameVersion] = useState("");
  const [imageTag, setImageTag] = useState("");
  const [commitSha, setCommitSha] = useState("");
  const [deploying, setDeploying] = useState(false);

  const fetchInstances = useCallback(async () => {
    try {
      const res = await gameVersionApi.list();
      setInstances(res.data);
    } catch {
      toast.error("인스턴스 목록 조회 실패");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchInstances();
  }, [fetchInstances]);

  const handleDeploy = async () => {
    if (!gameVersion.trim()) {
      toast.error("게임 버전을 입력하세요.");
      return;
    }
    setDeploying(true);
    try {
      await gameVersionApi.deploy({
        gameVersion: gameVersion.trim(),
        imageTag: imageTag.trim() || undefined,
        commitSha: commitSha.trim() || undefined,
      });
      toast.success(`${gameVersion} 배포 완료`);
      setGameVersion("");
      setImageTag("");
      setCommitSha("");
      await fetchInstances();
    } catch {
      toast.error("배포 실패");
    } finally {
      setDeploying(false);
    }
  };

  const handleStop = async (version: string) => {
    try {
      await gameVersionApi.stop(version);
      toast.success(`${version} 중지 완료`);
      await fetchInstances();
    } catch {
      toast.error("중지 실패");
    }
  };

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4">
      <PageHeader icon={Container} title="게임 버전 관리" />

      <Card>
        <CardHeader>
          <CardTitle>새 버전 배포</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-1">
              <span className="text-sm text-muted-foreground">게임 버전 *</span>
              <Input
                placeholder="v1.0.0"
                value={gameVersion}
                onChange={(e) => setGameVersion(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <span className="text-sm text-muted-foreground">
                이미지 태그 (선택)
              </span>
              <Input
                placeholder="opensam/game-app:v1.0.0"
                value={imageTag}
                onChange={(e) => setImageTag(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <span className="text-sm text-muted-foreground">
                Commit SHA (선택)
              </span>
              <Input
                placeholder="abc1234"
                value={commitSha}
                onChange={(e) => setCommitSha(e.target.value)}
              />
            </div>
          </div>
          <Button
            onClick={handleDeploy}
            disabled={deploying || !gameVersion.trim()}
            className="bg-red-400 hover:bg-red-500 text-white"
          >
            <Play className="size-4 mr-1" />
            {deploying ? "배포 중..." : "배포"}
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>실행 중인 인스턴스</CardTitle>
          <Button
            variant="ghost"
            size="sm"
            onClick={fetchInstances}
            className="text-muted-foreground"
          >
            <RefreshCw className="size-4" />
          </Button>
        </CardHeader>
        <CardContent>
          {instances.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              실행 중인 인스턴스가 없습니다.
            </p>
          ) : (
            <div className="space-y-3">
              {instances.map((inst) => (
                <div
                  key={inst.gameVersion}
                  className="flex items-center justify-between p-3 rounded-lg border border-border bg-card"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-mono font-bold">
                        {inst.gameVersion}
                      </span>
                      <Badge
                        variant={inst.alive ? "default" : "destructive"}
                        className={
                          inst.alive
                            ? "bg-green-500/20 text-green-400 border-green-500/30"
                            : ""
                        }
                      >
                        {inst.alive ? "Running" : "Stopped"}
                      </Badge>
                    </div>
                    <div className="text-xs text-muted-foreground space-x-4">
                      <span>SHA: {inst.commitSha.slice(0, 8)}</span>
                      <span>Port: {inst.port}</span>
                      {inst.containerId && (
                        <span>Container: {inst.containerId.slice(0, 12)}</span>
                      )}
                      {inst.imageTag && <span>Image: {inst.imageTag}</span>}
                      <span>
                        Worlds:{" "}
                        {inst.worldIds.length > 0
                          ? inst.worldIds.join(", ")
                          : "없음"}
                      </span>
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleStop(inst.gameVersion)}
                    className="text-red-400 hover:text-red-300 hover:bg-red-400/10"
                  >
                    <Square className="size-4 mr-1" />
                    중지
                  </Button>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
