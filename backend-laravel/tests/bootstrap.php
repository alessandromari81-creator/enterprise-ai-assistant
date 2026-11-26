<?php

/*
 * Bootstrap dei test.
 *
 * Perché esiste: in <php><env force="true"> phpunit.xml PHPUnit sovrascrive $_ENV e getenv(), ma NON
 * $_SERVER, che Laravel legge per primo. Dentro Docker le variabili reali (es. DB_DATABASE=eaa del
 * compose) finiscono in $_SERVER e vincerebbero sul valore di test: i test girerebbero sul database
 * di sviluppo. Qui si riallinea $_SERVER a quanto forzato da phpunit.xml.
 */

require __DIR__.'/../vendor/autoload.php';

foreach ($_ENV as $key => $value) {
    if (isset($_SERVER[$key]) && $_SERVER[$key] !== $value) {
        $_SERVER[$key] = $value;
    }
}
