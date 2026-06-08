# bbae second-server stack — superseded

Status: superseded on 2026-06-09.

The baked `main`/`bbae` server split is no longer the production model. Production must start with an empty server registry and no pre-created game worlds; admins create servers through the admin surface first.

Do not restore `docker-compose.bbae.yml`, bbae deploy steps, or bbae reseed scripts unless a new admin-created multi-server design explicitly replaces this decision.
