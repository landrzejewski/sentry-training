package pl.training.sentry.support;

import java.io.PrintStream;

/**
 * Formatowanie wydruku programów demonstracyjnych.
 *
 * <p>Każdy scenariusz zaczyna się nagłówkiem z celem, pod krokami wydruk transportu pokazuje,
 * co faktycznie trafiło do Sentry, a na końcu linie „Na co patrzeć” wskazują efekt w konsoli
 * i w Sentry UI. Dzięki temu wynik demo da się czytać bez otwierania kodu.</p>
 */
public final class DemoConsole {

    private static final PrintStream OUT = System.out;

    private DemoConsole() {
    }

    /** Nagłówek scenariusza: numer, tytuł i jedno zdanie o tym, czego scenariusz dowodzi. */
    public static void scenario(int number, String title, String goal) {
        OUT.printf("%n==== Scenariusz %d: %s ====%n", number, title);
        OUT.println("Cel: " + goal);
    }

    /** Krok wykonywany w scenariuszu, np. „Obsługa zamówienia PICKUP_POINT bez adresu”. */
    public static void step(String description) {
        OUT.println("> " + description);
    }

    /** Wniosek albo wskazówka, gdzie szukać efektu w konsoli albo w Sentry UI. */
    public static void lookAt(String hint) {
        OUT.println("Na co patrzeć: " + hint);
    }
}
