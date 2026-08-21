export const meta = {
  name: 'opensamguk-backlog-fanout',
  description:
    'Retired mixed legacy/current backlog workflow. ADR-LITE-042 requires new work to follow approved ADR/spec/current implementation instead.',
  phases: [{ title: 'Retired', detail: 'No agents or implementation tasks are dispatched.' }],
}

log(
  'backlog-fanout is retired: use the current task contract and approved ADR/spec/current implementation. ' +
    'Select historical comparison explicitly when frozen-regression maintenance requires it.'
)

return {
  error: 'retired workflow',
  replacement: 'current task contract plus targeted project skills',
}
