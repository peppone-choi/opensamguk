"""Materialize historical HWIHA seeds only after every person is source-bound.

The archive and province claims are inputs, never rewritten. Both ``--write``
and ``--check`` reject incomplete identity review before touching any seed.
"""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from tools.scenario.promote_3190 import render


ROOT = Path(__file__).resolve().parents[2]
ACTIVE_CODES = (1010, 1020, 1021, 1030, 1031, 1040, 1041, 1050, 1060,
                1070, 1080, 1090, 1100, 1110, 1120)
# Explicitly reviewed classic archive family. Keep this list fixed when new
# scenarios are added; every use below must have the same old identity/profile.
CLASSIC_ARCHIVE_CODES = (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080,
                         1090, 1100, 1110, 1120)
ALTERNATE_ARCHIVE_CODES = (1021, 1031, 1041)
LORD_NAME_OVERRIDES = {(1031, "유표"): "유표1"}
# These spellings identify the same historical person, not a similarly named officer.
# The archive's years, han_ownership.json's 金旋/劉表 identification, and the
# official RTK14 officer list (gamecity.ne.jp/sangokushi14/officers-list.html)
# agree with the 3190 RTK14 source rows below. Similar dates alone are excluded.
REVIEWED_RTK14_ALIASES = {
    ("국연", 160, 219): "국연1",  # 国淵
    ("김배삼결", 192, 225): "금환삼결",  # 金環三結, identical archive/RTK14 three stats
    ("교수", 143, 195): "교유",  # 袁術's 橋蕤, same years and old three-stat profile
    ("구역거", 152, 193): "구력거",  # 丘力居, Korean reading correction
    ("금선", 155, 208): "김선",      # 金旋, RTK14 official officer 223
    ("금의", 177, 218): "김의",      # 金禕, surname reading
    ("노숙1", 172, 217): "노숙",  # 魯粛, not his son 魯淑
    ("노숙2", 208, 274): "노숙1",  # 魯淑, not father 魯粛
    ("답둔", 158, 207): "답돈",  # 蹋頓
    ("동다나", 189, 225): "동도나",  # 南中 董荼那, same years
    ("마충", 187, 249): "마충1",  # 蜀 馬忠, exact old three stats
    ("마충1", 186, 222): "마충",  # 吳 馬忠, different namesake
    ("마충2", 187, 249): "마충1",  # 蜀 馬忠, not the earlier 吳 namesake
    ("마운", 183, 225): "마운록",  # 馬雲騄, fictional identity retained as such
    ("보질", 177, 247): "보즐",      # 步騭, later Korean reading
    ("비위", 193, 253): "비의",      # 費禕, novel spelling vs history
    ("번주", 149, 192): "번조",  # 董卓's 樊稠, same years and old three-stat profile
    ("서진순욱", 225, 289): "순욱1",  # 晉 荀勗, not 荀彧
    ("소제2", 224, 268): "소제",  # 邵悌, not an imperial title
    ("손소", 188, 241): "손소1",  # 孫韶
    ("순욱1", 163, 212): "순욱",  # 荀彧, not 晉 荀勗
    ("순욱2", 225, 289): "순욱1",  # 荀勗
    ("양부1", 178, 239): "양부",  # 楊阜
    ("양부2", 167, 212): "양회",  # 楊懐
    ("양봉1", 153, 197): "양봉",  # 楊奉, not 南中 楊鋒
    ("양봉2", 191, 252): "양봉1",  # 南中 楊鋒, not the earlier 楊奉
    ("양추2", 172, 238): "양추1",  # 馬騰/魏 楊秋
    ("양조", 223, 286): "양조1",  # 楊肇, not 楊祚
    ("양조1", 202, 256): "양조",  # 楊祚, not 楊肇
    ("양조2", 223, 286): "양조1",  # 楊肇, not 楊祚
    ("양추1", 159, 199): "양추",  # 楊醜, not 楊秋
    ("양혼", 220, 278): "양흔",  # 楊欣
    ("여영기", 182, 198): "여령기",  # 呂玲綺, fictional identity retained as such
    ("유선", 207, 271): "유선2",   # 劉禅, RTK14 official officer 945
    ("유표1", 142, 208): "유표",   # 劉表, RTK14 official officer 953
    ("왕기1", 190, 261): "왕기",  # 王基, not 王頎
    ("왕기2", 217, 281): "왕기1",  # 王頎, not 王基
    ("우금1", 159, 221): "우금",  # 于禁, not 牛金
    ("유선1", 207, 271): "유선2",  # 劉禅, same archive portrait as 유선
    ("유선2", 224, 264): "유선1",  # 劉璿, not 劉禅
    ("이풍3", 204, 254): "이풍2",  # later 李豊, distinct from two namesakes
    ("이풍2", 158, 199): "이풍",  # 袁術's 李豊, not later namesakes
    ("장남", 187, 222): "장남1",  # 蜀 張南
    ("장소", 202, 264): "장소1",  # 張紹, not 張昭
    ("장소1", 156, 236): "장소",  # 張昭, not 張紹
    ("장소 자포", 156, 236): "장소",  # 字子布, 張昭
    ("장소2", 202, 264): "장소1",  # 蜀 張紹, not 張昭
    ("장승", 178, 244): "장승1",  # 張承
    ("장제", 236, 280): "장제2",  # 張悌, not 張済
    ("장제1", 144, 196): "장제",  # 張済
    ("장제2", 188, 249): "장제1",  # 蒋済, not 張済
    ("장제3", 188, 249): "장제1",  # 蒋済
    ("장제3", 236, 280): "장제2",  # 張悌
    ("장포2", 225, 264): "장포",  # 吳 張布, not 蜀 張苞
    ("전위1", 160, 197): "전위",  # 曹操's 典韋
    ("전위2", 230, 274): "전의",  # 全禕, not 典韋
    ("정봉", 198, 266): "정봉1",  # 丁封, not 丁奉
    ("정봉1", 190, 271): "정봉",  # 丁奉, not 丁封
    ("정봉2", 198, 266): "정봉1",  # 吳 丁封, not 丁奉
    ("정봉승연", 190, 271): "정봉",  # 字承淵, 丁奉
    ("조홍", 156, 184): "조홍1",  # 黃巾 趙弘, not 曹洪
    ("조홍 자렴", 169, 232): "조홍",  # 字子廉, 曹洪
    ("조홍2", 169, 232): "조홍",  # 曹洪 in the other archive naming convention
    ("주태", 207, 261): "주태1",  # 州泰, not 周泰
    ("주태2", 171, 225): "주태",  # 吳 周泰
    ("주태유평", 171, 225): "주태",  # 字幼平, 周泰
    ("하후령녀", 207, 257): "하후영녀",  # 夏侯令女
    ("향랑", 167, 247): "상랑",  # 向朗
    ("향총", 195, 240): "상총",  # 向寵
    ("휴원진", 155, 200): "수원진",  # 袁紹's 眭元進, same years
}
# The historical placement review in han_ownership.json identifies these
# specific rulers by their polity and office even though archive and RTK14
# birth/death estimates differ. The target's stable officer ID is checked so
# an identically spelled but different officer cannot inherit its five stats.
# The scenario allowlist prevents one year's ruling from binding another year.
# In particular archive 장양1 is 張楊, while RTK14 장양1 is 張梁.
REVIEWED_RTK14_YEAR_VARIANTS = {
    ("장각", 140, 185): ("장각", 10348, (1010,)),  # 黃巾 張角
    ("장로", 163, 237): ("장로", 10358, (1020, 1030, 1040, 1050, 1060, 1070, 1080, 1120)),  # 漢中 張魯
    ("장양1", 150, 199): ("장양", 10322, (1020, 1030, 1040, 1120)),  # 河內 張楊, not 張梁
    ("한복", 149, 193): ("한복", 10929, (1021,)),  # 冀州牧 韓馥
    ("조조", 151, 220): ("조조", 10405, (1021, 1031, 1041)),  # 曹操
    ("마등", 149, 212): ("마등", 10952, (1021, 1031, 1041)),  # 征西將軍 馬騰
    ("공주", 151, 191): ("공주", 10218, (1021,)),  # 豫州刺史 孔伷
    ("엄백호", 150, 197): ("엄백호", 10118, (1030,)),  # 會稽 嚴白虎
    ("엄백호", 142, 198): ("엄백호", 10118, (1031, 1041)),
    ("유요", 156, 195): ("유요", 10099, (1030,)),  # 揚州刺史 劉繇
    ("유요", 156, 198): ("유요", 10099, (1031,)),
    ("왕랑", 167, 228): ("왕랑", 10571, (1031,)),  # 會稽太守 王朗
    ("맹획", 186, 245): ("맹획", 10224, (1100,)),  # 南中 孟獲
}
# A few archive names and dates differ from RTK14 while identifying the same
# officer. Pin the exact source profile, allowed scenario, and target ID. In
# particular, archive 우금1 is 于禁 and 우금2 is 牛金; archive 유표1 is 劉表,
# whereas 유표2 / 유표(흉노) denote the Xiongnu 劉豹.
REVIEWED_RTK14_ROSTER_VARIANTS = {
    ("우금2", 173, 226): ("우금1", 10550, ACTIVE_CODES,
                         ((63, 77, 37), (72, 79, 38))),
    ("유표2", 173, 229): ("유표1", 10103,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080,
                          1090, 1100, 1110, 1120), ((76, 55, 71),)),
    ("유표(흉노)", 173, 252): ("유표1", 10103, (1021, 1031, 1041), ((73, 74, 48),)),
    ("보련사", 198, 248): ("보연사", 10515, (1021, 1031, 1041), ((45, 30, 77),)),
    ("포삼낭", 191, 240): ("포삼랑", 10975, (1021, 1031, 1041), ((87, 83, 36),)),
    # 1021/1041 separately contain 고당륭, so only 1031 may use this source ID.
    ("고담륭", 190, 251): ("고당륭", 10957, (1031,), ((35, 24, 76),)),
    # Same Hanzi identity and exact three-ability fingerprint in the 1021
    # archive family; differing archive dates are retained, not silently
    # rewritten to the workbook estimates. Other scenario families need their
    # own source review when their three-ability profiles differ.
    ("고정", 190, 251): ("고정", 10958, (1021, 1031, 1041), ((55, 63, 43),)),
    ("곽원", 162, 205): ("곽원", 10816, (1021, 1031, 1041), ((66, 73, 36),)),
    ("김선", 155, 220): ("김선", 10840, (1021, 1031, 1041), ((52, 68, 13),)),
    ("능조", 168, 203): ("능조", 10068, (1021, 1031, 1041), ((75, 81, 42),)),
    ("마일제", 130, 194): ("마일제", 10941, (1021, 1031, 1041), ((43, 25, 70),)),
    ("마준", 196, 230): ("마준", 10946, (1021, 1031, 1041), ((38, 52, 31),)),
    ("맹우", 190, 243): ("맹우", 10220, (1021, 1031, 1041), ((50, 68, 22),)),
    ("문칙", 164, 199): ("문칙", 10383, (1021, 1031, 1041), ((59, 53, 32),)),
    ("사일", 153, 230): ("사일", 10185, (1021, 1031, 1041), ((48, 36, 67),)),
    ("소유", 167, 210): ("소유", 10720, (1021, 1031, 1041), ((51, 59, 48),)),
    ("습진", 189, 221): ("습진", 10646, (1021, 1031, 1041), ((64, 68, 51),)),
    ("엄백호", 142, 198): ("엄백호", 10118, (1021,), ((67, 70, 23),)),
    ("왕랑", 167, 228): ("왕랑", 10571, (1021, 1041), ((47, 35, 79),)),
    ("왕위", 163, 222): ("왕위", 10561, (1021, 1031, 1041), ((60, 70, 62),)),
    ("원환", 165, 225): ("원환", 10729, (1021, 1031, 1041), ((30, 17, 72),)),
    ("유화", 165, 216): ("유화", 10076, (1021, 1031, 1041), ((21, 16, 59),)),
    ("정은", 184, 220): ("정은", 10631, (1021, 1031, 1041), ((69, 73, 40),)),
    ("제갈정", 246, 300): ("제갈정", 10758, (1021, 1031, 1041), ((58, 63, 66),)),
    ("최림", 187, 244): ("최림", 10289, (1021, 1031, 1041), ((32, 22, 66),)),
    ("추단", 144, 193): ("추단", 10823, (1021, 1031, 1041), ((61, 64, 34),)),
    ("축융", 182, 230): ("축융", 10619, (1021, 1031, 1041), ((74, 85, 29),)),
    ("포도", 159, 190): ("포도", 10979, (1021, 1031, 1041), ((57, 70, 43),)),
    # Named main officers in the alternate 1021 archive family, reconciled
    # against the RTK14 stable Hanzi registry. Their old ability triples and
    # faction/role context were reviewed separately;
    # no other family's similarly named record inherits this date exception.
    ("감녕", 174, 219): ("감녕", 10594, (1021, 1031, 1041), ((93, 94, 76),)),  # 甘寧
    ("곽도", 157, 205): ("곽도", 10813, (1021, 1031, 1041), ((52, 50, 82),)),  # 郭圖
    ("마초", 176, 222): ("마초", 10945, (1021, 1031, 1041), ((93, 97, 44),)),  # 馬超
    ("문추", 169, 200): ("문추", 10387, (1021, 1031, 1041), ((89, 93, 47),)),  # 文醜
    ("방통", 178, 213): ("방통", 10998, (1021, 1031, 1041), ((85, 34, 97),)),  # 龐統
    ("장비", 165, 221): ("장비", 10357, (1021, 1031, 1041), ((94, 98, 30),)),  # 張飛
    ("제갈량", 184, 234): ("제갈량", 10748, (1021, 1031, 1041), ((98, 38, 100),)),  # 諸葛亮
    ("태사자", 166, 206): ("태사자", 10211, (1021, 1031, 1041), ((90, 93, 69),)),  # 太史慈
    ("한당", 156, 227): ("한당", 10913, (1021, 1031, 1041), ((76, 85, 61),)),  # 韓當
    ("황충", 154, 220): ("황충", 10987, (1021, 1031, 1041), ((90, 93, 65),)),  # 黃忠
    # The following source identities preserve two of three old ability values
    # and differ by at most four in the third. The exact triple and stable ID
    # distinguish their alternate archive dates from unreviewed namesakes.
    ("고담", 203, 244): ("고담", 10933, (1010, 1020, 1030, 1040, 1050, 1060,
                                   1070, 1080, 1090, 1100, 1110, 1120), ((33, 21, 69),)),  # 顧譚
    ("공손강", 172, 211): ("공손강", 10057, (1021, 1031, 1041), ((72, 66, 65),)),  # 公孫康
    ("공손공", 174, 240): ("공손공", 10058, (1021, 1031, 1041), ((39, 17, 69),)),  # 公孫恭
    ("공주", 151, 191): ("공주", 10218, (1031, 1041), ((26, 16, 68),)),  # 孔伷
    ("교모", 137, 190): ("교모", 10510, (1021, 1031, 1041), ((54, 45, 69),)),  # 橋瑁
    ("국의", 146, 197): ("국의", 10983, (1021, 1031, 1041), ((82, 78, 51),)),  # 麹義
    ("능통", 189, 217): ("능통", 10069, (1021, 1031, 1041), ((77, 89, 60),)),  # 凌統
    ("등지", 178, 251): ("등지", 10830, (1021, 1031, 1041), ((73, 52, 81),)),  # 鄧芝
    ("마철", 179, 212): ("마철", 10949, (1021, 1031, 1041), ((70, 62, 58),)),  # 馬鉄
    ("마휴", 178, 212): ("마휴", 10936, (1021, 1031, 1041), ((68, 73, 48),)),  # 馬休
    ("무안국", 156, 193): ("무안국", 10512, (1021, 1031, 1041), ((70, 83, 34),)),  # 武安国
    ("미방", 169, 226): ("미방", 10640, (1021, 1031, 1041), ((54, 61, 32),)),  # 糜芳
    ("반장", 177, 234): ("반장", 10543, (1021, 1031, 1041), ((76, 80, 74),)),  # 潘璋
    ("방희", 152, 192): ("방희", 10999, (1021, 1031, 1041), ((60, 38, 69),)),  # 龐羲
    ("범강", 174, 222): ("범강", 10667, (1021, 1031, 1041), ((49, 50, 36),)),  # 范彊
    ("사광", 175, 235): ("사광", 10184, (1021, 1031, 1041), ((58, 45, 64),)),  # 士匡
    ("사마염", 238, 290): ("사마염", 10134, (1021, 1031, 1041), ((69, 59, 77),)),  # 司馬炎
    ("사휘", 165, 227): ("사휘", 10188, (1021, 1031, 1041), ((69, 64, 44),)),  # 士徽
    ("서성", 177, 234): ("서성", 10369, CLASSIC_ARCHIVE_CODES, ((83, 76, 83),)),  # 徐盛
    ("서서", 160, 300): ("서서", 10363, (1021, 1031, 1041), ((87, 64, 93),)),  # 徐庶
    ("서성", 178, 232): ("서성", 10369, (1021, 1031, 1041), ((86, 81, 78),)),  # 徐盛
    ("성공영", 171, 220): ("성공영", 10377, (1021,), ((73, 71, 80),)),  # 成公英
    ("여개", 194, 227): ("여개", 10142, (1021, 1031, 1041), ((53, 30, 67),)),  # 呂凱
    ("여건", 173, 238): ("여건", 10154, (1021, 1031, 1041), ((57, 70, 62),)),  # 呂虔
    ("온회", 178, 228): ("온회", 10536, (1021, 1031, 1041), ((62, 36, 74),)),  # 温恢
    ("왕상", 180, 268): ("왕상", 10580, (1021, 1031, 1041), ((23, 19, 68),)),  # 王祥
    ("왕준", 206, 286): ("왕준", 10577, (1021, 1031, 1041), ((81, 73, 77),)),  # 王濬
    ("유심", 238, 264): ("유심", 10102, (1021, 1031, 1041), ((60, 62, 70),)),  # 劉諶
    ("윤직", 196, 237): ("윤직", 10038, (1021, 1031, 1041), ((46, 42, 73),)),  # 倫直
    ("이전", 181, 216): ("이전", 10439, (1021, 1031, 1041), ((78, 77, 79),)),  # 李典
    ("장개", 155, 194): ("장개", 10355, (1021, 1031, 1041), ((34, 66, 8),)),  # 張闓
    ("장달", 175, 222): ("장달", 10350, (1021, 1031, 1041), ((43, 56, 34),)),  # 張達
    ("조무", 155, 193): ("조무", 10618, (1021, 1031, 1041), ((70, 71, 66),)),  # 祖茂
    ("주거", 190, 246): ("주거", 10425, (1021, 1031, 1041), ((77, 57, 72),)),  # 朱拠
    ("주방", 200, 240): ("주방", 10178, (1021, 1031, 1041), ((52, 41, 80),)),  # 周魴
    ("진군", 167, 236): ("진군", 10884, (1021, 1031, 1041), ((32, 14, 75),)),  # 陳羣
    ("진등", 169, 208): ("진등", 10880, (1021, 1031, 1041), ((79, 64, 81),)),  # 陳登
    ("포훈", 180, 226): ("포훈", 10977, (1021, 1031, 1041), ((41, 35, 75),)),  # 鮑勛
    ("하의", 161, 196): ("하의", 10023, (1031, 1041), ((56, 69, 36),)),  # 何儀
    ("하후현", 208, 254): ("하후현", 10206, (1021, 1031, 1041), ((54, 39, 76),)),  # 夏侯玄
    ("하후혜", 206, 242): ("하후혜", 10199, (1021, 1031, 1041), ((50, 44, 76),)),  # 夏侯恵
    ("하후화", 207, 265): ("하후화", 10194, (1021, 1031, 1041), ((48, 51, 71),)),  # 夏侯和
    ("한섬", 159, 197): ("한섬", 10917, (1041,), ((69, 66, 36),)),  # 韓暹
    ("호진", 148, 190): ("호진", 10660, (1021, 1031, 1041), ((65, 74, 12),)),  # 胡軫
    # Officers already bound as rulers in other years keep that same stable ID
    # when the archive makes them non-ruling officers in these exact scenarios.
    ("장양1", 150, 199): ("장양", 10322,
                          (1010, 1050, 1060, 1070, 1080, 1090, 1100, 1110),
                          ((62, 66, 65),)),  # 張楊: same archive profile as the 1020 ruler
    ("유요", 156, 195): ("유요", 10099,
                          (1010, 1020, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                          ((23, 22, 48),)),  # 劉繇: same archived identity/profile as the 1030 ruler
    ("장로", 163, 237): ("장로", 10358, (1010, 1090, 1100, 1110),
                         ((76, 44, 80),)),  # 張魯: same archived identity/profile as the later ruler
    ("엄백호", 150, 197): ("엄백호", 10118,
                           (1010, 1020, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                           ((48, 68, 30),)),  # 嚴白虎: same archived identity/profile as the 1030 ruler
    ("장각", 140, 185): ("장각", 10348,
                         (1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((93, 25, 93),)),  # 張角: same archived identity/profile as the 1010 ruler
    ("한복", 149, 193): ("한복", 10929, (1031, 1041),
                         ((28, 3, 27),)),  # 韓馥: same archived identity/profile as the 1021 ruler
    ("유요", 156, 198): ("유요", 10099, (1021, 1041),
                         ((62, 67, 46),)),  # 劉繇: same archived identity/profile as the 1031 ruler
    # The alternate 1021/1031/1041 archive family already carries these exact
    # RTK14 dates and stable Hanzi IDs. Pin the older family by its distinct
    # date and three-ability fingerprint instead of inheriting by name.
    ("강단", 168, 230): ("강단", 10359,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((41, 73, 43),)),  # 強端
    ("목순", 157, 191): ("목순", 10632,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((17, 21, 68),)),  # 穆順
    ("장보", 148, 185): ("장보", 10308,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((78, 81, 76),)),  # 張宝, not 張苞
    ("조홍1", 156, 185): ("조홍1", 10784, CLASSIC_ARCHIVE_CODES, ((52, 66, 42),)),  # 黃巾 趙弘
    ("조무", 155, 191): ("조무", 10618, CLASSIC_ARCHIVE_CODES, ((71, 68, 71),)),  # 祖茂
    # Further exact archive profiles matched to stable RTK14 Hanzi IDs with
    # close date estimates. Same-name records outside these scenario lists remain
    # unresolved until independently reviewed.
    ("고승", 145, 185): ("고승", 10960,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((42, 73, 24),)),  # 高昇: same 1010 黃巾 roster identity in later campaigns
    ("동백", 173, 192): ("동백", 10691, (1021, 1031, 1041), ((12, 7, 52),)),  # 董白
    ("등무", 147, 185): ("등무", 10831,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((43, 74, 19),)),  # 鄧茂: same 1010 黃巾 roster identity in later campaigns
    ("마량", 187, 225): ("마량", 10943, CLASSIC_ARCHIVE_CODES, ((57, 25, 87),)),  # 馬良
    ("맹획", 186, 245): ("맹획", 10224,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1110, 1120),
                         ((78, 92, 50),)),  # 孟獲: 1100 ruler is checked by the separate ruler binding
    ("무안국", 156, 191): ("무안국", 10512, CLASSIC_ARCHIVE_CODES, ((51, 73, 18),)),  # 武安国
    ("반봉", 155, 191): ("반봉", 10545, CLASSIC_ARCHIVE_CODES, ((61, 75, 17),)),  # 潘鳳
    ("번능", 158, 194): ("번능", 10509, CLASSIC_ARCHIVE_CODES, ((70, 61, 47),)),  # 樊能
    ("사지", 163, 227): ("사지", 10191, (1021, 1031, 1041), ((58, 51, 52),)),  # 士祗
    ("서영", 147, 191): ("서영", 10365, CLASSIC_ARCHIVE_CODES, ((47, 63, 33),)),  # 徐栄
    ("서영", 147, 192): ("서영", 10365, (1021, 1031, 1041), ((80, 76, 62),)),
    ("성렴", 168, 199): ("성렴", 10379, (1021, 1031, 1041), ((66, 72, 43),)),  # 成廉
    ("손중", 154, 185): ("손중", 10229,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((53, 63, 24),)),  # 孫仲: same 1010 黃巾 roster identity in later campaigns
    ("염포", 167, 231): ("염포", 10857, (1021, 1031, 1041), ((29, 25, 82),)),  # 閻圃
    ("위월", 170, 199): ("위월", 10971, (1021, 1031, 1041), ((68, 76, 39),)),  # 魏越
    ("유기", 174, 210): ("유기", 10091, (1021, 1031, 1041), ((52, 12, 63),)),  # 劉琦
    ("유종", 191, 208): ("유종", 10092, CLASSIC_ARCHIVE_CODES, ((22, 26, 61),)),  # 劉琮
    ("이엄", 172, 234): ("이엄", 10441, (1010, 1020, 1030, 1040, 1050, 1060,
                                   1070, 1080, 1090, 1100, 1110, 1120), ((80, 84, 81),)),  # 李厳
    ("장영", 154, 196): ("장영", 10342, (1031, 1041), ((75, 74, 41),)),  # 張英
    ("정원", 137, 190): ("정원", 10002, CLASSIC_ARCHIVE_CODES, ((64, 77, 58),)),  # 丁原
    ("정원지", 145, 185): ("정원지", 10630,
                           (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                           ((41, 74, 38),)),  # 程遠志: same 1010 黃巾 roster identity in later campaigns
    ("송겸", 175, 215): ("송겸", 10272, CLASSIC_ARCHIVE_CODES, ((61, 48, 44),)),  # 宋謙
    ("엄여", 153, 197): ("엄여", 10120, CLASSIC_ARCHIVE_CODES, ((35, 66, 24),)),  # 厳輿
    ("주앙", 162, 195): ("주앙", 10170,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((75, 64, 64),)),  # 周昂: alternate family has its own fingerprint below
    ("진횡", 161, 196): ("진횡", 10875, (1031, 1041), ((64, 63, 26),)),  # 陳横
    ("착융", 161, 194): ("착융", 10633, CLASSIC_ARCHIVE_CODES, ((62, 59, 21),)),  # 笮融
    ("착융", 161, 196): ("착융", 10633, (1031, 1041), ((60, 69, 38),)),
    ("채모", 155, 208): ("채모", 10711, CLASSIC_ARCHIVE_CODES, ((79, 69, 68),)),  # 蔡瑁
    ("채모", 160, 215): ("채모", 10711, (1021, 1031, 1041), ((70, 70, 77),)),  # 蔡瑁
    ("대교", 177, 235): ("대교", 10209, CLASSIC_ARCHIVE_CODES, ((42, 10, 54),)),  # 大喬
    ("대교", 177, 215): ("대교", 10209, (1021, 1031, 1041), ((33, 30, 66),)),  # 大喬
    ("손상향", 193, 244): ("손상향", 10238, CLASSIC_ARCHIVE_CODES, ((72, 62, 42),)),  # 孫尚香
    ("채염", 168, 237): ("채염", 10710, CLASSIC_ARCHIVE_CODES, ((40, 22, 64),)),  # 蔡琰
    ("채염", 168, 224): ("채염", 10710, (1021, 1031, 1041), ((53, 14, 80),)),  # 蔡琰
    ("초선", 176, 211): ("초선", 10764, CLASSIC_ARCHIVE_CODES, ((66, 15, 72),)),  # 貂蝉
    ("초선", 176, 230): ("초선", 10764, (1021, 1031, 1041), ((42, 18, 81),)),  # 貂蝉
    ("하후씨", 186, 249): ("하후씨", 10203, CLASSIC_ARCHIVE_CODES, ((29, 16, 47),)),  # 夏侯氏
    ("번씨", 176, 220): ("번씨", 10507, CLASSIC_ARCHIVE_CODES, ((32, 17, 45),)),  # 樊氏
    ("번씨", 183, 225): ("번씨", 10507, (1021, 1031, 1041), ((6, 12, 50),)),  # 樊氏
    ("추씨", 165, 225): ("추씨", 10824, CLASSIC_ARCHIVE_CODES, ((36, 13, 54),)),  # 鄒氏
    ("추씨", 165, 200): ("추씨", 10824, (1021, 1031, 1041), ((4, 9, 66),)),  # 鄒氏
    ("교모", 150, 191): ("교모", 10510, CLASSIC_ARCHIVE_CODES, ((59, 58, 61),)),  # 橋瑁
    ("소교", 178, 218): ("소교", 10278, CLASSIC_ARCHIVE_CODES, ((57, 23, 66),)),  # 小喬
    ("소교", 178, 212): ("소교", 10278, (1021, 1031, 1041), ((40, 3, 70),)),  # 小喬
    ("오국태", 161, 222): ("오국태", 10156, CLASSIC_ARCHIVE_CODES, ((31, 11, 60),)),  # 呉国太
    ("배수", 223, 271): ("배수", 10739, CLASSIC_ARCHIVE_CODES, ((10, 11, 77),)),  # 裴秀
    # Alternate 1021 archive dates differ slightly from the already reviewed
    # classic records. Each old three-ability fingerprint remains exact.
    ("가화", 176, 222): ("가화", 10775, ALTERNATE_ARCHIVE_CODES, ((49, 65, 41),)),  # 賈華
    ("곽마", 239, 281): ("곽마", 10821, ALTERNATE_ARCHIVE_CODES, ((68, 71, 40),)),  # 郭馬
    ("강단", 168, 234): ("강단", 10359, ALTERNATE_ARCHIVE_CODES, ((65, 83, 48),)),  # 強端
    ("이통", 168, 209): ("이통", 10460, ALTERNATE_ARCHIVE_CODES, ((73, 83, 62),)),  # 李通
    ("마막", 221, 266): ("마막", 10947, ALTERNATE_ARCHIVE_CODES, ((34, 12, 44),)),  # 馬邈
    ("곽혁", 187, 225): ("곽혁", 10814, ALTERNATE_ARCHIVE_CODES, ((33, 29, 70),)),  # 郭奕
    ("순의", 207, 274): ("순의", 10677, ALTERNATE_ARCHIVE_CODES, ((16, 13, 72),)),  # 荀顗
    ("조충", 196, 207): ("조충", 10408, ALTERNATE_ARCHIVE_CODES, ((31, 21, 85),)),  # 曹沖
    ("부사인", 182, 225): ("부사인", 10041, ALTERNATE_ARCHIVE_CODES, ((46, 63, 37),)),  # 傅士仁
    ("요화", 168, 264): ("요화", 10299, ALTERNATE_ARCHIVE_CODES, ((73, 76, 69),)),  # 廖化
    ("염행", 159, 215): ("염행", 10861, ALTERNATE_ARCHIVE_CODES, ((76, 84, 66),)),  # 閻行
    ("한현", 163, 220): ("한현", 10921, ALTERNATE_ARCHIVE_CODES, ((22, 33, 62),)),  # 韓玄
    ("한호", 164, 224): ("한호", 10919, ALTERNATE_ARCHIVE_CODES, ((69, 72, 84),)),  # 韓浩
    ("오경", 151, 211): ("오경", 10160, ALTERNATE_ARCHIVE_CODES, ((69, 66, 52),)),  # 呉景
    ("장흠", 159, 208): ("장흠", 10697, ALTERNATE_ARCHIVE_CODES, ((78, 84, 54),)),  # 蒋欽
    ("장위", 172, 228): ("장위", 10346, ALTERNATE_ARCHIVE_CODES, ((74, 66, 45),)),  # 張衛
    ("유종", 195, 220): ("유종", 10092, ALTERNATE_ARCHIVE_CODES, ((24, 22, 65),)),  # 劉琮
    ("진표", 207, 237): ("진표", 10887, ALTERNATE_ARCHIVE_CODES, ((66, 42, 74),)),  # 陳表
    ("뇌서", 170, 212): ("뇌서", 10904, ALTERNATE_ARCHIVE_CODES, ((68, 65, 34),)),  # 雷緒
    ("마륭", 231, 296): ("마륭", 10950, ALTERNATE_ARCHIVE_CODES, ((84, 75, 76),)),  # 馬隆
    ("손노반", 202, 258): ("손노반", 10268, ALTERNATE_ARCHIVE_CODES, ((17, 15, 80),)),  # 孫魯班
    ("손노육", 204, 253): ("손노육", 10269, ALTERNATE_ARCHIVE_CODES, ((52, 10, 66),)),  # 孫魯育
    ("사환", 173, 209): ("사환", 10124, ALTERNATE_ARCHIVE_CODES, ((70, 71, 45),)),  # 史渙
    ("위강", 168, 213): ("위강", 10910, ALTERNATE_ARCHIVE_CODES, ((58, 35, 56),)),  # 韋康
    ("장홍", 164, 196): ("장홍", 10664, ALTERNATE_ARCHIVE_CODES, ((72, 81, 65),)),  # 臧洪
    ("왕필", 170, 228): ("왕필", 10564, ALTERNATE_ARCHIVE_CODES, ((53, 44, 59),)),  # 王必
    ("왕이", 171, 230): ("왕이", 10579, ALTERNATE_ARCHIVE_CODES, ((87, 59, 77),)),  # 王異
    ("습정", 181, 229): ("습정", 10647, ALTERNATE_ARCHIVE_CODES, ((49, 29, 70),)),  # 習禎
    ("조앙", 161, 197): ("조앙", 10406, ALTERNATE_ARCHIVE_CODES, ((74, 67, 69),)),  # 曹昂
    ("진취", 174, 208): ("진취", 10872, (1021, 1031), ((56, 63, 45),)),  # 陳就
    ("채씨", 159, 212): ("채씨", 10709, ALTERNATE_ARCHIVE_CODES, ((38, 7, 70),)),  # 蔡氏
    ("토안", 191, 233): ("토안", 10183, ALTERNATE_ARCHIVE_CODES, ((53, 82, 30),)),  # 土安
    ("해니", 191, 233): ("해니", 10212, ALTERNATE_ARCHIVE_CODES, ((62, 78, 35),)),  # 奚泥
    ("정비", 158, 214): ("정비", 10006, ALTERNATE_ARCHIVE_CODES, ((34, 30, 57),)),  # 丁斐
    ("허사", 163, 199): ("허사", 10744, ALTERNATE_ARCHIVE_CODES, ((24, 20, 60),)),  # 許汜
    ("문앙", 238, 291): ("문앙", 10388, ALTERNATE_ARCHIVE_CODES, ((76, 93, 65),)),  # 文鴦
    ("원윤", 150, 199): ("원윤", 10733, ALTERNATE_ARCHIVE_CODES, ((15, 14, 39),)),  # 袁胤
    ("장간", 175, 208): ("장간", 10695, ALTERNATE_ARCHIVE_CODES, ((19, 6, 66),)),  # 蒋幹
    ("장춘화", 179, 234): ("장춘화", 10317, ALTERNATE_ARCHIVE_CODES, ((66, 29, 77),)),  # 張春華
    ("화만", 191, 233): ("화만", 10666, ALTERNATE_ARCHIVE_CODES, ((85, 92, 40),)),  # 花鬘
    ("유선", 208, 270): ("유선2", 10098, ALTERNATE_ARCHIVE_CODES, ((3, 5, 9),)),  # 劉禅, not 劉宣 or 劉璿
    ("장포", 198, 219): ("장포1", 10341, ALTERNATE_ARCHIVE_CODES, ((78, 87, 48),)),  # 張苞, not 吳 張布
    ("학맹", 156, 196): ("학맹", 10810, (1041,), ((62, 67, 42),)),  # 郝萌, unlike 300-sentinel rows
    ("유대", 155, 202): ("유대", 10079, ALTERNATE_ARCHIVE_CODES, ((63, 58, 30),)),  # 劉岱, not 유대연주
    ("손기", 227, 276): ("손기", 10231, CLASSIC_ARCHIVE_CODES, ((62, 65, 52),)),  # 孫冀
    ("유략", 206, 260): ("유략", 10605, CLASSIC_ARCHIVE_CODES, ((72, 68, 59),)),  # 留略
    ("유벽", 168, 210): ("유벽", 10105, CLASSIC_ARCHIVE_CODES, ((63, 71, 23),)),  # 劉辟
    # Three 李豊 identities coexist in this archive family. The other two are
    # pinned above by their exact 158/199 and 204/254 lives; this remaining
    # 206/260 profile binds only the distinct RTK14 10456 record.
    ("이풍1", 206, 260): ("이풍1", 10456, CLASSIC_ARCHIVE_CODES, ((59, 56, 62),)),  # 李豊
    ("추단", 148, 193): ("추단", 10823, CLASSIC_ARCHIVE_CODES, ((63, 71, 36),)),  # 鄒丹
    ("하후위", 204, 254): ("하후위", 10195, CLASSIC_ARCHIVE_CODES, ((73, 76, 71),)),  # 夏侯威
    ("한충", 151, 185): ("한충", 10916,
                         (1010, 1020, 1030, 1040, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120),
                         ((41, 66, 29),)),  # 韓忠: same 1010 黃巾 roster identity in later campaigns
    ("화웅", 155, 191): ("화웅", 10680, CLASSIC_ARCHIVE_CODES, ((68, 88, 24),)),  # 華雄
    ("희지재", 157, 196): ("희지재", 10380, (1021, 1031, 1041), ((63, 7, 88),)),  # 戯志才
}
# These identities have a second, independently reviewed archive fingerprint
# in the alternate 1021 family. Include the fingerprint in the key so neither
# family's allowlist can silently replace or admit the other's profile.
REVIEWED_RTK14_PROFILE_VARIANTS = {
    # These source-game identities also require the archived roster group.
    ("단경", 156, 199, (68, 61, 68)): ("선경", 10112, CLASSIC_ARCHIVE_CODES, "general"),  # 単経
    ("진복", 160, 226, (36, 27, 76)): ("진밀", 10620, CLASSIC_ARCHIVE_CODES, "general"),  # 秦宓
    ("진복", 160, 226, (31, 7, 73)): ("진밀", 10620, ALTERNATE_ARCHIVE_CODES, "general"),  # 秦宓
    ("휴고", 151, 199, (61, 72, 40)): ("수고", 10614, CLASSIC_ARCHIVE_CODES, "general_ex"),  # 眭固
    ("휴고", 151, 199, (63, 71, 38)): ("수고", 10614, ALTERNATE_ARCHIVE_CODES, "general"),  # 眭固
    # Reviewed archive spellings reuse existing RTK14 portraits only in the
    # exact source family, years and three-ability fingerprint below.
    ("루반", 178, 207, (65, 76, 39)): ("누반", 10502, ALTERNATE_ARCHIVE_CODES),  # 楼班
    ("반임", 168, 225, (66, 79, 38)): ("반림", 10544, ALTERNATE_ARCHIVE_CODES),  # 潘臨
    ("곽씨", 184, 235, (42, 4, 55)): ("곽여왕", 10815, ALTERNATE_ARCHIVE_CODES),  # 郭女王
    ("장량", 153, 184, (78, 80, 74)): ("장양1", 10321, ALTERNATE_ARCHIVE_CODES),  # 張梁
    ("부동", 183, 222, (58, 69, 69)): ("부융", 10045, CLASSIC_ARCHIVE_CODES),  # 傅彤
    ("이엄", 172, 234, (83, 84, 76)): ("이엄", 10441, (1021, 1031, 1041)),  # 李厳
    ("주앙", 162, 195, (74, 65, 65)): ("주앙", 10170, (1021, 1031, 1041)),  # 周昂
    # Same archive name and years, with an independently reviewed classic
    # family fingerprint. The alternate family keeps its own old profile.
    ("고정", 190, 251, (67, 65, 55)): ("고정", 10958, CLASSIC_ARCHIVE_CODES),  # 高定
    ("사광", 175, 235, (57, 49, 66)): ("사광", 10184, CLASSIC_ARCHIVE_CODES),  # 士匡
    ("사일", 153, 230, (59, 44, 68)): ("사일", 10185, CLASSIC_ARCHIVE_CODES),  # 士壱
    ("사지", 163, 227, (61, 49, 70)): ("사지", 10191, CLASSIC_ARCHIVE_CODES),  # 士祗
    ("사휘", 165, 227, (67, 71, 61)): ("사휘", 10188, CLASSIC_ARCHIVE_CODES),  # 士徽
    ("여개", 194, 227, (51, 42, 67)): ("여개", 10142, CLASSIC_ARCHIVE_CODES),  # 呂凱
    ("여건", 173, 238, (44, 68, 29)): ("여건", 10154, CLASSIC_ARCHIVE_CODES),  # 呂虔
    ("왕상", 180, 268, (25, 19, 65)): ("왕상", 10580, CLASSIC_ARCHIVE_CODES),  # 王祥
    ("주거", 190, 246, (73, 71, 72)): ("주거", 10425, CLASSIC_ARCHIVE_CODES),  # 朱拠
    ("주방", 200, 240, (56, 36, 76)): ("주방", 10178, CLASSIC_ARCHIVE_CODES),  # 周魴
    ("하후현", 208, 254, (57, 23, 75)): ("하후현", 10206, CLASSIC_ARCHIVE_CODES),  # 夏侯玄
    ("하후혜", 206, 242, (76, 66, 78)): ("하후혜", 10199, CLASSIC_ARCHIVE_CODES),  # 夏侯恵
    ("하후화", 207, 265, (77, 61, 80)): ("하후화", 10194, CLASSIC_ARCHIVE_CODES),  # 夏侯和
    ("장휴", 204, 244, (42, 35, 70)): ("장휴", 10302, CLASSIC_ARCHIVE_CODES),  # 張休
    ("장휴", 204, 244, (63, 27, 74)): ("장휴", 10302, (1021, 1031, 1041)),  # 張休
    ("옹개", 188, 225, (58, 67, 51)): ("옹개", 10902, CLASSIC_ARCHIVE_CODES),  # 雍闓
    ("옹개", 188, 225, (73, 75, 57)): ("옹개", 10902, (1021, 1031, 1041)),  # 雍闓
    ("주포", 191, 225, (59, 72, 12)): ("주포", 10432, CLASSIC_ARCHIVE_CODES),  # 朱褒
    ("주포", 191, 225, (68, 73, 34)): ("주포", 10432, (1021, 1031, 1041)),  # 朱褒
    ("미당대왕", 202, 260, (64, 75, 32)): ("미당대왕", 10803, CLASSIC_ARCHIVE_CODES),  # 迷当大王
    ("미당대왕", 202, 260, (59, 69, 21)): ("미당대왕", 10803, (1021, 1031, 1041)),  # 迷当大王
    ("유찬", 172, 255, (74, 75, 66)): ("유찬", 10606, CLASSIC_ARCHIVE_CODES),  # 留賛
    ("유찬", 172, 255, (78, 81, 64)): ("유찬", 10606, (1021, 1031, 1041)),  # 留賛
    ("맹획", 186, 245, (87, 87, 42)): ("맹획", 10224, ALTERNATE_ARCHIVE_CODES),  # 孟獲
    ("고담", 203, 244, (53, 23, 77)): ("고담", 10933, ALTERNATE_ARCHIVE_CODES),  # 顧譚
    ("진취", 174, 208, (56, 65, 48)): ("진취", 10872, (1041,)),  # 陳就
}
ARCHIVE = Path("data/archive/scenarios")
CLAIMS = Path("data/curated/han/scenario-province-claims-v1.json")
MAP = Path("infra/src/main/resources/map/han-world-v3.json")
PACKAGED = Path("infra/src/main/resources/scenario")


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def inputs(root: Path = ROOT) -> tuple[set[int], dict]:
    claims = load(root / CLAIMS)
    if tuple(claims["activeScenarioCodes"]) != ACTIVE_CODES:
        raise ValueError("reviewed active scenario list changed; adjudicate before materializing")
    city_ids = {city["id"] for city in load(root / MAP)["cities"]}
    template = load(root / PACKAGED / "scenario_990002.json")["warehouses"]
    county_ids = {row["countyId"] for row in template["warehouses"]}
    if len(county_ids) != len(template["warehouses"]) or not county_ids <= city_ids:
        raise ValueError("warehouse template does not match the packaged map")
    return city_ids, template


def roster_rows(source: dict) -> list[list]:
    return source["general"] + source.get("general_ex", []) + source.get("general_neutral", [])


def reviewed_rtk14_binding(source_row: list, rtk14: dict,
                           scenario_code: int | None = None,
                           roster_group: str | None = None) -> tuple[list, dict] | None:
    """Bind exact years or a specifically reviewed ruler in an allowed scenario.

    The archive's three abilities are a different game's values; the caller must
    replace all five abilities with the reviewed RTK14 values when using this
    binding. No same-name or near-year fallback is permitted. A reviewed date
    variant keeps the archive years; only its target stats and portrait change.
    A profile with a fourth field requires that exact source roster group.
    """
    name, birth, death = source_row[1], source_row[9], source_row[10]
    ruler_variant = REVIEWED_RTK14_YEAR_VARIANTS.get((name, birth, death))
    roster_variant = REVIEWED_RTK14_ROSTER_VARIANTS.get((name, birth, death))
    profile_variant = REVIEWED_RTK14_PROFILE_VARIANTS.get(
        (name, birth, death, tuple(source_row[5:8])))
    if ruler_variant is not None and scenario_code in ruler_variant[2] and source_row[8] == 12:
        target_name, officer_id, scenarios = ruler_variant
    elif profile_variant is not None and scenario_code in profile_variant[2]:
        target_name, officer_id, scenarios = profile_variant[:3]
        if len(profile_variant) == 4 and roster_group != profile_variant[3]:
            return None
    elif roster_variant is not None and scenario_code in roster_variant[2]:
        target_name, officer_id, scenarios, stat_profiles = roster_variant
        if tuple(source_row[5:8]) not in stat_profiles:
            return None
    elif ruler_variant is not None or roster_variant is not None or profile_variant is not None:
        return None
    else:
        target_name = REVIEWED_RTK14_ALIASES.get((name, birth, death), name)
    target_row = rtk14["people"].get(target_name)
    policy = rtk14["policies"].get(target_name)
    if target_row is None or policy is None:
        return None
    if target_row[2] != f"{policy['officerId']}.png":
        return None
    if ruler_variant is not None or roster_variant is not None or profile_variant is not None:
        if policy["officerId"] != officer_id:
            return None
    elif target_row[9:11] != [birth, death]:
        return None
    return target_row, policy


def materialize_reviewed_person(source_row: list, binding: tuple[list, dict]) -> tuple[list, dict]:
    """Carry the archive's identity and affiliation, but use all five RTK14 abilities."""
    if len(source_row) not in (13, 14):
        raise ValueError(f"legacy person tuple for {source_row[1]} changed")
    target_row, policy = binding
    converted = copy.deepcopy(source_row)
    converted[2] = target_row[2]  # stable portrait identity required by person policy
    converted[5:8] = target_row[5:8]
    if len(converted) == 13:
        converted.append(None)  # optional archive biography text
    converted.extend([target_row[14], target_row[15]])
    declaration = copy.deepcopy(policy)
    declaration["name"] = source_row[1]
    return converted, declaration


def project_reviewed_roster(code: int, source: dict, rtk14: dict) -> tuple[dict, list[tuple[str, int, int]]]:
    """Project only source-bound people; report every unresolved archive identity.

    This is an intermediate source review, not a playable seed. The caller must
    resolve the remaining people before attaching policies to packaged worlds.
    """
    if code not in ACTIVE_CODES:
        raise ValueError(f"scenario {code} is not in the reviewed playable set")
    projected = copy.deepcopy(source)
    index = {
        "people": {row[1]: row for row in rtk14["general"]},
        "policies": {row["name"]: row for row in rtk14["personPolicies"]},
    }
    policies = []
    unresolved = []
    officer_ids = set()
    for group in ("general", "general_ex", "general_neutral"):
        converted_rows = []
        for row in source.get(group, []):
            binding = reviewed_rtk14_binding(row, index, code, group)
            if binding is None:
                unresolved.append((row[1], row[9], row[10]))
                converted_rows.append(copy.deepcopy(row))
                continue
            converted, declaration = materialize_reviewed_person(row, binding)
            officer_id = declaration["officerId"]
            if officer_id in officer_ids:
                raise ValueError(f"scenario {code} duplicates reviewed officer {officer_id}")
            officer_ids.add(officer_id)
            converted_rows.append(converted)
            policies.append(declaration)
        if group in source:
            projected[group] = converted_rows
    projected["personPolicies"] = policies
    return projected, unresolved


def ruler_for(code: int, nation: list, nation_id: int, roster: list[list]) -> str:
    candidates = [row[1] for row in roster if row[3] == nation_id and row[8] == 12]
    override = LORD_NAME_OVERRIDES.get((code, nation[0]))
    if override is not None:
        if candidates or sum(row[3] == nation_id and row[1] == override for row in roster) != 1:
            raise ValueError(f"{code} {nation[0]} ruler override no longer matches the archive")
        return override
    if len(candidates) != 1:
        raise ValueError(f"{code} {nation[0]} needs one reviewed ruler, got {candidates}")
    return candidates[0]


def materialize(code: int, source: dict, city_ids: set[int], warehouse_template: dict) -> dict:
    if code not in ACTIVE_CODES:
        raise ValueError(f"scenario {code} is not in the reviewed playable set")
    if source.get("worldFormat") is not None or source.get("rulers") is not None:
        raise ValueError(f"scenario {code} archive is no longer the legacy input format")
    if source.get("map", {}).get("mapName") != "han-world-v3":
        raise ValueError(f"scenario {code} map needs a separate reviewed projection")
    result = copy.deepcopy(source)
    nations = result["nation"]
    roster = roster_rows(result)
    names = [row[1] for row in roster]
    if len(names) != len(set(names)):
        raise ValueError(f"scenario {code} has duplicate officer names")

    owner_by_city: dict[int, int] = {}
    for nation_id, nation in enumerate(nations, start=1):
        for city_id in nation[8]:
            if not isinstance(city_id, int) or city_id not in city_ids:
                raise ValueError(f"scenario {code} has invalid county {city_id}")
            if city_id in owner_by_city:
                raise ValueError(f"scenario {code} gives county {city_id} two owners")
            owner_by_city[city_id] = nation_id
    for row in roster:
        if not isinstance(row[3], int) or not 0 <= row[3] <= len(nations):
            raise ValueError(f"scenario {code} has invalid affiliation for {row[1]}")
        if row[4] is not None and row[4] not in city_ids:
            raise ValueError(f"scenario {code} has invalid location for {row[1]}")

    lords = [ruler_for(code, nation, nation_id, roster)
             for nation_id, nation in enumerate(nations, start=1)]
    lord_by_nation = dict(enumerate(lords, start=1))
    result["worldFormat"] = "GENERAL_RETAINER_CAMPAIGN"
    result["lords"] = lords
    result["rulers"] = [dict(nation=nation[0], general=lord_by_nation[nation_id])
                         for nation_id, nation in enumerate(nations, start=1)]
    result["retainers"] = [dict(general=row[1], master=lord_by_nation[row[3]])
                           for row in roster if row[3] > 0 and row[1] != lord_by_nation[row[3]]]

    warehouses = copy.deepcopy(warehouse_template)
    stock_by_city = {row["countyId"]: row["stock"] for row in warehouses["warehouses"]}
    for stock in stock_by_city.values():
        for resource in stock:
            stock[resource] = 0
    for nation in nations:
        gold, rice = nation[2:4]
        if not isinstance(gold, int) or not isinstance(rice, int) or gold < 0 or rice < 0:
            raise ValueError(f"scenario {code} has invalid initial treasury for {nation[0]}")
        if nation[8]:
            capital = nation[8][0]
            if capital not in stock_by_city:
                raise ValueError(f"scenario {code} capital {capital} has no county warehouse")
            stock_by_city[capital]["money"] += gold
            stock_by_city[capital]["grain"] += rice
            nation[2:4] = [0, 0]
        # A landless faction has no county stock or capital. Keep its source national
        # treasury; inventing a host county would change the reviewed scenario.
    result["warehouses"] = warehouses
    return result


def prepared_materialization(root: Path = ROOT) -> tuple[dict[int, str], dict[int, list[tuple[str, int, int]]], dict[int, list[str]]]:
    """Review every candidate in memory, including confirmed five-stat policies."""
    city_ids, template = inputs(root)
    rtk14 = load(root / PACKAGED / "scenario_3190.json")
    outputs = {}
    unresolved_by_code = {}
    missing_rulers_by_code = {}
    for code in ACTIVE_CODES:
        placed = materialize(code, load(root / ARCHIVE / f"scenario_{code}.json"), city_ids, template)
        reviewed, unresolved = project_reviewed_roster(code, placed, rtk14)
        policy_names = {policy["name"] for policy in reviewed["personPolicies"]}
        missing_rulers = [name for name in placed["lords"] if name not in policy_names]
        outputs[code] = render(reviewed)
        if unresolved:
            unresolved_by_code[code] = unresolved
        if missing_rulers:
            missing_rulers_by_code[code] = missing_rulers
    return outputs, unresolved_by_code, missing_rulers_by_code


def materialized(root: Path = ROOT) -> dict[int, str]:
    outputs, unresolved, missing_rulers = prepared_materialization(root)
    if missing_rulers:
        raise ValueError(f"unreviewed ruler policies: {missing_rulers}")
    if unresolved:
        raise ValueError(f"unresolved historical identities: "
                         f"{ {code: len(rows) for code, rows in unresolved.items()} }")
    return outputs


def main(argv: list[str] | None = None, root: Path = ROOT) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true")
    group.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    # Complete all source and identity checks before replacing the first file.
    try:
        expected = materialized(root)
    except (ValueError, FileNotFoundError) as error:
        raise SystemExit(f"historical HWIHA seeds not materialized: {error}") from error
    for code, text in expected.items():
        target = root / PACKAGED / f"scenario_{code}.json"
        if args.write:
            target.write_text(text, encoding="utf-8")
        elif not target.is_file() or target.read_text(encoding="utf-8") != text:
            raise SystemExit(f"packaged scenario_{code}.json differs from the reviewed materialization")
    print(f"{len(expected)} historical HWIHA scenarios {'written' if args.write else 'verified'}")


if __name__ == "__main__":
    main()
