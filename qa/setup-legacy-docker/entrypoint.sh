#!/bin/bash
set -e

echo "[entrypoint] Waiting for MariaDB at ${DB_HOST}:${DB_PORT}..."
for i in $(seq 1 60); do
    if mysqladmin ping -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" --silent 2>/dev/null; then
        echo "[entrypoint] MariaDB is ready."
        break
    fi
    sleep 2
done

# Create additional game databases if they don't exist (legacy expects up to 8)
echo "[entrypoint] Ensuring game databases exist..."
for i in $(seq 1 7); do
    mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e \
        "CREATE DATABASE IF NOT EXISTS sammo_game${i} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null || true
    mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e \
        "GRANT ALL PRIVILEGES ON sammo_game${i}.* TO '${DB_USER}'@'%';" 2>/dev/null || true
done
mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e "FLUSH PRIVILEGES;" 2>/dev/null || true

# Auto-generate d_setting/DB.php if missing (skip web installer)
if [ ! -f /var/www/html/d_setting/DB.php ]; then
    echo "[entrypoint] Generating d_setting/DB.php..."
    mkdir -p /var/www/html/d_setting
    cat > /var/www/html/d_setting/DB.php <<DBEOF
<?php
namespace sammo;
class RootDB {
    const \$serverList = [
        0 => ['host'=>'${DB_HOST}','port'=>${DB_PORT},'user'=>'${DB_USER}','password'=>'${DB_PASSWORD}','db'=>'${DB_NAME}'],
    ];
    public static function db() {
        \$s = self::\$serverList[0];
        return new \mysqli(\$s['host'], \$s['user'], \$s['password'], \$s['db'], \$s['port']);
    }
}
DBEOF
    chown www-data:www-data /var/www/html/d_setting/DB.php
fi

exec "$@"
