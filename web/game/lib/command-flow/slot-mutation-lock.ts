// At most one write per server, actor and slot in a tab; ordinary reservation POSTs and cancellation DELETEs share this lock.
// Synchronous acquisition and release prevent duplicate clicks or overlapping reservation and cancellation writes.
const held = new Set<string>();

export function slotMutationKey(server: string | null, actor: number, turnIdx: number): string {
  return JSON.stringify([server, actor, turnIdx]);
}

/** Returns the release function, or null when another write for this slot is in flight. Releasing twice is harmless. */
export function acquireSlotMutation(key: string): (() => void) | null {
  if (held.has(key)) return null;
  held.add(key);
  let released = false;
  return () => {
    if (released) return;
    released = true;
    held.delete(key);
  };
}
