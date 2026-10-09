"""Cold PG/Redis clones used by the preserving rehearsal; never live volumes."""
from contextlib import contextmanager
import hashlib
import io
import json
import re
import tempfile
import uuid

from game_server_recovery import (REDIS_CMD, VOLUMES, RecoveryError, require)
from pep_application_drill import PepApplicationDrill, expected_clock, status_matches


def identifier(value):
    require(isinstance(value, str) and value and '\0' not in value, 'invalid database identifier')
    return '"' + value.replace('"', '""') + '"'


def redis_fingerprint(recovery, container):
    """Read all 16 DBs; preserve stream data/groups and refuse any pending delivery.

    Empty consumers are runtime readers: XREADGROUP can create them without an AOF
    write. Their names/count/idle clocks are not recovery data when their PEL is empty.
    All other types retain exact DUMP comparison; key names and expiry always count.
    """
    require(recovery.docker.run(['container', 'exec', container, 'redis-cli', '--raw',
            'CONFIG', 'GET', 'databases']).strip() == b'databases\n16', 'unsupported Redis database inventory')
    script = """local function field(values,name)
      for i=1,#values,2 do if values[i]==name then return values[i+1] end end
      error('unsupported stream metadata'); end;
    local keys=redis.call('KEYS','*'); table.sort(keys); local out={};
    for _,key in ipairs(keys) do local data=redis.call('DUMP',key);
      if not data then return redis.error_reply('key expired during fingerprint') end;
      if redis.call('TYPE',key).ok=='stream' then
        local info=redis.call('XINFO','STREAM',key); local groups=redis.call('XINFO','GROUPS',key);
        table.sort(groups,function(a,b) return field(a,'name')<field(b,'name') end);
        local persistent={};
        for _,group in ipairs(groups) do
          if field(group,'pending')~=0 then return redis.error_reply('pending stream delivery unsupported') end;
          local name=field(group,'name');
          for _,consumer in ipairs(redis.call('XINFO','CONSUMERS',key,name)) do
            if field(consumer,'pending')~=0 then return redis.error_reply('pending stream consumer unsupported') end;
          end;
          table.insert(persistent,{name,field(group,'last-delivered-id'),field(group,'entries-read'),field(group,'lag')});
        end;
        data={'stream-persistent-v1',redis.call('XRANGE',key,'-','+'),field(info,'last-generated-id'),
          field(info,'max-deleted-entry-id'),field(info,'entries-added'),field(info,'recorded-first-entry-id'),persistent};
      end;
      table.insert(out,key); table.insert(out,data); table.insert(out,tostring(redis.call('PEXPIRETIME',key)));
    end; return out"""
    fingerprint = hashlib.sha256()
    for database in range(16):
        # redis-cli's explicit quoted representation escapes binary/newline bytes unambiguously.
        raw = recovery.docker.run(['container', 'exec', container, 'redis-cli', '-n', str(database),
                                  '--no-raw', 'EVAL', script, '0'])
        require(raw and b'(error)' not in raw[:16] and not raw.startswith((b'ERR ', b'error:')),
                'Redis fingerprint failed')
        fingerprint.update(database.to_bytes(1, 'big'))
        fingerprint.update(len(raw).to_bytes(8, 'big'))
        fingerprint.update(raw)
    return fingerprint.hexdigest()


def application_clock(rows, server_ids, world_id):
    """Bind the current engine clock contract to persisted world/active-server identity."""
    require(type(world_id) is int and world_id > 0 and isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict)
            and type(rows[0].get('id')) is int and rows[0]['id'] == world_id,
            'application clock world identity mismatch')
    require(isinstance(server_ids, list) and all(isinstance(value, str) and value.strip() for value in server_ids),
            'invalid persisted server identities')
    clock = expected_clock(rows)
    meta = rows[0].get('meta') or {}
    configured = next((meta[key] for key in ('serverId', 'server_id')
                       if isinstance(meta.get(key), str) and meta[key].strip()), None)
    if configured is not None:
        require(server_ids.count(configured) == 1, 'configured active server absent or ambiguous')
        server = configured
    else:
        require(len(server_ids) <= 1, 'active server identity is ambiguous')
        server = server_ids[0] if server_ids else None
    return dict(clock, worldId=world_id, serverId=server)


class StorageClone:
    """Ownership-checked disposable clone. Original source volumes are never mounted."""
    def __init__(self, recovery, bundle, manifest, env):
        self.recovery, self.bundle, self.manifest, self.env = recovery, bundle, manifest, env
        self.owner = PepApplicationDrill()
        self.owner.token = uuid.uuid4().hex
        self.prefix = 'pep-migrate-' + self.owner.token + '-'
        self.network = self.pg = self.redis = None

    def create(self, kind, suffix, args):
        name = self.prefix + suffix
        require(kind in ('container', 'volume', 'network'), 'unsupported clone resource')
        self.recovery.refuse_collisions([name])
        if kind == 'network':
            require(name not in self.recovery.docker.run(['network', 'ls', '--format', '{{.Name}}']).decode().splitlines(),
                    'migration network collision')
        return self.owner._create(self.recovery, kind, name,
            [kind, 'create', *(['--name', name] if kind == 'container' else []),
             '--label', 'org.opensamguk.pep-drill=' + self.owner.token, *args,
             *([] if kind == 'container' else [name])])

    @contextmanager
    def restored(self):
        manifest, env = self.recovery.validate_bundle(self.manifest['server'], self.bundle)
        require(manifest == self.manifest and env == self.env, 'cold bundle changed between clone stages')
        try:
            self.network = self.create('network', 'network', ['--internal'])
            stores = {}
            for service, suffix, filename in [('game-postgres', 'pgdata', 'postgres.tar'),
                                              ('game-redis', 'redisdata', 'redis.tar')]:
                stores[service] = self.create('volume', suffix, [])
                destination = VOLUMES[service][1]
                image = self.manifest['containers'][service]['image_id']
                extract = self.create('container', 'extract-' + suffix,
                    ['--network', 'none', '--pull=never', '--log-driver', 'none', '-i',
                     '--entrypoint', 'tar', '--mount',
                     f'type=volume,source={stores[service]},target={destination}', image,
                     '-xpf', '-', '--numeric-owner', '-C', destination])
                with (self.bundle / filename).open('rb') as archive:
                    self.recovery.docker.run(['container', 'start', '--attach', '--interactive', extract], stdin=archive)
            self.pg = self.create('container', 'postgres',
                ['--network', self.network, '--pull=never', '--log-driver', 'none',
                 '--memory', str(512 * 1024**2), '--user', 'postgres', '--entrypoint', 'postgres',
                 '--mount', f'type=volume,source={stores["game-postgres"]},target=/var/lib/postgresql/data',
                 self.manifest['containers']['game-postgres']['image_id'], '-D', '/var/lib/postgresql/data',
                 '-c', 'listen_addresses=*', '-c', 'unix_socket_directories=/tmp'])
            self.redis = self.create('container', 'redis',
                ['--network', self.network, '--pull=never', '--log-driver', 'none',
                 '--memory', str(256 * 1024**2), '--user', 'redis', '--entrypoint', 'redis-server',
                 '--mount', f'type=volume,source={stores["game-redis"]},target=/data',
                 self.manifest['containers']['game-redis']['image_id'], *REDIS_CMD[1:],
                 '--dir', '/data', '--aof-load-truncated', 'no'])
            for name in (self.pg, self.redis):
                self.recovery.docker.run(['container', 'start', name])
            self.recovery.postgres_check(self.pg, self.env)
            self.recovery.redis_check(self.redis, socket=None)
            yield self
        finally:
            cleanup = self.owner._cleanup(self.recovery)
            require(cleanup['success'], 'migration clone cleanup failed; inspect private owned resources')

    def sql(self, query, *, stdout=None):
        return self.recovery.docker.run(['container', 'exec', '-i', self.pg, 'psql', '-X', '-A', '-t',
            '-v', 'ON_ERROR_STOP=1', '-h', '/tmp', '-U', self.env['GAME_POSTGRES_USER'],
            '-d', self.env['GAME_POSTGRES_DB']], stdin=io.BytesIO(query.encode()), stdout=stdout)

    def columns(self):
        query = """SELECT coalesce(json_object_agg(name, columns), '{}'::json) FROM (
            SELECT c.relname AS name, json_agg(a.attname ORDER BY a.attnum) AS columns
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_attribute a ON a.attrelid=c.oid
            WHERE n.nspname='public' AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
              AND c.relname <> 'flyway_schema_history' GROUP BY c.relname) q;"""
        result = json.loads(self.sql(query))
        require(isinstance(result, dict) and {'world_state', 'city', 'nation', 'general'} <= set(result),
                'original world tables missing')
        return result

    def data_fingerprint(self, columns):
        """All existing rows/columns and sequence values, including troop, inbox and assets.

        Added stored/generated columns are not projected into old data. Dropped/renamed columns
        fail SQL. Sorting serialized rows also covers duplicate rows without relying on an ID.
        """
        current = self.columns()
        require(set(columns) <= set(current) and all(set(cols) <= set(current[table])
                for table, cols in columns.items()), 'migration removed original table/column')
        hashes = {}
        for table, fields in sorted(columns.items()):
            selected = ','.join(identifier(c) for c in fields)
            query = f"COPY (SELECT data FROM (SELECT row_to_json(q)::text AS data FROM (SELECT {selected} FROM public.{identifier(table)}) q) serialized ORDER BY data COLLATE \"C\") TO STDOUT;"
            with tempfile.TemporaryFile() as stream:
                self.sql(query, stdout=stream)
                stream.seek(0)
                digest = hashlib.sha256()
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
                hashes[table] = digest.hexdigest()
        sequences = json.loads(self.sql("SELECT coalesce(json_agg(sequencename ORDER BY sequencename), '[]'::json) FROM pg_sequences WHERE schemaname='public';"))
        hashes['__sequences__'] = hashlib.sha256(b''.join(
            name.encode() + b'\0' + self.sql('SELECT last_value, is_called FROM public.' + identifier(name) + ';')
            for name in sequences)).hexdigest()
        return hashes

    def redis_fingerprint(self):
        return redis_fingerprint(self.recovery, self.redis)

    def app(self, suffix, image, environment, tree, memory):
        require(type(memory) is int and memory > 0, 'finite source application memory required')
        return self.create('container', suffix,
            ['--network', self.network, '--pull=never', '--log-driver', 'none', '--memory', str(memory),
             *[part for key, value in sorted(environment.items()) for part in ('-e', key + '=' + value)],
             '--mount', f'type=bind,source={tree},target=/data/scenarios,readonly', image])

    def app_env(self, original):
        value = dict(original)
        match = re.fullmatch(r'jdbc:postgresql://[^/?#]+/([a-zA-Z_][a-zA-Z0-9_]{0,62})(\?[^#]*)?', value.get('GAME_DATABASE_URL', ''))
        require(match and match[1] == self.env['GAME_POSTGRES_DB'] and
                value.get('GAME_DB_USER') == self.env['GAME_POSTGRES_USER'] and
                value.get('OPENSAMGUK_WORLD_ID') == self.env['OPENSAMGUK_WORLD_ID'],
                'source application database/world mismatch')
        # No live external service is reachable from the internal clone network.
        value.update(GAME_DATABASE_URL=f'jdbc:postgresql://{self.pg}:5432/{match[1]}{match[2] or ""}',
                     REDIS_HOST=self.redis, REDIS_PORT='6379', SCENARIO_SEED_ENABLED='false',
                     SENTRY_DSN='', SPRING_FLYWAY_ENABLED='true')
        return value

    def clock(self):
        world = self.env['OPENSAMGUK_WORLD_ID']
        rows = json.loads(self.sql("SELECT json_agg(row_to_json(q)) FROM (SELECT id,current_year,current_month,"
            "current_phase,tick_seconds,jsonb_build_object('serverId',meta->'serverId','server_id',meta->'server_id',"
            "'lastTurnTime',meta->'lastTurnTime') AS meta,start_time FROM world_state WHERE id=" + world + ') q;'))
        servers = json.loads(self.sql("SELECT coalesce(json_agg(server_id ORDER BY id),'[]'::json) FROM ng_games WHERE world_id=" + world + ';'))
        return application_clock(rows, servers, int(world))

    def boot_engine(self, source, image, tree):
        drill = PepApplicationDrill()
        clock = self.clock()
        drill._plock(self.recovery, self.pg, self.env)
        columns = self.columns()
        expected_data, expected_redis = self.data_fingerprint(columns), self.redis_fingerprint()
        environment = self.app_env(source.engine_env)
        environment['OPENSAMGUK_DAEMON_ENABLED'] = 'true'
        engine = self.app('engine', image, environment, tree, source.services['game-engine'].memory)
        self.recovery.docker.run(['container', 'start', engine])
        ready = False
        for _ in range(120):
            try:
                value = json.loads(self.recovery.docker.run(['container', 'exec', engine,
                    'wget', '-T', '2', '-t', '1', '-qO-', 'http://localhost:8082/admin/turn-daemon/status']))
                if status_matches(value, environment['TURN_PROFILE_NAME'], clock):
                    ready = True
                    break
            except (RecoveryError, ValueError, TypeError):
                pass
            if not self.recovery.inspect('container', engine)['State']['Running']:
                break
            self.recovery.sleep(1)
        require(ready, 'clone engine did not materialize the same paused world')
        self.recovery.docker.run(['container', 'stop', '--time', '120', engine])
        state = self.recovery.inspect('container', engine)['State']
        require(not state['Running'] and not state.get('OOMKilled') and state.get('ExitCode') in (0, 143),
                'clone engine shutdown was not clean')
        require(self.data_fingerprint(columns) == expected_data and self.redis_fingerprint() == expected_redis,
                'clone engine changed original rows, sequences or Redis')
        return {'same_world_paused_ready': True, 'seed_enabled': False,
                'original_data_preserved': True, 'redis_preserved': True, 'image_id': image}

    def migrate_api(self, source, image, tree):
        columns = self.columns()
        before, redis = self.data_fingerprint(columns), self.redis_fingerprint()
        api = self.app('api', image, self.app_env(source.api_env), tree, source.services['game-api'].memory)
        self.recovery.docker.run(['container', 'start', api])
        ready = False
        for _ in range(180):
            try:
                value = json.loads(self.recovery.docker.run(['container', 'exec', api,
                    'curl', '-fsS', '--max-time', '2', 'http://localhost:8081/actuator/health']))
                if value.get('status') == 'UP':
                    ready = True
                    break
            except (RecoveryError, ValueError, TypeError):
                pass
            if not self.recovery.inspect('container', api)['State']['Running']:
                break
            self.recovery.sleep(1)
        require(ready, 'candidate API/Flyway did not become healthy on restored data')
        self.recovery.docker.run(['container', 'stop', '--time', '120', api])
        state = self.recovery.inspect('container', api)['State']
        require(not state['Running'] and not state.get('OOMKilled') and state.get('ExitCode') in (0, 143),
                'candidate API shutdown was not clean')
        require(self.data_fingerprint(columns) == before and self.redis_fingerprint() == redis,
                'candidate migration changed original rows, sequences or Redis')
        return self.recovery.postgres_check(self.pg, self.env)
