"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { nationPolicyApi } from "@/lib/gameApi";
import { Landmark } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";

export default function InternalAffairsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const [loading, setLoading] = useState(true);

  // Policy fields
  const [rate, setRate] = useState(15);
  const [bill, setBill] = useState(100);
  const [secretLimit, setSecretLimit] = useState(0);
  const [strategicCmdLimit, setStrategicCmdLimit] = useState(0);
  const [notice, setNotice] = useState("");
  const [scoutMsg, setScoutMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    nationPolicyApi
      .getPolicy(myGeneral.nationId)
      .then(({ data }) => {
        setRate((data.rate as number) ?? 15);
        setBill((data.bill as number) ?? 100);
        setSecretLimit((data.secretLimit as number) ?? 0);
        setStrategicCmdLimit((data.strategicCmdLimit as number) ?? 0);
        setNotice((data.notice as string) ?? "");
        setScoutMsg((data.scoutMsg as string) ?? "");
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  const handleSavePolicy = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    setMsg("");
    try {
      await nationPolicyApi.updatePolicy(myGeneral.nationId, {
        rate,
        bill,
        secretLimit,
        strategicCmdLimit,
      });
      setMsg("정책이 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSaveNotice = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    try {
      await nationPolicyApi.updateNotice(myGeneral.nationId, notice);
      setMsg("공지가 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSaveScoutMsg = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    try {
      await nationPolicyApi.updateScoutMsg(myGeneral.nationId, scoutMsg);
      setMsg("정찰 메시지가 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4 text-muted-foreground">소속 국가가 없습니다.</div>
    );

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Landmark} title="내무부" />

      {msg && <p className="text-sm text-green-400">{msg}</p>}

      <Tabs defaultValue="policy">
        <TabsList>
          <TabsTrigger value="policy">정책</TabsTrigger>
          <TabsTrigger value="notice">공지</TabsTrigger>
          <TabsTrigger value="scout">정찰 메시지</TabsTrigger>
        </TabsList>

        <TabsContent value="policy" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>국가 정책</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    교역율 (5-30)
                  </label>
                  <Input
                    type="number"
                    min={5}
                    max={30}
                    value={rate}
                    onChange={(e) => setRate(Number(e.target.value))}
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    세율 (20-200)
                  </label>
                  <Input
                    type="number"
                    min={20}
                    max={200}
                    value={bill}
                    onChange={(e) => setBill(Number(e.target.value))}
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    기밀 제한
                  </label>
                  <Input
                    type="number"
                    min={0}
                    value={secretLimit}
                    onChange={(e) => setSecretLimit(Number(e.target.value))}
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    전략명령 제한
                  </label>
                  <Input
                    type="number"
                    min={0}
                    value={strategicCmdLimit}
                    onChange={(e) =>
                      setStrategicCmdLimit(Number(e.target.value))
                    }
                  />
                </div>
              </div>
              <Button onClick={handleSavePolicy} disabled={saving}>
                {saving ? "저장 중..." : "정책 저장"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="notice" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>국가 공지</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <Textarea
                value={notice}
                onChange={(e) => setNotice(e.target.value)}
                placeholder="국가 공지를 입력하세요..."
                className="resize-none h-32"
              />
              <Button onClick={handleSaveNotice} disabled={saving}>
                {saving ? "저장 중..." : "공지 저장"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="scout" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>정찰 메시지</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <Textarea
                value={scoutMsg}
                onChange={(e) => setScoutMsg(e.target.value)}
                placeholder="정찰 메시지를 입력하세요..."
                className="resize-none h-32"
              />
              <Button onClick={handleSaveScoutMsg} disabled={saving}>
                {saving ? "저장 중..." : "정찰 메시지 저장"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
