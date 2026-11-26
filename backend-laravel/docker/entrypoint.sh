#!/bin/sh
# Prepara l'app al primo avvio del container e poi esegue il comando richiesto (di default php artisan serve).
set -e

cd /var/www/html

[ -d vendor ] || composer install --no-interaction --prefer-dist
[ -f .env ] || cp .env.example .env
grep -q '^APP_KEY=.\+' .env || php artisan key:generate --force

# Idempotente: crea le tabelle solo se non esistono già (vedi migration).
if [ "${RUN_MIGRATIONS:-true}" = "true" ]; then
    php artisan migrate --force
fi

exec "$@"
