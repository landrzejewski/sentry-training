package pl.training.sentry.module11;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Zamrożony zakres sesji diagnostycznej: pytanie, organizacja, projekt, środowisko i okno czasu.
 *
 * <p>Zakres powstaje przed pierwszym odczytem
 * i się nie zmienia: record nie ma setterów, a każdy dowód w {@link EvidenceLog} jest
 * sprawdzany względem zakresu. Rozszerzenie (inny projekt, inne środowisko, dłuższe okno)
 * wymaga nowego obiektu, czyli świadomej decyzji człowieka, a nie zdania w treści eventu.</p>
 *
 * <p>{@code end} jest jednocześnie cutoffem: wszystkie zapytania sesji używają tej samej chwili
 * granicznej, więc kolejne kroki opisują tę samą populację danych.</p>
 *
 * @param question     dokładne pytanie diagnostyczne
 * @param issueShortId issue będące punktem wejścia, np. {@code SENTRY-TRAINING-2}
 */
public record DiagnosticScope(
        String question,
        String organization,
        String project,
        String environment,
        Instant start,
        Instant end,
        String issueShortId
) {

    /** Górna granica okna. Dłuższe okno to zwykle „szukam czegokolwiek”, a nie diagnoza. */
    static final Duration MAX_WINDOW = Duration.ofDays(14);

    public DiagnosticScope {
        requireText(question, "question");
        requireText(organization, "organization");
        requireText(project, "project");
        // PUŁAPKA: brak environment to nie „wszystkie środowiska dla pewności”, tylko mieszanie
        // produkcji ze stagingiem i laptopami w jednej statystyce.
        requireText(environment, "environment");
        requireText(issueShortId, "issueShortId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Okno musi być zamkniętym przedziałem: start przed end");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Okno dłuższe niż " + MAX_WINDOW.toDays() + " dni");
        }
    }

    /** Cutoff sesji: ta sama chwila graniczna dla wszystkich odczytów. */
    public Instant cutoff() {
        return end;
    }

    /**
     * Sprawdza, czy odczyt dotyczy zamrożonego zakresu.
     *
     * @throws IllegalArgumentException gdy odczyt wychodzi poza zakres
     */
    public void requireWithin(String organization, String project, String environment) {
        if (!this.organization.equals(organization) || !this.project.equals(project)
                || !this.environment.equals(environment)) {
            throw new IllegalArgumentException("Odczyt poza zamrożonym zakresem: " + organization + "/" + project
                    + " environment=" + environment + ", a zakres to " + describe());
        }
    }

    public String describe() {
        return organization + "/" + project + " environment=" + environment + " okno " + start + " .. " + end;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Pole zakresu " + name + " jest wymagane");
        }
    }
}
