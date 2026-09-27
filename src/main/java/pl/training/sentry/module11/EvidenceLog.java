package pl.training.sentry.module11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rejestr dowodów sesji diagnostycznej: każdy odczyt z Sentry ze stanem pobrania i samplingiem.
 *
 * <p>Rejestr nie przechowuje
 * pełnych eventów ani PII, tylko pochodzenie i granice odczytu: źródło, parametry bez sekretów,
 * stan, sampling i krótkie streszczenie. Fakt w raporcie agenta powinien wskazywać ID wpisu.</p>
 */
public final class EvidenceLog {

    /** Stan pobrania. Niezależny od samplingu. */
    public enum DataState {
        /** Wykonano cały zdefiniowany, ograniczony odczyt. */
        COMPLETE,
        /** Wynik skrócony, ucięty albo z nieobsłużoną kolejną stroną. */
        PARTIAL,
        /** Poprawne zapytanie nie zwróciło rekordów. To nie jest obserwowane zero. */
        NO_DATA,
        /** Narzędzie, autoryzacja albo zapytanie zawiodły. */
        FAILED
    }

    /** Czy dane źródłowe były próbkowane. {@code UNKNOWN} nie jest równoważne {@code NOT_SAMPLED}. */
    public enum SamplingStatus {
        NOT_SAMPLED,
        SAMPLED,
        UNKNOWN,
        /** Wymaga uzasadnienia w streszczeniu, np. metadane issue, a nie liczby eventów. */
        NOT_APPLICABLE
    }

    /**
     * Wynik jednego odczytu, zanim trafi do rejestru.
     *
     * @param source     narzędzie MCP albo endpoint REST
     * @param parameters parametry zapytania; rejestr redaguje je przed zapisem
     */
    public record Read(String source, Map<String, String> parameters, String organization, String project,
                       String environment, DataState state, SamplingStatus sampling, String summary) {
    }

    /** Wpis rejestru. {@code id} (E1, E2, ...) cytuje się przy fakcie w raporcie. */
    public record Evidence(String id, String source, Map<String, String> parameters, DataState state,
                           SamplingStatus sampling, String summary) {

        /** Co ten dowód pozwala stwierdzić, a czego nie. */
        public String limitation() {
            return switch (state) {
                case COMPLETE -> switch (sampling) {
                    case NOT_SAMPLED, NOT_APPLICABLE -> "pełny odczyt zdefiniowanego zakresu";
                    case SAMPLED -> "pełny odczyt próbki: liczby opisują próbkę, nie populację";
                    case UNKNOWN -> "pełny odczyt, ale nie wiadomo, czy dane były próbkowane: "
                            + "liczby to dolna granica, nie populacja";
                };
                case PARTIAL -> "fragment wyniku (kolejna strona albo skrócenie): ranking i sumy dotyczą tylko fragmentu";
                case NO_DATA -> "brak rekordów w tym zapytaniu, a nie obserwowane zero: filtr, sampling, "
                        + "retencja albo opóźnienie ingestii dają ten sam wynik";
                case FAILED -> "odczyt się nie odbył: nie wolno obniżyć priorytetu ani ogłosić zdrowia systemu";
            };
        }
    }

    private final DiagnosticScope scope;
    private final List<Evidence> entries = new ArrayList<>();

    public EvidenceLog(DiagnosticScope scope) {
        this.scope = scope;
    }

    /**
     * Rejestruje odczyt po sprawdzeniu zakresu i redakcji parametrów.
     *
     * @throws IllegalArgumentException gdy odczyt dotyczy innej organizacji, projektu albo środowiska
     */
    public Evidence add(Read read) {
        scope.requireWithin(read.organization(), read.project(), read.environment());
        Map<String, String> redacted = new LinkedHashMap<>();
        read.parameters().forEach((key, value) -> redacted.put(key, TelemetrySanitizer.redact(key + "=" + value)
                .substring(key.length() + 1)));
        Evidence evidence = new Evidence("E" + (entries.size() + 1), read.source(), Map.copyOf(redacted),
                read.state(), read.sampling(), TelemetrySanitizer.redact(read.summary()));
        entries.add(evidence);
        return evidence;
    }

    public List<Evidence> entries() {
        return List.copyOf(entries);
    }

    public DiagnosticScope scope() {
        return scope;
    }

    /**
     * Co blokuje wniosek „tego problemu nie ma” na podstawie pustego wyniku.
     *
     * <p>Przed wnioskiem o braku zdarzeń potrzebna jest kontrola pozytywna na znanym
     * zasobie (to samo źródło i zakres zwracają dane, gdy dane istnieją) oraz wiedza o samplingu.
     * Pusta lista oznacza, że wniosek o braku jest uzasadniony w granicach tego zakresu.</p>
     *
     * @param positiveControl odczyt znanego zasobu tym samym źródłem albo {@code null}
     */
    public static List<String> absenceClaimBlockers(Evidence noData, Evidence positiveControl) {
        List<String> blockers = new ArrayList<>();
        if (noData.state() != DataState.NO_DATA) {
            blockers.add(noData.id() + " ma stan " + noData.state() + ", a nie NO_DATA");
        }
        if (noData.sampling() != SamplingStatus.NOT_SAMPLED) {
            blockers.add(noData.id() + " ma sampling " + noData.sampling()
                    + ": brak w zapisanych danych nie oznacza braku w populacji");
        }
        if (positiveControl == null) {
            blockers.add("brak kontroli pozytywnej: nie wiadomo, czy to zapytanie w ogóle potrafi coś znaleźć");
        } else if (positiveControl.state() != DataState.COMPLETE && positiveControl.state() != DataState.PARTIAL) {
            blockers.add("kontrola pozytywna " + positiveControl.id() + " nie zwróciła danych (" + positiveControl.state() + ")");
        } else if (!positiveControl.source().equals(noData.source())) {
            blockers.add("kontrola pozytywna " + positiveControl.id() + " użyła innego źródła niż " + noData.id());
        }
        return blockers;
    }
}
