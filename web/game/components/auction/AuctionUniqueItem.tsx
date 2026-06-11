'use client';

// AuctionUniqueItem — legacy hwe/ts/components/AuctionUniqueItem.vue 충실 포팅(FE grand truth, PHP가 이김).
// 유니크 경매: 익명 입찰(obfuscatedName=내 가명), 유산포인트 잔여, 입찰가 min=ceil(최고*1.01)/
// max=remainPoint + confirm, 진행중/종료 경매 목록 + 상세 카드(경매명/주최자(익명)/종료일시/최대지연/
// 입찰자 목록).
//
// read:
//   D2 `GET /api/auctions/unique`(UniqueItemAuctionListResponse) {result, list[], obfuscatedName}.
//   D3 `GET /api/auctions/{id}/unique-detail`(UniqueItemAuctionDetailResponse)
//      {result, auction, bidList[], obfuscatedName, remainPoint}.
// write: api.commands.auctionBid({auctionId, amount}) — 자원/유니크 공통 typed 명령(wire 코드 기존).
//
// BLOCKED(BE, 날조 금지):
//   - obfuscatedName(D2/D3) — 세션 generalID + game_env KV hiddenSeed 미주입 → null. '내 가명'은
//     null이면 '-'(없음) 렌더.
//   - remainPoint(D3) — viewer-specific 유산 포인트 + 세션 없음 → null. '잔여' 표기는 null이면 '-'.
//     remainPoint가 null이면 입찰가 max 상한도 없다(legacy는 max=remainPoint).
//   - 아이템 툴팁(legacy getItemTooltipText): gameConstStore.iActionInfo.item — game-api const에
//     미제공이라 보류. target(아이템 키)만 title 보조로 노출(툴팁 없음).

import { useCallback, useEffect, useState } from 'react';
import { api, isIntakeQueued, isIntakeDenied } from '../../lib/api';

// ── D2/D3 와이어 타입(game-api AuctionDto.kt와 동형) ──────────────────────────────────────
interface UniqueItemAuctionBidder {
    generalName: string | null;
    amount: number;
    isCallerHighestBidder: boolean;
    date: string;
}

interface UniqueItemAuctionInfo {
    id: number;
    finished: boolean;
    title: string;
    target: string | null;
    isCallerHost: boolean;
    hostName: string;
    closeDate: string;
    remainCloseDateExtensionCnt: number | null;
    availableLatestBidCloseDate: string | null;
}

interface UniqueItemAuctionListItem extends UniqueItemAuctionInfo {
    highestBid: UniqueItemAuctionBidder;
}

interface UniqueItemAuctionList {
    result: boolean;
    list: UniqueItemAuctionListItem[];
    obfuscatedName: string | null;
}

interface UniqueItemAuctionDetail {
    result: boolean;
    auction: UniqueItemAuctionInfo;
    bidList: UniqueItemAuctionBidder[];
    obfuscatedName: string | null;
    remainPoint: number | null;
}

// legacy cutDateTime: substring(5, 16)('MM-dd HH:mm') / showSecond → substring(5, 19)('MM-dd HH:mm:ss').
function cutDateTime(dateTime: string, showSecond = false): string {
    return showSecond ? dateTime.substring(5, 19) : dateTime.substring(5, 16);
}

interface Props {
    generalId: number | null;
    onToast: (msg: string) => void;
}

export default function AuctionUniqueItem({ generalId, onToast }: Props) {
    const [ongoing, setOngoing] = useState<UniqueItemAuctionListItem[]>([]);
    const [finished, setFinished] = useState<UniqueItemAuctionListItem[]>([]);
    const [obfuscatedName, setObfuscatedName] = useState<string | null>(null);

    const [currentAuctionId, setCurrentAuctionId] = useState<number | undefined>();
    const [currentAuction, setCurrentAuction] = useState<UniqueItemAuctionDetail | undefined>();
    const [bidAmount, setBidAmount] = useState<number>(5000);

    const refreshDetail = useCallback(async (auctionId: number | undefined) => {
        if (auctionId === undefined) return;
        try {
            const detail = await api.auctionUniqueDetail<UniqueItemAuctionDetail>(auctionId);
            setCurrentAuction(detail);
            const bidList = detail.bidList;
            if (bidList.length > 0) {
                // legacy: max(bidList[0], bidList[last]) → ceil(*1.01)(amount DESC라 보통 bidList[0]).
                const highest = Math.max(bidList[0].amount, bidList[bidList.length - 1].amount);
                setBidAmount(Math.ceil(highest * 1.01));
            }
        } catch (e) {
            console.error(e);
            onToast('경매 상세를 불러올 수 없습니다.');
        }
    }, [onToast]);

    const refreshList = useCallback(async () => {
        try {
            const result = await api.auctionsUnique<UniqueItemAuctionList>();
            setObfuscatedName(result.obfuscatedName);
            setFinished(result.list.filter(a => a.finished));
            const ong = result.list.filter(a => !a.finished);
            setOngoing(ong);
            // 진행중 첫 경매를 자동 선택(legacy: currentAuctionID === undefined && size > 0).
            setCurrentAuctionId(prev => {
                if (prev === undefined && ong.length > 0) {
                    const first = ong[0];
                    setBidAmount(b => Math.max(b, Math.ceil(first.highestBid.amount * 1.01)));
                    return first.id;
                }
                return prev;
            });
        } catch (e) {
            console.error(e);
            onToast('유니크 경매 목록을 불러올 수 없습니다.');
        }
    }, [onToast]);

    useEffect(() => {
        void refreshList();
    }, [refreshList]);

    useEffect(() => {
        void refreshDetail(currentAuctionId);
    }, [currentAuctionId, refreshDetail]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => {
            void refreshList();
            void refreshDetail(currentAuctionId);
        });
        es.onerror = () => es.close();
        return () => es.close();
    }, [refreshList, refreshDetail, currentAuctionId]);

    async function bidAuction() {
        if (currentAuction === undefined) return;
        if (generalId == null) {
            onToast('장수가 없어 입찰할 수 없습니다.');
            return;
        }
        const amount = bidAmount;
        const auctionInfo = currentAuction.auction;
        // legacy confirm 다이얼로그 verbatim.
        if (!window.confirm(`${auctionInfo.title}에 ${amount}유산포인트를 입찰하시겠습니까?`)) return;
        try {
            const res = await api.commands.auctionBid({ auctionId: auctionInfo.id, amount }, generalId);
            if (isIntakeQueued(res)) {
                onToast('입찰이 완료되었습니다.');
                await refreshList();
                await refreshDetail(currentAuctionId);
            } else if (isIntakeDenied(res)) {
                onToast(res.reason ?? '입찰할 수 없습니다.');
            }
        } catch (e) {
            console.error(e);
            onToast('입찰에 실패했습니다.');
        }
    }

    // 입찰 최소가 = ceil(현재 최고 입찰 * 1.01)(legacy: bidList[0].amount * 1.01).
    const minBid = currentAuction && currentAuction.bidList.length > 0
        ? Math.ceil(currentAuction.bidList[0].amount * 1.01)
        : undefined;

    function listSection(title: string, rows: UniqueItemAuctionListItem[], clickable: boolean) {
        return (
            <>
                <div className="bg1">{title}</div>
                <div className="uniqueListHeader">
                    <div>번호</div>
                    <div>경매명</div>
                    <div>주최자</div>
                    <div>종료일시</div>
                    <div>연장</div>
                    <div>1순위</div>
                    <div className="text-end">포인트</div>
                </div>
                {rows.map(a => (
                    <div
                        key={a.id}
                        className={clickable ? 'uniqueListRow clickableRow' : 'uniqueListRow'}
                        onClick={clickable ? () => setCurrentAuctionId(a.id) : undefined}
                    >
                        <div>{a.id}</div>
                        <div title={a.target ?? undefined}>{a.title}</div>
                        <div className={a.isCallerHost ? 'isMe' : undefined}>{a.hostName}</div>
                        <div className="f_tnum">{cutDateTime(a.closeDate)}</div>
                        <div>{(a.remainCloseDateExtensionCnt ?? 0) > 0 ? '남음' : '소진'}</div>
                        <div className={a.highestBid.isCallerHighestBidder ? 'isMe' : undefined}>
                            {a.highestBid.generalName ?? '-'}
                        </div>
                        <div className="text-end f_tnum">{a.highestBid.amount.toLocaleString()}</div>
                    </div>
                ))}
            </>
        );
    }

    return (
        <div className="bg0">
            <div>
                내 가명: <span className="isMe">{obfuscatedName ?? '-'}</span>
            </div>

            {currentAuction !== undefined && (
                <>
                    <div className="bg2">경매 {currentAuction.auction.id}번 상세</div>
                    <div className="detailGrid">
                        <div className="bg1">경매명</div>
                        <div title={currentAuction.auction.target ?? undefined}>{currentAuction.auction.title}</div>
                        <div className="bg1">주최자(익명)</div>
                        <div className={currentAuction.auction.isCallerHost ? 'isMe' : undefined}>
                            {currentAuction.auction.hostName}
                        </div>
                        <div className="bg1">종료일시</div>
                        <div className="f_tnum">{cutDateTime(currentAuction.auction.closeDate, true)}</div>
                        <div className="bg1">최대지연</div>
                        <div className="f_tnum">
                            {currentAuction.auction.availableLatestBidCloseDate
                                ? cutDateTime(currentAuction.auction.availableLatestBidCloseDate, true)
                                : '-'}
                        </div>
                    </div>

                    <div className="bg1">입찰자 목록</div>
                    <div className="bidListHeader">
                        <div>입찰자</div>
                        <div className="text-end">입찰포인트</div>
                        <div>시각</div>
                    </div>
                    {currentAuction.bidList.map(bidder => (
                        <div key={bidder.amount} className="bidListRow">
                            <div className={bidder.isCallerHighestBidder ? 'isMe' : undefined}>
                                {bidder.generalName ?? '-'}
                            </div>
                            <div className="text-end f_tnum">{bidder.amount.toLocaleString()}</div>
                            <div className="f_tnum">{cutDateTime(bidder.date)}</div>
                        </div>
                    ))}

                    <div className="bg1">입찰하기</div>
                    <div className="bidInputRow">
                        <label>
                            유산포인트 (잔여: {currentAuction.remainPoint != null
                                ? `${currentAuction.remainPoint.toLocaleString()}포인트`
                                : '-'})
                        </label>
                        <input
                            type="number"
                            value={bidAmount}
                            min={minBid}
                            max={currentAuction.remainPoint ?? undefined}
                            step={1}
                            onChange={e => setBidAmount(Math.trunc(Number(e.target.value)))}
                        />
                        <button onClick={() => void bidAuction()}>입찰</button>
                    </div>
                </>
            )}

            {listSection('진행중인 경매 목록', ongoing, true)}
            {listSection('종료된 경매 목록', finished, true)}

            <style jsx>{`
                .bg1 { font-weight: 600; color: var(--text-secondary); padding: var(--space-xs) var(--space-sm); }
                .isMe { font-weight: bold; color: aquamarine; }
                .clickableRow { cursor: pointer; }
                .clickableRow:hover { background-color: rgba(255, 255, 255, 0.15); }
                .text-end { text-align: end; }
                .detailGrid {
                    display: grid;
                    grid-template-columns: 1fr 2fr 1fr 2fr;
                    text-align: center;
                }
                .detailGrid > div { align-self: center; padding: var(--space-xs); }
                .uniqueListHeader, .uniqueListRow {
                    display: grid;
                    grid-template-columns: 1fr 4fr 1fr 2fr 1fr 1fr 2fr;
                    text-align: center;
                    border-bottom: solid 1px var(--border-subtle);
                }
                .uniqueListHeader { font-weight: 600; color: var(--text-secondary); }
                .uniqueListHeader > div, .uniqueListRow > div { align-self: center; }
                .bidListHeader, .bidListRow {
                    display: grid;
                    grid-template-columns: 3fr 2fr 3fr;
                    text-align: center;
                    border-bottom: solid 1px var(--border-subtle);
                }
                .bidInputRow {
                    display: flex;
                    gap: var(--space-sm);
                    align-items: center;
                    padding: var(--space-sm) 0;
                }
                .bidInputRow input { width: 10rem; }
            `}</style>
        </div>
    );
}
