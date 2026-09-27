package pl.training.sentry.module07;

import pl.training.sentry.module07.ActionPolicy.ActionRequest;
import pl.training.sentry.module07.ActionPolicy.Decision;
import pl.training.sentry.module07.ActionPolicy.Outcome;
import pl.training.sentry.module07.EvidenceCollector.EvidencePackage;

import java.util.ArrayList;
import java.util.List;

/**
 * Maszyna stanów pracy agenta nad jednym issue: od zebrania dowodów do przeglądu człowieka.
 *
 * <p>Kolejność etapów:</p>
 * <pre>
 * COLLECT -&gt; ANALYZE -&gt; PLAN -&gt; DRAFT_PATCH -&gt; VERIFY -&gt; OPEN_PR -&gt; HUMAN_REVIEW -&gt; COMPLETE
 * VERIFY -&gt; DRAFT_PATCH, gdy testy nie przechodzą
 * HUMAN_REVIEW -&gt; DRAFT_PATCH, gdy przegląd wymaga zmian
 * </pre>
 *
 * <p>Każda metoda sprawdza bieżący etap, więc agent nie przeskoczy bramki, nawet gdy model
 * „zna odpowiedź”. Bramki: raport musi przejść walidację, plan zatwierdza człowiek, wynik
 * testów dotyczy dokładnego digestu patcha, a PR otwiera się dopiero po decyzji
 * {@link ActionPolicy}, o którą workflow pyta sam. Nie przyjmuje gotowego „ALLOW” od
 * wywołującego. Nowy patch kasuje wynik testów poprzedniego.</p>
 *
 * <p>{@code COMPLETE} kończy zakres agenta. Nie oznacza merge ani deployu: te decyzje należą do
 * procesu wydawniczego, a {@link ActionPolicy} odrzuca je zawsze.</p>
 */
public final class AgentWorkflow {

    public enum Stage {
        COLLECT,
        ANALYZE,
        PLAN,
        DRAFT_PATCH,
        VERIFY,
        OPEN_PR,
        HUMAN_REVIEW,
        COMPLETE
    }

    private final AgentSession session;
    private final ActionPolicy policy;
    private final String pullRequestResource;
    private final List<String> history = new ArrayList<>();

    private Stage stage = Stage.COLLECT;
    private String patch;
    private String patchDigest;

    /**
     * @param pullRequestResource zasób, którego dotyczy zgoda na PR, np. {@code branch:fix/discount-code}
     */
    public AgentWorkflow(AgentSession session, ActionPolicy policy, String pullRequestResource) {
        this.session = session;
        this.policy = policy;
        this.pullRequestResource = pullRequestResource;
    }

    public Stage stage() {
        return stage;
    }

    /** Ścieżka audytowa: każde przejście i każda odmowa z powodem. */
    public List<String> history() {
        return List.copyOf(history);
    }

    /** Digest bieżącego patcha, liczony przez hosta; {@code null} przed pierwszym patchem. */
    public String patchDigest() {
        return patchDigest;
    }

    public void acceptEvidence(EvidencePackage evidence) {
        require(Stage.COLLECT, "przyjąć dowodów");
        if (evidence.items().isEmpty()) {
            throw new IllegalStateException("Bez dowodów nie ma analizy");
        }
        move(Stage.ANALYZE, "pakiet dowodów: " + evidence.items().size() + " pozycji");
    }

    /** @return {@code true}, gdy raport przeszedł walidację; odrzucony zostawia etap ANALYZE */
    public boolean acceptReport(ReportValidator.Result report) {
        require(Stage.ANALYZE, "przyjąć raportu");
        if (!report.accepted()) {
            String reason = report.stopReason() != null
                    ? "model zatrzymał się: " + report.stopReason()
                    : report.errors().size() + " błędów walidacji";
            history.add("ANALYZE: raport odrzucony (" + reason + ")");
            return false;
        }
        move(Stage.PLAN, "raport przyjęty do przeglądu");
        return true;
    }

    /**
     * Plan zatwierdza człowiek. W produkcji ta metoda jest wywoływana z narzędzia do przeglądu,
     * nigdy przez model: port decyzji człowieka dostępny dla modelu przestaje być bramką.
     */
    public void approvePlan(String approvedBy) {
        require(Stage.PLAN, "zatwierdzić planu");
        move(Stage.DRAFT_PATCH, "plan zatwierdził " + approvedBy);
    }

    public void submitPatch(String newPatch) {
        require(Stage.DRAFT_PATCH, "przyjąć patcha");
        patch = newPatch;
        patchDigest = ActionPolicy.digest(newPatch);
        move(Stage.VERIFY, "patch " + ActionPolicy.shortDigest(patchDigest));
    }

    /**
     * Wynik testów z CI dla konkretnego digestu. Wynik zadeklarowany przez model nie jest
     * niezależnym dowodem, dlatego ta metoda przyjmuje digest, który sprawdził CI.
     */
    public void recordVerification(String testedDigest, boolean passed) {
        require(Stage.VERIFY, "przyjąć wyniku testów");
        if (!testedDigest.equals(patchDigest)) {
            throw new IllegalStateException("Wynik testów dotyczy patcha " + ActionPolicy.shortDigest(testedDigest)
                    + ", a bieżący to " + ActionPolicy.shortDigest(patchDigest));
        }
        if (passed) {
            move(Stage.OPEN_PR, "testy przeszły dla " + ActionPolicy.shortDigest(patchDigest));
        } else {
            move(Stage.DRAFT_PATCH, "testy nie przeszły, PR zablokowany");
        }
    }

    /** Otwarcie PR: workflow sam pyta politykę o decyzję dla bieżącego patcha. */
    public Decision openPullRequest() {
        require(Stage.OPEN_PR, "otworzyć PR");
        Decision decision = policy.decide(new ActionRequest(
                ActionPolicy.AgentAction.OPEN_PULL_REQUEST.tool(), session.project(), pullRequestResource, patch));
        if (decision.outcome() == Outcome.ALLOW) {
            move(Stage.HUMAN_REVIEW, "PR otwarty: " + decision.reason());
        } else {
            history.add("OPEN_PR: " + decision.outcome() + " (" + decision.reason() + ")");
        }
        return decision;
    }

    /** Przegląd PR przez człowieka: akceptacja kończy zakres agenta, uwagi otwierają nowy cykl. */
    public void review(boolean accepted, String comment) {
        require(Stage.HUMAN_REVIEW, "zakończyć przeglądu");
        if (accepted) {
            move(Stage.COMPLETE, "przegląd zaakceptował propozycję: " + comment);
        } else {
            // Stary patch, jego testy i zgoda na jego digest nie mogą zostać użyte ponownie.
            patch = null;
            patchDigest = null;
            move(Stage.DRAFT_PATCH, "przegląd wymaga zmian: " + comment);
        }
    }

    private void require(Stage expected, String what) {
        if (stage != expected) {
            String message = "Nie można " + what + " w etapie " + stage + " (wymagany " + expected + ")";
            history.add(stage + ": odmowa, " + message);
            throw new IllegalStateException(message);
        }
    }

    private void move(Stage next, String reason) {
        history.add(stage + " -> " + next + ": " + reason);
        stage = next;
    }
}
