# Ambiente di sviluppo per backend-laravel: PHP 8.4 + estensione pdo_pgsql + Composer.
# Serve perché Laravel 13 richiede PHP >= 8.3 e non è detto che sia installato sul computer.
FROM php:8.4-cli-bookworm

RUN apt-get update \
 && apt-get install -y --no-install-recommends libpq-dev libzip-dev unzip git \
 && docker-php-ext-install pdo_pgsql zip \
 && rm -rf /var/lib/apt/lists/*

COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

# Limiti di upload coerenti con il contratto (PDF fino a 20 MB) e tempo per l'ingestione.
COPY docker/php.ini /usr/local/etc/php/conf.d/zz-eaa.ini
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /var/www/html
EXPOSE 8000
ENTRYPOINT ["entrypoint.sh"]
# NON "php artisan serve": inoltra al server solo poche variabili d'ambiente e leggerebbe DB_HOST dal
# file .env (127.0.0.1) ignorando quello impostato da docker-compose. Il server integrato di PHP
# eredita invece tutto l'ambiente. PHP_CLI_SERVER_WORKERS (default 4 nel compose) abilita più worker.
# public/index.php fa da router: è un'API, non ci sono file statici da servire.
CMD ["php", "-S", "0.0.0.0:8000", "-t", "public", "public/index.php"]
