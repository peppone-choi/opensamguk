// Temporary red probe for the arch_lint ratchet — a new component that calls fetch directly. Reverted in the next commit.
export function ArchRatchetProbe() {
  void fetch('/__arch_ratchet_probe');
  return null;
}
