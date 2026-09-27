package pl.training.sentry.module07;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Autoryzacja akcji agenta w kodzie hosta: klasyfikacja, zakres sesji i zgoda związana z digestem.
 *
 * <p>Model zgłasza wywołanie narzędzia jako tekst
 * (nazwa, projekt, zasób, treść zmiany). O tym, czy wywołanie się wykona, decyduje ta klasa,
 * a nie prompt ani opis narzędzia:</p>
 * <ol>
 *   <li>narzędzie spoza katalogu hosta: odmowa (brak wpisu oznacza odmowę);</li>
 *   <li>merge i deploy: odmowa zawsze, także ze zgodą, bo to decyzje procesu wydawniczego;</li>
 *   <li>projekt inny niż w {@link AgentSession}: odmowa, bez względu na to, co napisano w evencie;</li>
 *   <li>odczyt i szkic w sandboxie: zgoda niepotrzebna;</li>
 *   <li>mutacja: wymaga zgody człowieka na tę akcję, ten zasób i dokładnie tę treść zmiany
 *   (digest SHA-256 liczony przez hosta z treści, a nie deklarowany przez agenta). Zgoda jest
 *   jednorazowa: decyzja {@code ALLOW} ją wykorzystuje, więc powtórzenie tej samej mutacji
 *   wymaga nowej zgody.</li>
 * </ol>
 *
 * <p>Klasyfikacja akcji jest w kodzie ({@link AgentAction}), więc prompt nie może jej zmienić:
 * zdanie „to tylko odczyt” w odpowiedzi modelu nie ma tu żadnego wejścia.</p>
 *
 * <p>PRODUKCJA: zgody wydaje system poza procesem agenta (np. przycisk w narzędziu do przeglądu
 * z uwierzytelnieniem osoby zatwierdzającej) i podpisuje je, a host sprawdza podpis. Metoda
 * {@link #grant} nie może być narzędziem dostępnym dla modelu. Tutaj wykorzystane zgody są
 * tylko na liście w pamięci; w produkcji identyfikator zgody oznacza się jako wykorzystany
 * atomowo w trwałym magazynie, żeby dwa równoległe wywołania nie użyły tej samej zgody.</p>
 */
public final class ActionPolicy {

    public enum Risk {
        /** Odczyt danych w zakresie sesji. */
        READ,
        /** Artefakt w sandboxie agenta: analiza, plan, patch nieopublikowany. */
        DRAFT,
        /** Zmiana stanu poza sandboxem: repozytorium, issue, konfiguracja Sentry. */
        MUTATION,
        /** Poza delegacją agenta niezależnie od zgody. */
        FORBIDDEN
    }

    /** Katalog narzędzi hosta. Nazwa to identyfikator, którym posługuje się model. */
    public enum AgentAction {
        READ_ISSUE("read_issue", Risk.READ),
        READ_EVENT("read_event", Risk.READ),
        LIST_ISSUES("list_issues", Risk.READ),
        DRAFT_PATCH("draft_patch", Risk.DRAFT),
        OPEN_PULL_REQUEST("open_pull_request", Risk.MUTATION),
        RESOLVE_ISSUE("resolve_issue", Risk.MUTATION),
        UPDATE_SENTRY_CONFIG("update_sentry_config", Risk.MUTATION),
        MERGE_PULL_REQUEST("merge_pull_request", Risk.FORBIDDEN),
        DEPLOY_PRODUCTION("deploy_production", Risk.FORBIDDEN);

        private final String tool;
        private final Risk risk;

        AgentAction(String tool, Risk risk) {
            this.tool = tool;
            this.risk = risk;
        }

        public String tool() {
            return tool;
        }

        public Risk risk() {
            return risk;
        }

        static Optional<AgentAction> byTool(String tool) {
            return Arrays.stream(values()).filter(action -> action.tool.equals(tool)).findFirst();
        }
    }

    /**
     * Wywołanie narzędzia zgłoszone przez model. Wszystkie pola są niezaufane.
     *
     * @param resource np. {@code issue:SENTRY-TRAINING-12} albo {@code branch:fix/discount-code}
     * @param payload  dokładna treść zmiany (diff, nowy status); z niej host liczy digest
     */
    public record ActionRequest(String tool, String project, String resource, String payload) {

        public ActionRequest {
            payload = payload == null ? "" : payload;
        }
    }

    /** Zgoda człowieka na jedną akcję, jeden zasób i jedną treść zmiany, z terminem ważności. */
    public record Approval(AgentAction action, String resource, String digest, String approvedBy, Instant expiresAt) {
    }

    public enum Outcome {
        ALLOW,
        NEEDS_APPROVAL,
        DENY
    }

    public record Decision(Outcome outcome, String reason) {
    }

    private final AgentSession session;
    private final Clock clock;
    private final List<Approval> approvals = new ArrayList<>();
    private final List<Approval> used = new ArrayList<>();

    /**
     * @param clock zaufany zegar hosta; wywołujący nie podaje czasu oceny zgody
     */
    public ActionPolicy(AgentSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** Rejestruje zgodę wydaną przez człowieka. Wywołuje ją kod hosta, nigdy model. */
    public void grant(Approval approval) {
        approvals.add(approval);
    }

    /**
     * Decyzja dla jednego wywołania narzędzia. Host pyta o nią tuż przed wykonaniem akcji:
     * {@code ALLOW} dla mutacji wykorzystuje zgodę.
     */
    public Decision decide(ActionRequest request) {
        Optional<AgentAction> known = AgentAction.byTool(request.tool());
        if (known.isEmpty()) {
            return new Decision(Outcome.DENY, "narzędzie „" + request.tool() + "” spoza katalogu hosta");
        }
        AgentAction action = known.get();
        if (action.risk() == Risk.FORBIDDEN) {
            return new Decision(Outcome.DENY, "merge i deploy są poza delegacją agenta, także ze zgodą");
        }
        if (!session.project().equals(request.project())) {
            return new Decision(Outcome.DENY, "projekt „" + request.project() + "” spoza zakresu sesji ("
                    + session.project() + ")");
        }
        if (action.risk() != Risk.MUTATION) {
            return new Decision(Outcome.ALLOW, action.risk() == Risk.READ ? "odczyt w zakresie sesji" : "szkic w sandboxie");
        }

        String digest = digest(request.payload());
        Instant now = clock.instant();
        Optional<Approval> valid = approvals.stream()
                .filter(approval -> matches(approval, action, request.resource(), digest))
                .filter(approval -> now.isBefore(approval.expiresAt()))
                .findFirst();
        if (valid.isPresent()) {
            approvals.remove(valid.get());
            used.add(valid.get());
            return new Decision(Outcome.ALLOW, "zgoda " + valid.get().approvedBy() + " na digest " + shortDigest(digest));
        }
        if (approvals.stream().anyMatch(approval -> matches(approval, action, request.resource(), digest))) {
            return new Decision(Outcome.NEEDS_APPROVAL, "zgoda na digest " + shortDigest(digest) + " wygasła");
        }
        if (used.stream().anyMatch(approval -> matches(approval, action, request.resource(), digest))) {
            return new Decision(Outcome.NEEDS_APPROVAL, "zgoda na digest " + shortDigest(digest)
                    + " została już wykorzystana; powtórzenie wymaga nowej zgody");
        }
        boolean otherContent = Stream.concat(approvals.stream(), used.stream())
                .anyMatch(approval -> approval.action() == action && approval.resource().equals(request.resource()));
        if (otherContent) {
            // Typowy przypadek: człowiek zatwierdził patch, agent go potem „poprawił”.
            return new Decision(Outcome.NEEDS_APPROVAL, "zgoda dotyczy innej treści zmiany; aktualny digest "
                    + shortDigest(digest) + " wymaga nowej zgody");
        }
        return new Decision(Outcome.NEEDS_APPROVAL, "mutacja wymaga zgody człowieka na digest " + shortDigest(digest));
    }

    private static boolean matches(Approval approval, AgentAction action, String resource, String digest) {
        return approval.action() == action && approval.resource().equals(resource) && approval.digest().equals(digest);
    }

    /** SHA-256 treści zmiany. Każda zmiana choćby jednego znaku daje inny digest. */
    public static String digest(String payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM bez SHA-256", exception);
        }
    }

    public static String shortDigest(String digest) {
        return digest.substring(0, 12);
    }
}
