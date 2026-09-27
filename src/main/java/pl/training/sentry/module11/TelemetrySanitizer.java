package pl.training.sentry.module11;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Przygotowanie tekstu z telemetrii, zanim trafi do agenta, raportu albo logu.
 *
 * <p>Dwie operacje:</p>
 * <ul>
 *   <li>{@link #redact}: usuwa sekrety i dane osobowe, które często trafiają do eventów
 *   przypadkiem (nagłówek {@code Authorization} w breadcrumbie klienta HTTP, token w komunikacie
 *   wyjątku, e-mail w user albo w treści);</li>
 *   <li>{@link #asUntrustedData}: oznacza treść jako dane, a nie polecenia.</li>
 * </ul>
 *
 * <p>PUŁAPKA: redakcja wzorcami jest siatką bezpieczeństwa, a nie kontrolą. Wzorzec rozpozna
 * token Sentry po prefiksie albo po kontekście, ale nie rozpozna dowolnego sekretu bez kontekstu.
 * Ochroną jest to, żeby sekretów w telemetrii nie było (moduł 2, {@code beforeSend}), oraz
 * minimalny zakres tokenu, który mimo wycieku niewiele pozwala.</p>
 */
public final class TelemetrySanitizer {

    private static final List<Rule> RULES = List.of(
            // Nowe tokeny Sentry mają prefiks typu: sntryu_ (User Auth Token), sntrys_ (Organization
            // Token), sntrya_ (token aplikacji) i sntryi_ (integracja), co pozwala je rozpoznać
            // bez kontekstu (sentry/types/token.py w self-hosted 26.9.0).
            new Rule(Pattern.compile("sntry[usai]_[A-Za-z0-9_=+/.-]+"), "[TOKEN SENTRY]"),
            // Tokeny bez prefiksu (64 znaki hex: starsze albo utworzone bez typu, jak ten
            // z ./docker/sentry/sentry.sh token) rozpoznajemy tylko po kontekście: nagłówek
            // Authorization i zmienne albo flagi z tokenem. Warunek „nie zaczyna się od [”
            // pomija wartości zredagowane już przez wcześniejszą regułę.
            new Rule(Pattern.compile("(?i)((?:Sentry-)?Bearer\\s+)(?!\\[)[^\\s\"',;]+"), "$1[UKRYTY]"),
            new Rule(Pattern.compile("(?i)((?:access[_-]?token|auth[_-]?token|api[_-]?key|password|secret)[\"']?\\s*[=:]\\s*[\"']?)(?!\\[)[^\\s\"',;&]+"), "$1[UKRYTY]"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"));

    private static final String OPEN = "<UNTRUSTED_DATA source=\"%s\">";
    private static final String CLOSE = "</UNTRUSTED_DATA>";

    private TelemetrySanitizer() {
    }

    public static String redact(String text) {
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * Treść po redakcji, otoczona znacznikiem danych niezaufanych.
     *
     * <p>Oznaczenie ułatwia modelowi odróżnienie danych od instrukcji, ale nie tworzy sandboxa:
     * tekst „zignoruj poprzednie polecenia” nadal do modelu dociera. Skutki ogranicza dopiero
     * polityka narzędzi ({@link ToolPolicy}) i zamrożony zakres ({@link DiagnosticScope}).</p>
     */
    public static String asUntrustedData(String source, String text) {
        // PUŁAPKA: treść może zawierać znacznik zamykający i „wyjść” poza blok danych.
        // Neutralizujemy go, zanim otoczymy tekst znacznikami.
        String neutralized = Pattern.compile("(?i)</?\\s*UNTRUSTED_DATA[^>]*>")
                .matcher(redact(text))
                .replaceAll(Matcher.quoteReplacement("[znacznik usunięty]"));
        return OPEN.formatted(source.replaceAll("[^A-Za-z0-9_.:/-]", "_")) + neutralized + CLOSE;
    }

    private record Rule(Pattern pattern, String replacement) {
    }
}
