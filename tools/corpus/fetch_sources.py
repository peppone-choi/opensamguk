#!/usr/bin/env python3
"""zh.wikisource(PD) 사료 코퍼스 수집기.

받은 원문은 `data/corpus/` 로 떨어지며 **git-ignore·미커밋**이다(ADR-LITE-039 와 동일한 격리).
커밋되는 것은 이 스크립트뿐이다. 원문은 모두 public domain 이지만 저장소를 사료 배포처로
쓰지 않는다는 방침이며, 이 스크립트로 언제든 재현된다.

주의 — urllib 기본 요청은 403 이다. User-Agent 를 반드시 보낸다.

용법:  python3 tools/corpus/fetch_sources.py [--jobs 4]
"""
import argparse, os, sys, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

OUT = os.environ.get('SHILIAO_HOME') or (
    'data/corpus' if os.path.isdir('data') else os.path.expanduser('~/.shiliao'))
UA = 'opensamguk-research/1.0 (contact: sangchis2@gmail.com)'


def titles():
    """(위키소스 제목, 저장 파일명). 제목 형식은 실제 확인한 것만 쓴다.

    後漢書 는 이 판본에서 紀10 + 列傳80 = 卷1~90, 續漢書 志30 = 卷91~120 이다.
    즉 郡國志 = 卷109~113, 百官志 = 卷114~118 로 卷 번호만으로 전부 받힌다.
    卷1~10 은 上/下 분권이 있어 세 형태를 모두 시도한다(없는 것은 그냥 실패).
    """
    for i in range(1, 66):
        yield f'三國志/卷{i:02d}', f'sgz-{i:02d}.txt'
    for i in range(1, 121):
        yield f'後漢書/卷{i}', f'hhs-{i:03d}.txt'
        yield f'後漢書/卷{i}上', f'hhs-{i:03d}A.txt'
        yield f'後漢書/卷{i}下', f'hhs-{i:03d}B.txt'
    for i in range(1, 121):
        yield f'三國演義/第{i:03d}回', f'yy-{i:03d}.txt'
    for i in '一 二 三 四 五 六 七 八 九 十 十一 十二'.split():
        yield f'華陽國志/卷{i}', f'hyg-{i}.txt'
    for i in range(1, 131):
        yield f'晉書/卷{i:03d}', f'js-{i:03d}.txt'
        yield f'晉書/卷{i}', f'js2-{i:03d}.txt'
    for i in range(49, 121):                     # 후한 말~삼국~서진. 편년의 정본.
        yield f'資治通鑑/卷{i:03d}', f'zztj-{i:03d}.txt'
    for i in range(1, 41):
        yield f'元和郡縣圖志/卷{i}', f'yhjx-{i:02d}.txt'
    for i in range(1, 101):
        yield f'宋書/卷{i}', f'ss-{i:03d}.txt'
    for i in ('德行 言語 政事 文學 方正 雅量 識鑒 賞譽 品藻 規箴 捷悟 夙惠 豪爽 容止 自新 企羨 '
              '傷逝 棲逸 賢媛 術解 巧藝 寵禮 任誕 簡傲 排調 輕詆 假譎 黜免 儉嗇 汰侈 忿狷 讒險 '
              '尤悔 紕漏 惑溺 仇隙').split():
        yield f'世說新語/{i}', f'ssxy-{i}.txt'


def fetch(job):
    title, name = job
    path = os.path.join(OUT, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return None
    url = 'https://zh.wikisource.org/w/index.php?' + urllib.parse.urlencode(
        {'action': 'raw', 'title': title})
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                body = r.read()
            if not body:
                return None
            with open(path, 'wb') as f:
                f.write(body)
            return name
        except Exception:
            time.sleep(1 + attempt)          # 병렬도를 올리면 조용히 throttle 된다. 재시도로 흡수.
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jobs', type=int, default=4,
                    help='병렬도. 8 이상이면 위키소스가 조용히 빈 응답을 준다.')
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    jobs = list(titles())
    with ThreadPoolExecutor(args.jobs) as ex:
        got = [n for n in ex.map(fetch, jobs) if n]
    have = len([f for f in os.listdir(OUT) if f.endswith('.txt')])
    print(f'요청 {len(jobs)} · 이번에 받음 {len(got)} · 보유 {have}', file=sys.stderr)


if __name__ == '__main__':
    main()
