package pl.training.sentry.module07;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redakcja sekretów i adresów e-mail w treści dowodu, zanim trafi do modelu.
 *
 * <p>Redaktor usuwa ograniczony zestaw wzorców: nagłówki Bearer, wartości popularnych pól
 * sekretów ({@code password}, {@code token}, {@code api_key} itp.), cookie i adresy e-mail.
 * Wynik mówi, które kategorie znalazł, żeby host mógł to odnotować w audycie albo zatrzymać
 * eksport.</p>
 *
 * <p>PUŁAPKA: to redaktor demonstracyjny, nie pełny DLP. Nie wykryje sekretu w nietypowym
 * formacie, wartości zakodowanej (np. Base64), danych rozdzielonych między pola ani
 * informacji, które da się wywnioskować. Dlatego redakcja jest ostatnią warstwą ochrony danych
 * przed modelem, a nie pierwszą: wcześniej działa scrubbing podczas ingestu w Sentry
 * i minimalizacja ({@link EvidenceCollector}).</p>
 */
public final class Redactor {

    /** Kolejność ma znaczenie: nagłówek Bearer przed ogólnym wzorcem {@code nazwa=wartość}. */
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("bearer-token", Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"));
        PATTERNS.put("cookie", Pattern.compile("(?i)\\b(set-cookie|cookie)\\s*[:=]\\s*[^\\s|]+"));
        PATTERNS.put("secret-field", Pattern.compile(
                "(?i)\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\s*[:=]\\s*[^\\s,;|}]+"));
        PATTERNS.put("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
    }

    /**
     * @param text       treść po redakcji
     * @param categories kategorie, które redaktor znalazł i zastąpił; pusty zbiór nie dowodzi,
     *                   że treść jest bezpieczna
     */
    public record Result(String text, Set<String> categories) {
    }

    public Result redact(String text) {
        String result = text;
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, Pattern> rule : PATTERNS.entrySet()) {
            Matcher matcher = rule.getValue().matcher(result);
            if (matcher.find()) {
                found.add(rule.getKey());
                result = matcher.replaceAll(match -> replacement(rule.getKey(), match.group()));
            }
        }
        return new Result(result, Set.copyOf(found));
    }

    private static String replacement(String category, String match) {
        return switch (category) {
            case "bearer-token" -> "Bearer [REDACTED]";
            // Nazwa pola zostaje: „password=[REDACTED]” mówi agentowi, że wartość była, nie jaka.
            case "cookie", "secret-field" -> Matcher.quoteReplacement(match.split("\\s*[:=]\\s*", 2)[0]) + "=[REDACTED]";
            default -> "[EMAIL]";
        };
    }
}
