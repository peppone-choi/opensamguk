# OPENSAM-81 Admin Board Control Contract

## Scope

This note covers the gateway-admin control surface only. It does not change the
public board UI, CSS, package metadata, backend implementation, or authorization
policy.

## UI and API boundary

`web/gateway/components/admin/BoardControl.tsx` owns the state and mutation
logic, while `BoardControlTable.tsx` is presentation-only. The control calls the
same-origin gateway proxy so browser code never reads a bearer token. The
existing proxy reads the httpOnly `sam_access` cookie and attaches the bearer
credential server-side.

The locked OPENSAM-79 gateway-api contract is:

| Operation | Gateway path | Expected response |
| --- | --- | --- |
| List | `GET /board/posts?category=NOTICE|FREE|SUGGESTION&page=0&size=20` | Paged post DTOs |
| Pin | `PATCH /board/posts/{id}/pin` with `{ "pinned": boolean }` | Updated post DTO |
| Delete | `DELETE /board/posts/{id}` | `204 No Content` |

The admin page keeps the control under its existing `AuthGate admin`. The UI is
not an authorization boundary: OPENSAM-79 keeps pinning admin-only and deletion
owner-or-admin at the service layer.

## Rendering and state rules

- Categories are fixed to `NOTICE`, `FREE`, and `SUGGESTION`.
- Title, author, error, and confirmation values are rendered as React text. The
  component does not inject `contentHtml` into the DOM.
- A list response applies only while it is the newest request, so a late previous
  category/page response cannot overwrite current results.
- After deleting the only post on a nonzero final page, the control requests the
  previous valid page instead of showing an invalid page index.

## Landing dependencies

OPENSAM-81 is intentionally a frontend consumer of two parallel changes:

1. OPENSAM-79 supplies the exact gateway-api `/board/posts` routes and backend
   authorization described above.
2. OPENSAM-80 preserves an upstream `204 No Content` response through the
   generic gateway proxy without constructing a response body.

The admin control is not live-stack ready until both dependencies are integrated
with this frontend change. Focused UI tests use the locked response contract at
the proxy boundary; they do not substitute for the combined gateway-api runtime
check.
