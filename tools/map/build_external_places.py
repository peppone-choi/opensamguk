#!/usr/bin/env python3
"""CHGIS 커버리지 밖 지점 — 좌표를 Wikidata(CC0)에서 받는다.

입력  없음 (Wikidata SPARQL)
출력  data/map/external-places.json

**왜 필요한가.** CHGIS V6 는 현대 중화인민공화국 국경 안만 담는다. 그래서 後漢의 정식
행정구역인데도 소스에 아예 없는 곳이 있다 — 交州 남부 3郡(베트남), 樂浪·帶方(한반도).
격자가 잘라낸 게 아니라 원본에 없다. 여기에 郡國志에 없는 주변 세력까지 더한다.

**좌표를 손으로 적지 않는다.** 손으로 적으면 출처가 나고, 그건 출처가 아니다.
비정(어느 현대 지점인가)은 문헌이 정하고, 좌표(그 지점이 어디인가)는 Wikidata P625 가
준다. QID 를 같이 적어 사후에 남이 검증할 수 있게 한다. 후보가 여럿이면 임의로 고르지
않고 unresolved 로 남긴다 — 지도에 안 실리는 편이 틀린 자리에 실리는 것보다 낫다.

**소국은 적게 찍는다.** 三國志 魏書 東夷傳이 이름을 남긴 것 중 위치 비정이 굳은 것만
넣는다. 邑落 단위까지 찍으면 지도가 이름으로 덮이고, 대부분은 비정이 갈린다.

usage:  python3 tools/map/build_external_places.py [--check]
"""
from __future__ import annotations

import argparse, json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from resolve_city_coords import sparql          # noqa: E402  (같은 SPARQL 헬퍼를 쓴다)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "map" / "external-places.json"

CN, KR, KP, VN, JP, RU, TW = "Q148", "Q884", "Q423", "Q881", "Q17", "Q159", "Q865"

# (한자, 종류, 등급, 현대 비정지 영문 라벨, 국가, 소속郡, 소속州, 비정 근거, 상위행정구역, 허용반경km)
#
# 상위행정구역은 동명이지를 가른다 — Ji'an 은 길림과 강서에 둘 다 있다. 허용반경은
# 후보들이 그 안에 모이면 같은 곳으로 본다. 유목 王庭처럼 애초에 점이 아닌 것은 넓게 준다.
#
# COMMANDERY/COUNTY = 後漢 정식 행정구역. CHGIS 지점과 완전히 동격이고 정복 대상이다.
# EXTERNAL_PLACE    = 漢 밖 세력. 정복이 아니라 외교·교역·이민족 경로. lv4('이').
#
# 비정 근거는 반드시 남긴다. 근거 없는 줄은 넣지 마라 — 없는 줄이 곧 "아직 모른다"다.
PLACES = [
    # --- 交州 남부 3郡. 郡國志 卷23. CHGIS 미커버(베트남) ---
    ("交趾郡", "COMMANDERY", 6, "Bắc Ninh", VN, "交趾郡", "交州",
     "치소 龍編. 羸𨻻(Luy Lâu) 성터가 있는 박닌", "province of Vietnam", 40),
    ("九真郡", "COMMANDERY", 6, "Thanh Hóa", VN, "九真郡", "交州",
     "치소 胥浦. 馬江(Sông Mã) 하류 타인호아 시가", "provincial city of Vietnam", 20),
    ("日南郡", "COMMANDERY", 6, "Quảng Trị", VN, "日南郡", "交州",
     "치소 西捲. 192년 象林이 이탈해 林邑(참파)이 된 제국 최남단",
     "province of Vietnam", 40),
    # --- 한반도. 樂浪은 정식 郡, 帶方은 204년 공손강이 낙랑 남부를 갈라 세웠다 ---
    ("樂浪郡", "COMMANDERY", 6, "Pyongyang", KP, "樂浪郡", "幽州",
     "치소 朝鮮. 평양 낙랑토성", None, 20),
    ("帶方郡", "COMMANDERY", 6, "Sariwon", KP, "帶方郡", "幽州",
     "치소 帶方. 황해도 사리원~봉산 일대", None, 20),
    # --- CHGIS 220년 레이어에 治所가 없어 앵커가 만들어지지 않는 漢 郡 ---
    # 앵커가 없으면 MAX_KM 필터가 통째로 꺼져 동명이인 縣을 1500km 밖에서 물어온다
    # (遼東 襄平이 하남에, 雲中 武進이 강소성에 찍히던 이유). 治所를 사료로 비정해 넣는다.
    ("遼東郡", "COMMANDERY", 6, "Liaoyang", CN, None, None,
     "郡國志 遼東郡 治 襄平. 요양 일대", "Liaoning", 30),
    ("玄菟郡", "COMMANDERY", 6, "Fushun", CN, None, None,
     "郡國志 玄菟郡 治 高句驪. 3차 이치 후 무순 일대", "Liaoning", 30),
    ("遼東屬國", "COMMANDERY", 6, "Yi County", CN, None, None,
     "郡國志 遼東屬國 治 昌遼(昌黎). 금주 의현 일대", "Liaoning", 40),
    ("上谷郡", "COMMANDERY", 6, "Huailai County", CN, None, None,
     "郡國志 上谷郡 治 沮陽. 하북 회래", "Hebei", 30),
    ("雲中郡", "COMMANDERY", 6, "Togtoh County", CN, None, None,
     "郡國志 雲中郡 治 雲中. 내몽골 托克托", "Inner Mongolia", 40),
    ("五原郡", "COMMANDERY", 6, "Baotou", CN, None, None,
     "郡國志 五原郡 治 九原. 포두 일대", "Inner Mongolia", 40),
    ("朔方郡", "COMMANDERY", 6, "Linhe District", CN, None, None,
     "郡國志 朔方郡 治 臨戎. 하투 임하", "Inner Mongolia", 50),
    ("鉅鹿郡", "COMMANDERY", 6, "Xingtai", CN, None, None,
     "郡國志 鉅鹿郡 治 廮陶 = 하북 寧晉. 상위 邢台로 잡는다", "Hebei", 45),
    ("東郡", "COMMANDERY", 6, "Puyang", CN, None, None,
     "郡國志 東郡 治 濮陽", "Henan", 30),
    # 續漢書 郡國志 卷113 「凡郡、國百五」: 魯國은 秦 薛郡이 高后 때 國으로 개칭된 사례.
    ("魯國", "KINGDOM", 6, "Qufu", CN, None, None,
     "郡國志 魯國 治 魯縣 = 곡부", "Shandong", 25),
    # 卷118 百官志 州郡: 京兆尹·左馮翊·右扶風(三輔)은 郡도 國도 아닌 수도권 특수
    # 행정구역이라 COMMANDERY 로 뭉개지 않는다.
    ("左馮翊", "METROPOLITAN", 6, "Gaoling District", CN, None, None,
     "郡國志 左馮翊 治 高陵", "Shaanxi", 30),
    ("右扶風", "METROPOLITAN", 6, "Xingping", CN, None, None,
     "郡國志 右扶風 治 槐里 = 흥평", "Shaanxi", 30),
    ("廬江郡", "COMMANDERY", 6, "Lujiang County", CN, None, None,
     "郡國志 廬江郡 治 舒", "Anhui", 30),
    ("越巂郡", "COMMANDERY", 6, "Xichang", CN, None, None,
     "郡國志 越巂郡 治 邛都 = 서창", "Sichuan", 30),
    ("牂牁郡", "COMMANDERY", 6, "Huangping County", CN, None, None,
     "郡國志 牂牁郡 治 且蘭. 귀주 황평 비정 채택 — 貴陽설과 미결", "Guizhou", 60),
    ("蜀郡屬國", "COMMANDERY", 6, "Ya'an", CN, None, None,
     "郡國志 蜀郡屬國 治 漢嘉. 아안 일대", "Sichuan", 40),
    ("張掖居延屬國", "COMMANDERY", 6, "Ejin Banner", CN, None, None,
     "郡國志 張掖居延屬國 治 居延. 額濟納 거연택", "Inner Mongolia", 60),

    # --- 郡國志에는 있는데 CHGIS 에도, 위 목록에도 없어 지도에서 통째로 빠졌던 郡.
    #     치소 縣의 좌표가 CHGIS 에 없으면 郡 자체가 사라진다(縣이 하나도 안 붙은 郡은
    #     빈 영역이 되어 지워진다). 郡國志가 적은 治所를 근거로 되살린다. ---
    ("上郡", "COMMANDERY", 6, "Yulin", CN, None, None,
     "郡國志 上郡 治 膚施. 섬서 유림 — 魚河堡설/綏德설 중 유림 채택", "Shaanxi", 60),
    ("西河郡", "COMMANDERY", 6, "Lishi District", CN, None, None,
     "郡國志 西河郡 治 離石. 후한대 美稷에서 離石로 이치 — 산서 여량 이석",
     "Shanxi", 40),
    ("定襄郡", "COMMANDERY", 6, "Youyu County", CN, None, None,
     "郡國志 定襄郡 治 善無. 산서 우옥", "Shanxi", 40),
    # 문현의 위키데이터 영문 라벨은 "Wen County" 가 아니라 "Wen" 이다("Wen County" 는
    # 하남 溫縣이라 감숙 필터에 걸려 후보 0개가 됐다). 陰平 라벨 항목은 삼국지연의 소설
    # 지명이라 근거로 쓰지 않는다.
    ("廣漢屬國", "COMMANDERY", 6, "Wen", CN, None, None,
     "郡國志 廣漢屬國 治 陰平道. 감숙 문현", "Gansu", 40),
    ("龜茲屬國", "COMMANDERY", 6, "Yuyang District", CN, None, None,
     "郡國志 上郡 龜茲屬國. 龜茲縣 = 유림 북부 — 上郡 治와 가까워 비정이 겹친다",
     "Shaanxi", 60),
    ("張掖屬國", "COMMANDERY", 6, "Zhangye", CN, None, None,
     "郡國志 張掖屬國 「有五城 … 候官·左騎·千人·司馬官·千人官」. 治所 이름이 안 남아 "
     "張掖 일대로만 잡는다 — 점이 아니라 영역", "Gansu", 80),

    # --- 漢 밖 세력. 三國志 魏書30 烏丸鮮卑東夷傳 · 後漢書 卷85 東夷列傳 ---
    # level 은 계약대로 전부 lv4('이')다 — 등급을 도로 허브 표시로 겸용하지 않는다.
    # 중요 세력은 대신 아래 HUB 에 이름을 올려 별도 필드로 표시한다.
    # 고구려는 점 하나로 두지 않는다 — 220년까지 도읍이 卒本 → 國內城 → 丸都城으로
    # 옮겨 다녔고, 그 자취가 다 남아 있다. 다만 옮긴 자리가 같은 칸이면 점을 나누지
    # 않는다(丸都城은 國內城에서 4km — 지도 한 칸 안이라 國內城 basis 에 적는다).
    # 평양성은 넣지 않는다: 천도가 427년이라 이 지도(220년)의 밖이고, 그 자리는
    # 지금 樂浪郡 治 朝鮮縣으로 이미 지도에 있다.
    ("國內城", "EXTERNAL_PLACE", 4, "Ji'an", CN, None, None,
     "『삼국사기』 고구려본기 유리명왕 22년 「移都於國內，築尉那巖城」. 지린성 지안 — 유리왕 22년 천도. 산상왕 13년(209) 丸都城으로 옮겼으나 "
     "산성자산성이 국내성에서 4km라 같은 칸이다 — 220년의 도읍은 이 점이다", "Jilin", 10),
    ("卒本", "EXTERNAL_PLACE", 4, "Huanren Manchu Autonomous County", CN, None, None,
     "『魏書』高句麗傳 「遂至紇升骨城，遂居焉，號曰高句麗」. 卒本 = 오녀산성, 환런 일대. "
     "건국 도읍이자 220년에도 남은 고구려의 옛 거점", "Liaoning", 30),
    ("北沃沮", "EXTERNAL_PLACE", 4, "Hoeryong", KP, None, None,
     "東夷傳 「北沃沮一名置溝婁，去南沃沮八百餘里」. 두만강 유역 회령 일대 — 비정이 갈린다",
     "North Hamgyong Province", 40),
    ("安邪國", "EXTERNAL_PLACE", 4, "Haman County", KR, None, None,
     "韓傳 「弁辰安邪國」. 『삼국사기』 지리지 咸安郡 「法興王以大兵滅阿尸良國(一云阿那加耶)」 "
     "— 함안 말이산 고분군", None, 20),
    # --- 220년 한반도 남부의 소국. 『삼국사기』 신라본기 + 지리지가 병합 연대와 現 지명을
    # 같이 적어 놓아 위치가 잡히는 것만 싣는다. 이름만 남고 자리가 안 잡히는 소국
    # (于尸山國·居柒山國·浦上八國의 대부분)은 넣지 않는다.
    ("悉直國", "EXTERNAL_PLACE", 4, "Samcheok", KR, None, None,
     "『삼국사기』 신라본기 파사이사금 23년 「悉直谷國來降」 · 지리지 三陟郡 「本悉直國」",
     "Gangwon Province", 20),
    ("押督國", "EXTERNAL_PLACE", 4, "Gyeongsan", KR, None, None,
     "『삼국사기』 지리지 獐山郡 「祗味王時伐取押督小國置郡」. 경산", None, 20),
    ("召文國", "EXTERNAL_PLACE", 4, "Uiseong County", KR, None, None,
     "『삼국사기』 신라본기 벌휴이사금 2년 「波珍飡仇道 … 伐召文國」 · 지리지 聞韶郡 "
     "「本召文國」. 의성 금성산 고분군", None, 20),
    ("于山國", "EXTERNAL_PLACE", 4, "Ulleung County", KR, None, None,
     "『삼국사기』 신라본기 지증마립간 13년 「于山國歸服 … 或名鬱陵島」. 220년에는 아직 "
     "신라 밖의 섬 세력", None, 20),
    ("夫餘", "EXTERNAL_PLACE", 4, "Nong'an County", CN, None, None,
     "쑹화강 유역 눙안 일대. 東夷傳 「夫餘在長城之北，去玄菟千里」", None, 20),
    ("東沃沮", "EXTERNAL_PLACE", 4, "Hamhung", KP, None, None,
     "東夷傳 「東沃沮在高句麗蓋馬大山之東，濱大海而居」. 함흥 일대", None, 20),
    ("濊", "EXTERNAL_PLACE", 4, "Gangneung", KR, None, None,
     "東夷傳 「濊南與辰韓，北與高句麗、沃沮接，東窮大海」. 영동 강릉", "Gangwon Province", 15),
    ("挹婁", "EXTERNAL_PLACE", 4, "Ussuriysk", RU, None, None,
     "東夷傳 「挹婁在夫餘東北千餘里，濱大海」. 연해주 남부", None, 30),
    # 삼한은 세력 이름이 아니라 그 안의 나라 이름으로 찍는다 — 馬韓/辰韓/弁韓은 지도의
    # 한 점이 아니라 수십 나라의 묶음이고, 그 자리에 실제로 있던 건 國邑(目支·斯盧·狗邪)이다.
    # 韓傳이 국명 78개를 적어놨지만 자리가 통설로 굳은 건 아래뿐이다. 나머지는 넣지 않는다.
    ("目支國", "EXTERNAL_PLACE", 4, "Iksan", KR, None, None,
     "韓傳 「辰王治月支國」. 마한 54국의 國邑 — 익산~천안 직산 중 익산 채택(비정이 갈린다)",
     None, 20),
    ("辟卑離國", "EXTERNAL_PLACE", 4, "Gimje", KR, None, None,
     "韓傳 마한 54국의 辟卑離國. 김제의 옛 이름 碧骨과 음이 이어진다는 통설 — 확정은 아니다",
     None, 20),
    ("伯濟國", "EXTERNAL_PLACE", 4, "Seoul", KR, None, None,
     "韓傳 마한 54국의 伯濟國. 『삼국사기』 백제본기 온조왕 「都河南慰禮城」 — 220년 구수왕대의 도읍. 한강 하류 풍납토성 일대", None, 20),
    ("州胡", "EXTERNAL_PLACE", 4, "Jeju City", KR, None, None,
     "韓傳 「又有州胡在馬韓之西海中大島上 … 乘船往來，巿買韓中」. 탐라", None, 30),
    ("斯盧國", "EXTERNAL_PLACE", 4, "Gyeongju", KR, None, None,
     "韓傳 진한 12국의 斯盧國 = 경주. 『삼국사기』 신라본기 「築城曰金城」 — 220년 "
     "나해이사금대의 도읍", None, 15),
    ("狗邪國", "EXTERNAL_PLACE", 4, "Gimhae", KR, None, None,
     "韓傳 「弁辰狗邪國」·倭 여정의 출발점 「其北岸狗邪韓國」 = 김해. 『삼국유사』 駕洛國記 "
     "首露王의 金官加耶", None, 15),
    # 나머지 변진 소국은 韓傳 국명으로는 자리가 안 잡힌다(半路·甘路·彌烏邪馬 …).
    # 자리가 확실한 곳은 『삼국유사』 五伽耶條가 이름을 남긴 쪽이라 그 이름으로 찍는다.
    ("古資彌凍國", "EXTERNAL_PLACE", 4, "Goseong County", KR, None, None,
     "韓傳 「弁辰古資彌凍國」 = 고성. 『삼국유사』 五伽耶條의 小伽耶",
     "South Gyeongsang", 20),
    ("大伽耶", "EXTERNAL_PLACE", 4, "Goryeong County", KR, None, None,
     "『삼국유사』 五伽耶條 「大伽耶(今高靈)」. 고령 지산동 고분군. 韓傳 半路國을 여기로 "
     "보는 설이 있으나 성주설과 갈린다", None, 20),
    ("星山伽耶", "EXTERNAL_PLACE", 4, "Seongju County", KR, None, None,
     "『삼국유사』 五伽耶條 「星山伽耶(今京山，一云碧珍)」. 성주 성산동 고분군", None, 20),
    ("古寧伽耶", "EXTERNAL_PLACE", 4, "Hamchang", KR, None, None,
     "『삼국유사』 五伽耶條 「古寧伽耶(今咸寧)」. 상주 함창", None, 20),
    # 倭 여정. 東夷傳이 帶方에서 邪馬壹國까지 里程을 그대로 적어놨다 —
    #   「從郡至倭，循海岸水行，歷韓國，乍南乍東，到其北岸狗邪韓國，七千餘里，
    #    始渡一海，千餘里至對馬國 … 又渡一海，千餘里至末盧國 … 東南陸行五百里，到伊都國 …
    #    東南至奴國百里 … 南至投馬國，水行二十日 … 南至邪馬壹國，女王之所都」
    # 여정의 앞부분은 고고학 비정이 굳어 있다. 뒷부분(投馬·邪馬壹)만 논쟁이다.
    ("對馬國", "EXTERNAL_PLACE", 4, "Tsushima", JP, None, None,
     "東夷傳 狗邪韓國에서 바다 건너 천여 리. 쓰시마", "Nagasaki Prefecture", 30),
    ("一大國", "EXTERNAL_PLACE", 4, "Iki", JP, None, None,
     "東夷傳 對馬國 남쪽 천여 리 瀚海. 이키섬 하루노쓰지 유적", "Nagasaki Prefecture", 20),
    ("末盧國", "EXTERNAL_PLACE", 4, "Karatsu", JP, None, None,
     "東夷傳 「又渡一海，千餘里至末盧國，有四千餘戶，濵山海居」. 가라쓰", None, 20),
    ("伊都國", "EXTERNAL_PLACE", 4, "Itoshima", JP, None, None,
     "東夷傳 「東南陸行五百里，到伊都國」. 이토시마 미쿠모 유적", None, 20),
    ("奴國", "EXTERNAL_PLACE", 4, "Kasuga", JP, None, None,
     "後漢書 「建武中元二年，倭奴國奉貢朝賀 … 倭國之極南界也」. 金印은 志賀島 출토지만 "
     "왕도 후보는 須玖岡本 유적의 가스가", "Fukuoka Prefecture", 20),
    # 邪馬壹國(야마타이) — 규슈설/기나이설이 지역 자체로 갈려 단일 좌표가 정본이 아니다.
    # 그런데 devsam che 맵의 이민족 거점 '왜'가 바로 여기다(CityConst.kt:178). 게임 세계가
    # 먹는 거점이라 빼면 세력 하나가 통째로 사라진다. 그래서 **빼지 않고 미결로 표시**한다 —
    # 반경 60km 로 넓혀 점이 아니라 영역으로 읽히게 두고, `uncertain` 로 계약을 남긴다.
    ("邪馬壹國", "EXTERNAL_PLACE", 4, "Yoshinogari", JP, None, None,
     "三國志는 邪馬壹國, 後漢書는 邪馬臺國으로 적는다(원문에 壹/臺 이체자 주석). "
     "규슈설 좌표를 대표로 쓰되 기나이설(나라 분지)과 미결 — 단일 정본 아님. "
     "devsam che 맵의 이민족 '왜'", "Saga Prefecture", 60),
    # --- 남해. 후한 판도 밖이지만 사료가 항로 끝을 적어놨다 ---
    ("夷洲", "EXTERNAL_PLACE", 4, "Tainan", TW, None, None,
     "吳主傳 「遣將軍衞溫、諸葛直將甲士萬人，浮海求夷洲及亶洲」. 資治通鑑은 "
     "「欲俘其民以益衆」이라 적었다 — 인구 약탈이 목적이었다. 대만 비정은 미결", None, 60),
    # 流求 — 隋書 卷81 東夷 流求國이라 후한대 기록이 아니다. 그런데 che 맵의 '왜'가
    # 인접으로 '유구'를 들고 있다(CityConst.kt:178). 게임 항로의 끝이라 남기고,
    # 시대 밖이라는 사실은 `anachronistic` 으로 표시한다.
    ("流求", "EXTERNAL_PLACE", 4, "Naha", JP, None, None,
     "隋書 卷81 東夷 流求國. 후한대 사료 아님 — devsam che 맵의 남해 항로 끝 '유구'",
     "Okinawa Prefecture", 30),
    # --- 기존 devsam 맵이 "이민족"으로 뭉뚱그린 곳. 사료로 비정해 되돌린다 ---
    ("西羌", "EXTERNAL_PLACE", 4, "Xining", CN, None, None,
     "後漢書 卷87 西羌傳 「濱於賜支，至乎河首，綿地千里」. 賜支河曲 = 황하 상류 청해",
     "Qinghai", 40),
    ("白馬氐", "EXTERNAL_PLACE", 4, "Longnan", CN, None, None,
     "元和郡縣圖志 39 「戰國時，白馬氐居焉，氐即西戎之別種也」. 武都 = 隴南",
     "Gansu", 40),
    # 哀牢 를 保山 에 두면 永昌郡 治 不韋(99.26,25.15)와 10km 안에서 겹쳐 같은 칸을 다툰다.
    # 永昌郡은 애초에 哀牢 를 내속시켜 세운 郡이라 治所가 곧 哀牢 땅이지만, 지도에서는
    # 두 세력이 한 점일 수 없다. 後漢書가 적은 본거지(牢山·瀾滄江 서편)를 좌표로 삼아
    # 高黎貢山 너머 騰衝 으로 물린다 — 郡 밖 서쪽이라는 사료의 그림과도 맞는다.
    ("哀牢", "EXTERNAL_PLACE", 4, "Tengchong City", CN, None, None,
     "後漢書 卷86 「哀牢夷者，其先有婦人名沙壹，居於牢山」. 永昌郡 서쪽 본거지 — "
     "devsam 맵의 南蠻. 保山(不韋)은 永昌郡 治所라 비워둔다",
     "Yunnan", 60),
    ("山越", "EXTERNAL_PLACE", 4, "Huangshan", CN, None, None,
     "三國志 55·60 「諸山越不賔，有寇難之縣」. 丹陽 산지 黟·歙 일대 — 부족 영역이라 넓다",
     "Anhui", 60),
    # --- 초원 ---
    ("烏桓", "EXTERNAL_PLACE", 4, "Chifeng", CN, None, None,
     "東夷傳 「烏丸者，東胡也 … 餘類保烏丸山」. 蹋頓의 柳城은 遼西郡이라 CHGIS 에 있다",
     "Inner Mongolia", 20),
    ("鮮卑", "EXTERNAL_PLACE", 4, "Zhangjiakou", CN, None, None,
     "檀石槐 王庭 = 高柳 북쪽 삼백여 리. 軻比能은 雲中·五原 동쪽을 다 거뒀다", None, 20),
    ("南匈奴", "EXTERNAL_PLACE", 4, "Ordos City", CN, None, None,
     "單于庭 美稷 = 西河郡 오르도스 남부. 王庭은 애초에 점이 아니라 넓게 잡는다",
     "Inner Mongolia", 40),
]

DISPUTED = {"目支國", "辟卑離國", "大伽耶", "北沃沮", "鮮卑", "南匈奴", "邪馬壹國", "流求", "張掖屬國", "龜茲屬國"}                       # 비정이 갈리는 것. 게임은 견디지만 기록은 못 견딘다.
# 도로 간선의 허브 — 郡國志에 없는 세력이라도 이곳들은 郡治급으로 승격해 오갈 수 있어야
# 한다(build_terrain_grid.py). level 이 아니라 여기서만 표시한다.
HUB = {"國內城", "卒本", "北沃沮", "安邪國", "悉直國", "押督國", "召文國", "于山國", "夫餘", "東沃沮", "濊", "目支國", "辟卑離國", "伯濟國", "州胡", "斯盧國",
       "狗邪國", "古資彌凍國", "大伽耶", "星山伽耶", "古寧伽耶",
       "夷洲", "邪馬壹國", "流求", "西羌", "白馬氐", "哀牢", "山越", "烏桓", "鮮卑", "南匈奴"}
FIELDS = ("nameFt", "kind", "level", "modern", "country", "jun", "prov", "basis", "adm", "tol")


def spread_km(cand):
    """후보들이 얼마나 흩어져 있나. 위도 1도 = 111km, 경도는 cos 로 누른다."""
    import math
    worst = 0.0
    for i in range(len(cand)):
        for j in range(i + 1, len(cand)):
            _, x1, y1 = cand[i]
            _, x2, y2 = cand[j]
            dy, dx = (y1 - y2) * 111.0, (x1 - x2) * 111.0 * math.cos(math.radians((y1 + y2) / 2))
            worst = max(worst, math.hypot(dx, dy))
    return worst


def resolve():
    """라벨+국가로 좌표를 조회한다. 후보가 여럿이면 실패로 남긴다(임의로 고르지 않는다)."""
    # 라벨을 언어 태그 리터럴로 직접 매칭한다. FILTER(STR(?l)=...) 는 전체 라벨을 훑어
    # 504 로 죽는다 — 태그 리터럴은 색인을 타서 즉시 돌아온다.
    # 한 번에 다 물으면 504 가 난다. 20행씩 끊어 묻고 합친다.
    rows = []
    for k in range(0, len(PLACES), 20):
        values = " ".join(f'("{r[3]}"@en wd:{r[4]})' for r in PLACES[k:k + 20])
        rows += sparql(f"""
SELECT ?label ?item ?coord WHERE {{
  VALUES (?label ?country) {{ {values} }}
  ?item rdfs:label ?label ; wdt:P17 ?country ; wdt:P625 ?coord .
}}""")
    # 동명이지는 상위 행정구역(P131*)이나 종류(P31)로 가른다 — Ji'an 은 길림과 강서에
    # 둘 다 있고, Thanh Hóa 는 省과 省都와 面이 같은 이름을 쓴다.
    adms = {r[3]: r[8] for r in PLACES if r[8]}
    if adms:
        keep, items = set(), list(adms.items())
        for k in range(0, len(items), 8):     # P131* 재귀는 무거워 8행씩 끊는다
            av = " ".join(f'("{lab}"@en "{adm}"@en)' for lab, adm in items[k:k + 8])
            keep |= {(b["label"]["value"], b["item"]["value"].rsplit("/", 1)[-1])
                     for b in sparql(f"""
SELECT ?label ?item WHERE {{
  VALUES (?label ?adm) {{ {av} }}
  ?item rdfs:label ?label .
  {{ ?item wdt:P131* ?a . ?a rdfs:label ?adm . }}
  UNION
  {{ ?item wdt:P31 ?t . ?t rdfs:label ?adm . }}
}}""")}
        rows = [b for b in rows if b["label"]["value"] not in adms
                or (b["label"]["value"], b["item"]["value"].rsplit("/", 1)[-1]) in keep]
    hits = {}
    for b in rows:
        lon, lat = b["coord"]["value"].removeprefix("Point(").removesuffix(")").split()
        hits.setdefault(b["label"]["value"], []).append(
            (b["item"]["value"].rsplit("/", 1)[-1], float(lon), float(lat)))

    out, unresolved = [], []
    for i, row in enumerate(PLACES):
        p = dict(zip(FIELDS, row))
        cand = hits.get(p["modern"], [])
        # 같은 곳을 여러 항목이 조금씩 다르게 적어둔 경우가 많다(시청 좌표 대 중심 좌표).
        # 후보가 10km 안에 모여 있으면 같은 곳으로 보고 첫 항목을 쓴다. 흩어져 있으면
        # 서로 다른 동명 지점이라는 뜻이므로 고르지 않는다 — Ji'an(길림/강서)이 그렇다.
        if not cand or spread_km(cand) > p["tol"]:
            unresolved.append(f'{p["nameFt"]} ({p["modern"]}): 후보 {len(cand)}개'
                              f'{"" if not cand else f" · 최대 {spread_km(cand):.0f}km 흩어짐"}'
                              " — 임의 선택하지 않음")
            continue
        qid, lon, lat = cand[0]
        lon, lat = round(lon, 5), round(lat, 5)
        p.pop("adm"); p.pop("tol")
        p.update(id=f"X{i:03d}", nameCh=p["nameFt"], namePy="", typeCh="",
                 lon=lon, lat=lat, wikidata=qid, begYr=-9999, endYr=9999,
                 conf="DISPUTED" if p["nameFt"] in DISPUTED else "IDENTIFIED",
                 hub=p["nameFt"] in HUB, presLoc=p.pop("modern"))
        p.pop("country")
        out.append(p)
    return out, unresolved


def build():
    places, unresolved = resolve()
    return {
        "_meta": {
            "source": "비정 = 三國志 魏書 東夷傳·後漢書 郡國志 · 좌표 = Wikidata P625 (CC0)",
            "generator": "tools/map/build_external_places.py",
            "note": "CHGIS V6 커버리지(현대 중국 국경) 밖 지점. 좌표는 손으로 적지 않는다.",
            "resolved": len(places), "unresolved": len(unresolved),
        },
        "places": places,
        "unresolved": unresolved,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    doc = build()
    blob = json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
    if a.check:
        same = OUT.exists() and OUT.read_text() == blob
        print("드리프트 없음." if same else f"드리프트: {OUT}")
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(blob)
    m = doc["_meta"]
    print(f'{OUT.relative_to(ROOT)}: 해결 {m["resolved"]} · 미해결 {m["unresolved"]}')
    for u in doc["unresolved"]:
        print(f"  미해결  {u}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
