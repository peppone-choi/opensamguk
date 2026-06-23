# Dockerfile for opensamguk PHP golden capture environment.
# Extends the official php:8.3-cli image with the MariaDB/MySQL extensions and
# utilities required by legacy/devsam-core.
#
# Build once from repo root:
#   docker build -t opensamguk-php-golden:latest -f tools/php-golden/Dockerfile .
#
# Used by tools/php-golden/run_longsim.sh instead of php:8.3-cli.

FROM php:8.3-cli

RUN apt-get update -qq \
    && apt-get install -y -qq \
        libonig-dev \
        libzip-dev \
        unzip \
        git \
        mariadb-client \
    && docker-php-ext-install \
        pdo_mysql \
        mysqli \
        mbstring \
        bcmath \
        zip \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Make composer available (vendor is mounted from the host, but composer is handy).
COPY --from=composer:latest /usr/bin/composer /usr/bin/composer

WORKDIR /work
