# Legacy Diplomacy and Messaging

This document summarizes the legacy message system and how diplomatic messages
trigger nation command flows. References include
`legacy/hwe/sammo/Message.php`, `legacy/hwe/sammo/DiplomaticMessage.php`,
`legacy/hwe/sammo/MessageTarget.php`, and `legacy/hwe/sammo/Target.php`.

## Message Types and Mailboxes

`Message` supports multiple mailbox types:

- **Public**: `MAILBOX_PUBLIC` (global announcements).
- **National**: `MAILBOX_NATIONAL + nationID` for diplomacy/nation mail.
- **Private**: mailbox id is the receiver general id.

Message types (`MessageType`) include `public`, `national`, `private`,
and `diplomacy`.

## Message Lifecycle

- `Message::send()` (not shown here) writes to the `message` table.
- `Message::getMessagesFromMailBox()` reads by mailbox/type with validity
  filtering (`valid_until > now`).
- `Message::buildFromArray()` instantiates:
  - `DiplomaticMessage` when type is diplomacy.
  - `ScoutMessage` or `RaiseInvaderMessage` when `option.action` matches.

## DiplomaticMessage Flow

`DiplomaticMessage` wraps diplomacy requests and accepts/rejects them:

- Supported actions:
  - `TYPE_NO_AGGRESSION` (불가침)
  - `TYPE_CANCEL_NA` (불가침 파기)
  - `TYPE_STOP_WAR` (종전)
- `agreeMessage()` validates:
  - message is still valid
  - receiver is a valid diplomacy officer
- On accept, builds and runs the matching nation command:
  - `che_불가침수락`, `che_불가침파기수락`, `che_종전수락`
  - Uses `NoRNG` to avoid randomness in acceptance.

Each acceptance updates `diplomacyDetail` with command briefs and writes logs.

## Message Targets

`MessageTarget` and `Target` provide lightweight identifiers:

- `MessageTarget` includes general/nation names, colors, and icons.
- Helper constructors (`buildQuick`, `buildFromGeneralObj`) resolve from DB.

## Open Questions / Follow-ups

- The full list of `MessageType` values and message table schema live outside
  this file and can be documented alongside the UI mailbox APIs.
