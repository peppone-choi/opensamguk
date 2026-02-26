"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { nationPolicyApi } from "@/lib/gameApi";
import type { Diplomacy, City, Nation } from "@/types";
import {
  Landmark,
  Bold,
  Italic,
  List,
  Heading2,
  Undo,
  Redo,
  Image as ImageIcon,
  Handshake,
  Calculator,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";

/* ── Simple Rich Text Editor (contentEditable-based WYSIWYG) ── */

function RichTextEditor({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (html: string) => void;
  placeholder?: string;
}) {
  const editorRef = useRef<HTMLDivElement>(null);
  const isInitRef = useRef(false);

  useEffect(() => {
    if (editorRef.current && !isInitRef.current) {
      editorRef.current.innerHTML = value;
      isInitRef.current = true;
    }
  }, [value]);

  const exec = useCallback(
    (cmd: string, val?: string) => {
      document.execCommand(cmd, false, val);
      if (editorRef.current) {
        onChange(editorRef.current.innerHTML);
      }
    },
    [onChange],
  );

  const handleInput = useCallback(() => {
    if (editorRef.current) {
      onChange(editorRef.current.innerHTML);
    }
  }, [onChange]);

  const handleInsertImage = useCallback(() => {
    const url = prompt("이미지 URL을 입력하세요:");
    if (url) {
      exec("insertImage", url);
    }
  }, [exec]);

  return (
    <div className="border rounded-md overflow-hidden">
      <div className="flex items-center gap-1 p-1 border-b bg-muted/30">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("bold")}
          title="굵게"
        >
          <Bold className="size-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("italic")}
          title="기울임"
        >
          <Italic className="size-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("formatBlock", "h3")}
          title="제목"
        >
          <Heading2 className="size-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("insertUnorderedList")}
          title="목록"
        >
          <List className="size-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={handleInsertImage}
          title="이미지 삽입"
        >
          <ImageIcon className="size-3.5" />
        </Button>
        <div className="flex-1" />
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("undo")}
          title="실행 취소"
        >
          <Undo className="size-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={() => exec("redo")}
          title="다시 실행"
        >
          <Redo className="size-3.5" />
        </Button>
      </div>
      <div
        ref={editorRef}
        contentEditable
        className="min-h-[160px] p-3 text-sm focus:outline-none prose prose-invert prose-sm max-w-none"
        onInput={handleInput}
        data-placeholder={placeholder}
        suppressContentEditableWarning
      />
    </div>
  );
}

const DIPLOMACY_STATES: Record<string, { label: string; color: string }> = {
  ally: { label: "동맹", color: "text-blue-400" },
  war: { label: "전쟁", color: "text-red-400" },
  ceasefire: { label: "휴전", color: "text-yellow-400" },
  trade: { label: "교역", color: "text-green-400" },
  nonaggression: { label: "불가침", color: "text-cyan-400" },
  neutral: { label: "중립", color: "text-gray-400" },
};

export default function InternalAffairsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { cities, nations, diplomacy, loadAll } = useGameStore();
  const [loading, setLoading] = useState(true);

  // Policy fields
  const [rate, setRate] = useState(15);
  const [bill, setBill] = useState(100);
  const [secretLimit, setSecretLimit] = useState(0);
  const [strategicCmdLimit, setStrategicCmdLimit] = useState(0);
  const [blockWar, setBlockWar] = useState(false);
  const [blockScout, setBlockScout] = useState(false);
  const [notice, setNotice] = useState("");
  const [scoutMsg, setScoutMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  // Diplomacy for my nation
  const myDiplomacy = useMemo(() => {
    if (!myGeneral?.nationId) return [];
    return diplomacy.filter(
      (d) =>
        (d.srcNationId === myGeneral.nationId ||
          d.destNationId === myGeneral.nationId) &&
        !d.isDead,
    );
  }, [diplomacy, myGeneral?.nationId]);

  // Financial calculator
  const myCities = useMemo(() => {
    if (!myGeneral?.nationId) return [];
    return cities.filter((c) => c.nationId === myGeneral.nationId);
  }, [cities, myGeneral?.nationId]);

  const myNation = myGeneral?.nationId
    ? nationMap.get(myGeneral.nationId)
    : null;

  const financeSummary = useMemo(() => {
    let totalGoldIncome = 0;
    let totalRiceIncome = 0;
    let totalExpense = 0;
    for (const city of myCities) {
      const trustRatio = city.trust / 200 + 0.5;
      const goldIncome =
        city.commMax > 0
          ? Math.round(
              (city.pop * (city.comm / city.commMax) * trustRatio) / 30,
            )
          : 0;
      const riceIncome =
        city.agriMax > 0
          ? Math.round(
              (city.pop * (city.agri / city.agriMax) * trustRatio) / 30,
            )
          : 0;
      const expense = Math.round(city.pop * ((myNation?.bill ?? 100) / 1000));
      totalGoldIncome += goldIncome;
      totalRiceIncome += riceIncome;
      totalExpense += expense;
    }
    return {
      totalGoldIncome,
      totalRiceIncome,
      totalExpense,
      netGold: totalGoldIncome - totalExpense,
    };
  }, [myCities, myNation?.bill]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    nationPolicyApi
      .getPolicy(myGeneral.nationId)
      .then(({ data }) => {
        setRate((data.rate as number) ?? 15);
        setBill((data.bill as number) ?? 100);
        setSecretLimit((data.secretLimit as number) ?? 0);
        setStrategicCmdLimit((data.strategicCmdLimit as number) ?? 0);
        setBlockWar(
          Boolean((data as unknown as Record<string, unknown>).blockWar),
        );
        setBlockScout(
          Boolean((data as unknown as Record<string, unknown>).blockScout),
        );
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
        blockWar,
        blockScout,
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
          <TabsTrigger value="diplomacy">외교 현황</TabsTrigger>
          <TabsTrigger value="finance">재정 계산</TabsTrigger>
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
              <div className="grid grid-cols-2 gap-4">
                <div className="flex items-center justify-between rounded-md border p-3">
                  <div className="space-y-0.5">
                    <label className="text-sm font-medium">전쟁 차단</label>
                    <p className="text-xs text-muted-foreground">
                      소속 장수의 전쟁 명령을 차단합니다
                    </p>
                  </div>
                  <Switch checked={blockWar} onCheckedChange={setBlockWar} />
                </div>
                <div className="flex items-center justify-between rounded-md border p-3">
                  <div className="space-y-0.5">
                    <label className="text-sm font-medium">정찰 차단</label>
                    <p className="text-xs text-muted-foreground">
                      소속 장수의 정찰 명령을 차단합니다
                    </p>
                  </div>
                  <Switch
                    checked={blockScout}
                    onCheckedChange={setBlockScout}
                  />
                </div>
              </div>
              <Button onClick={handleSavePolicy} disabled={saving}>
                {saving ? "저장 중..." : "정책 저장"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Diplomacy Tab */}
        <TabsContent value="diplomacy" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Handshake className="size-4" />
                외교 현황
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {myDiplomacy.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  외교 관계가 없습니다.
                </p>
              ) : (
                <div className="space-y-2">
                  {myDiplomacy.map((d) => {
                    const otherId =
                      d.srcNationId === myGeneral!.nationId
                        ? d.destNationId
                        : d.srcNationId;
                    const otherNation = nationMap.get(otherId);
                    const stateInfo = DIPLOMACY_STATES[d.stateCode] ?? {
                      label: d.stateCode,
                      color: "text-gray-400",
                    };
                    return (
                      <div
                        key={d.id}
                        className="flex items-center justify-between border rounded p-2"
                      >
                        <div className="flex items-center gap-2">
                          {otherNation && (
                            <NationBadge
                              name={otherNation.name}
                              color={otherNation.color}
                            />
                          )}
                          <span className="text-sm">
                            {otherNation?.name ?? `국가#${otherId}`}
                          </span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span
                            className={`text-xs font-bold ${stateInfo.color}`}
                          >
                            {stateInfo.label}
                          </span>
                          <span className="text-[10px] text-muted-foreground">
                            잔여 {d.term}턴
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Finance Tab */}
        <TabsContent value="finance" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Calculator className="size-4" />
                재정 계산기
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="border rounded p-3 text-center">
                  <div className="text-[10px] text-muted-foreground">
                    금 수입
                  </div>
                  <div className="text-sm font-bold text-amber-400 tabular-nums">
                    {financeSummary.totalGoldIncome.toLocaleString()}
                  </div>
                </div>
                <div className="border rounded p-3 text-center">
                  <div className="text-[10px] text-muted-foreground">
                    쌀 수입
                  </div>
                  <div className="text-sm font-bold text-green-400 tabular-nums">
                    {financeSummary.totalRiceIncome.toLocaleString()}
                  </div>
                </div>
                <div className="border rounded p-3 text-center">
                  <div className="text-[10px] text-muted-foreground">지출</div>
                  <div className="text-sm font-bold text-red-400 tabular-nums">
                    {financeSummary.totalExpense.toLocaleString()}
                  </div>
                </div>
                <div className="border rounded p-3 text-center">
                  <div className="text-[10px] text-muted-foreground">
                    금 순수익
                  </div>
                  <div
                    className={`text-sm font-bold tabular-nums ${financeSummary.netGold >= 0 ? "text-green-400" : "text-red-400"}`}
                  >
                    {financeSummary.netGold >= 0 ? "+" : ""}
                    {financeSummary.netGold.toLocaleString()}
                  </div>
                </div>
              </div>
              <div className="text-xs text-muted-foreground">
                도시 수: {myCities.length}개 / 보유금:{" "}
                {myNation?.gold?.toLocaleString() ?? 0} / 보유쌀:{" "}
                {myNation?.rice?.toLocaleString() ?? 0}
              </div>
              <div className="text-[10px] text-muted-foreground">
                ※ 예상치이며 실제와 다를 수 있습니다. 관직자, 수도 보너스 등
                미반영.
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="notice" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>국가 공지</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <RichTextEditor
                value={notice}
                onChange={setNotice}
                placeholder="국가 공지를 입력하세요..."
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
