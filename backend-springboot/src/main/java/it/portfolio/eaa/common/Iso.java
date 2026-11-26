package it.portfolio.eaa.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formato delle date del contratto: ISO-8601 in UTC, SENZA frazioni di secondo
 * (es. "2026-11-25T10:15:30Z"). Identico a quello del backend Laravel: il serializzatore JSON
 * predefinito aggiungerebbe i nanosecondi e il frontend vedrebbe due formati diversi.
 */
public final class Iso {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Iso() {}

    public static String utc(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
