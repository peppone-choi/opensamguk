'use client';

import { useState, useEffect, useMemo } from 'react';
import { useRouter } from 'next/navigation';
import Shell from '../../../components/Shell';
import { api } from '../../../lib/api';
import type { JoinFormResponse } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { resolveServerGamePath, useServerId } from '../../../lib/serverGameUrl';
import { JOIN_STAT_TOTAL, JOIN_STAT_MIN, JOIN_STAT_MAX, BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import { onPortraitError, portraitUrl } from '../../../lib/portrait';
import type { MapPreviewResponse } from '../../../lib/types';

// 능력치 상수 — 레거시 GameConst(d_setting)·BE common GameConst.kt와 동일값.
//   defaultStatTotal=275 / defaultStatMin=15 / defaultStatMax=80.
// 정본 lib/constants.ts의 JOIN_STAT_* 로 통일(종전 로컬 하드코딩 제거).
const DEFAULT_STAT_TOTAL = JOIN_STAT_TOTAL;
const STAT_MIN = JOIN_STAT_MIN;
const STAT_MAX = JOIN_STAT_MAX;

// 가입 보너스 능력치 범위 — 레거시 GameConst.bornMinStatBonus/bornMaxStatBonus(common GameConst.kt:177-178).
// game-api /api/const GameConstResponse(FE 타입)에는 노출돼 있지 않아 BE 상수와 동일값으로 보존한다(폼 안내문 전용).
const BORN_MIN_STAT_BONUS = 3;
const BORN_MAX_STAT_BONUS = 5;

// 성격 코드 → {이름, 설명} — 레거시 ActionPersonality/*.php의 protected $name/$info를 byte-faithful 전사
// (common GameConst.kt personalityName + 각 클래스 $info). game-api /api/const는 personality name/info를
// BLOCKED(null)로 비우므로(GetConstController 주석: getName/getInfo 동적 인스턴스화 BLOCKED) 여기서
// 표시용으로 하드코딩한다 — 폼 편의(RNG draw 게이트 밖). 'Random'은 레거시 PageJoin의 합성 항목.
const PERSONALITY_INFO: Record<string, { name: string; info: string }> = {
  Random: { name: '???', info: '무작위 성격을 선택합니다.' },
  che_안전: { name: '안전', info: '사기 -5, 징·모병 비용 -20%' },
  che_유지: { name: '유지', info: '훈련 -5, 징·모병 비용 -20%' },
  che_재간: { name: '재간', info: '명성 -10%, 징·모병 비용 -20%' },
  che_출세: { name: '출세', info: '명성 +10%, 징·모병 비용 +20%' },
  che_할거: { name: '할거', info: '명성 -10%, 훈련 +5' },
  che_정복: { name: '정복', info: '명성 -10%, 사기 +5' },
  che_패권: { name: '패권', info: '훈련 +5, 징·모병 비용 +20%' },
  che_의협: { name: '의협', info: '사기 +5, 징·모병 비용 +20%' },
  che_대의: { name: '대의', info: '명성 +10%, 훈련 -5' },
  che_왕좌: { name: '왕좌', info: '명성 +10%, 사기 -5' },
};

// 성격 셀렉트 순서 — 레거시 GameConst.availablePersonality + 합성 'Random'(PageJoin은 Random을 끝에 추가).
const PERSONALITIES = [
  'che_안전', 'che_유지', 'che_재간', 'che_출세', 'che_할거', 'che_정복',
  'che_패권', 'che_의협', 'che_대의', 'che_왕좌', 'Random',
];

// isBrightColor — 레거시 util: perceived-luminance(r*.299 + g*.587 + b*.114) > 임계값 → 검은 글자.
// 국가색 배경 위 텍스트 가독성 결정. vote/diplomacy 페이지와 동일 로직.
function isBrightColor(color: string): boolean {
  const c = color.replace('#', '');
  if (c.length !== 6) return false;
  const r = parseInt(c.slice(0, 2), 16);
  const g = parseInt(c.slice(2, 4), 16);
  const b = parseInt(c.slice(4, 6), 16);
  return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD;
}

// 능력치 분배식 — 레거시 hwe/ts/util/generalStats.ts를 그대로 포팅(통/무/지 순).
// PHP는 패러티 오라클이 아니다(폼 편의 기능, RNG draw 게이트 밖) → Vue 정본을 충실 이식.
type Stats = { min: number; max: number; total: number };

// abilityRand: 각 스탯 = random*65+10 → 비율 정규화 → floor → 부족분은 통솔에 가산 → 범위 벗어나면 재추첨.
function abilityRand(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 65 + 10;
  let strength = Math.random() * 65 + 10;
  let intel = Math.random() * 65 + 10;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    leadership += 1;
  }

  if (
    leadership > stats.max || strength > stats.max || intel > stats.max ||
    leadership < stats.min || strength < stats.min || intel < stats.min
  ) {
    return abilityRand(stats);
  }

  return [leadership, strength, intel];
}

// abilityLeadpow(통솔무력형): 통6:무6:지1 가중 → 부족분은 무력 가산 → min/max 클램프 캐스케이드.
function abilityLeadpow(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 6;
  let strength = Math.random() * 6;
  let intel = Math.random() * 1;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    strength += 1;
  }

  if (intel < stats.min) {
    leadership -= stats.min - intel;
    intel = stats.min;
  }
  if (leadership > stats.max) {
    strength += leadership - stats.max;
    leadership = stats.max;
  }
  if (strength > stats.max) {
    leadership += strength - stats.max;
    strength = stats.max;
  }
  if (leadership > stats.max) {
    intel += leadership - stats.max;
    leadership = stats.max;
  }

  return [leadership, strength, intel];
}

// abilityLeadint(통솔지력형): 통6:무1:지6 가중 → 부족분은 지력 가산 → min/max 클램프 캐스케이드.
function abilityLeadint(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 6;
  let strength = Math.random() * 1;
  let intel = Math.random() * 6;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    intel += 1;
  }

  if (strength < stats.min) {
    leadership -= stats.min - strength;
    strength = stats.min;
  }
  if (leadership > stats.max) {
    intel += leadership - stats.max;
    leadership = stats.max;
  }
  if (intel > stats.max) {
    leadership += intel - stats.max;
    intel = stats.max;
  }
  if (leadership > stats.max) {
    strength += leadership - stats.max;
    leadership = stats.max;
  }

  return [leadership, strength, intel];
}

// abilityPowint(무력지력형): 통1:무6:지6 가중 → 부족분은 지력 가산 → min/max 클램프 캐스케이드.
function abilityPowint(stats: Stats): [number, number, number] {
  let leadership = Math.random() * 1;
  let strength = Math.random() * 6;
  let intel = Math.random() * 6;
  const rate = leadership + strength + intel;

  leadership = Math.floor((leadership / rate) * stats.total);
  strength = Math.floor((strength / rate) * stats.total);
  intel = Math.floor((intel / rate) * stats.total);

  while (leadership + strength + intel < stats.total) {
    intel += 1;
  }

  if (leadership < stats.min) {
    strength -= stats.min - leadership;
    leadership = stats.min;
  }
  if (strength > stats.max) {
    intel += strength - stats.max;
    strength = stats.max;
  }
  if (intel > stats.max) {
    strength += intel - stats.max;
    intel = stats.max;
  }
  if (strength > stats.max) {
    leadership += strength - stats.max;
    strength = stats.max;
  }

  return [leadership, strength, intel];
}

function abilityRandAll(stats: Stats): [number, number, number, number, number] {
  const count = 5;
  const remainingTotal = stats.total - stats.min * count;

  while (true) {
    const weights = Array.from({ length: count }, () => Math.random() * 65 + 10);
    const weightTotal = weights.reduce((sum, weight) => sum + weight, 0);
    const values = weights.map((weight) => stats.min + Math.floor((weight / weightTotal) * remainingTotal));

    const remainder = stats.total - values.reduce((sum, value) => sum + value, 0);
    for (let i = 0; i < remainder; i += 1) {
      values[i % count] += 1;
    }

    if (values.every((value) => value <= stats.max && value >= stats.min)) {
      return values as [number, number, number, number, number];
    }
  }
}

// 기본 능력치 분배 — 레거시 PageJoin: 통=total-2*floor(total/3), 무=floor(total/3), 지=floor(total/3) (165→55/55/55).
const DEFAULT_STAT = Math.floor(DEFAULT_STAT_TOTAL / 5);
const LEGACY_THREE_STAT_TOTAL = DEFAULT_STAT_TOTAL - DEFAULT_STAT * 2;
const DEFAULT_LEADERSHIP = LEGACY_THREE_STAT_TOTAL - 2 * Math.floor(LEGACY_THREE_STAT_TOTAL / 3);
const DEFAULT_OTHER = Math.floor(LEGACY_THREE_STAT_TOTAL / 3);

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export default function JoinPage() {
  const router = useRouter();
  const serverId = useServerId();
  const { frontInfo, loading: frontInfoLoading } = useFrontInfo();
  const selectedServerId = serverId ?? frontInfo?.global.serverId;
  const homeHref = selectedServerId ? resolveServerGamePath(undefined, selectedServerId, '/game', '') : '/game';
  const [name, setName] = useState('');
  const [leadership, setLeadership] = useState(DEFAULT_LEADERSHIP);
  const [strength, setStrength] = useState(DEFAULT_OTHER);
  const [intel, setIntel] = useState(DEFAULT_OTHER);
  const [politics, setPolitics] = useState(DEFAULT_STAT);
  const [charm, setCharm] = useState(DEFAULT_STAT);
  const [character, setCharacter] = useState('Random');
  const [pic, setPic] = useState(true); // 전콘 사용 — 레거시 args.pic 기본 true
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [joinStatus, setJoinStatus] = useState('');
  const [displayInherit, setDisplayInherit] = useState(false);
  const [joinForm, setJoinForm] = useState<JoinFormResponse | null>(null);
  const [inheritSpecial, setInheritSpecial] = useState<string | undefined>();
  const [inheritTurntimeZone, setInheritTurntimeZone] = useState<number | undefined>();
  const [inheritCity, setInheritCity] = useState<number | undefined>();
  const [inheritBonusStat, setInheritBonusStat] = useState<[number, number, number]>([0, 0, 0]);

  // 국가 목록(임관권유문 표시 전용 — 입장 안 함, 재야로 시작). 레거시 v_join.php nationList + scout_msg.
  const [nations, setNations] = useState<MapPreviewResponse['nations']>([]);
  const [displayTable, setDisplayTable] = useState(true); // 국가표 보이기/숨기기 토글
  const [toggleZoom, setToggleZoom] = useState(true);     // 임관권유문 크게/작게 토글

  const total = leadership + strength + intel + politics + charm;
  const remaining = DEFAULT_STAT_TOTAL - total;
  const inheritBonusTotal = inheritBonusStat.reduce((sum, value) => sum + value, 0);
  const inheritRequiredPoint =
    (inheritSpecial === undefined ? 0 : (joinForm?.inheritCosts.special ?? 0)) +
    (inheritTurntimeZone === undefined ? 0 : (joinForm?.inheritCosts.turntime ?? 0)) +
    (inheritCity === undefined ? 0 : (joinForm?.inheritCosts.city ?? 0)) +
    (inheritBonusTotal === 0 ? 0 : (joinForm?.inheritCosts.stat ?? 0));

  const turnTimeZoneList = useMemo(() => {
    const zoneSeconds = joinForm?.turnTermMinutes ?? 0;
    if (zoneSeconds <= 0) return [];
    const format = (seconds: number) => {
      const minute = Math.floor(seconds / 60).toString().padStart(2, '0');
      const second = (seconds % 60).toString().padStart(2, '0');
      return `${minute}:${second}`;
    };
    return Array.from({ length: 60 }, (_, index) => {
      const start = index * zoneSeconds;
      const end = start + zoneSeconds - 1;
      return `${format(start)}.000 ~ ${format(end)}.999`;
    });
  }, [joinForm?.turnTermMinutes]);

  useEffect(() => {
    let alive = true;
    api.joinForm()
      .then((data) => {
        if (!alive) return;
        setJoinForm(data);
        setPic(data.member.canUsePicture);
      })
      .catch((cause: unknown) => {
        if (!alive) return;
        setPic(false);
        setError(cause instanceof Error ? cause.message : '장수 생성 정보를 불러오지 못했습니다.');
      });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    if (!frontInfoLoading && !loading && frontInfo?.general?.hasGeneral) {
      router.replace(homeHref);
    }
  }, [frontInfo?.general?.hasGeneral, frontInfoLoading, homeHref, loading, router]);

  // 국가 목록 로드 — game-api /api/map/preview의 nations(id/name/color)를 사용한다(레거시는 nation 테이블 직접
  // read). 임관권유문(scout_msg)은 FE에 노출하는 read 채널이 아직 없어(NationFinance에서 P0-53 BLOCKED) 표시 보류.
  useEffect(() => {
    let alive = true;
    api.mapPreview()
      .then((res) => { if (alive) setNations(res.nations ?? []); })
      .catch(() => { /* 부재 시 빈 표(graceful) — 날조 금지 */ });
    return () => { alive = false; };
  }, []);

  // 셔플된 국가 목록 — 레거시 PageJoin: shuffle(staticValues.nationList). 마운트 시 1회 고정.
  const shuffledNations = useMemo(() => {
    const arr = [...nations];
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [arr[i], arr[j]] = [arr[j], arr[i]];
    }
    return arr;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nations]);

  async function waitForCreatedGeneral(timeoutMs = 20000): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const info = await api.frontInfo();
        if (info.general?.hasGeneral) {
          return true;
        }
      } catch {
        await delay(700);
        continue;
      }
      await delay(700);
    }
    return false;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setJoinStatus('');
    if (total > DEFAULT_STAT_TOTAL) {
      setError(`능력치 합계가 ${DEFAULT_STAT_TOTAL}를 초과합니다.`);
      return;
    }
    if (leadership < STAT_MIN || leadership > STAT_MAX ||
        strength < STAT_MIN || strength > STAT_MAX ||
        intel < STAT_MIN || intel > STAT_MAX ||
        politics < STAT_MIN || politics > STAT_MAX ||
        charm < STAT_MIN || charm > STAT_MAX) {
      setError(`각 능력치는 ${STAT_MIN}~${STAT_MAX} 사이여야 합니다.`);
      return;
    }
    // 레거시 PageJoin submitForm: 설정 합이 최대치보다 적으면 진행 여부 확인.
    if (total < DEFAULT_STAT_TOTAL) {
      const ok = window.confirm(
        `설정한 능력치가 ${total}으로, 실제 최대치인 ${DEFAULT_STAT_TOTAL}보다 적습니다. 그래도 진행할까요?`,
      );
      if (!ok) return;
    }
    if (inheritBonusTotal !== 0 && (inheritBonusTotal < 3 || inheritBonusTotal > 5)) {
      setError('보너스 능력치 합은 3~5 사이여야 합니다.');
      return;
    }
    if (inheritRequiredPoint > (joinForm?.inheritTotalPoint ?? 0)) {
      setError('유산 포인트가 부족합니다. 다시 가입해주세요!');
      return;
    }
    if (inheritSpecial !== undefined && (joinForm?.geniusRemaining ?? 0) <= 0) {
      setError('이미 천재가 모두 나타났습니다. 다시 가입해주세요!');
      return;
    }
    setLoading(true);
    try {
      setJoinStatus('장수 생성 요청 중...');
      const res = await api.join({
        name: name.trim(),
        leadership,
        strength,
        intel,
        politics,
        charm,
        character,
        pic, // 전콘 사용 여부 — 레거시 Join.php 'pic' 필드
        ...(inheritSpecial === undefined ? {} : { inheritSpecial }),
        ...(inheritTurntimeZone === undefined ? {} : { inheritTurntimeZone }),
        ...(inheritCity === undefined ? {} : { inheritCity }),
        ...(inheritBonusTotal === 0 ? {} : { inheritBonusStat }),
      });
      if (res.status === 'AVAILABLE') {
        setJoinStatus('장수 생성 반영 중...');
        const ready = await waitForCreatedGeneral();
        if (!ready) {
          setError('장수 생성은 접수됐지만 아직 게임에 반영되지 않았습니다. 잠시 후 로비에서 다시 확인해주세요.');
          return;
        }
        alert('정상적으로 생성되었습니다.\n위키와 팁/강좌 게시판을 꼭 읽어보세요!');
        router.push(homeHref);
        router.refresh();
      } else {
        setError(res.reason ?? '등록할 수 없습니다.');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '서버 오류가 발생했습니다.');
    } finally {
      setJoinStatus('');
      setLoading(false);
    }
  }

  function preset(type: 'random' | 'leadpow' | 'leadint' | 'powint' | 'allRandom') {
    if (type === 'allRandom') {
      const [nextLeadership, nextStrength, nextIntel, nextPolitics, nextCharm] = abilityRandAll({
        min: STAT_MIN,
        max: STAT_MAX,
        total: DEFAULT_STAT_TOTAL,
      });
      setLeadership(nextLeadership);
      setStrength(nextStrength);
      setIntel(nextIntel);
      setPolitics(nextPolitics);
      setCharm(nextCharm);
      return;
    }
    const stats: Stats = { min: STAT_MIN, max: STAT_MAX, total: LEGACY_THREE_STAT_TOTAL };
    let next: [number, number, number];
    switch (type) {
      case 'random': next = abilityRand(stats); break;
      case 'leadpow': next = abilityLeadpow(stats); break;
      case 'leadint': next = abilityLeadint(stats); break;
      case 'powint': next = abilityPowint(stats); break;
    }
    const [l, s, i] = next;
    setLeadership(l); setStrength(s); setIntel(i);
  }

  // 다시 입력(reset) — 레거시 PageJoin.vue resetArgs: 기본 분배·기본 성격·전콘 사용으로 복귀.
  function resetArgs() {
    setName('');
    setLeadership(DEFAULT_LEADERSHIP);
    setStrength(DEFAULT_OTHER);
    setIntel(DEFAULT_OTHER);
    setPolitics(DEFAULT_STAT);
    setCharm(DEFAULT_STAT);
    setCharacter('Random');
    setPic(joinForm?.member.canUsePicture ?? false);
    setError('');
  }

  // 전콘 미리보기 — 레거시는 member.imgsvr/member.picture(계정 아이콘)로 getIconPath를 호출하나, 회원(member)
  const iconPath = portraitUrl(
    pic ? joinForm?.member.picture : null,
    pic ? joinForm?.member.imageServer : 0,
  );

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-lg)' }}>
        장수 생성
      </h1>

      {error && (
        <div style={{
          background: 'var(--color-danger-bg, #fee2e2)',
          color: 'var(--color-danger, #dc2626)',
          padding: 'var(--space-md)',
          borderRadius: 'var(--radius-md)',
          marginBottom: 'var(--space-md)',
        }}>
          {error}
        </div>
      )}

      {joinStatus && (
        <div style={{
          background: 'var(--color-surface-2, #1f2937)',
          color: 'var(--color-text)',
          padding: 'var(--space-md)',
          borderRadius: 'var(--radius-md)',
          marginBottom: 'var(--space-md)',
        }}>
          {joinStatus}
        </div>
      )}

      {/* 국가 목록 — 국가명(색상배경) + 임관권유문. 표시 전용(입장 안 함, 재야로 시작). 레거시 PageJoin nation-list. */}
      <section style={{ marginBottom: 'var(--space-lg)', border: '1px solid var(--color-border)', borderRadius: 'var(--radius-md)', overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', padding: 'var(--space-sm) var(--space-md)', background: 'var(--color-surface-2, #1f2937)', fontWeight: 600 }}>
          <span style={{ flex: 1 }}>국가 목록</span>
          <button type="button" onClick={() => setDisplayTable((v) => !v)} style={{ fontSize: 'var(--text-sm)', padding: '4px 10px' }}>
            {displayTable ? '숨기기' : '보이기'}
          </button>
          <button type="button" disabled={!displayTable} onClick={() => setToggleZoom((v) => !v)} style={{ fontSize: 'var(--text-sm)', padding: '4px 10px', opacity: displayTable ? 1 : 0.5 }}>
            {toggleZoom ? '작게 보기' : '크게 보기'}
          </button>
        </div>
        {displayTable && (
          <div>
            {shuffledNations.length === 0 ? (
              <div style={{ padding: 'var(--space-md)', color: 'var(--color-text-muted)', fontSize: 'var(--text-sm)' }}>표시할 국가가 없습니다.</div>
            ) : (
              shuffledNations.map((nation) => {
                const scoutText = nation.scoutMsg ?? nation.infoText ?? '-';
                return (
                  <div key={nation.id} style={{ display: 'grid', gridTemplateColumns: '130px 1fr', borderTop: '1px solid var(--color-border)' }}>
                    <div style={{
                      backgroundColor: nation.color,
                      color: isBrightColor(nation.color) ? '#000' : '#fff',
                      fontSize: '1.1em',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      padding: 'var(--space-sm)', textAlign: 'center',
                    }}>
                      {nation.name}
                    </div>
                    <div
                      style={{
                        padding: 'var(--space-sm) var(--space-md)',
                        fontSize: toggleZoom ? 'var(--text-base)' : 'var(--text-sm)',
                        color: 'var(--color-text-muted)',
                        alignSelf: 'center',
                      }}
                      dangerouslySetInnerHTML={{ __html: scoutText }}
                    />
                  </div>
                );
              })
            )}
          </div>
        )}
      </section>

      <form onSubmit={handleSubmit} style={{ maxWidth: 560, display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>장수명</label>
          {/* blockCustomGeneralName(block_general_create & 2)은 FE에 노출되지 않아 '무작위' 전환을 감지할 수 없다(backlog). */}
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={18}
            required
            style={{ width: '100%', padding: 'var(--space-sm)', borderRadius: 'var(--radius-md)', border: '1px solid var(--color-border)' }}
          />
        </div>

        {/* 전콘 사용 — 레거시 PageJoin.vue: 아이콘 미리보기 + 'args.pic' 체크박스(Join.php 'pic' 필드로 전송) */}
        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>전콘 사용</label>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)' }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={iconPath} alt="전콘" onError={onPortraitError} style={{ height: 64, width: 64, borderRadius: 'var(--radius-sm)', objectFit: 'cover', background: 'var(--color-surface-2, #1f2937)' }} />
            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
              <input
                type="checkbox"
                checked={pic}
                disabled={!joinForm?.member.canUsePicture}
                onChange={(e) => setPic(e.target.checked)}
              />
              사용
            </label>
          </div>
        </div>

        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>성격</label>
          <select
            value={character}
            onChange={(e) => setCharacter(e.target.value)}
            style={{ width: '100%', padding: 'var(--space-sm)', borderRadius: 'var(--radius-md)', border: '1px solid var(--color-border)' }}
          >
            {PERSONALITIES.map((p) => (
              <option key={p} value={p}>{PERSONALITY_INFO[p]?.name ?? p}</option>
            ))}
          </select>
          {/* 선택 성격 설명 — 레거시 availablePersonality[args.character].info */}
          <small style={{ display: 'block', marginTop: 'var(--space-xs)', color: 'var(--color-text-muted)' }}>
            {PERSONALITY_INFO[character]?.info ?? ''}
          </small>
        </div>

        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>
            능력치 <small style={{ color: 'var(--color-text-muted)' }}>(통/무/지/정/매)</small> &mdash; 합계 {total} / {DEFAULT_STAT_TOTAL} {remaining >= 0 ? `(남음 ${remaining})` : <span style={{ color: 'var(--color-danger)' }}>초과 {-remaining}</span>}
          </label>

          <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', flexWrap: 'wrap' }}>
            {([
              ['random', '랜덤형'],
              ['leadpow', '통솔무력형'],
              ['leadint', '통솔지력형'],
              ['powint', '무력지력형'],
              ['allRandom', '전체 랜덤형'],
            ] as const).map(([t, label]) => (
              <button key={t} type="button" onClick={() => preset(t)} style={{ fontSize: 'var(--text-sm)', padding: '4px 8px' }}>
                {label}
              </button>
            ))}
          </div>

          {[
            { label: '통솔', value: leadership, set: setLeadership },
            { label: '무력', value: strength, set: setStrength },
            { label: '지력', value: intel, set: setIntel },
            { label: '정치', value: politics, set: setPolitics },
            { label: '매력', value: charm, set: setCharm },
          ].map(({ label, value, set }) => (
            <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>
              <span style={{ width: 48, fontWeight: 500 }}>{label}</span>
              <input
                type="range"
                min={STAT_MIN}
                max={STAT_MAX}
                value={value}
                onChange={(e) => set(parseInt(e.target.value))}
                style={{ flex: 1 }}
              />
              <input
                type="number"
                min={STAT_MIN}
                max={STAT_MAX}
                value={value}
                onChange={(e) => set(Math.max(STAT_MIN, Math.min(STAT_MAX, parseInt(e.target.value) || STAT_MIN)))}
                style={{ width: 64, textAlign: 'center', padding: '4px' }}
              />
            </div>
          ))}
        </div>

        {/* 능력치 안내문 2종 — 레거시 PageJoin.vue 충실 이식 */}
        <div style={{ borderTop: '1px solid var(--color-border)', paddingTop: 'var(--space-sm)' }}>
          <p style={{ textAlign: 'center', color: 'orange', margin: 0 }}>
            모든 능력치는 ( {STAT_MIN} &lt;= 능력치 &lt;= {STAT_MAX} ) 사이로 잡으셔야 합니다.<br />그 외의 능력치는 가입되지 않습니다.
          </p>
          <p style={{ textAlign: 'center', margin: 'var(--space-sm) 0 0', color: 'var(--color-text-muted)' }}>
            능력치의 총합은 {DEFAULT_STAT_TOTAL} 입니다. 가입후 {BORN_MIN_STAT_BONUS} ~ {BORN_MAX_STAT_BONUS} 의 능력치 보너스를 받게 됩니다.<br />임의의 도시에서 재야로 시작하며 건국과 임관은 게임 내에서 실행합니다.
          </p>
        </div>

        <section style={{ borderTop: '1px solid var(--color-border)', paddingTop: 'var(--space-sm)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
            <strong style={{ flex: 1 }}>유산 포인트 사용</strong>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 'var(--text-sm)' }}>
              <input type="checkbox" checked={displayInherit} onChange={(e) => setDisplayInherit(e.target.checked)} />
              {displayInherit ? '숨기기' : '보이기'}
            </label>
          </div>
          {displayInherit && (
            <div style={{ display: 'grid', gap: 'var(--space-sm)', color: 'var(--color-text-muted)', fontSize: 'var(--text-sm)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-sm)' }}>
                <label>
                  <span style={{ display: 'block', marginBottom: 4 }}>보유한 유산 포인트</span>
                  <input type="text" value={joinForm?.inheritTotalPoint ?? 0} readOnly style={{ width: '100%', padding: 'var(--space-sm)' }} />
                </label>
                <label>
                  <span style={{ display: 'block', marginBottom: 4 }}>필요 유산 포인트</span>
                  <input type="text" value={inheritRequiredPoint} readOnly style={{ width: '100%', padding: 'var(--space-sm)' }} />
                </label>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 'var(--space-sm)' }}>
                <label>
                  <span style={{ display: 'block', marginBottom: 4 }}>천재로 생성</span>
                  <select
                    value={inheritSpecial ?? ''}
                    disabled={joinForm === null || joinForm.geniusRemaining <= 0}
                    onChange={(e) => setInheritSpecial(e.target.value || undefined)}
                    style={{ width: '100%', padding: 'var(--space-sm)' }}
                  >
                    <option value="">사용안함</option>
                    {Object.entries(joinForm?.availableSpecialWar ?? {}).map(([key, special]) => (
                      <option key={key} value={key}>{special.title}</option>
                    ))}
                  </select>
                  {inheritSpecial !== undefined && (
                    <small dangerouslySetInnerHTML={{ __html: joinForm?.availableSpecialWar[inheritSpecial]?.info ?? '' }} />
                  )}
                </label>
                <label>
                  <span style={{ display: 'block', marginBottom: 4 }}>도시</span>
                  <select
                    value={inheritCity ?? ''}
                    disabled={joinForm === null}
                    onChange={(e) => setInheritCity(e.target.value === '' ? undefined : Number(e.target.value))}
                    style={{ width: '100%', padding: 'var(--space-sm)' }}
                  >
                    <option value="">사용안함</option>
                    {(joinForm?.cities ?? []).map((city) => (
                      <option key={city.id} value={city.id}>{`[${city.region}] ${city.name}`}</option>
                    ))}
                  </select>
                </label>
                <label>
                  <span style={{ display: 'block', marginBottom: 4 }}>턴 시간 지정</span>
                  <select
                    value={inheritTurntimeZone ?? ''}
                    disabled={joinForm === null}
                    onChange={(e) => setInheritTurntimeZone(e.target.value === '' ? undefined : Number(e.target.value))}
                    style={{ width: '100%', padding: 'var(--space-sm)' }}
                  >
                    <option value="">사용안함</option>
                    {turnTimeZoneList.map((zone, index) => (
                      <option key={zone} value={index}>{zone}</option>
                    ))}
                  </select>
                </label>
                <fieldset disabled={joinForm === null} style={{ border: '1px solid var(--color-border)', padding: 'var(--space-sm)' }}>
                  <legend>추가 능력치 고정(통/무/지)</legend>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--space-xs)' }}>
                    {(['통솔', '무력', '지력'] as const).map((statName, index) => (
                      <input
                        key={statName}
                        aria-label={`추가 능력치 ${statName}`}
                        type="number"
                        min={0}
                        max={BORN_MAX_STAT_BONUS}
                        value={inheritBonusStat[index]}
                        onChange={(e) => {
                          const value = Math.max(0, Math.min(BORN_MAX_STAT_BONUS, Number(e.target.value) || 0));
                          setInheritBonusStat((current) => {
                            if (index === 0) return [value, current[1], current[2]];
                            if (index === 1) return [current[0], value, current[2]];
                            return [current[0], current[1], value];
                          });
                        }}
                      />
                    ))}
                  </div>
                </fieldset>
              </div>
            </div>
          )}
        </section>

        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginTop: 'var(--space-xs)' }}>
          <button
            type="submit"
            disabled={loading || total > DEFAULT_STAT_TOTAL}
            style={{
              flex: 1,
              padding: 'var(--space-md)',
              borderRadius: 'var(--radius-md)',
              background: 'var(--color-primary)',
              color: '#fff',
              fontWeight: 700,
              opacity: loading || total > DEFAULT_STAT_TOTAL ? 0.6 : 1,
              cursor: loading || total > DEFAULT_STAT_TOTAL ? 'not-allowed' : 'pointer',
            }}
          >
            {loading ? '생성 중...' : '장수 생성'}
          </button>
          <button
            type="button"
            onClick={resetArgs}
            disabled={loading}
            style={{
              padding: 'var(--space-md) var(--space-lg)',
              borderRadius: 'var(--radius-md)',
              background: 'var(--color-surface-2, #374151)',
              color: 'var(--color-text)',
              fontWeight: 600,
              cursor: loading ? 'not-allowed' : 'pointer',
            }}
          >
            다시 입력
          </button>
        </div>
      </form>
    </Shell>
  );
}
