# P0-A: Foundation Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a Dockerized, CI-tested Kotlin/Spring multi-module monorepo plus two Next.js apps where every module compiles, all three Spring services boot, Postgres+Redis+nginx run under Docker Compose, and Flyway applies the full game-schema baseline verified by a JPA round-trip test against a Testcontainers Postgres.

**Architecture:** Gradle (Kotlin DSL) multi-module build mirroring core2026 — `common`/`logic`/`infra` libraries + `app:gateway-api`/`app:game-api`/`app:game-engine` Spring Boot services + `web/gateway`/`web/game` Next.js (App Router) apps. `infra` owns JPA + Flyway + Redis; the game services depend on `infra`+`logic`+`common`. No game rules are implemented here — this phase makes the stack runnable and the schema testable. (Part 1 of phase P0; the parity kernel + memory-CQRS skeleton is P0-B.)

**Tech Stack:** Kotlin 2.1, Spring Boot 3.4, Gradle 8.12 (wrapper), JDK 21 (LTS toolchain), PostgreSQL 16, Redis 7, Flyway 11, Testcontainers, Next.js 15 + pnpm, nginx, Docker Compose, GitHub Actions.

**Prerequisites (host):** JDK 21 available; `gradle` on PATH (system Gradle, used once to generate the wrapper); Docker; Node 20+ and `corepack`/`pnpm`. Run all Gradle commands with `JAVA_HOME` pointing at a JDK 21 (Gradle 8.12 fails to parse Java 25 sources/daemon — keep the daemon on 21).

**Conventions:** Base package `opensamguk` (`opensamguk.common`, `opensamguk.logic`, `opensamguk.infra`, `opensamguk.gameapi`, `opensamguk.engine`, `opensamguk.gateway`). Gradle `group = "opensamguk"`, `version = "0.0.1-SNAPSHOT"`. All Gradle modules pin `kotlin { jvmToolchain(21) }`.

**Verification policy:** `BUILD SUCCESSFUL`/exit-0 is not sufficient evidence — always confirm by reading the relevant tail of build/test output (grep for the test name / `Tests run` / `BUILD`), not just the exit code.

---

## File Structure

```
opensamguk/
├─ settings.gradle.kts              # module includes + foojay toolchain resolver
├─ build.gradle.kts                 # root: group/version, plugin aliases (apply false)
├─ gradle.properties                # gradle daemon/caching flags
├─ gradle/libs.versions.toml        # version catalog
├─ gradlew / gradlew.bat / gradle/wrapper/   # wrapper (8.12)
├─ .gitignore .gitattributes .editorconfig
├─ .env.example
├─ README.md
├─ common/build.gradle.kts          # pure Kotlin lib
│  └─ src/{main,test}/kotlin/opensamguk/common/...
├─ logic/build.gradle.kts           # pure Kotlin lib (depends common)
│  └─ src/{main,test}/kotlin/opensamguk/logic/...
├─ infra/build.gradle.kts           # Spring data-jpa + flyway + redis (library)
│  ├─ src/main/kotlin/opensamguk/infra/{config,worldstate}/...
│  └─ src/main/resources/db/migration/V1__baseline.sql
│  └─ src/test/kotlin/opensamguk/infra/...   # Testcontainers JPA round-trip
├─ app/gateway-api/build.gradle.kts # Spring Boot service
│  └─ src/{main,test}/kotlin/opensamguk/gateway/...
├─ app/game-api/build.gradle.kts    # Spring Boot service (depends infra/logic/common)
│  └─ src/{main,test}/kotlin/opensamguk/gameapi/...
├─ app/game-engine/build.gradle.kts # Spring Boot daemon (depends infra/logic/common)
│  └─ src/{main,test}/kotlin/opensamguk/engine/...
├─ web/gateway/                      # Next.js App Router app
├─ web/game/                         # Next.js App Router app
├─ docker/{gateway-api,game-api,game-engine,web-gateway,web-game}.Dockerfile
├─ nginx/nginx.conf
├─ docker-compose.yml
├─ tools/smoke.sh                    # compose up + health curl
└─ .github/workflows/ci.yml
```

---

## Task 1: Root Gradle multi-module setup

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `.gitignore`, `.gitattributes`, `.editorconfig`

- [ ] **Step 1: Initialize git and write `.gitignore`**

Run: `cd /Users/apple/Desktop/개인프로젝트/opensamguk && git init -b main`

Create `.gitignore`:

```gitignore
# Gradle / JVM
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
*.class
# IDE
.idea/
*.iml
.vscode/
# Node / Next.js
node_modules/
web/*/.next/
web/*/out/
.pnpm-store/
# Env / OS
.env
.env.local
.DS_Store
# Legacy reference repos (kept locally, never committed)
legacy/
# Decompiled / generated artifacts
**/generated/
```

Create `.gitattributes`:

```gitattributes
* text=auto eol=lf
*.jar binary
gradlew text eol=lf
```

Create `.editorconfig`:

```editorconfig
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
[*.{kt,kts}]
indent_style = space
indent_size = 4
[*.{ts,tsx,js,jsx,json,yml,yaml}]
indent_style = space
indent_size = 2
```

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.1.0"
springBoot = "3.4.1"
springDepMgmt = "1.1.7"
testcontainers = "1.20.4"
foojay = "0.9.0"

[libraries]
flyway-postgres = { module = "org.flywaydb:flyway-database-postgresql" }
testcontainers-postgres = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
kotlin-jpa = { id = "org.jetbrains.kotlin.plugin.jpa", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-depmgmt = { id = "io.spring.dependency-management", version.ref = "springDepMgmt" }
```

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "opensamguk"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("common", "logic", "infra")
include("app:gateway-api", "app:game-api", "app:game-engine")
```

- [ ] **Step 4: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.depmgmt) apply false
}

allprojects {
    group = "opensamguk"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}
```

- [ ] **Step 5: Write `gradle.properties`**

```properties
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

- [ ] **Step 6: Generate the Gradle wrapper (pinned 8.12)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle wrapper --gradle-version 8.12`
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`. Confirm `gradle/wrapper/gradle-wrapper.properties` contains `gradle-8.12-bin.zip`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: scaffold Gradle multi-module build (P0-A task 1)"
```

---

## Task 2: `common` Kotlin library module

**Files:**
- Create: `common/build.gradle.kts`
- Create: `common/src/main/kotlin/opensamguk/common/BuildInfo.kt`
- Test: `common/src/test/kotlin/opensamguk/common/BuildInfoTest.kt`

- [ ] **Step 1: Write `common/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the failing test** `common/src/test/kotlin/opensamguk/common/BuildInfoTest.kt`

```kotlin
package opensamguk.common

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `module name is common`() {
        assertEquals("common", BuildInfo.MODULE)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :common:test --tests 'opensamguk.common.BuildInfoTest'`
Expected: FAIL — `BuildInfo` unresolved (compilation error).

- [ ] **Step 4: Write minimal implementation** `common/src/main/kotlin/opensamguk/common/BuildInfo.kt`

```kotlin
package opensamguk.common

/** Sentinel proving the module compiles and is on the classpath. Replaced by real utilities in P0-B. */
object BuildInfo {
    const val MODULE: String = "common"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :common:test --tests 'opensamguk.common.BuildInfoTest'`
Expected: PASS (`BUILD SUCCESSFUL`; confirm `BuildInfoTest > module name is common PASSED` in output).

- [ ] **Step 6: Commit**

```bash
git add common
git commit -m "feat(common): add common library module skeleton (P0-A task 2)"
```

---

## Task 3: `logic` Kotlin library module

**Files:**
- Create: `logic/build.gradle.kts`
- Create: `logic/src/main/kotlin/opensamguk/logic/BuildInfo.kt`
- Test: `logic/src/test/kotlin/opensamguk/logic/BuildInfoTest.kt`

- [ ] **Step 1: Write `logic/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the failing test** `logic/src/test/kotlin/opensamguk/logic/BuildInfoTest.kt`

```kotlin
package opensamguk.logic

import opensamguk.common.BuildInfo as CommonBuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `logic depends on common`() {
        assertEquals("logic", BuildInfo.MODULE)
        assertEquals("common", CommonBuildInfo.MODULE)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :logic:test --tests 'opensamguk.logic.BuildInfoTest'`
Expected: FAIL — `BuildInfo` unresolved.

- [ ] **Step 4: Write minimal implementation** `logic/src/main/kotlin/opensamguk/logic/BuildInfo.kt`

```kotlin
package opensamguk.logic

/** Sentinel proving the pure-logic module compiles and can depend on :common. */
object BuildInfo {
    const val MODULE: String = "logic"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :logic:test --tests 'opensamguk.logic.BuildInfoTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add logic
git commit -m "feat(logic): add pure-logic library module skeleton (P0-A task 3)"
```

---

## Task 4: `infra` module — JPA, Flyway baseline, Redis, round-trip test

**Files:**
- Create: `infra/build.gradle.kts`
- Create: `infra/src/main/resources/db/migration/V1__baseline.sql`
- Create: `infra/src/main/kotlin/opensamguk/infra/worldstate/WorldStateEntity.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/worldstate/WorldStateRepository.kt`
- Test: `infra/src/test/kotlin/opensamguk/infra/worldstate/WorldStateRepositoryIT.kt`
- Test: `infra/src/test/resources/application-test.yml`

- [ ] **Step 1: Write `infra/build.gradle.kts`** (Spring-managed library — bootJar disabled, jar enabled)

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly(libs.flyway.postgres)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
}

// Library module: produce a plain jar, not an executable bootJar.
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the full game-schema baseline** `infra/src/main/resources/db/migration/V1__baseline.sql`

Faithful transcription of `legacy/devsam-core2026/packages/infra/prisma/game.prisma` (Prisma `Int`→`integer`, `Int @id @default(autoincrement())`→`serial`, app-assigned `Int @id`→`integer primary key`, `String`→`text`, `@db.VarChar(20)`→`varchar(20)`, `Float`→`double precision`, `Boolean`→`boolean`, `DateTime`→`timestamptz`, `Json`→`jsonb`).

```sql
-- P0-A baseline: game-profile schema (mirrors core2026 game.prisma)
-- Enums --------------------------------------------------------------------
CREATE TYPE log_scope AS ENUM ('SYSTEM', 'NATION', 'GENERAL', 'USER');
CREATE TYPE log_category AS ENUM ('HISTORY', 'SUMMARY', 'ACTION', 'BATTLE_BRIEF', 'BATTLE_DETAIL', 'USER');
CREATE TYPE auction_status AS ENUM ('OPEN', 'FINALIZING', 'FINISHED', 'CANCELED');
CREATE TYPE auction_type AS ENUM ('BUY_RICE', 'SELL_RICE', 'UNIQUE_ITEM');
CREATE TYPE diplomacy_letter_state AS ENUM ('PROPOSED', 'ACTIVATED', 'CANCELLED', 'REPLACED');

-- World / core entities ----------------------------------------------------
CREATE TABLE world_state (
    id            serial PRIMARY KEY,
    scenario_code text NOT NULL,
    current_year  integer NOT NULL,
    current_month integer NOT NULL,
    tick_seconds  integer NOT NULL,
    config        jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta          jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nation (
    id              integer PRIMARY KEY,
    name            text NOT NULL,
    color           text NOT NULL,
    capital_city_id integer,
    gold            integer NOT NULL DEFAULT 0,
    rice            integer NOT NULL DEFAULT 0,
    tech            double precision NOT NULL DEFAULT 0,
    level           integer NOT NULL DEFAULT 0,
    type_code       text NOT NULL DEFAULT 'che_중립',
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE city (
    id              integer PRIMARY KEY,
    name            text NOT NULL,
    level           integer NOT NULL,
    nation_id       integer NOT NULL DEFAULT 0,
    supply_state    integer NOT NULL DEFAULT 1,
    front_state     integer NOT NULL DEFAULT 0,
    population      integer NOT NULL,
    population_max  integer NOT NULL,
    agriculture     integer NOT NULL,
    agriculture_max integer NOT NULL,
    commerce        integer NOT NULL,
    commerce_max    integer NOT NULL,
    security        integer NOT NULL,
    security_max    integer NOT NULL,
    trust           integer NOT NULL DEFAULT 0,
    trade           integer NOT NULL DEFAULT 100,
    defence         integer NOT NULL,
    defence_max     integer NOT NULL,
    wall            integer NOT NULL,
    wall_max        integer NOT NULL,
    region          integer NOT NULL,
    conflict        jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE general (
    id             integer PRIMARY KEY,
    user_id        text,
    name           text NOT NULL,
    nation_id      integer NOT NULL DEFAULT 0,
    city_id        integer NOT NULL DEFAULT 0,
    troop_id       integer NOT NULL DEFAULT 0,
    npc_state      integer NOT NULL DEFAULT 0,
    affinity       integer,
    born_year      integer NOT NULL DEFAULT 180,
    dead_year      integer NOT NULL DEFAULT 300,
    picture        text,
    image_server   integer NOT NULL DEFAULT 0,
    leadership     integer NOT NULL DEFAULT 50,
    strength       integer NOT NULL DEFAULT 50,
    intel          integer NOT NULL DEFAULT 50,
    injury         integer NOT NULL DEFAULT 0,
    experience     integer NOT NULL DEFAULT 0,
    dedication     integer NOT NULL DEFAULT 0,
    officer_level  integer NOT NULL DEFAULT 0,
    gold           integer NOT NULL DEFAULT 1000,
    rice           integer NOT NULL DEFAULT 1000,
    crew           integer NOT NULL DEFAULT 0,
    crew_type_id   integer NOT NULL DEFAULT 0,
    train          integer NOT NULL DEFAULT 0,
    atmos          integer NOT NULL DEFAULT 0,
    weapon_code    text NOT NULL DEFAULT 'None',
    book_code      text NOT NULL DEFAULT 'None',
    horse_code     text NOT NULL DEFAULT 'None',
    item_code      text NOT NULL DEFAULT 'None',
    turn_time      timestamptz NOT NULL,
    recent_war_time timestamptz,
    age            integer NOT NULL DEFAULT 20,
    start_age      integer NOT NULL DEFAULT 20,
    personal_code  text NOT NULL DEFAULT 'None',
    special_code   text NOT NULL DEFAULT 'None',
    special2_code  text NOT NULL DEFAULT 'None',
    last_turn      jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta           jsonb NOT NULL DEFAULT '{}'::jsonb,
    penalty        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE troop (
    troop_leader_id integer PRIMARY KEY,
    nation_id       integer NOT NULL,
    name            text NOT NULL
);

CREATE TABLE general_turn (
    id          serial PRIMARY KEY,
    general_id  integer NOT NULL,
    turn_idx    integer NOT NULL,
    action_code text NOT NULL,
    arg         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (general_id, turn_idx)
);

CREATE TABLE nation_turn (
    id            serial PRIMARY KEY,
    nation_id     integer NOT NULL,
    officer_level integer NOT NULL,
    turn_idx      integer NOT NULL,
    action_code   text NOT NULL,
    arg           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (nation_id, officer_level, turn_idx)
);

CREATE TABLE diplomacy (
    id              serial PRIMARY KEY,
    src_nation_id   integer NOT NULL,
    dest_nation_id  integer NOT NULL,
    state_code      integer NOT NULL,
    term            integer NOT NULL DEFAULT 0,
    is_dead         boolean NOT NULL DEFAULT false,
    is_showing      boolean NOT NULL DEFAULT true,
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (src_nation_id, dest_nation_id)
);

CREATE TABLE diplomacy_letter (
    id             serial PRIMARY KEY,
    src_nation_id  integer NOT NULL,
    dest_nation_id integer NOT NULL,
    prev_id        integer,
    state          diplomacy_letter_state NOT NULL DEFAULT 'PROPOSED',
    text_brief     text NOT NULL,
    text_detail    text NOT NULL,
    date           timestamptz NOT NULL DEFAULT now(),
    src_signer_id  integer NOT NULL,
    dest_signer_id integer,
    aux            jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX diplomacy_letter_src_dest_idx ON diplomacy_letter (src_nation_id, dest_nation_id);
CREATE INDEX diplomacy_letter_dest_src_idx ON diplomacy_letter (dest_nation_id, src_nation_id);
CREATE INDEX diplomacy_letter_state_date_idx ON diplomacy_letter (state, date);

-- Ranking / history --------------------------------------------------------
CREATE TABLE rank_data (
    id          serial PRIMARY KEY,
    nation_id   integer NOT NULL DEFAULT 0,
    general_id  integer NOT NULL,
    type        varchar(20) NOT NULL,
    value       integer NOT NULL DEFAULT 0,
    UNIQUE (general_id, type)
);
CREATE INDEX rank_data_by_type ON rank_data (type, value);
CREATE INDEX rank_data_by_nation ON rank_data (nation_id, type, value);

CREATE TABLE hall (
    id         serial PRIMARY KEY,
    server_id  text NOT NULL,
    season     integer NOT NULL,
    scenario   integer NOT NULL,
    general_no integer NOT NULL,
    type       varchar(20) NOT NULL,
    value      double precision NOT NULL,
    owner      text,
    aux        jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id, type, general_no),
    UNIQUE (owner, server_id, type)
);
CREATE INDEX hall_server_show ON hall (server_id, type, value);
CREATE INDEX hall_scenario ON hall (season, scenario, type, value);

CREATE TABLE ng_games (
    id            serial PRIMARY KEY,
    server_id     text NOT NULL,
    date          timestamptz NOT NULL,
    winner_nation integer,
    map           text,
    season        integer NOT NULL,
    scenario      integer NOT NULL,
    scenario_name text NOT NULL,
    env           jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id)
);
CREATE INDEX ng_games_date_idx ON ng_games (date);

CREATE TABLE ng_old_nations (
    id        serial PRIMARY KEY,
    server_id text NOT NULL,
    nation    integer NOT NULL DEFAULT 0,
    data      jsonb NOT NULL DEFAULT '{}'::jsonb,
    date      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (server_id, nation)
);

CREATE TABLE ng_old_generals (
    id              serial PRIMARY KEY,
    server_id       text NOT NULL,
    general_no      integer NOT NULL,
    owner           text,
    name            text NOT NULL,
    last_year_month integer NOT NULL,
    turn_time       timestamptz NOT NULL,
    data            jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id, general_no)
);
CREATE INDEX ng_old_generals_by_name ON ng_old_generals (server_id, name);
CREATE INDEX ng_old_generals_owner ON ng_old_generals (owner, server_id);

-- Yearbook / events / logs -------------------------------------------------
CREATE TABLE yearbook_history (
    id           serial PRIMARY KEY,
    profile_name text NOT NULL,
    year         integer NOT NULL,
    month        integer NOT NULL,
    map          jsonb NOT NULL,
    nations      jsonb NOT NULL,
    hash         text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_name, year, month)
);

CREATE TABLE event (
    id          serial PRIMARY KEY,
    target_code text NOT NULL,
    priority    integer NOT NULL DEFAULT 0,
    condition   jsonb NOT NULL DEFAULT '{}'::jsonb,
    action      jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE log_entry (
    id         serial PRIMARY KEY,
    scope      log_scope NOT NULL,
    category   log_category NOT NULL,
    sub_type   text,
    year       integer NOT NULL,
    month      integer NOT NULL,
    text       text NOT NULL,
    general_id integer,
    nation_id  integer,
    user_id    integer,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX log_entry_scope_idx ON log_entry (scope, category, id);
CREATE INDEX log_entry_general_idx ON log_entry (general_id, category, id);
CREATE INDEX log_entry_nation_idx ON log_entry (nation_id, category, id);
CREATE INDEX log_entry_user_idx ON log_entry (user_id, category, id);

CREATE TABLE error_log (
    id         serial PRIMARY KEY,
    category   text NOT NULL,
    source     text,
    message    text NOT NULL,
    trace      text,
    context    jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX error_log_category_idx ON error_log (category, id);

-- Inheritance (cross-season; excluded from per-season truncate in P0-B) -----
CREATE TABLE inheritance_point (
    id         serial PRIMARY KEY,
    user_id    text NOT NULL,
    key        text NOT NULL,
    value      double precision NOT NULL DEFAULT 0,
    aux        jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, key)
);
CREATE INDEX inheritance_point_user_idx ON inheritance_point (user_id);

CREATE TABLE inheritance_log (
    id         serial PRIMARY KEY,
    user_id    text NOT NULL,
    year       integer NOT NULL,
    month      integer NOT NULL,
    text       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inheritance_log_user_idx ON inheritance_log (user_id, id);

CREATE TABLE inheritance_result (
    id         serial PRIMARY KEY,
    server_id  text NOT NULL,
    owner      text NOT NULL,
    general_id integer NOT NULL,
    year       integer NOT NULL,
    month      integer NOT NULL,
    value      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inheritance_result_server_owner_idx ON inheritance_result (server_id, owner);

CREATE TABLE inheritance_user_state (
    user_id    text PRIMARY KEY,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Auction / betting --------------------------------------------------------
CREATE TABLE auction (
    id              serial PRIMARY KEY,
    type            auction_type NOT NULL,
    target_code     text,
    host_general_id integer NOT NULL,
    host_name       text,
    detail          jsonb NOT NULL DEFAULT '{}'::jsonb,
    status          auction_status NOT NULL DEFAULT 'OPEN',
    close_at        timestamptz NOT NULL,
    latest_event_id text NOT NULL DEFAULT '',
    latest_event_at timestamptz NOT NULL DEFAULT now(),
    finalizing_at   timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auction_status_close_idx ON auction (status, close_at);

CREATE TABLE auction_bid (
    id         serial PRIMARY KEY,
    auction_id integer NOT NULL REFERENCES auction(id) ON DELETE CASCADE,
    general_id integer NOT NULL,
    amount     integer NOT NULL,
    event_id   text NOT NULL,
    event_at   timestamptz NOT NULL,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auction_bid_amount_idx ON auction_bid (auction_id, amount);
CREATE INDEX auction_bid_event_idx ON auction_bid (auction_id, event_at);

-- Board / vote -------------------------------------------------------------
CREATE TABLE board_post (
    id                serial PRIMARY KEY,
    nation_id         integer NOT NULL,
    is_secret         boolean NOT NULL DEFAULT false,
    author_general_id integer NOT NULL,
    author_name       text NOT NULL,
    title             text NOT NULL,
    content_html      text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX board_post_nation_idx ON board_post (nation_id, is_secret, created_at);

CREATE TABLE board_comment (
    id                serial PRIMARY KEY,
    post_id           integer NOT NULL REFERENCES board_post(id) ON DELETE CASCADE,
    nation_id         integer NOT NULL,
    is_secret         boolean NOT NULL DEFAULT false,
    author_general_id integer NOT NULL,
    author_name       text NOT NULL,
    content_text      text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX board_comment_post_idx ON board_comment (post_id, created_at);

CREATE TABLE vote_poll (
    id                serial PRIMARY KEY,
    title             text NOT NULL,
    body              text NOT NULL DEFAULT '',
    options           jsonb NOT NULL,
    multiple_options  integer NOT NULL DEFAULT 1,
    reveal_mode       text NOT NULL,
    opener_general_id integer NOT NULL,
    opener_name       text NOT NULL,
    start_at          timestamptz NOT NULL DEFAULT now(),
    end_at            timestamptz,
    closed_at         timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vote (
    id         serial PRIMARY KEY,
    vote_id    integer NOT NULL REFERENCES vote_poll(id) ON DELETE CASCADE,
    general_id integer NOT NULL,
    nation_id  integer NOT NULL,
    selection  jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (vote_id, general_id)
);
CREATE INDEX vote_poll_idx ON vote (vote_id);

CREATE TABLE vote_comment (
    id           serial PRIMARY KEY,
    vote_id      integer NOT NULL REFERENCES vote_poll(id) ON DELETE CASCADE,
    general_id   integer NOT NULL,
    nation_id    integer NOT NULL,
    general_name text NOT NULL,
    nation_name  text NOT NULL,
    text         text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX vote_comment_idx ON vote_comment (vote_id, created_at);
```

> Note: the `emperior` (Emperor) prisma model is a denormalized hall-of-fame snapshot with ~50 nullable display columns; it is not on the P0/P1 critical path and is intentionally deferred to a later migration (tracked in the P7 read-API phase). All other game.prisma models are included above.

- [ ] **Step 3: Write the test datasource config** `infra/src/test/resources/application-test.yml`

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.format_sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- [ ] **Step 4: Write the failing test** `infra/src/test/kotlin/opensamguk/infra/worldstate/WorldStateRepositoryIT.kt`

```kotlin
package opensamguk.infra.worldstate

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorldStateRepositoryIT {

    @Autowired
    lateinit var repository: WorldStateRepository

    @Test
    fun `flyway baseline applied and world_state round-trips`() {
        val saved = repository.save(
            WorldStateEntity(
                scenarioCode = "scenario_2",
                currentYear = 190,
                currentMonth = 1,
                tickSeconds = 3600,
            )
        )
        assertNotNull(saved.id)

        val found = repository.findById(saved.id!!).orElseThrow()
        assertEquals("scenario_2", found.scenarioCode)
        assertEquals(190, found.currentYear)
        assertEquals(3600, found.tickSeconds)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.worldstate.WorldStateRepositoryIT'`
Expected: FAIL — `WorldStateEntity` / `WorldStateRepository` unresolved (compilation error).

- [ ] **Step 6: Write the entity** `infra/src/main/kotlin/opensamguk/infra/worldstate/WorldStateEntity.kt`

```kotlin
package opensamguk.infra.worldstate

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "world_state")
class WorldStateEntity(
    @Column(name = "scenario_code", nullable = false)
    var scenarioCode: String,

    @Column(name = "current_year", nullable = false)
    var currentYear: Int,

    @Column(name = "current_month", nullable = false)
    var currentMonth: Int,

    @Column(name = "tick_seconds", nullable = false)
    var tickSeconds: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
```

> `config`/`meta` jsonb columns are omitted from this entity on purpose — P0-A proves the pipeline with scalar columns; jsonb mapping (with the lazy/delete-on-null write semantics) lands in P0-B with the full entity set.

- [ ] **Step 7: Write the repository** `infra/src/main/kotlin/opensamguk/infra/worldstate/WorldStateRepository.kt`

```kotlin
package opensamguk.infra.worldstate

import org.springframework.data.jpa.repository.JpaRepository

interface WorldStateRepository : JpaRepository<WorldStateEntity, Int>
```

- [ ] **Step 8: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.worldstate.WorldStateRepositoryIT'`
Expected: PASS — Testcontainers starts `postgres:16-alpine`, Flyway applies `V1__baseline.sql`, Hibernate `ddl-auto: validate` succeeds against `world_state`, round-trip asserts pass. Confirm `Tests run: 1` / `PASSED` in the output tail.

- [ ] **Step 9: Commit**

```bash
git add infra
git commit -m "feat(infra): JPA + Flyway baseline schema with Testcontainers round-trip (P0-A task 4)"
```

---

## Task 5: `app:game-api` Spring Boot service

**Files:**
- Create: `app/game-api/build.gradle.kts`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/GameApiApplication.kt`
- Create: `app/game-api/src/main/resources/application.yml`
- Test: `app/game-api/src/test/kotlin/opensamguk/gameapi/GameApiApplicationTests.kt`

- [ ] **Step 1: Write `app/game-api/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    implementation(project(":logic"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the application config** `app/game-api/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: game-api
  datasource:
    url: ${GAME_DATABASE_URL:jdbc:postgresql://localhost:5432/sammo}
    username: ${GAME_DB_USER:sammo}
    password: ${GAME_DB_PASSWORD:sammo}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
server:
  port: ${GAME_API_PORT:8081}
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 3: Write the failing test** `app/game-api/src/test/kotlin/opensamguk/gameapi/GameApiApplicationTests.kt`

```kotlin
package opensamguk.gameapi

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameApiApplicationTests {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Test
    fun `context loads and health endpoint reports UP`() {
        val body = rest.getForObject("http://localhost:$port/actuator/health", String::class.java)
        assertTrue(body!!.contains("\"status\":\"UP\""), "health body: $body")
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Disable Redis health contribution for the boot test (no Redis container here).
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test`
Expected: FAIL — `GameApiApplication` unresolved.

- [ ] **Step 5: Write the application class** `app/game-api/src/main/kotlin/opensamguk/gameapi/GameApiApplication.kt`

```kotlin
package opensamguk.gameapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EntityScan(basePackages = ["opensamguk.infra"])
@EnableJpaRepositories(basePackages = ["opensamguk.infra"])
class GameApiApplication

fun main(args: Array<String>) {
    runApplication<GameApiApplication>(*args)
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test`
Expected: PASS — app boots against Testcontainers Postgres, Flyway runs (migrations on classpath via `:infra`), `/actuator/health` returns `UP`. Confirm in output tail.

- [ ] **Step 7: Commit**

```bash
git add app/game-api
git commit -m "feat(game-api): boot Spring service with health + JPA wiring (P0-A task 5)"
```

---

## Task 6: `app:game-engine` daemon service

**Files:**
- Create: `app/game-engine/build.gradle.kts`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/GameEngineApplication.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt`
- Create: `app/game-engine/src/main/resources/application.yml`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/GameEngineApplicationTests.kt`

- [ ] **Step 1: Write `app/game-engine/build.gradle.kts`** (identical structure to game-api)

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    implementation(project(":logic"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the application config** `app/game-engine/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: game-engine
  datasource:
    url: ${GAME_DATABASE_URL:jdbc:postgresql://localhost:5432/sammo}
    username: ${GAME_DB_USER:sammo}
    password: ${GAME_DB_PASSWORD:sammo}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
server:
  port: ${GAME_ENGINE_PORT:8082}
management:
  endpoints:
    web:
      exposure:
        include: health,info
opensamguk:
  profile: ${TURN_PROFILE_NAME:che:scenario_2}
```

- [ ] **Step 3: Write the failing test** `app/game-engine/src/test/kotlin/opensamguk/engine/GameEngineApplicationTests.kt`

```kotlin
package opensamguk.engine

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameEngineApplicationTests {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Test
    fun `status endpoint reports the configured profile and idle state`() {
        val body = rest.getForObject(
            "http://localhost:$port/admin/turn-daemon/status", String::class.java
        )
        assertTrue(body!!.contains("\"state\":\"idle\""), "status body: $body")
        assertTrue(body.contains("che:scenario_2"), "status body: $body")
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test`
Expected: FAIL — `GameEngineApplication` / controller unresolved.

- [ ] **Step 5: Write the application class** `app/game-engine/src/main/kotlin/opensamguk/engine/GameEngineApplication.kt`

```kotlin
package opensamguk.engine

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EntityScan(basePackages = ["opensamguk.infra"])
@EnableJpaRepositories(basePackages = ["opensamguk.infra"])
class GameEngineApplication

fun main(args: Array<String>) {
    runApplication<GameEngineApplication>(*args)
}
```

- [ ] **Step 6: Write the status controller** `app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt`

This is a placeholder mirroring the `GET /admin/turn-daemon/status` contract from the design doc; the real daemon state machine arrives in P0-B.

```kotlin
package opensamguk.engine.status

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TurnDaemonStatus(
    val profile: String,
    val state: String,
    val running: Boolean,
    val paused: Boolean,
    val queueDepth: Int,
)

@RestController
@RequestMapping("/admin/turn-daemon")
class StatusController(
    @Value("\${opensamguk.profile}") private val profile: String,
) {
    @GetMapping("/status")
    fun status(): TurnDaemonStatus =
        TurnDaemonStatus(profile = profile, state = "idle", running = false, paused = false, queueDepth = 0)
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test`
Expected: PASS — daemon boots, `/admin/turn-daemon/status` returns idle + the configured profile. Confirm in output tail.

- [ ] **Step 8: Commit**

```bash
git add app/game-engine
git commit -m "feat(game-engine): boot daemon service with status endpoint (P0-A task 6)"
```

---

## Task 7: `app:gateway-api` service

**Files:**
- Create: `app/gateway-api/build.gradle.kts`
- Create: `app/gateway-api/src/main/kotlin/opensamguk/gateway/GatewayApiApplication.kt`
- Create: `app/gateway-api/src/main/resources/application.yml`
- Test: `app/gateway-api/src/test/kotlin/opensamguk/gateway/GatewayApiApplicationTests.kt`

- [ ] **Step 1: Write `app/gateway-api/build.gradle.kts`** (no JPA/infra dependency yet — gateway DB/auth is built in P8)

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the application config** `app/gateway-api/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: gateway-api
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
server:
  port: ${GATEWAY_API_PORT:8080}
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: Write the failing test** `app/gateway-api/src/test/kotlin/opensamguk/gateway/GatewayApiApplicationTests.kt`

```kotlin
package opensamguk.gateway

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApiApplicationTests {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Test
    fun `context loads and health endpoint reports UP`() {
        val body = rest.getForObject("http://localhost:$port/actuator/health", String::class.java)
        assertTrue(body!!.contains("\"status\":\"UP\""), "health body: $body")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test`
Expected: FAIL — `GatewayApiApplication` unresolved.

- [ ] **Step 5: Write the application class** `app/gateway-api/src/main/kotlin/opensamguk/gateway/GatewayApiApplication.kt`

```kotlin
package opensamguk.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GatewayApiApplication

fun main(args: Array<String>) {
    runApplication<GatewayApiApplication>(*args)
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test`
Expected: PASS — gateway boots, health `UP`.

- [ ] **Step 7: Commit**

```bash
git add app/gateway-api
git commit -m "feat(gateway-api): boot Spring service with health (P0-A task 7)"
```

---

## Task 8: `web/gateway` Next.js app

**Files:**
- Create: `web/gateway/package.json`, `web/gateway/next.config.mjs`, `web/gateway/tsconfig.json`
- Create: `web/gateway/app/layout.tsx`, `web/gateway/app/page.tsx`, `web/gateway/app/api/health/route.ts`
- Create: `web/gateway/.eslintrc.json`

- [ ] **Step 1: Write `web/gateway/package.json`**

```json
{
  "name": "@opensamguk/web-gateway",
  "private": true,
  "version": "0.0.1",
  "scripts": {
    "dev": "next dev -p 3000",
    "build": "next build",
    "start": "next start -p 3000",
    "lint": "next lint",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "next": "15.1.3",
    "react": "19.0.0",
    "react-dom": "19.0.0"
  },
  "devDependencies": {
    "typescript": "5.7.2",
    "@types/node": "22.10.2",
    "@types/react": "19.0.2",
    "@types/react-dom": "19.0.2",
    "eslint": "9.17.0",
    "eslint-config-next": "15.1.3"
  }
}
```

- [ ] **Step 2: Write config files**

`web/gateway/next.config.mjs`:

```javascript
/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    reactStrictMode: true,
};

export default nextConfig;
```

`web/gateway/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["dom", "dom.iterable", "esnext"],
    "module": "esnext",
    "moduleResolution": "bundler",
    "jsx": "preserve",
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": { "@/*": ["./*"] }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
```

`web/gateway/.eslintrc.json`:

```json
{ "extends": "next/core-web-vitals" }
```

- [ ] **Step 3: Write the app shell**

`web/gateway/app/layout.tsx`:

```tsx
export const metadata = { title: 'opensamguk Gateway' };

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="ko">
            <body>{children}</body>
        </html>
    );
}
```

`web/gateway/app/page.tsx`:

```tsx
export default function Home() {
    return (
        <main>
            <h1>opensamguk — Gateway</h1>
            <p>Scaffold OK.</p>
        </main>
    );
}
```

`web/gateway/app/api/health/route.ts`:

```typescript
import { NextResponse } from 'next/server';

export function GET() {
    return NextResponse.json({ status: 'UP', app: 'web-gateway' });
}
```

- [ ] **Step 4: Install and verify the build**

Run: `cd web/gateway && corepack pnpm install && corepack pnpm build`
Expected: `✓ Compiled successfully` and a `.next/` standalone output is produced. Confirm by reading the build output tail for `Compiled successfully` (not just exit code).

- [ ] **Step 5: Commit**

```bash
cd /Users/apple/Desktop/개인프로젝트/opensamguk
git add web/gateway
git commit -m "feat(web-gateway): scaffold Next.js gateway app (P0-A task 8)"
```

---

## Task 9: `web/game` Next.js app

**Files:**
- Create: `web/game/package.json`, `web/game/next.config.mjs`, `web/game/tsconfig.json`, `web/game/.eslintrc.json`
- Create: `web/game/app/layout.tsx`, `web/game/app/page.tsx`, `web/game/app/api/health/route.ts`

- [ ] **Step 1: Write `web/game/package.json`** (same as gateway, different name + port)

```json
{
  "name": "@opensamguk/web-game",
  "private": true,
  "version": "0.0.1",
  "scripts": {
    "dev": "next dev -p 3001",
    "build": "next build",
    "start": "next start -p 3001",
    "lint": "next lint",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "next": "15.1.3",
    "react": "19.0.0",
    "react-dom": "19.0.0"
  },
  "devDependencies": {
    "typescript": "5.7.2",
    "@types/node": "22.10.2",
    "@types/react": "19.0.2",
    "@types/react-dom": "19.0.2",
    "eslint": "9.17.0",
    "eslint-config-next": "15.1.3"
  }
}
```

- [ ] **Step 2: Write config files** (identical to Task 8 Step 2, in `web/game/`)

`web/game/next.config.mjs`:

```javascript
/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    reactStrictMode: true,
};

export default nextConfig;
```

`web/game/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["dom", "dom.iterable", "esnext"],
    "module": "esnext",
    "moduleResolution": "bundler",
    "jsx": "preserve",
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": { "@/*": ["./*"] }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
```

`web/game/.eslintrc.json`:

```json
{ "extends": "next/core-web-vitals" }
```

- [ ] **Step 3: Write the app shell**

`web/game/app/layout.tsx`:

```tsx
export const metadata = { title: 'opensamguk Game' };

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="ko">
            <body>{children}</body>
        </html>
    );
}
```

`web/game/app/page.tsx`:

```tsx
export default function Home() {
    return (
        <main>
            <h1>opensamguk — Game</h1>
            <p>Scaffold OK.</p>
        </main>
    );
}
```

`web/game/app/api/health/route.ts`:

```typescript
import { NextResponse } from 'next/server';

export function GET() {
    return NextResponse.json({ status: 'UP', app: 'web-game' });
}
```

- [ ] **Step 4: Install and verify the build**

Run: `cd web/game && corepack pnpm install && corepack pnpm build`
Expected: `✓ Compiled successfully`; `.next/` standalone produced. Confirm in output tail.

- [ ] **Step 5: Commit**

```bash
cd /Users/apple/Desktop/개인프로젝트/opensamguk
git add web/game
git commit -m "feat(web-game): scaffold Next.js game app (P0-A task 9)"
```

---

## Task 10: Docker Compose + Dockerfiles + nginx + smoke test

**Files:**
- Create: `docker/gateway-api.Dockerfile`, `docker/game-api.Dockerfile`, `docker/game-engine.Dockerfile`
- Create: `docker/web-gateway.Dockerfile`, `docker/web-game.Dockerfile`
- Create: `nginx/nginx.conf`
- Create: `docker-compose.yml`
- Create: `tools/smoke.sh`

- [ ] **Step 1: Write the Spring service Dockerfile** `docker/game-api.Dockerfile` (multi-stage Gradle build → JRE 21 runtime)

```dockerfile
# syntax=docker/dockerfile:1
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:game-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /src/app/game-api/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: Write `docker/game-engine.Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:game-engine:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /src/app/game-engine/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: Write `docker/gateway-api.Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:gateway-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /src/app/gateway-api/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 4: Write the Next.js Dockerfiles**

`docker/web-gateway.Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /src
RUN corepack enable
COPY web/gateway/package.json web/gateway/
WORKDIR /src/web/gateway
RUN corepack pnpm install --no-frozen-lockfile
COPY web/gateway/ .
RUN corepack pnpm build

FROM node:20-alpine AS run
WORKDIR /app
COPY --from=build /src/web/gateway/.next/standalone ./
COPY --from=build /src/web/gateway/.next/static ./.next/static
COPY --from=build /src/web/gateway/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
```

`docker/web-game.Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /src
RUN corepack enable
COPY web/game/package.json web/game/
WORKDIR /src/web/game
RUN corepack pnpm install --no-frozen-lockfile
COPY web/game/ .
RUN corepack pnpm build

FROM node:20-alpine AS run
WORKDIR /app
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./.next/static
COPY --from=build /src/web/game/public ./public
EXPOSE 3001
CMD ["node", "server.js"]
```

> Next.js `output: 'standalone'` does not emit `public/` if it is empty. Create an empty placeholder so the `COPY public` layer never fails: `mkdir -p web/gateway/public web/game/public && touch web/gateway/public/.gitkeep web/game/public/.gitkeep`.

- [ ] **Step 5: Write `nginx/nginx.conf`**

```nginx
events {}

http {
    upstream gateway_api { server gateway-api:8080; }
    upstream game_api    { server game-api:8081; }
    upstream web_gateway { server web-gateway:3000; }
    upstream web_game    { server web-game:3001; }

    server {
        listen 80;

        location /api/gateway/ { proxy_pass http://gateway_api/; }
        location /api/game/    { proxy_pass http://game_api/; }
        location /game/        { proxy_pass http://web_game/; }
        location /             { proxy_pass http://web_gateway/; }

        # SSE: disable buffering so realtime frames flush immediately (used in P0-B/P7).
        location /api/game/sse/ {
            proxy_pass http://game_api/sse/;
            proxy_buffering off;
            proxy_cache off;
            proxy_set_header Connection '';
            proxy_http_version 1.1;
            chunked_transfer_encoding off;
        }
    }
}
```

- [ ] **Step 6: Write `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: sammo
      POSTGRES_USER: sammo
      POSTGRES_PASSWORD: sammo
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U sammo"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  gateway-api:
    build:
      context: .
      dockerfile: docker/gateway-api.Dockerfile
    environment:
      REDIS_HOST: redis
    depends_on:
      redis: { condition: service_healthy }
    ports: ["8080:8080"]

  game-api:
    build:
      context: .
      dockerfile: docker/game-api.Dockerfile
    environment:
      GAME_DATABASE_URL: jdbc:postgresql://postgres:5432/sammo
      GAME_DB_USER: sammo
      GAME_DB_PASSWORD: sammo
      REDIS_HOST: redis
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    ports: ["8081:8081"]

  game-engine:
    build:
      context: .
      dockerfile: docker/game-engine.Dockerfile
    environment:
      GAME_DATABASE_URL: jdbc:postgresql://postgres:5432/sammo
      GAME_DB_USER: sammo
      GAME_DB_PASSWORD: sammo
      REDIS_HOST: redis
      TURN_PROFILE_NAME: "che:scenario_2"
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    ports: ["8082:8082"]

  web-gateway:
    build:
      context: .
      dockerfile: docker/web-gateway.Dockerfile
    ports: ["3000:3000"]

  web-game:
    build:
      context: .
      dockerfile: docker/web-game.Dockerfile
    ports: ["3001:3001"]

  nginx:
    image: nginx:1.27-alpine
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on: [gateway-api, game-api, web-gateway, web-game]
    ports: ["80:80"]
```

> `game-api`/`game-engine` both run Flyway on boot. Flyway holds a per-schema advisory lock, so concurrent startup is safe (one applies the baseline, the other waits then sees it applied).

- [ ] **Step 7: Write the smoke test** `tools/smoke.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "==> building + starting stack"
docker compose up -d --build

cleanup() { docker compose logs --no-color > tools/smoke.log 2>&1 || true; }
trap cleanup EXIT

wait_for() {
    local name="$1" url="$2" tries=60
    echo "==> waiting for $name ($url)"
    until curl -fsS "$url" >/dev/null 2>&1; do
        tries=$((tries - 1))
        if [ "$tries" -le 0 ]; then echo "FAIL: $name not healthy"; exit 1; fi
        sleep 3
    done
    echo "OK: $name"
}

wait_for "gateway-api" "http://localhost:8080/actuator/health"
wait_for "game-api"    "http://localhost:8081/actuator/health"
wait_for "game-engine" "http://localhost:8082/admin/turn-daemon/status"
wait_for "web-gateway" "http://localhost:3000/api/health"
wait_for "web-game"    "http://localhost:3001/api/health"
wait_for "nginx->gateway" "http://localhost:80/api/gateway/actuator/health"

echo "==> ALL SERVICES HEALTHY"
docker compose down
```

- [ ] **Step 8: Make executable and run the smoke test**

Run:
```bash
chmod +x tools/smoke.sh
mkdir -p web/gateway/public web/game/public && touch web/gateway/public/.gitkeep web/game/public/.gitkeep
./tools/smoke.sh
```
Expected: terminates with `==> ALL SERVICES HEALTHY`. (First run is slow — Gradle/Next builds inside Docker.) If a service fails, inspect `tools/smoke.log`.

- [ ] **Step 9: Commit**

```bash
git add docker docker-compose.yml nginx tools web/gateway/public/.gitkeep web/game/public/.gitkeep
git commit -m "feat(ops): Docker Compose stack (postgres/redis/nginx + 5 services) + smoke test (P0-A task 10)"
```

---

## Task 11: GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the CI workflow** `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  jvm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build + test (Testcontainers uses the runner's Docker)
        run: ./gradlew build --no-daemon
      - name: Surface test results
        if: always()
        run: |
          echo "== test summary =="
          find . -path '*/build/test-results/*/*.xml' -print | head -50

  web:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        app: [gateway, game]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - run: corepack enable
      - name: Install + build web/${{ matrix.app }}
        working-directory: web/${{ matrix.app }}
        run: |
          corepack pnpm install --no-frozen-lockfile
          corepack pnpm build
```

- [ ] **Step 2: Verify the workflow locally (lint the YAML + dry-run the Gradle target)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --dry-run`
Expected: prints the task graph including each module's `:test` and `:bootJar` without errors. (CI itself runs on push.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add JVM + web build/test workflow (P0-A task 11)"
```

---

## Task 12: Root README, `.env.example`, and full-stack verification

**Files:**
- Create: `README.md`, `.env.example`

- [ ] **Step 1: Write `.env.example`**

```dotenv
# Game DB
GAME_DATABASE_URL=jdbc:postgresql://localhost:5432/sammo
GAME_DB_USER=sammo
GAME_DB_PASSWORD=sammo
# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
# Ports
GATEWAY_API_PORT=8080
GAME_API_PORT=8081
GAME_ENGINE_PORT=8082
# Profile (server:scenario)
TURN_PROFILE_NAME=che:scenario_2
```

- [ ] **Step 2: Write `README.md`**

````markdown
# opensamguk

삼국지 모의전투 HiDCHe(삼모) — Kotlin/Spring + Next.js, 메모리 중심 CQRS 재작성.

Migration program design: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.

## Modules

- `common` / `logic` / `infra` — shared libraries (RNG/log kernel, pure game logic, JPA+Flyway+Redis).
- `app/gateway-api` — auth + profile orchestration (`:8080`).
- `app/game-api` — read + precheck + mutation intake + SSE (`:8081`).
- `app/game-engine` — turn daemon: in-memory authoritative world + bulk flush (`:8082`).
- `web/gateway` / `web/game` — Next.js apps (`:3000` / `:3001`).

## Develop

Requires JDK 21 (build Gradle with `JAVA_HOME` on 21), Docker, Node 20 + pnpm.

```bash
./gradlew build           # compile + unit/integration tests (Testcontainers)
./tools/smoke.sh          # build + boot full Docker stack, assert health
cd web/gateway && corepack pnpm dev   # run a frontend
```
````

- [ ] **Step 3: Final verification — whole build is green**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean build`
Expected: `BUILD SUCCESSFUL`. Confirm by reading the output tail: every module reports `Tests run` with 0 failures (`:common:test`, `:logic:test`, `:infra:test`, `:app:game-api:test`, `:app:game-engine:test`, `:app:gateway-api:test`).

- [ ] **Step 4: Commit**

```bash
git add README.md .env.example
git commit -m "docs: add README + env example; P0-A foundation scaffold complete"
```

---

## Self-Review

**1. Spec coverage (against design doc §11 P0 + §5):**
- Gradle multi-module monorepo (common/infra/logic + game-engine/game-api/gateway-api) → Tasks 1–7. ✔
- Next.js gateway + game apps → Tasks 8–9. ✔
- Docker Compose (postgres/redis/nginx/daemon/api) + nginx → Task 10. ✔
- GitHub Actions CI with Testcontainers → Task 11 (+ Testcontainers used in Tasks 4–6). ✔
- Flyway baseline migration from prisma schema → Task 4 (V1__baseline.sql, all game.prisma models except deferred `emperior`). ✔
- JVM toolchain 21 / verify-output policy → pinned in every module + run commands. ✔
- **Deferred to P0-B (documented, not gaps):** RNG/RandUtil/serializeSeed kernel, JosaUtil + log-token model, constants transcription, Redis wire-contract sealed types, InMemoryTurnWorld + dirty-set + databaseHooks flush stub + Redis Streams consumer + SSE, flush-exclusion/rehydrate contract. P0-A scope is explicitly "stack runnable + schema testable"; the parity kernel + CQRS skeleton is the P0-B plan. ✔

**2. Placeholder scan:** No "TBD/handle errors/add validation". The two `StatusController`/`WorldStateEntity` notes explicitly state what is intentionally minimal and where the full version lands — not vague placeholders. Each code step contains complete, runnable content.

**3. Type consistency:** Package `opensamguk.*` and module coordinates (`:common`, `:logic`, `:infra`, `:app:game-api`, `:app:game-engine`, `:app:gateway-api`) are consistent across settings, build files, Dockerfiles, and CI. Table/column names in `V1__baseline.sql` match the `WorldStateEntity` `@Column` mappings (`scenario_code`, `current_year`, `current_month`, `tick_seconds`, `updated_at`). Health/status URLs in tests match the `application.yml` actuator exposure and the `StatusController` route. Compose env var names (`GAME_DATABASE_URL`, `REDIS_HOST`, `TURN_PROFILE_NAME`) match the `application.yml` placeholders and `.env.example`.

No issues found requiring further fixes.
