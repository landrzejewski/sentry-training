package pl.training.sentry.module03;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Ownership Rules projektu: kto odpowiada za które issues checkout-api i payments-api.
 *
 * <p>Reguły dopasowują dane eventu: ścieżkę pliku z ramek stosu ({@code path:}) i tagi
 * ({@code tags.NAZWA:}). Wzorce są globami, reguły są oceniane od góry do dołu, a wynik
 * rozstrzyga ostatnia pasująca reguła; przy kilku ownerach tej reguły auto-assignment przypisuje
 * pierwszego. Te dane ustawia kod z tego pakietu: {@code PaymentGrouping} dodaje tag
 * {@code payment.failure_reason}, a o ścieżce decydują pakiet i plik klasy w ramce, także
 * w ramce spoza in-app (dlatego reguła biblioteki {@code module03legacy} działa przy prefiksie
 * in-app z kropką).</p>
 *
 * <p>Tekst {@link #RULES} trafia do Sentry bez zmian ({@link TeamSetup}). {@link #preview} to
 * narzędzie szkoleniowe: pokazuje dopasowanie na evencie w procesie aplikacji, zanim event
 * opuści proces. Ostatecznie decyduje Sentry, a jego wynik zwraca API sugerowanych ownerów
 * ({@code GET /projects/{org}/{project}/events/{id}/owners/}), co pokazuje
 * {@link TriageWalkthrough}.</p>
 */
public final class OwnershipRules {

    public static final String CHECKOUT_TEAM = "checkout";
    public static final String PAYMENTS_TEAM = "payments";
    /** Opiekunka biblioteki lojalnościowej; konto tworzy {@code sentry createuser} ({@code README.md}). */
    public static final String LOYALTY_OWNER = "anna.checkout@sentry-training.local";

    /**
     * Reguły w składni Sentry. Kolejność jest częścią reguł: ogólna na górze, szczegółowe niżej.
     *
     * <p>PUŁAPKA: {@code path:pl/training/sentry/module03*} bez ukośnika obejmie też
     * {@code module03legacy}, tak samo jak prefiks in-app bez kropki w scenariuszu 2. Taka reguła
     * ustawiona pod regułą biblioteki przejęłaby jej issues, bo wygrywa ostatnia pasująca. Glob
     * {@code *} w Ownership Rules przechodzi też przez {@code /}, więc {@code module03/*} obejmuje
     * podpakiety.</p>
     */
    public static final String RULES = """
            # checkout-api: każdy event z ramką kodu checkout (ścieżka z pakietu i nazwy pliku)
            path:pl/training/sentry/module03/* #checkout
            # awarie bramki płatności: tag z przyczyną, który ustawia PaymentGrouping
            tags.payment.failure_reason:* #payments #checkout
            # biblioteka lojalnościowa innego zespołu: opiekunka biblioteki
            path:pl/training/sentry/module03legacy/* anna.checkout@sentry-training.local
            # payments-api z modułu 6: tag komponentu z PaymentTelemetry
            tags.component:payments #payments
            """;

    private OwnershipRules() {
    }

    /** Jedna reguła: typ dopasowania ({@code path} albo {@code tags.NAZWA}), wzorzec i ownerzy. */
    public record Rule(String matcher, String pattern, List<String> owners) {

        @Override
        public String toString() {
            return matcher + ":" + pattern + " " + String.join(" ", owners);
        }
    }

    /** Reguły z tekstu w kolejności z pliku; komentarze i puste linie są pomijane. */
    public static List<Rule> parse(String text) {
        List<Rule> rules = new ArrayList<>();
        for (String line : text.lines().map(String::strip).toList()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            int colon = parts[0].indexOf(':');
            rules.add(new Rule(parts[0].substring(0, colon), parts[0].substring(colon + 1),
                    List.of(parts).subList(1, parts.length)));
        }
        return rules;
    }

    /**
     * Wynik podglądu.
     *
     * @param matched      wszystkie pasujące reguły, w kolejności z pliku
     * @param owners       ownerzy w kolejności auto-assignment: od ostatniej pasującej reguły, bez powtórzeń
     *                     (API sugerowanych ownerów zwraca tych samych ownerów w kolejności z pliku)
     * @param autoAssignee kogo przypisze auto-assignment (pierwszy owner ostatniej reguły) albo {@code null}
     */
    public record Preview(List<Rule> matched, List<String> owners, String autoAssignee) {
    }

    /** Podgląd dopasowania reguł do eventu: tagi i ścieżki plików z ramek. */
    public static Preview preview(List<Rule> rules, SentryEvent event) {
        List<String> paths = javaPaths(event);
        Map<String, String> tags = event.getTags() == null ? Map.of() : event.getTags();
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) {
            boolean matches = switch (rule.matcher()) {
                // Ścieżka: każda ramka stosu, także spoza in-app, bez rozróżniania wielkości liter.
                case "path" -> paths.stream().anyMatch(path -> glob(rule.pattern(), path, true));
                default -> rule.matcher().startsWith("tags.")
                        && tags.containsKey(rule.matcher().substring(5))
                        && glob(rule.pattern(), tags.get(rule.matcher().substring(5)), false);
            };
            if (matches) {
                matched.add(rule);
            }
        }
        // Do auto-assignment Sentry porządkuje ownerów od ostatniej pasującej reguły (ona rozstrzyga), a
        // auto-assignment bierze pierwszego z nich.
        Set<String> owners = new LinkedHashSet<>();
        for (int i = matched.size() - 1; i >= 0; i--) {
            owners.addAll(matched.get(i).owners());
        }
        String assignee = matched.isEmpty() ? null : matched.getLast().owners().getFirst();
        return new Preview(List.copyOf(matched), List.copyOf(owners), assignee);
    }

    /**
     * Dokłada do {@code beforeSend} wydruk podglądu Ownership Rules dla każdego wysyłanego eventu
     * (linia {@code · ownership} nad wydrukiem eventu), jak {@link EventPreview} dla ramek in-app.
     */
    public static Consumer<SentryOptions> installPreview() {
        List<Rule> rules = parse(RULES);
        return options -> {
            SentryOptions.BeforeSendCallback delegate = options.getBeforeSend();
            options.setBeforeSend((event, hint) -> {
                SentryEvent result = delegate == null ? event : delegate.execute(event, hint);
                if (result != null) {
                    Preview preview = preview(rules, result);
                    if (preview.matched().isEmpty()) {
                        System.out.println("  · ownership   żadna reguła nie pasuje: issue bez ownera");
                    } else {
                        System.out.println("  · ownership   pasuje " + preview.matched().size() + " z " + rules.size()
                                + ", rozstrzyga ostatnia: " + preview.matched().getLast()
                                + " -> auto-assign " + preview.autoAssignee());
                    }
                }
                return result;
            });
        };
    }

    /**
     * Ścieżki plików z ramek, zbudowane jak w Sentry dla Javy: pakiet z {@code module}
     * i nazwa pliku, np. {@code pl/training/sentry/module03/CheckoutService.java}.
     */
    static List<String> javaPaths(SentryEvent event) {
        List<String> paths = new ArrayList<>();
        List<SentryException> exceptions = event.getExceptions() == null ? List.of() : event.getExceptions();
        for (SentryException exception : exceptions) {
            if (exception.getStacktrace() == null || exception.getStacktrace().getFrames() == null) {
                continue;
            }
            for (SentryStackFrame frame : exception.getStacktrace().getFrames()) {
                String module = frame.getModule();
                if (module != null && module.contains(".") && frame.getFilename() != null) {
                    paths.add(module.substring(0, module.lastIndexOf('.')).replace('.', '/') + "/" + frame.getFilename());
                }
            }
        }
        return paths;
    }

    /** Glob jak w Ownership Rules: {@code *} to dowolny ciąg (także z {@code /}), {@code ?} jeden znak. */
    static boolean glob(String pattern, String value, boolean ignoreCase) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0).matcher(value).matches();
    }
}
