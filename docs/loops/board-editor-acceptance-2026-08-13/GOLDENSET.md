# Board and editor acceptance matrix

Date: 2026-08-13

This matrix closes evidence only at the tier actually observed. A fixture-backed
browser run uses the real Next server, React runtime, Tiptap instance, DOM,
responsive CSS, and client request code while fulfilling API responses at the
browser boundary. It is not an authenticated gateway-api/game-api/daemon run.

| Ticket | Required observable | Fixture browser | Authenticated backend / daemon |
| --- | --- | --- | --- |
| OPENSAM-80 | login -> Tiptap Korean post -> detail -> list -> comment; ADMIN pin | required | required for closure |
| OPENSAM-81 | admin pin/delete UI; non-admin pin 403; non-owner delete 403 | admin UI required | denial matrix required for closure |
| OPENSAM-82 | storage/search ADR and dependency-ordered ticket split | not applicable | not applicable |
| OPENSAM-87 | mailbox/diplomacy editor and safe render | required | required only for persistence/result claims |

Parents close only when every child is closed. OPENSAM-83 therefore remains open
because OPENSAM-88 is outside this acceptance lane and still open.
