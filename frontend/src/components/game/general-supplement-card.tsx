"use client";

import type { GeneralFrontInfo } from "@/types";
import { SammoBar } from "@/components/game/sammo-bar";
import { formatDexLevel, formatHonor } from "@/lib/game-utils";

interface GeneralSupplementCardProps {
  general: GeneralFrontInfo;
  showCommandList?: boolean;
}

/**
 * Supplementary general info card showing stats, dexterity levels,
 * and optionally reserved commands. Ported from legacy GeneralSupplementCard.vue.
 */
export function GeneralSupplementCard({
  general,
  showCommandList = false,
}: GeneralSupplementCardProps) {
  const dexList: [string, number][] = [
    ["보병", general.dex1],
    ["궁병", general.dex2],
    ["기병", general.dex3],
    ["귀병", general.dex4],
    ["차병", general.dex5],
  ];

  const winRate =
    general.warnum > 0
      ? ((general.killnum / general.warnum) * 100).toFixed(2)
      : "0.00";

  const killRate =
    general.deathcrew > 0
      ? ((general.killcrew / Math.max(general.deathcrew, 1)) * 100).toFixed(2)
      : "0.00";

  const reservedCommands =
    showCommandList && general.reservedCommand
      ? Object.values(general.reservedCommand).slice(0, 5)
      : [];

  return (
    <div className="text-center text-sm border border-border">
      {/* Stats section */}
      <div className="grid grid-cols-6 bg-muted/30">
        <div className="col-span-6 bg-muted/50 font-medium py-0.5">
          추가 정보
        </div>

        <div className="bg-muted/50 border-b border-border">명성</div>
        <div className="border-b border-border">
          {formatHonor(general.experience)} (
          {general.experience.toLocaleString()})
        </div>
        <div className="bg-muted/50 border-b border-border">계급</div>
        <div className="border-b border-border">
          {general.dedLevelText} ({general.dedication.toLocaleString()})
        </div>
        <div className="bg-muted/50 border-b border-border">봉급</div>
        <div className="border-b border-border">
          {general.bill.toLocaleString()}
        </div>

        <div className="bg-muted/50 border-b border-border">전투</div>
        <div className="border-b border-border">
          {general.warnum.toLocaleString()}
        </div>
        <div className="bg-muted/50 border-b border-border">계략</div>
        <div className="border-b border-border">
          {general.firenum.toLocaleString()}
        </div>
        <div className="bg-muted/50 border-b border-border">사관</div>
        <div className="border-b border-border">{general.belong}년차</div>

        <div className="bg-muted/50 border-b border-border">승률</div>
        <div className="border-b border-border">{winRate} %</div>
        <div className="bg-muted/50 border-b border-border">승리</div>
        <div className="border-b border-border">
          {general.killnum.toLocaleString()}
        </div>
        <div className="bg-muted/50 border-b border-border">패배</div>
        <div className="border-b border-border">
          {general.deathnum.toLocaleString()}
        </div>

        <div className="bg-muted/50 border-b border-border">살상률</div>
        <div className="border-b border-border">{killRate} %</div>
        <div className="bg-muted/50 border-b border-border">사살</div>
        <div className="border-b border-border">
          {general.killcrew.toLocaleString()}
        </div>
        <div className="bg-muted/50 border-b border-border">피살</div>
        <div className="border-b border-border">
          {general.deathcrew.toLocaleString()}
        </div>
      </div>

      {/* Dexterity section */}
      <div className="flex">
        <div
          className={`${reservedCommands.length > 0 ? "w-2/3" : "flex-1"} grid grid-cols-4 bg-muted/30`}
        >
          <div className="col-span-4 bg-muted/50 font-medium py-0.5">
            숙련도
          </div>
          {dexList.map(([name, dex]) => {
            const info = formatDexLevel(dex);
            return (
              <div key={name} className="contents">
                <div className="bg-muted/50 border-b border-border">{name}</div>
                <div
                  className="border-b border-border"
                  style={{ color: info.color }}
                >
                  {info.name}
                </div>
                <div className="border-b border-border tabular-nums">
                  {(dex / 1000).toLocaleString(undefined, {
                    minimumFractionDigits: 1,
                    maximumFractionDigits: 1,
                  })}
                  K
                </div>
                <div className="border-b border-border flex items-center px-1">
                  <SammoBar height={10} percent={(dex / 1_000_000) * 100} />
                </div>
              </div>
            );
          })}
        </div>

        {reservedCommands.length > 0 && (
          <div className="w-1/3 bg-muted/30">
            <div className="bg-muted/50 font-medium py-0.5">예약턴</div>
            {(reservedCommands as Record<string, unknown>[]).map(
              (turn, idx: number) => (
                <div
                  key={idx}
                  className="border-b border-border text-xs py-0.5 px-1"
                >
                  {String(turn.brief ?? "")}
                </div>
              ),
            )}
          </div>
        )}
      </div>
    </div>
  );
}
