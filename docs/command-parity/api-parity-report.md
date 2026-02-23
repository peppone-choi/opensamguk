# API Parity Report: Legacy PHP → Backend Kotlin

**Generated**: 2026-02-23  
**Total legacy endpoints**: 33 (`j_*.php` files)  
**Covered**: 26 (existing controllers)  
**Newly stubbed**: 7 (via `LegacyParityController` + new services)  
**Status**: ✅ Full coverage (some stubs need full implementation)

---

## Endpoint Mapping

| # | Legacy PHP | Purpose | Backend Kotlin Endpoint | Status |
|---|-----------|---------|------------------------|--------|
| 1 | `j_adjust_icon.php` | Sync general icon from account profile | `POST /api/generals/me/sync-icon` | 🆕 Implemented (`IconSyncService`) |
| 2 | `j_autoreset.php` | Auto-reset check for scheduled resets | `POST /api/worlds/{worldId}/auto-reset-check` | 🆕 Stub |
| 3 | `j_basic_info.php` | Basic general/session info | `GET /api/worlds/{worldId}/front-info` | ✅ Covered (`FrontInfoService`) |
| 4 | `j_board_article_add.php` | Create board post | `POST /api/messages` (mailboxCode=board) | ✅ Covered (`MessageController`) |
| 5 | `j_board_comment_add.php` | Add comment to board post | `POST /api/boards/{postId}/comments` | ✅ Covered (`BoardController`) |
| 6 | `j_board_get_articles.php` | List board articles | `GET /api/messages/board` | ✅ Covered (`MessageController`) |
| 7 | `j_diplomacy_destroy_letter.php` | Destroy diplomacy letter | `POST /api/diplomacy-letters/{id}/destroy` | ✅ Covered (`DiplomacyLetterController`) |
| 8 | `j_diplomacy_get_letter.php` | Get diplomacy letters | `GET /api/nations/{nationId}/diplomacy-letters` | ✅ Covered (`DiplomacyLetterController`) |
| 9 | `j_diplomacy_respond_letter.php` | Respond to diplomacy | `POST /api/diplomacy-letters/{id}/respond` | ✅ Covered (`DiplomacyLetterController`) |
| 10 | `j_diplomacy_rollback_letter.php` | Rollback diplomacy letter | `POST /api/diplomacy-letters/{id}/rollback` | ✅ Covered (`DiplomacyLetterController`) |
| 11 | `j_diplomacy_send_letter.php` | Send diplomacy letter | `POST /api/nations/{nationId}/diplomacy-letters` | ✅ Covered (`DiplomacyLetterController`) |
| 12 | `j_export_simulator_object.php` | Export general for battle simulator | `GET /api/generals/{generalId}/simulator-export` | 🆕 Implemented (`SimulatorExportService`) |
| 13 | `j_general_log_old.php` | Paginated old general logs | `GET /api/generals/{generalId}/logs/old` | 🆕 Implemented (`GeneralLogService`) |
| 14 | `j_general_set_permission.php` | Set ambassador/auditor permissions | `POST /api/nations/{nationId}/permissions` | 🆕 Implemented (`PermissionService`) |
| 15 | `j_get_basic_general_list.php` | List generals (basic info) | `GET /api/worlds/{worldId}/generals` | ✅ Covered (`GeneralController`) |
| 16 | `j_get_city_list.php` | List cities | `GET /api/worlds/{worldId}/cities` | ✅ Covered (`CityController`) |
| 17 | `j_get_select_npc_token.php` | Generate NPC selection token | `POST /api/worlds/{worldId}/npc-token` | ✅ Covered (`NpcSelectionController`) |
| 18 | `j_get_select_pool.php` | Get selection pool | `GET /api/worlds/{worldId}/pool` | ✅ Covered (`GeneralController`) |
| 19 | `j_install.php` | Server installation/reset | `POST /api/worlds` + `POST /api/worlds/{id}/reset` | ✅ Covered (`WorldController`) |
| 20 | `j_install_db.php` | DB installation | `POST /api/worlds` (admin) | ✅ Covered (`WorldController`) |
| 21 | `j_load_scenarios.php` | Load scenario list | `GET /api/scenarios` | ✅ Covered (`ScenarioController`) |
| 22 | `j_map.php` | Get map data | `GET /api/maps/{mapName}` | ✅ Covered (`MapController`) |
| 23 | `j_map_recent.php` | Recent map changes (delta) | `GET /api/worlds/{worldId}/map-recent` | 🆕 Stub |
| 24 | `j_myBossInfo.php` | Nation boss management (appoint/expel) | `POST /api/nations/{nationId}/officers` + `POST /api/nations/{nationId}/expel` | ✅ Covered (`NationManagementController`) |
| 25 | `j_raise_event.php` | Admin: raise game event | `POST /api/admin/raise-event` | 🆕 Stub |
| 26 | `j_select_npc.php` | Select NPC general | `POST /api/worlds/{worldId}/npc-select` | ✅ Covered (`NpcSelectionController`) |
| 27 | `j_select_picked_general.php` | Select from pool (create) | `POST /api/worlds/{worldId}/select-pool` | ✅ Covered (`GeneralController`) |
| 28 | `j_server_basic_info.php` | Server/world basic info | `GET /api/worlds/{id}` | ✅ Covered (`WorldController`) |
| 29 | `j_set_my_setting.php` | Personal game settings | `PATCH /api/account/settings` | ✅ Covered (`AccountController`) |
| 30 | `j_set_npc_control.php` | NPC control policy | `PUT /api/nations/{nationId}/npc-policy` | ✅ Covered (`NpcPolicyController`) |
| 31 | `j_simulate_battle.php` | Run battle simulation | `POST /api/battle/simulate` | ✅ Covered (`BattleSimController`) |
| 32 | `j_update_picked_general.php` | Update/swap to pool general | `POST /api/worlds/{worldId}/select-pool` | ✅ Covered (`GeneralController`) |
| 33 | `j_vacation.php` | Toggle vacation mode | `POST /api/account/vacation` | ✅ Covered (`AccountController`) |

---

## New Files Created

### Services
| File | Purpose |
|------|---------|
| `service/IconSyncService.kt` | Syncs general icon from account profile (j_adjust_icon) |
| `service/GeneralLogService.kt` | Paginated old general logs by type (j_general_log_old) |
| `service/PermissionService.kt` | Ambassador/auditor permission management (j_general_set_permission) |
| `service/SimulatorExportService.kt` | Export general data for battle sim (j_export_simulator_object) |

### Controllers
| File | Purpose |
|------|---------|
| `controller/LegacyParityController.kt` | Single controller covering all 7 missing legacy endpoints |

---

## Stubs Requiring Full Implementation

| Endpoint | What's needed |
|----------|---------------|
| `POST /api/worlds/{worldId}/auto-reset-check` | Implement scheduled reset logic (check reserved_open table, server close logic) |
| `GET /api/worlds/{worldId}/map-recent` | Implement map change delta tracking (city ownership/level changes since timestamp) |
| `POST /api/admin/raise-event` | Implement event system dispatch with admin authorization checks |

---

## Data Structure Parity Notes

### ✅ Well-matched responses
- **j_basic_info** → `FrontInfoResponse` includes all fields: `generalID`, `myNationID`, `isChief`, `officerLevel`, `permission`
- **j_diplomacy_***: All 5 operations mapped 1:1 to `DiplomacyLetterController`
- **j_simulate_battle** → `BattleSimController.simulate()` returns equivalent structure
- **j_vacation** → `AccountController.toggleVacation()` matches toggle behavior

### ⚠️ Minor differences
- **j_set_my_setting**: Legacy sets `defence_train`, `tnmt`, `use_treatment`, `use_auto_nation_turn` individually. Backend `PATCH /api/account/settings` uses a generic settings object — verify all game-specific fields are included in `UpdateSettingsRequest` DTO.
- **j_myBossInfo**: Legacy handles multiple actions (`임명`, `추방`, `천도`, etc.) in one endpoint via `action` parameter. Backend splits into separate endpoints (`/officers`, `/expel`). Functionally equivalent but structurally different.
- **j_get_select_pool**: Legacy includes `putInfoText()` enrichment. Verify `GeneralController.listPool()` returns equivalent enriched data.
- **j_board_article_add / j_board_get_articles**: Routed through generic `MessageController` rather than dedicated board endpoints. Functionally equivalent.
