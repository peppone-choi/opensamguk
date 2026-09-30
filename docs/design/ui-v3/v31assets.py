# 캔버스 v3.1(전 페이지 설계) 그림 — 아티팩트 「오픈삼국 설계 승인 묶음 1 개정 (09-30)」(KCFDJTVgSGFa9N4qzrQ6By) 저장소의 /_blob 참조.
# 09-18 캔버스(YU3AkStNuos4kXdMW5RJC2)의 blob id(ui.py 의 PT·ART·FIELD)는 그 캔버스에서만 풀린다. v3.1 보드는 이 파일의 id 를 쓴다.
# 지도 그림은 와룡전 원본 타일 렌더(2026-09-30 사용자 결정: 원본 그대로, owner-accepted). 원본 그림은 이 저장소에 커밋하지 않는다.

PT = {  # 초상(09-18 캔버스에서 복사)
    'jojo': '/_blob/5be91f2202b749bc87ae84efb5c05782', 'hahoudon': '/_blob/03d6cfe670ed2785cf3bbc28d22e3248',
    'sunuk': '/_blob/fc1ddfcfcca08452f70b347ad51fbbfc', 'heojeo': '/_blob/c74d99362d43fdd33ca6c94036a3105e',
    'join': '/_blob/df5519b57f94b658406e0fc4a09d6303', 'ijeon': '/_blob/7c4790649a8cebcf24d3e01c369d0b45',
}
ART = {  # 계책 카드 그림
    '매복': '/_blob/c5464ba9120eaf0a0c84428c18107133', '의병': '/_blob/c0b83146ed6b44c54d96d6269f4e1c62',
    '화계': '/_blob/b54388386e908d5688f7f880b8496544', '첩보': '/_blob/8937800d07c38785da9436e059d4e743',
    '견벽': '/_blob/3b8883229e1ffec47fd824d2014ac91c', '간파': '/_blob/de60cd519282c4d60a27c57b8b418bfd',
}
FIELD = '/_blob/d8f3bc2ca330c82b2f016e72af047846'  # 전장 배경
LOGO = '/_blob/4a257d9d77d3e8c73933527a881c51e1'   # 기존 web/*/public/logo-wordmark.png(1200×448)를 480×179로 줄인 것 — 상단 막대 · 로그인

MAP = {  # 지도(와룡전 원본 타일 렌더, 1447 월드 로컬 자료 기준 예시)
    'desk': '/_blob/f669c50c8c6cc5e3df0f1cf0133264e2',       # 영천 일대 16px/칸 1048×952 — 작전실 데스크톱
    'mob': '/_blob/04924482900226bb1114d319829336dd',        # 양적 일대 16px/칸 390×844 — 작전실 모바일
    'prov': '/_blob/7511547ea890d48c122ddd52138da3d8',       # 전체 개관 1/3px/칸 1024×892 — 州 보기 · 작은 지도
    'jun': '/_blob/c4a3bfb91465b5eb25f8f02f35268382',        # 영천 일대 4px/칸 840×480 — 郡 보기
    'xian32': '/_blob/7af3130033acb862efc5966a981761b2',     # 양적 32px/칸 832×640 — 縣 최대 확대
    'hero': '/_blob/c7cb2811db26f785ca285c2c32d0e9b7',       # 中原(낙양·하수·낙수) 4px/칸 1440×1000 — 로그인 데스크톱 배경
    'hero_m': '/_blob/2ecec60da1953e33e5b9ba9400736c71',     # 낙양 8px/칸 390×480 — 로그인 모바일 배경
    'thumb': '/_blob/088e3ff7cf575f9b374ef52f4b6bd2b0',      # 中原 4px/칸 360×220 — 로비 서버 카드
    'guanzhong': '/_blob/56b58e1c058500514582169d40aea62c',  # 관중 8px/칸 1280×800
    'jiangdong': '/_blob/c8e85003ec01e2c989f00970c3ec376e',  # 강동 8px/칸 1280×800
    'castles': '/_blob/266f28662b3d3b47deb5bb5e4d2e4efd',    # 성 크기 B안 7단, 중 이상 외성 + 내성(중·대 3×3, 특·경 5×5) 2110×540
    'castles_old': '/_blob/38376d6affc57296cfdbf262aef9ec29',  # (낡음) 내성 없는 한 겹 판 — 쓰지 않는다
    'flags': '/_blob/4390770fc6235434751919ce7c402021',      # 깃발 시연 1280×800
    'icons1': '/_blob/740bc7bc1a72570250d9672c35aff89a',     # 1칸 거점 4종(장현 · 수 · 진 · 이) × 세력색 4 + 깃발 천, 원작 부품만(opensamguk-images #20) 768×512
    'icons1_old': '/_blob/b40155fea2074c0cae4814cd514509ac', # (낡음) 09-26 손으로 찍은 부분이 섞인 판 — 쓰지 않는다
    'pep': '/_blob/efe30789ffd3844319d47a8fc035b395',        # 지금 pep /login 아이소 824×588
    'pepm': '/_blob/51c5184f876362c4c6c348a4cb405695',       # 지금 pep /login 모바일 1170×2532
    'pass_ok': '/_blob/411fe29671fffc65b194f895c3931348',    # 원작 관: 동서 길 위 관문 + 남북 성벽이 산에 닿음 624×432
    'pass_bad_hulao': '/_blob/94d20abaa9a21bf20e102fd15caaa3b6',   # 09-26 시안 虎牢關: 성벽이 들판에서 끝나 길이 돌아감 800×600
    'pass_bad_jiange': '/_blob/29bb313888d04b9efc52205eee0e40d5',  # 09-26 시안 劍閣: 능선이 섬처럼 떠 길이 옆으로 돎 842×594
    'county32_yangcheng': '/_blob/5e3fddbb3500808def360fcc7a33e9bf',  # 양성현(陽城, 영천군, id 129) 縣 보기 32px 832×640
    'county32_changshe': '/_blob/26200b3970f7d2f3450fc4873ed6e504',   # 장사현(id 133) 縣 보기 32px 832×640
    'castle_chenliu': '/_blob/e861d60270b153de307071d2420e0739',      # 진류현(특, id 286) B안 11×11 외성 + 내성 5×5, 32px 768×768(기슭 이동안 반영)
    'battle_siege': '/_blob/4e185edc19008a36dc1abbf6f8c10ee4',  # 원작 전장 040판(성새: 성벽 · 성문 · 강 · 다리), 아이소 1024×524 — 실시간 전투 · 리플레이
    'battle_field': '/_blob/f7457752c84fd68cd74ff055cddff6ae',  # 원작 전장 192판(야전: 능선), 아이소 1024×544
    # 원작 전투 유닛(BATTLE.SCH 역할층 재채색, 원본 대조 99.1%) 160×64, 배경 투명, 32×32 칸 간격 없음, 확대 없음.
    # 열(x): 0 장수 기마 · 32 기병 · 64 궁병 · 96 보병 · 128 깃발 / 행(y): 0 조조 #4f7fbf · 32 원소 #b0569a
    'units': '/_blob/c31a30619fb3462635d26678063a522b',
}
