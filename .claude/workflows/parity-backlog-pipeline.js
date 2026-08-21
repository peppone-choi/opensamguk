export const meta = {
  name: 'parity-backlog-pipeline',
  description:
    'Retired mixed legacy/current backlog workflow. ADR-LITE-042 limits PHP comparison to explicitly selected historical frozen-regression maintenance.',
  phases: [{ title: 'Retired', detail: 'No agents or implementation tasks are dispatched.' }],
}

log(
  'parity-backlog-pipeline is retired: use the current task contract and approved ADR/spec/current implementation. ' +
    'Use parity-wave only for explicitly selected historical frozen-regression maintenance.'
)

return {
  error: 'retired workflow',
  replacement: 'current task contract or explicit historical parity-wave',
}
