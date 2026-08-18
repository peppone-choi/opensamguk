#!/usr/bin/env python3
"""사료 코퍼스에서 병종·군제 근거를 실측한다.

지어내지 않기 위한 도구다. 후보 용어를 사서별로 세고 **최초 용례 원문**을 함께 뽑아,
`data/unitset/units.json` 의 모든 항목이 인용으로 뒷받침되는지 확인할 수 있게 한다.
0 건이면 미검출로 보고할 뿐 추측으로 채우지 않는다.

용법:  python3 tools/corpus/scan_units.py [용어 …]     (인자 없으면 CANDIDATES 전체)
"""
import collections, glob, os, re, sys

SRC = 'data/corpus'
GROUP = {'sgz': '三國志', 'hhs': '後漢書', 'hyg': '華陽國志', 'js': '晉書', 'js2': '晉書',
         'yy': '演義', 'zztj': '資治通鑑', 'yhjx': '元和郡縣圖志', 'ssxy': '世說新語', 'ss': '宋書'}
ORDER = ['三國志', '後漢書', '華陽國志', '晉書', '資治通鑑', '演義']

CANDIDATES = [
    # 정사 부대
    '突騎', '陷陣營', '虎豹騎', '白馬義從', '丹楊', '山越', '板楯', '賨', '叟', '青羌',
    '飛軍', '赤甲軍', '連弩士', '湟中', '義從', '先零', '燒當', '參狼', '解煩', '無難',
    '敢死', '車下虎士', '先登', '青州兵', '泰山兵', '鬼卒', '白波', '黑山',
    # 함종
    '樓船', '蒙衝', '鬥艦', '走舸', '舟師',
    # 중앙 상비
    '屯騎', '越騎', '步兵', '長水', '射聲', '虎賁', '羽林', '期門', '胡騎', '武衛',
    # 장비
    '連弩', '元戎', '木牛', '流馬', '八陣',
    # 연의
    '籐甲', '藤甲', '烏戈', '木鹿', '銀坑', '鐵車', '牌刀', '飛刀', '蠻兵',
    # 미검출 확인용 — 게임·후대 창작 판별에 쓴다
    '白毦', '象兵', '狼騎', '戈船', '鉤鐮',
]


def load():
    texts = collections.defaultdict(list)
    for path in sorted(glob.glob(os.path.join(SRC, '*.txt'))):
        m = re.match(r'([a-z0-9]+?)-', os.path.basename(path))
        if not m or m.group(1) not in GROUP:
            continue
        texts[GROUP[m.group(1)]].append(
            (os.path.basename(path), open(path, encoding='utf-8', errors='replace').read()))
    return texts


def main():
    texts = load()
    if not texts:
        sys.exit(f'{SRC} 가 비었다. 먼저 tools/corpus/fetch_sources.py 를 돌려라.')
    words = sys.argv[1:] or CANDIDATES
    print(f"{'용어':<10}" + ''.join(f'{g:>8}' for g in ORDER) + '   최초 용례')
    for w in words:
        counts = {g: sum(t.count(w) for _, t in texts.get(g, [])) for g in ORDER}
        cite = ''
        for g in ORDER:                       # 정사 우선으로 용례를 뽑는다. 연의가 먼저 잡히면 안 된다.
            for name, t in texts.get(g, []):
                i = t.find(w)
                if i >= 0:
                    cite = f'[{g} {name}] ' + t[max(0, i - 24):i + len(w) + 24].replace('\n', '')
                    break
            if cite:
                break
        print(f'{w:<10}' + ''.join(f'{counts[g]:>10}' for g in ORDER) +
              '   ' + (cite or '✗ 미검출 — 사료 근거 없음'))


if __name__ == '__main__':
    main()
