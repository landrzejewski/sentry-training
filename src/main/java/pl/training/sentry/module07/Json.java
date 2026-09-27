package pl.training.sentry.module07;

import io.sentry.JsonObjectReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Minimalny odczyt odpowiedzi JSON z REST API Sentry: mapy, listy i wartości proste.
 *
 * <p>Korzysta z czytnika JSON wbudowanego w Sentry SDK, żeby przykład nie potrzebował
 * osobnej biblioteki. Nawigacja po ścieżce zwraca {@code null} zamiast wyjątku, bo odpowiedzi
 * API różnią się zestawem pól między wersjami Sentry i typami eventów.</p>
 */
final class Json {

    private Json() {
    }

    static Object parse(String json) {
        try (JsonObjectReader reader = new JsonObjectReader(new StringReader(json))) {
            return reader.nextObjectOrNull();
        } catch (IOException exception) {
            throw new UncheckedIOException("Niepoprawny JSON z API Sentry", exception);
        }
    }

    /** Wartość pod ścieżką kluczy, np. {@code at(event, "release", "version")}, albo {@code null}. */
    static Object at(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    static String text(Object node, String... path) {
        Object value = at(node, path);
        if (value instanceof Double number && number == Math.rint(number)) {
            // Czytnik SDK zwraca część liczb jako Double; licznik „3.0” czyta się źle.
            return String.valueOf(number.longValue());
        }
        return value == null ? null : String.valueOf(value);
    }

    static List<?> list(Object node, String... path) {
        return at(node, path) instanceof List<?> list ? list : List.of();
    }

    static long number(Object node, String... path) {
        String value = text(node, path);
        return value == null ? 0 : Long.parseLong(value);
    }
}
