package pl.training.sentry.module08;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wynik audytu gotowości: status każdej kontroli i decyzja, która nie uśrednia braków.
 *
 * <p>Wszystkie kontrole tego audytu są bramkami produkcyjnymi o wyniku binarnym, więc nie ma
 * statusu „częściowo spełnione” ani decyzji „gotowe z zadaniami do wykonania”. Raport nie liczy
 * procentu gotowości: dziesięć kontroli {@code PASS} nie równoważy jednej {@code FAIL}.</p>
 */
public record ReadinessReport(DeploymentProfile profile, List<ControlResult> results) {

    public enum Status {
        /** Kryterium potwierdzone. */
        PASS,
        /** Stan narusza kryterium. */
        FAIL,
        /** Brakuje podstaw do oceny. To nie jest słabszy {@code PASS}. */
        NOT_VERIFIED,
        /** Profil nie aktywuje kontroli. */
        NOT_APPLICABLE
    }

    public enum Decision {
        /** Co najmniej jedna kontrola ma {@code FAIL}. */
        NOT_READY,
        /** Nic nie zawiodło, ale czegoś nie potwierdzono. */
        INSUFFICIENT_EVIDENCE,
        /** Wszystkie stosowalne kontrole mają {@code PASS}. */
        READY
    }

    /**
     * @param control stabilny identyfikator kontroli, np. {@code RELEASE}
     * @param detail  stan zastany i jego skutek, bez sekretów: DSN tylko jako host i projekt
     */
    public record ControlResult(String control, Status status, String detail) {
    }

    public ReadinessReport {
        results = List.copyOf(results);
    }

    public Decision decision() {
        if (results.stream().anyMatch(result -> result.status() == Status.FAIL)) {
            return Decision.NOT_READY;
        }
        if (results.stream().anyMatch(result -> result.status() == Status.NOT_VERIFIED)) {
            return Decision.INSUFFICIENT_EVIDENCE;
        }
        return Decision.READY;
    }

    public Status status(String control) {
        return results.stream()
                .filter(result -> result.control().equals(control))
                .map(ControlResult::status)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nieznana kontrola " + control));
    }

    /** Raport z nową wersją jednej kontroli, np. {@code DELIVERY} po sprawdzeniu eventu odbiorowego. */
    public ReadinessReport with(ControlResult replacement) {
        List<ControlResult> updated = new ArrayList<>();
        for (ControlResult result : results) {
            updated.add(result.control().equals(replacement.control()) ? replacement : result);
        }
        return new ReadinessReport(profile, updated);
    }

    /** Tekst do konsoli: każda stosowalna kontrola w osobnej linii, niestosowalne razem. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Audyt " + profile.service() + " [" + profile.environment() + "]");
        for (ControlResult result : results) {
            if (result.status() != Status.NOT_APPLICABLE) {
                lines.add(String.format("  %-16s %-19s %s", "[" + result.status() + "]", result.control(), result.detail()));
            }
        }
        String notApplicable = results.stream()
                .filter(result -> result.status() == Status.NOT_APPLICABLE)
                .map(ControlResult::control)
                .collect(Collectors.joining(", "));
        if (!notApplicable.isEmpty()) {
            lines.add(String.format("  %-16s %s", "[NOT_APPLICABLE]", notApplicable));
        }
        lines.add("  Decyzja: " + decision());
        return lines;
    }
}
