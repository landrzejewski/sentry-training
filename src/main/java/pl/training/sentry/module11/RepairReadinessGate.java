package pl.training.sentry.module11;

import pl.training.sentry.module11.EvidenceLog.Evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Bramka między diagnozą a planem naprawy.
 *
 * <p>Wynik {@code READY} oznacza tylko, że jest
 * wystarczający pakiet dowodów, żeby przygotować szkic planu. Nie autoryzuje edycji kodu,
 * pull requesta, zmiany issue ani wdrożenia: każda z tych czynności ma osobną zgodę
 * ({@link #SEPARATE_APPROVALS}).</p>
 */
public final class RepairReadinessGate {

    /** Czynności, których {@code READY} nie obejmuje. */
    public static final List<String> SEPARATE_APPROVALS =
            List.of("patch w repozytorium", "pull request", "merge", "deploy", "zmiana statusu issue w Sentry");

    /** Skąd pochodzi potwierdzenie mechanizmu runtime. */
    public enum RuntimeConfirmation {
        /** Brak potwierdzenia: jest hipoteza. */
        NONE,
        /**
         * Agent przeczytał plik konfiguracyjny albo kod. To nie potwierdza zachowania w runtime:
         * zmienne środowiskowe, profile, wartości domyślne i dane wejściowe mogą je zmienić.
         */
        CONFIG_READ,
        /** Kontrolowany test lokalny odtworzył mechanizm. */
        LOCALLY_VERIFIED,
        /** Człowiek zatwierdził mechanizm na podstawie dowodów. */
        HUMAN_APPROVED
    }

    public enum Blocker {
        MISSING_EVIDENCE,
        PARTIAL_EVIDENCE,
        NO_DATA,
        FAILED_RETRIEVAL,
        RUNTIME_CAUSE_UNCONFIRMED
    }

    /** Wynik bramki: {@code READY} albo {@code NOT_READY_FOR_REPAIR_PLAN} z listą blokad. */
    public record Readiness(List<String> blockers) {

        public boolean ready() {
            return blockers.isEmpty();
        }

        public String status() {
            return ready() ? "READY" : "NOT_READY_FOR_REPAIR_PLAN";
        }
    }

    /**
     * @param evidence     dowody, na których opiera się wskazany mechanizm
     * @param confirmation skąd pochodzi potwierdzenie mechanizmu runtime
     */
    public Readiness evaluate(List<Evidence> evidence, RuntimeConfirmation confirmation) {
        List<String> blockers = new ArrayList<>();
        if (evidence.isEmpty()) {
            blockers.add(Blocker.MISSING_EVIDENCE + ": mechanizm nie wskazuje żadnego dowodu");
        }
        for (Evidence item : evidence) {
            switch (item.state()) {
                case PARTIAL -> blockers.add(Blocker.PARTIAL_EVIDENCE + " " + item.id() + ": " + item.limitation());
                case NO_DATA -> blockers.add(Blocker.NO_DATA + " " + item.id() + ": pusty wynik nie potwierdza mechanizmu");
                case FAILED -> blockers.add(Blocker.FAILED_RETRIEVAL + " " + item.id() + ": " + item.summary());
                case COMPLETE -> {
                    // Kompletny dowód nie blokuje. Sampling ogranicza wnioski ilościowe, ale nie
                    // przeszkadza w potwierdzeniu mechanizmu testem lokalnym.
                }
            }
        }
        if (confirmation != RuntimeConfirmation.LOCALLY_VERIFIED && confirmation != RuntimeConfirmation.HUMAN_APPROVED) {
            blockers.add(Blocker.RUNTIME_CAUSE_UNCONFIRMED + ": potwierdzenie " + confirmation
                    + ", a wymagany jest test lokalny (LOCALLY_VERIFIED) albo decyzja człowieka (HUMAN_APPROVED)");
        }
        return new Readiness(List.copyOf(blockers));
    }
}
