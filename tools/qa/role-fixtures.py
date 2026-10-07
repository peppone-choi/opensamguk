#!/usr/bin/env python3
"""QA setup only: actual authentication and creation, then explicitly marked role fixtures."""
import argparse
import json
from pathlib import Path
import runpy
import secrets
import time
import urllib.error
import urllib.request

STACK = runpy.run_path(str(Path(__file__).with_name("local-stack.py")))
ORIGIN = "http://127.0.0.1:18300"
ROLES = [(nation, role) for nation in "ABC" for role in ("lord", "manager", "general1", "general2")]
ROLES += [("free", f"wanderer{i}") for i in range(1, 5)]


class LoopbackOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("QA authentication must not redirect to another destination")


def api(path, body=None, token=None):
    if not path.startswith("/") or path.startswith("//"):
        raise ValueError("QA API path must be local")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Cookie"] = f"sam_access={token}; sam_server=qa160"
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(ORIGIN + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    with urllib.request.build_opener(LoopbackOnly()).open(request, timeout=30) as response:
        return response.status, json.load(response)


def save_accounts(directory, rows):
    path = directory / "accounts.json"
    if not path.exists():
        STACK["private_json"](path, rows)
    else:
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("QA credentials must remain private")
        path.write_text(json.dumps(rows, ensure_ascii=False) + "\n")


def credentials(directory):
    path = directory / "accounts.json"
    if path.exists():
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("QA credentials must remain private")
        return json.loads(path.read_text())
    rows = [{"nation": nation, "role": role, "username": f"qa160{nation.lower()}{role}",
             "password": secrets.token_urlsafe(24), "name": f"QA-{nation}-{role}"}
            for nation, role in ROLES]
    save_accounts(directory, rows)
    return rows


def login(row):
    _, result = api("/api/gateway/auth/login", {"username": row["username"], "password": row["password"]})
    if result["user"]["role"] != "USER":
        raise ValueError("Gameplay QA accounts must have the normal USER account role")
    token = result["accessToken"]
    _, me = api("/api/gateway/auth/me", token=token)
    if me["id"] != result["user"]["id"] or me["role"] != "USER":
        raise ValueError("Normal authentication roundtrip failed")
    row["userId"] = me["id"]
    return token


def general_rows(directory, rows):
    ids = ",".join(quote(int(row["userId"])) for row in rows)
    statement = f"""SELECT coalesce(json_agg(json_build_object('id',id,'userId',user_id,
        'name',name,'nationId',nation_id,'npcState',npc_state,'cityId',city_id,'officerLevel',officer_level,'meta',meta)), '[]')
        FROM general WHERE world_id=160 AND user_id IN ({ids});"""
    return json.loads(STACK["sql"](directory, "game", statement))


def create_accounts(directory):
    rows = credentials(directory)
    for row in rows:
        try:
            token = login(row)
        except urllib.error.HTTPError as exc:
            if exc.code != 401:
                raise
            api("/api/gateway/auth/register", {"username": row["username"], "password": row["password"],
                                               "nickname": row["name"]})
            token = login(row)
        save_accounts(directory, rows)
        existing = general_rows(directory, [row])
        if not existing:
            _, result = api("/api/game/api/join?server=qa160", {"name": row["name"], "leadership": 50,
                "strength": 50, "intel": 50, "politics": 50, "charm": 50, "pic": False}, token)
            if result.get("status") not in ("AVAILABLE", "ACCEPTED"):
                raise ValueError("Normal character creation was rejected")
        deadline = time.monotonic() + 60
        while not existing and time.monotonic() < deadline:
            time.sleep(2)
            existing = general_rows(directory, [row])
        if len(existing) != 1 or existing[0]["name"] != row["name"] or existing[0]["npcState"] != 0:
            raise ValueError("Actual engine-persisted character ownership did not match the account")
        row["generalId"] = existing[0]["id"]
        save_accounts(directory, rows)
        print(f"{row['nation']}/{row['role']}: normal login and persisted character link verified")
    return rows


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def role_sql(rows, plan):
    if len(rows) != 16 or len({r["generalId"] for r in rows}) != 16:
        raise ValueError("Prepare all sixteen real characters before assigning fixtures")
    nations, cities, samples = (plan[key] for key in ("nationIds", "cityIds", "positionGeneralIds"))
    if any(len(values) != 3 or len(set(values)) != 3 or any(type(v) != int or v <= 0 for v in values)
           for values in (nations, cities, samples)):
        raise ValueError("Use three explicit seeded nations, counties and position witnesses")
    statements = ["BEGIN;", "SELECT pg_advisory_xact_lock(160);", "DO $$ BEGIN IF "
        "(SELECT count(*) FROM world_state)!=1 OR NOT EXISTS(SELECT 1 FROM world_state WHERE id=160 AND tick_seconds=120) "
        "THEN RAISE EXCEPTION 'Not QA160 120-second world'; END IF; END $$;"]
    for index, letter in enumerate("ABC"):
        nation, city, sample = nations[index], cities[index], samples[index]
        people = [r for r in rows if r["nation"] == letter]
        lord = next(r["generalId"] for r in people if r["role"] == "lord")
        statements.append(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM city WHERE world_id=160 AND id={city} AND nation_id={nation}) "
            f"OR NOT EXISTS(SELECT 1 FROM general_spatial_position p JOIN general g ON g.world_id=p.world_id AND g.id=p.general_id "
            f"WHERE g.world_id=160 AND g.id={sample} AND g.city_id={city}) THEN RAISE EXCEPTION 'Fixture position mismatch'; END IF; END $$;")
        statements.append(f"INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,release_policy) "
            f"SELECT 160,(SELECT coalesce(max(id),0)+1 FROM general_retainers WHERE world_id=160),{lord},'EXISTING',g.id,g.name,'lieutenant','MUTUAL' "
            f"FROM general g WHERE g.world_id=160 AND g.nation_id={nation} AND g.officer_level=12 "
            "AND NOT EXISTS(SELECT 1 FROM general_retainers r WHERE r.world_id=g.world_id AND r.general_id=g.id);")
        statements.append(f"UPDATE general SET officer_level=0 WHERE world_id=160 AND nation_id={nation} AND officer_level=12;")
        binding = {"currentRulerBinding": {"generalId": lord, "revision": "synthetic-qa160-setup-v1", "sourceInputId": "scenario.seed"}}
        statements.append(f"UPDATE nation SET meta=meta||{quote(json.dumps(binding))}::jsonb WHERE world_id=160 AND id={nation};")
        for row in people:
            gid, level = row["generalId"], 12 if row["role"] == "lord" else 0
            patch = {"lord": row["role"] == "lord", "qa160Fixture": {"role": row["role"], "version": 1},
                     "portableStock": {"version": 1, "money": 50000, "grain": 50000, "iron": 20000,
                                       "timber": 20000, "horses": 10000}}
            statements.append(f"UPDATE general SET nation_id={nation},city_id={city},officer_level={level},gold=50000,rice=50000,"
                f"meta=jsonb_set(jsonb_set(meta||{quote(json.dumps(patch))}::jsonb,'{{personPolicy,renownCapacity}}','100'::jsonb),"
                "'{personPolicy,acceptsEnlistment}','true'::jsonb) "
                f"WHERE world_id=160 AND id={gid} AND user_id={quote(row['userId'])} AND npc_state=0;")
            statements.append(f"UPDATE general_spatial_position SET (topology_revision,topology_hash,node_kind,node_id)="
                f"(SELECT topology_revision,topology_hash,node_kind,node_id FROM general_spatial_position WHERE world_id=160 AND general_id={sample}),"
                f"revision=revision+1 WHERE world_id=160 AND general_id={gid};")
            if row["role"] != "lord":
                statements.append(retainer_insert(lord, gid, row["name"]))
            if row["role"] in ("lord", "manager"):
                statements.append(bugok_insert(gid, row["name"]))
        manager = next(r["generalId"] for r in people if r["role"] == "manager")
        statements.append(f"UPDATE general_retainers SET master_general_id={manager} WHERE world_id=160 AND id IN "
            f"(SELECT r.id FROM general_retainers r JOIN general g ON g.world_id=r.world_id AND g.id=r.general_id "
            f"WHERE r.world_id=160 AND g.nation_id={nation} AND g.npc_state>=2 AND g.id<>{sample} ORDER BY r.id LIMIT 2);")
        statements.append(f"DO $$ BEGIN IF (SELECT count(*) FROM general_retainers WHERE world_id=160 AND master_general_id={manager})<2 "
            f"OR NOT EXISTS(SELECT 1 FROM general_bugok WHERE world_id=160 AND master_general_id={manager}) "
            "THEN RAISE EXCEPTION 'Manager needs actual NPC followers and a bugok'; END IF; END $$;")
    a, b, c = nations
    statements.append(f"UPDATE diplomacy SET state_code=0,term=24 WHERE world_id=160 AND (src_nation_id,dest_nation_id) IN (({a},{b}),({b},{a}));")
    # The current product has explicit non-aggression (7), not an alliance code.
    # Keep this distinction visible; this fixture never claims an alliance feature passed.
    statements.append(f"UPDATE diplomacy SET state_code=7,term=24 WHERE world_id=160 AND (src_nation_id,dest_nation_id) IN (({a},{c}),({c},{a}));")
    statements += ["UPDATE world_state SET meta=meta||jsonb_build_object('qa160RoleFixture',1,'maxRetainerId',"
        "(SELECT max(id) FROM general_retainers WHERE world_id=160),'maxBugokId',"
        "(SELECT max(id) FROM general_bugok WHERE world_id=160)) WHERE id=160;", "COMMIT;"]
    return "\n".join(statements)


def retainer_insert(master, general, name):
    return f"""INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,release_policy)
        SELECT 160,coalesce(max(id),0)+1,{master},'EXISTING',{general},{quote(name)},'lieutenant','MUTUAL'
        FROM general_retainers WHERE world_id=160;"""


def bugok_insert(master, name):
    return f"""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,provisions)
        SELECT 160,coalesce(max(id),0)+1,{master},{quote(name+' 부곡')},3000,1100,70,70,10000
        FROM general_bugok WHERE world_id=160;"""


def apply_roles(directory, plan_path):
    rows = credentials(directory)
    plan = json.loads(Path(plan_path).read_text())
    statement = role_sql(rows, plan)
    with STACK["heavy"]():
        STACK["compose"](directory, "stop", "game-api", "game-engine")
        STACK["container_guard"]("game-engine", running=False)
        try:
            applied = STACK["sql"](directory, "game", "SELECT meta->>'qa160RoleFixture' FROM world_state WHERE id=160;")
            if applied:
                raise ValueError("Role fixtures already applied; do not overwrite live QA progress")
            STACK["sql"](directory, "game", statement)
        finally:
            STACK["compose"](directory, "up", "-d", "--no-build", "--wait", "--wait-timeout", "300", "game-engine", "game-api")
    verify(directory)


def verify(directory):
    rows = credentials(directory)
    actual = general_rows(directory, rows)
    if len(actual) != 16:
        raise ValueError("Sixteen persisted owned characters are required")
    by_user = {str(r["userId"]): r for r in actual}
    for row in rows:
        login(row)
        person = by_user.get(str(row["userId"]))
        if not person or person["id"] != row["generalId"] or person["npcState"] != 0:
            raise ValueError("QA account and character ownership mismatch")
        if row["nation"] == "free" and person["nationId"] != 0:
            raise ValueError("Wanderer fixture is affiliated")
        print(f"{row['nation']}/{row['role']}: account {row['userId']} → character {person['id']} verified")
    applied = STACK["sql"](directory, "game", "SELECT meta->>'qa160RoleFixture' FROM world_state WHERE id=160;")
    if not applied:
        print("Actual account/character links verified; role fixtures have not been applied")
        return
    for row in rows:
        person = by_user[str(row["userId"])]
        if row["nation"] != "free" and person["nationId"] <= 0:
            raise ValueError("Affiliated role fixture has no actual nation")
        if row["role"] == "lord" and (person["officerLevel"] != 12 or person["meta"].get("lord") is not True):
            raise ValueError("Lord fixture lacks actual lord status and ruler office")
        if row["role"] == "manager":
            followers = STACK["sql"](directory, "game", f"SELECT count(*) FROM general_retainers r JOIN general g "
                f"ON g.world_id=r.world_id AND g.id=r.general_id WHERE r.world_id=160 AND r.master_general_id={person['id']} "
                "AND g.npc_state>=2 AND g.nation_id=(SELECT nation_id FROM general WHERE world_id=160 AND id=r.master_general_id);")
            if int(followers) < 2:
                raise ValueError("Middle manager lacks actual same-nation NPC followers")
    print("Persisted role fixtures verified; actual browser interaction remains a separate check")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("accounts", "roles", "verify"))
    parser.add_argument("--custody", required=True)
    parser.add_argument("--fixture-plan")
    args = parser.parse_args()
    directory = STACK["custody"](args.custody)
    if args.action == "accounts":
        create_accounts(directory)
    elif args.action == "roles":
        apply_roles(directory, args.fixture_plan)
    else:
        verify(directory)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        raise SystemExit(f"QA fixture stopped: {type(exc).__name__}; credentials withheld") from None
