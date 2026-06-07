'use client';

// AuctionResource — legacy hwe/ts/components/AuctionResource.vue 충실 포팅(FE grand truth, PHP가 이김).
// 거래장(금/쌀 자원 경매): 쌀 구매 / 쌀 판매 2섹션 8컬럼 테이블 + 인라인 입찰(min=시작가/max=마감가/
// step=10) + 경매 등록 폼(매물타입·수량·기간턴·시작가·마감가) + 이전 경매(최근 20건) 로그.
//
// read: game-api `GET /api/auctions`(D1 AuctionResourceListResponse) — envelope
//   {result, buyRice[], sellRice[], recentLogs[], generalID}. recentLogs는 BE BLOCKED(원천 미포팅)
//   → 항상 빈 배열로 와서 '이전 경매(최근 20건)' 섹션은 비어 렌더(날조 금지).
// write: api.commands.auctionBid / auctionOpenBuyRice / auctionOpenSellRice (wire 코드 기존).

import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { formatLog } from '../../lib/utilGame';

// ── D1 와이어 타입(game-api AuctionDto.kt와 동형) ──────────────────────────────────────────
interface BasicResourceAuctionBidder {
    amount: number;
    date: string;
    generalID: number;
    generalName: string | null;
}

interface BasicResourceAuctionInfo {
    id: number;
    type: 'buyRice' | 'sellRice';
    hostGeneralId: number;
    hostName: string;
    openDate: string;
    closeDate: string;
    amount: number;
    startBidAmount: number;
    finishBidAmount: number | null;
    highestBid?: BasicResourceAuctionBidder | null;
}

interface ActiveResourceAuctionList {
    result: boolean;
    buyRice: BasicResourceAuctionInfo[];
    sellRice: BasicResourceAuctionInfo[];
    recentLogs: string[];
    generalID: number;
}

interface OpenAuctionInfo {
    type: 'buyRice' | 'sellRice';
    amount: number;
    startBidAmount: number;
    finishBidAmount: number;
    closeTurnCnt: number;
}

// legacy cutDateTime: dateTime.substring(5, 16)('MM-dd HH:mm') / showSecond → substring(5, 19).
function cutDateTime(dateTime: string, showSecond = false): string {
    return showSecond ? dateTime.substring(5, 19) : dateTime.substring(5, 16);
}

interface Props {
    generalId: number | null;
    onToast: (msg: string) => void;
}

export default function AuctionResource({ generalId, onToast }: Props) {
    const [buyRice, setBuyRice] = useState<BasicResourceAuctionInfo[]>([]);
    const [sellRice, setSellRice] = useState<BasicResourceAuctionInfo[]>([]);
    const [recentLogs, setRecentLogs] = useState<string[]>([]);

    // 선택된 경매 + 입찰 입력값(legacy: selected → bidAmount = highestBid?.amount ?? startBidAmount).
    const [selectedBuyRice, setSelectedBuyRice] = useState<BasicResourceAuctionInfo | undefined>();
    const [bidAmountBuyRice, setBidAmountBuyRice] = useState<number>(0);
    const [selectedSellRice, setSelectedSellRice] = useState<BasicResourceAuctionInfo | undefined>();
    const [bidAmountSellRice, setBidAmountSellRice] = useState<number>(0);

    // 경매 등록 폼(legacy 기본값 verbatim).
    const [openAuctionInfo, setOpenAuctionInfo] = useState<OpenAuctionInfo>({
        type: 'buyRice',
        amount: 1000,
        startBidAmount: 500,
        finishBidAmount: 2000,
        closeTurnCnt: 24,
    });

    const refresh = useCallback(async () => {
        try {
            const result = await api.auctions<ActiveResourceAuctionList>();
            setBuyRice(result.buyRice);
            setSellRice(result.sellRice);
            setRecentLogs(result.recentLogs);
        } catch (e) {
            console.error(e);
            onToast('거래장 목록을 불러올 수 없습니다.');
        }
    }, [onToast]);

    useEffect(() => {
        void refresh();
    }, [refresh]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => { void refresh(); });
        es.onerror = () => es.close();
        return () => es.close();
    }, [refresh]);

    // 선택 시 입찰값 프리필(legacy watch).
    function selectBuyRice(a: BasicResourceAuctionInfo) {
        setSelectedBuyRice(a);
        setBidAmountBuyRice(a.highestBid ? a.highestBid.amount : a.startBidAmount);
    }
    function selectSellRice(a: BasicResourceAuctionInfo) {
        setSelectedSellRice(a);
        setBidAmountSellRice(a.highestBid ? a.highestBid.amount : a.startBidAmount);
    }

    async function bidAuction(a: BasicResourceAuctionInfo | undefined, amount: number) {
        if (a === undefined) return;
        if (generalId == null) {
            onToast('장수가 없어 입찰할 수 없습니다.');
            return;
        }
        try {
            await api.commands.auctionBid({ auctionId: a.id, amount }, generalId);
            onToast('입찰했습니다.');
            await refresh();
        } catch (e) {
            console.error(e);
            onToast('입찰에 실패했습니다.');
        }
    }

    async function openAuction() {
        if (generalId == null) {
            onToast('장수가 없어 등록할 수 없습니다.');
            return;
        }
        const { type, amount, startBidAmount, finishBidAmount, closeTurnCnt } = openAuctionInfo;
        try {
            const args = { amount, startBidAmount, finishBidAmount, closeTurnCnt };
            if (type === 'buyRice') {
                await api.commands.auctionOpenBuyRice<{ auctionID?: number }>(args, generalId);
            } else {
                await api.commands.auctionOpenSellRice<{ auctionID?: number }>(args, generalId);
            }
            onToast('경매로 등록되었습니다.');
            await refresh();
        } catch (e) {
            console.error(e);
            onToast('경매 등록에 실패했습니다.');
        }
    }

    // 8컬럼 헤더(legacy: 번호/판매자/수량/입찰자/입찰가/단가/마감가/거래 종료).
    const header = (
        <div className="auctionItem auctionHeader">
            <div>번호</div>
            <div>판매자</div>
            <div>수량</div>
            <div>입찰자</div>
            <div>입찰가</div>
            <div>단가</div>
            <div>마감가</div>
            <div>거래 종료</div>
        </div>
    );

    // section: 쌀 구매(buyRice)는 수량='쌀 N'·입찰가='금 N'·마감가='금 N';
    //          쌀 판매(sellRice)는 수량='금N'·입찰가='쌀 N'·마감가='쌀 N'(legacy 접두사 verbatim).
    function renderRow(
        a: BasicResourceAuctionInfo,
        amountPrefix: string,
        bidPrefix: string,
        onSelect: () => void,
    ) {
        return (
            <div key={a.id} className="auctionItem auctionRow" onClick={onSelect}>
                <div className="f_tnum">{a.id}</div>
                <div>{a.hostName}</div>
                <div className="f_tnum">{amountPrefix} {a.amount.toLocaleString()}</div>
                <div>{a.highestBid?.generalName ?? '-'}</div>
                <div className={a.highestBid ? 'f_tnum' : 'f_tnum noBid'}>
                    {bidPrefix} {(a.highestBid?.amount ?? a.startBidAmount).toLocaleString()}
                </div>
                <div className="f_tnum">
                    {a.highestBid ? (a.highestBid.amount / a.amount).toFixed(2) : '-'}
                </div>
                <div className="f_tnum">{bidPrefix} {(a.finishBidAmount ?? 0).toLocaleString()}</div>
                <div className="f_tnum">{cutDateTime(a.closeDate)}</div>
            </div>
        );
    }

    return (
        <div className="bg0">
            <div className="bg2">거래장</div>

            {/* ── 쌀 구매 ── */}
            <div style={{ backgroundColor: 'orange', color: '#000', padding: 'var(--space-xs) var(--space-sm)' }}>쌀 구매</div>
            {header}
            {buyRice.map(a => renderRow(a, '쌀', '금', () => selectBuyRice(a)))}
            {selectedBuyRice !== undefined && (
                <div className="bidInputRow">
                    <span className="f_tnum">
                        {selectedBuyRice.id}번 쌀 {selectedBuyRice.amount} 경매에 금
                    </span>
                    <input
                        type="number"
                        value={bidAmountBuyRice}
                        min={selectedBuyRice.startBidAmount}
                        max={selectedBuyRice.finishBidAmount ?? undefined}
                        step={10}
                        onChange={e => setBidAmountBuyRice(Math.trunc(Number(e.target.value)))}
                    />
                    <button onClick={() => void bidAuction(selectedBuyRice, bidAmountBuyRice)}>입찰</button>
                </div>
            )}

            {/* ── 쌀 판매 ── */}
            <div style={{ backgroundColor: 'skyblue', color: '#000', padding: 'var(--space-xs) var(--space-sm)' }}>쌀 판매</div>
            {header}
            {sellRice.map(a => renderRow(a, '금', '쌀', () => selectSellRice(a)))}
            {selectedSellRice !== undefined && (
                <div className="bidInputRow">
                    <span className="f_tnum">
                        {selectedSellRice.id}번 금 {selectedSellRice.amount} 경매에 쌀
                    </span>
                    <input
                        type="number"
                        value={bidAmountSellRice}
                        min={selectedSellRice.startBidAmount}
                        max={selectedSellRice.finishBidAmount ?? undefined}
                        step={10}
                        onChange={e => setBidAmountSellRice(Math.trunc(Number(e.target.value)))}
                    />
                    <button onClick={() => void bidAuction(selectedSellRice, bidAmountSellRice)}>입찰</button>
                </div>
            )}

            {/* ── 경매 등록 ── */}
            <div style={{ marginTop: 'var(--space-md)', fontWeight: 600 }}>경매 등록</div>
            <div className="openAuctionForm">
                <div>
                    매물
                    <div style={{ display: 'flex' }}>
                        <button
                            aria-pressed={openAuctionInfo.type === 'buyRice'}
                            style={{ fontWeight: openAuctionInfo.type === 'buyRice' ? 700 : 400 }}
                            onClick={() => setOpenAuctionInfo(p => ({ ...p, type: 'buyRice' }))}
                        >쌀</button>
                        <button
                            aria-pressed={openAuctionInfo.type === 'sellRice'}
                            style={{ fontWeight: openAuctionInfo.type === 'sellRice' ? 700 : 400 }}
                            onClick={() => setOpenAuctionInfo(p => ({ ...p, type: 'sellRice' }))}
                        >금</button>
                    </div>
                </div>
                <div>
                    수량 ({openAuctionInfo.type === 'buyRice' ? '쌀' : '금'})
                    <input
                        type="number" value={openAuctionInfo.amount} min={100} max={10000} step={10}
                        onChange={e => setOpenAuctionInfo(p => ({ ...p, amount: Math.trunc(Number(e.target.value)) }))}
                    />
                </div>
                <div>
                    기간(턴)
                    <input
                        type="number" value={openAuctionInfo.closeTurnCnt} min={3} max={24} step={1}
                        onChange={e => setOpenAuctionInfo(p => ({ ...p, closeTurnCnt: Math.trunc(Number(e.target.value)) }))}
                    />
                </div>
                <div>
                    시작가 ({openAuctionInfo.type === 'buyRice' ? '금' : '쌀'})
                    <input
                        type="number" value={openAuctionInfo.startBidAmount} min={100} max={10000} step={10}
                        onChange={e => setOpenAuctionInfo(p => ({ ...p, startBidAmount: Math.trunc(Number(e.target.value)) }))}
                    />
                </div>
                <div>
                    마감가 ({openAuctionInfo.type === 'buyRice' ? '금' : '쌀'})
                    <input
                        type="number" value={openAuctionInfo.finishBidAmount} min={100} max={10000} step={10}
                        onChange={e => setOpenAuctionInfo(p => ({ ...p, finishBidAmount: Math.trunc(Number(e.target.value)) }))}
                    />
                </div>
                <div style={{ display: 'flex', alignItems: 'flex-end' }}>
                    <button onClick={() => void openAuction()}>등록</button>
                </div>
            </div>

            {/* ── 이전 경매(최근 20건) — BE BLOCKED(recentLogs 원천 미포팅) → 빈 목록 ── */}
            <div style={{ marginTop: 'var(--space-md)', fontWeight: 600 }}>이전 경매(최근 20건)</div>
            {recentLogs.map((log, idx) => (
                // formatLog: 색/태그 마크업 → HTML(legacy v-html 패턴 동일).
                <div key={idx} dangerouslySetInnerHTML={{ __html: formatLog(log) }} />
            ))}

            <style jsx>{`
                .auctionItem {
                    display: grid;
                    grid-template-columns: 1fr 2fr 2fr 2fr 2fr 1fr 3fr 2fr;
                    text-align: center;
                    border-bottom: solid gray 1px;
                }
                .auctionItem > div { align-self: center; }
                .auctionHeader { font-weight: 600; color: var(--text-secondary); }
                .auctionRow { cursor: pointer; }
                .auctionRow:hover { background-color: rgba(255, 255, 255, 0.08); }
                .noBid { color: #ccc; }
                .bidInputRow {
                    display: flex;
                    gap: var(--space-sm);
                    align-items: center;
                    justify-content: flex-end;
                    padding: var(--space-sm) 0;
                }
                .bidInputRow input { width: 8rem; }
                .openAuctionForm {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
                    gap: var(--space-sm);
                    align-items: start;
                }
                .openAuctionForm input { width: 100%; }
            `}</style>
        </div>
    );
}
