// Temporary red probe for tools/ci/arch_lint.py — a component that calls fetch directly. Reverted in the next commit.
export function ArchLintProbe() {
  void fetch('/__arch_lint_probe');
  return null;
}
