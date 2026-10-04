/**
 * 「서버 대기 → 실제 값」 시험 도우미(K10, 2026-10-05).
 *
 * 서버가 아직 주지 않는 칸은 숨기지 않고 서버 대기로 둔다. 그 칸(또는 감싸는 영역)에 기다리는 계약판 행을
 * `data-server-wait="<행>"` 으로 단다. 서버(또는 어댑터의 고정 자료)가 값을 주기 시작하면 **그 행의 표지는 사라지고
 * 실제 값이 대기 칸 밖에 보여야** 한다. 이 도우미는 두 상태를 같은 방식으로 단언한다. 시험 틀(vitest · jest)에 묶이지
 * 않고, 어긋나면 무엇이 어긋났는지 적은 Error 를 던진다.
 *
 *   const { container, rerender } = render(<CrownCard crown={undefined} />);
 *   expectServerWait(container, ['K8-10']);                          // 값이 없을 때 — 기다리는 행이 정확히 이것들
 *   rerender(<CrownCard crown={{ name: '헌제' }} />);
 *   expectServerWaitGone(container, ['K8-10'], { value: '헌제' });   // 값이 오면 — 표지는 사라지고 값이 대기 칸 밖에
 *
 * e2e(Playwright)에서는 `serverWaitRows` 를 그대로 넘겨 같은 판정을 쓴다(바깥 이름을 쓰지 않는 함수다):
 *
 *   const rows = await page.locator('main').evaluate(serverWaitRows);
 */

/** 표지 속성 이름. */
export const SERVER_WAIT_ATTR = 'data-server-wait';

/** 영역 안의 서버 대기 행 — 문서 순서, 중복 포함, 빈 값은 ''. 영역 자신에 달린 표지도 센다. */
export function serverWaitRows(root: ParentNode): string[] {
    // Playwright `evaluate` 로 브라우저에 넘길 수 있게 바깥 이름(상수 · 도우미)을 쓰지 않는다.
    const attr = 'data-server-wait';
    const own = (root as Element).getAttribute?.(attr);
    const rows = own === null || own === undefined ? [] : [own];
    for (const el of Array.from(root.querySelectorAll(`[${attr}]`))) rows.push(el.getAttribute(attr) ?? '');
    return rows;
}

/**
 * 값이 없을 때: 기다리는 행이 정확히 `rows` 인지 본다(순서 무관, 중복은 하나로).
 * 빠진 행 · 남는 행 · 빈 행 이름이 있으면 던진다.
 */
export function expectServerWait(root: ParentNode, rows: readonly string[]): void {
    const found = serverWaitRows(root);
    const problems: string[] = [];
    if (found.some((row) => row.trim() === '')) problems.push(`행 이름이 빈 ${SERVER_WAIT_ATTR} 가 있다`);
    const have = new Set(found.filter((row) => row.trim() !== ''));
    const want = new Set(rows);
    const missing = [...want].filter((row) => !have.has(row));
    const extra = [...have].filter((row) => !want.has(row));
    if (missing.length) problems.push(`서버 대기 표지가 없다: ${missing.join(', ')}`);
    if (extra.length) problems.push(`기대하지 않은 서버 대기 표지: ${extra.join(', ')}`);
    if (problems.length) throw new Error(`expectServerWait — ${problems.join(' · ')} (찾은 행: [${found.join(', ')}])`);
}

/**
 * 값이 온 뒤: `rows` 의 표지가 하나도 남지 않았는지 본다(다른 행은 남아도 된다 — 그 행의 서버는 아직이다).
 * `value` 를 주면 그 글자가 **대기 칸 밖에** 보이는지도 본다. 대기 칸 안에만 있으면 값이 온 것이 아니다.
 */
export function expectServerWaitGone(
    root: ParentNode,
    rows: readonly string[],
    opts: { readonly value?: string | RegExp } = {},
): void {
    const found = serverWaitRows(root);
    const left = rows.filter((row) => found.includes(row));
    const problems: string[] = [];
    if (left.length) problems.push(`값이 왔는데 서버 대기 표지가 남았다: ${left.join(', ')}`);
    if (opts.value !== undefined) {
        const base = root instanceof Document ? root.body : root;
        const copy = (base as Node).cloneNode(true) as Element;
        for (const wait of Array.from(copy.querySelectorAll(`[${SERVER_WAIT_ATTR}]`))) wait.remove();
        const outside = copy.hasAttribute?.(SERVER_WAIT_ATTR) ? '' : copy.textContent ?? ''; // 영역 전체가 대기면 밖이 없다
        const inside = (base as Node).textContent ?? '';
        const hit = (text: string) => (typeof opts.value === 'string' ? text.includes(opts.value) : (opts.value as RegExp).test(text));
        if (!hit(outside)) problems.push(hit(inside) ? `값 「${String(opts.value)}」이 서버 대기 칸 안에만 있다` : `값 「${String(opts.value)}」이 보이지 않는다`);
    }
    if (problems.length) throw new Error(`expectServerWaitGone — ${problems.join(' · ')} (남은 행: [${found.join(', ')}])`);
}
