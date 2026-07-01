# Random Imgwan Actor Log Review

## Scope

- Bug: global random-join logs rendered a blank actor, e.g. `5월 상순:가 ... 임관했습니다.`
- Files changed:
  - `logic/src/main/kotlin/opensamguk/logic/actions/personnel/CheRandomImgwan.kt`
  - `logic/src/test/kotlin/opensamguk/logic/actions/personnel/RandomImgwanTest.kt`

## PHP Oracle

- `legacy/devsam-core/hwe/sammo/Command/General/che_랜덤임관.php:122-125`
  - PHP reads the actor from `$general->getName()`.
  - The josa is computed from that same name with `JosaUtil::pick($generalName, '이')`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_랜덤임관.php:253-255`
  - The global action log writes `<Y>{$generalName}</>{$josaYi} ... 임관했습니다.`
- `legacy/devsam-core/hwe/sammo/GeneralAI.php:3304-3308`
  - NPC founding intentionally builds nation names with `"㉿" . mb_substr($this->general->getName(), 1)`.
  - Therefore `㉿갈각` is a PHP-compatible AI-founded nation name when the source NPC name is prefixed, e.g. `ⓝ갈각`.
- `legacy/devsam-core/hwe/sammo/Command/General/che_거병.php:79-91`
  - 거병 uses the actor name as the nation name and only uses `㉥` as the duplicate-name prefix.

## Root Cause

`CheRandomImgwan.resolve()` read `d.general.meta["name"]` for the broadcast actor token. In live engine turns, `General` has no name field and the daemon supplies the actor name through `GeneralActionResolveContext.generalName`. If the metadata map does not carry `name`, the resolver produced an empty actor token while still computing josa, yielding logs like `<Y></>가 ... 임관했습니다.`

## Fix

The resolver now uses `context.generalName` first, with the old metadata lookup kept only as a fallback for existing unit-test construction paths.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests '*RandomImgwanTest*'`
  - `BUILD SUCCESSFUL`
  - XML: `tests=8 failures=0 errors=0 skipped=0`
- Added regression test: `global join log uses the actor name supplied by the engine context`.

## Review Result

Verdict: cleared

The change is narrow, matches the PHP actor-name source, does not alter RNG draw order, and preserves the metadata fallback for older tests. `㉿` handling was investigated but not changed because the observed AI founding prefix is present in the PHP grand truth.
