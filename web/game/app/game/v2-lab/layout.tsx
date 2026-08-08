import { notFound } from 'next/navigation';

/**
 * v2 frontend namespace gate (OPENSAM-35 / 0A-a).
 *
 * Every route under `/game/v2-lab/**` passes through this layout. Keeping the gate
 * here rather than on each page automatically places future v2 pages behind it.
 *
 * Middleware is the pre-render enforcement point and guarantees an HTTP 404. Keep
 * `notFound()` here as defense in depth: it prevents v2 content from rendering if
 * middleware is bypassed, even though the `AuthGate` client boundary can make this
 * layout resolve only after an HTTP 200 shell has started streaming.
 *
 * Value interpretation is deliberately stricter than the backend. Backend
 * `@ConditionalOnProperty(havingValue = "true")` uses `equalsIgnoreCase`, so it
 * opens for `TRUE` and `True` (measured by `V2SandboxConfigurationTest`,
 * `property value is matched case-insensitively`), while this frontend only accepts
 * strict `=== 'true'`. The backend also has `@Profile("v2-sandbox")`, so a
 * `V2_ENABLED=TRUE` typo cannot open production there. The frontend has no second
 * condition, making looser matching an actual exposure.
 * (docs/loops/opensam-35-v2-0a-2026-08-08/s2-conditional-bean-gate.md)
 *
 * Do not use a `NEXT_PUBLIC_` prefix: it would inline the value into the client
 * bundle and turn this server-side gate into public configuration. The server-only
 * `process.env` access follows established repository patterns in `lib/server-api.ts`
 * and `middleware.ts` (`SERVER_ID`).
 *
 * This is not a redirect, blank page, or "coming soon" notice; it is the
 * defense-in-depth rendering gate.
 */
export default function V2LabLayout({ children }: { children: React.ReactNode }) {
  if (process.env.V2_ENABLED !== 'true') {
    notFound();
  }
  return <>{children}</>;
}
