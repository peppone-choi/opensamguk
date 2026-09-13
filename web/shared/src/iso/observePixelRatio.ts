/** CSS dimensions can stay unchanged when the window moves between displays. */
export function observePixelRatio(onChange: () => void, source: Pick<Window, 'matchMedia' | 'devicePixelRatio'> = window): () => void {
  if (typeof source.matchMedia !== 'function') return () => {};
  let query: MediaQueryList;
  const changed = () => {
    query.removeEventListener('change', changed);
    watch();
    onChange();
  };
  const watch = () => {
    query = source.matchMedia(`(resolution: ${source.devicePixelRatio || 1}dppx)`);
    query.addEventListener('change', changed);
  };
  watch();
  return () => query.removeEventListener('change', changed);
}
