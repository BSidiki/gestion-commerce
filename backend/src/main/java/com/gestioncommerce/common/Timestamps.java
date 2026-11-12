package com.gestioncommerce.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Horodatage ISO-8601 UTC à précision fixe (millisecondes).
 * Une longueur constante garantit que le tri des textes suit l'ordre du temps.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static String now() {
        return FORMAT.format(Instant.now());
    }

    public static String of(Instant instant) {
        return FORMAT.format(instant);
    }
}
