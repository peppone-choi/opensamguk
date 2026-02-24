###############################################################################
# Legacy PHP 삼국지 HiDCHe — Apache + PHP-FPM
###############################################################################
FROM php:8.2-apache

# System deps
RUN apt-get update && apt-get install -y --no-install-recommends \
        libcurl4-openssl-dev \
        libfreetype6-dev \
        libjpeg62-turbo-dev \
        libpng-dev \
        libonig-dev \
        libsqlite3-dev \
        libzip-dev \
        unzip \
        git \
        curl \
    && rm -rf /var/lib/apt/lists/*

# PHP extensions required by legacy/README.md
RUN docker-php-ext-configure gd --with-freetype --with-jpeg \
    && docker-php-ext-install -j"$(nproc)" \
        curl \
        gd \
        mbstring \
        mysqli \
        pdo_sqlite \
        pdo_mysql \
        zip \
        opcache

# Enable Apache modules
RUN a2enmod rewrite headers

# Composer
COPY --from=composer:2 /usr/local/bin/composer /usr/local/bin/composer

# Copy legacy source
COPY legacy/ /var/www/html/

WORKDIR /var/www/html

# Install PHP dependencies
RUN if [ -f composer.json ]; then \
        composer install --no-dev --optimize-autoloader --no-interaction; \
    fi

# Writable dirs the app expects
RUN mkdir -p d_log d_setting logs \
    && chown -R www-data:www-data /var/www/html

# Apache vhost — serve from /var/www/html, allow .htaccess
RUN echo '<Directory /var/www/html>\n\
    AllowOverride All\n\
    Require all granted\n\
</Directory>' > /etc/apache2/conf-enabled/allow-htaccess.conf

# PHP config tweaks
RUN echo "session.cache_expire = 10080" >> /usr/local/etc/php/conf.d/sammo.ini \
    && echo "session.gc_maxlifetime = 604800" >> /usr/local/etc/php/conf.d/sammo.ini \
    && echo "date.timezone = Asia/Seoul" >> /usr/local/etc/php/conf.d/sammo.ini \
    && echo "mbstring.internal_encoding = UTF-8" >> /usr/local/etc/php/conf.d/sammo.ini \
    && echo "memory_limit = 256M" >> /usr/local/etc/php/conf.d/sammo.ini

# Startup script: wait for MariaDB, then auto-configure if needed
COPY qa/setup-legacy-docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
CMD ["apache2-foreground"]
