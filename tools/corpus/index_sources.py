#!/usr/bin/env python3
"""사료 코퍼스를 질의 가능한 인덱스로 만든다 — CodeGraph 가 코드에 하는 일을 사료에 한다.

22MB 를 매번 grep 하지 않고 `data/corpus/index.db`(SQLite FTS5) 한 번 만들어 두면
「어느 사서 어느 권에 이 말이 나오나」를 밀리초에 답한다. 인덱스는 원문에서 언제든
재생성되므로 **git-ignore·미커밋**이다.

한문은 공백이 없어 기본 토크나이저가 한 권을 토큰 하나로 본다. trigram 토크나이저는
3자 미만 질의를 놓친다(「義從」이 안 잡힌다). 그래서 **글자 사이에 공백을 넣어 색인하고
구(phrase) 질의로 되돌린다** — 길이에 상관없이 정확히 잡힌다.

용법:
    python3 tools/corpus/index_sources.py                # 인덱스 생성/갱신
    python3 tools/corpus/index_sources.py 白馬義從        # 질의
    python3 tools/corpus/index_sources.py 連弩 --book 華陽國志 --limit 5
"""
import argparse, glob, os, re, sqlite3, sys

SRC = os.environ.get('SHILIAO_HOME') or (
    'data/corpus' if os.path.isdir('data') else os.path.expanduser('~/.shiliao'))
DB = os.path.join(SRC, 'index.db')
BOOK = {'sgz': '三國志', 'hhs': '後漢書', 'hyg': '華陽國志', 'js': '晉書', 'js2': '晉書',
        'yy': '三國演義', 'zztj': '資治通鑑', 'yhjx': '元和郡縣圖志', 'ssxy': '世說新語', 'ss': '宋書'}
# 續漢書 志는 이 판본에서 後漢書 卷91~120 이다. 郡國志·百官志를 권 번호로 바로 부를 수 있게 이름을 붙인다.
ZHI = {**{i: '郡國志' for i in range(109, 114)}, **{i: '百官志' for i in range(114, 119)}}

split = lambda s: ' '.join(s)


def label(name):
    m = re.match(r'([a-z0-9]+?)-(.+)\.txt$', name)
    if not m or m.group(1) not in BOOK:
        return None
    book, num = BOOK[m.group(1)], m.group(2)
    if book == '後漢書':
        n = int(re.sub(r'\D', '', num) or 0)
        if n in ZHI:
            return book, f'卷{n} ({ZHI[n]})'
    return book, f'卷{num}'


def build():
    con = sqlite3.connect(DB)
    con.executescript('drop table if exists src; '
                      "create virtual table src using fts5(book, vol, body, tokenize='unicode61');")
    rows = []
    for path in sorted(glob.glob(os.path.join(SRC, '*.txt'))):
        lab = label(os.path.basename(path))
        if not lab:
            continue
        text = open(path, encoding='utf-8', errors='replace').read()
        rows.append((lab[0], lab[1], split(text)))
    con.executemany('insert into src values (?,?,?)', rows)
    con.commit()
    print(f'{len(rows)}권 색인 · {os.path.getsize(DB)/1e6:.1f}MB → {DB}', file=sys.stderr)


def query(term, book, limit):
    con = sqlite3.connect(DB)
    where, args = 'src match ?', ['body : "' + split(term) + '"']
    if book:
        where += ' and book = ?'
        args.append(book)
    args.append(limit)
    sql = (f'select book, vol, snippet(src, 2, "《", "》", "…", 24) from src '
           f'where {where} order by rank limit ?')
    hits = con.execute(sql, args).fetchall()
    if not hits:
        print(f'✗ {term} — 미검출. 사료 근거 없음(추측으로 채우지 말 것).')
        return
    for b, v, snip in hits:
        print(f'[{b} {v}] ' + snip.replace(' ', ''))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('term', nargs='?', help='없으면 인덱스를 새로 만든다')
    ap.add_argument('--book', help='사서 이름으로 한정 (三國志·後漢書·華陽國志·晉書·資治通鑑·三國演義 …)')
    ap.add_argument('--limit', type=int, default=10)
    a = ap.parse_args()
    if not a.term:
        build()
    elif not os.path.exists(DB):
        sys.exit('인덱스가 없다. 인자 없이 먼저 실행해라.')
    else:
        query(a.term, a.book, a.limit)


if __name__ == '__main__':
    main()
