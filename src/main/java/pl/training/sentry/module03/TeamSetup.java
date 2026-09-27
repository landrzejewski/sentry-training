package pl.training.sentry.module03;

import pl.training.sentry.support.SentryRestApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Współpraca zespołowa w Sentry przez REST API: zespoły, członkostwo, dostęp do projektu
 * i Ownership Rules z auto-assignment.
 *
 * <p>Owner w regule musi mieć dostęp do projektu: zespół przez przypisanie do projektu, osoba
 * przez członkostwo w takim zespole. Inaczej Sentry odrzuci zapis całych reguł (HTTP 400, np.
 * „Team #checkout does not have access to project”). Dlatego kolejność kroków jest stała:
 * zespoły, zespoły w projekcie, członkowie zespołów, dopiero potem reguły.</p>
 *
 * <p>Tryby ({@code args[0]}):</p>
 * <ul>
 *   <li>{@code setup} (domyślny): zespoły {@value OwnershipRules#PAYMENTS_TEAM}
 *   i {@value OwnershipRules#CHECKOUT_TEAM}, właściciel tokenu w payments, opiekunka biblioteki
 *   w checkout, oba zespoły w projekcie, reguły {@link OwnershipRules#RULES} i tryb
 *   „Auto-assign to issue owner”. Idempotentny: istniejące elementy zostają;</li>
 *   <li>{@code cleanup}: puste reguły i usunięcie obu zespołów razem z ich przypisaniami do
 *   issues. Użytkownika utworzonego przez {@code sentry createuser} usuwa się osobno
 *   ({@code README.md}).</li>
 * </ul>
 *
 * <p>Zakresy tokenu ({@code SENTRY_AUTH_TOKEN}): {@code setup} potrzebuje {@code org:read},
 * {@code member:read}, {@code team:write} i {@code project:write}; {@code cleanup}
 * {@code project:write} i {@code team:admin}.</p>
 *
 * <p>PRODUKCJA: zespoły i członkostwo przychodzą z dostawcy tożsamości (np. SCIM), CODEOWNERS
 * leży w repozytorium, a tekst Ownership Rules też warto trzymać w repozytorium i wdrażać przez
 * API, żeby przechodził review jak kod. Tu API zastępuje oba procesy.</p>
 */
public final class TeamSetup {

    /**
     * Tryb auto-assignment w API; w UI „Auto-assign to issue owner”: przypisanie tylko z Ownership
     * Rules i CODEOWNERS, bez suspect commits.
     */
    public static final String AUTO_ASSIGN_TO_OWNER = "Auto Assign to Issue Owner";

    private TeamSetup() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "setup";
        Optional<SentryRestApi> configured = SentryRestApi.fromEnv(System.getenv());
        if (configured.isEmpty()) {
            System.out.println("Brak SENTRY_AUTH_TOKEN. Zakresy tokenu i uruchomienie: src/main/java/pl/training/sentry/module03/README.md, „Współpraca zespołowa”.");
            return;
        }
        switch (mode) {
            case "setup" -> setup(configured.get(), System.out);
            case "cleanup" -> cleanup(configured.get(), System.out);
            default -> System.out.println("Nieznany tryb " + mode + ". Dostępne: setup, cleanup.");
        }
    }

    /** Kto do którego zespołu: {@code me} to właściciel tokenu (prowadzący). */
    static Map<String, String> memberships() {
        Map<String, String> memberships = new LinkedHashMap<>();
        memberships.put("me", OwnershipRules.PAYMENTS_TEAM);
        memberships.put(OwnershipRules.LOYALTY_OWNER, OwnershipRules.CHECKOUT_TEAM);
        return memberships;
    }

    public static void setup(SentryRestApi api, PrintStream out) throws IOException, InterruptedException {
        String org = api.org();
        for (String team : new String[]{OwnershipRules.PAYMENTS_TEAM, OwnershipRules.CHECKOUT_TEAM}) {
            if (exists(api, "/teams/" + org + "/" + team + "/")) {
                out.println("Zespół istnieje:       #" + team);
            } else {
                // PUŁAPKA: Sentry dopisuje autora zespołu do jego członków, więc właściciel tokenu
                // trafia do obu zespołów i dostaje też powiadomienia dla #checkout. W produkcji
                // członkostwo przychodzi z dostawcy tożsamości, a nie z tego, kto kliknął „Create Team”.
                api.post("/organizations/" + org + "/teams/", teamBody(team));
                out.println("Zespół utworzony:      #" + team + " (autor zespołu zostaje jego członkiem)");
            }
            // Zespół bez dostępu do projektu nie może być ownerem jego issues.
            api.post("/projects/" + org + "/" + api.project() + "/teams/" + team + "/", null);
            out.println("    dostęp do projektu " + api.project());
        }

        for (Map.Entry<String, String> membership : memberships().entrySet()) {
            Optional<String> memberId = memberId(api, membership.getKey());
            if (memberId.isEmpty()) {
                // Owner bez konta: Sentry odrzuci zapis reguł z nieznanym e-mailem.
                out.println("Brak członka " + membership.getKey() + ": utwórz konto (sentry createuser, src/main/java/pl/training/sentry/module03/README.md).");
                continue;
            }
            api.post("/organizations/" + org + "/members/" + memberId.get() + "/teams/" + membership.getValue() + "/", null);
            out.println("Członek zespołu:       " + membership.getKey() + " -> #" + membership.getValue());
        }

        JsonNode ownership = api.put("/projects/" + org + "/" + api.project() + "/ownership/", ownershipBody(OwnershipRules.RULES));
        out.println("Ownership Rules zapisane, auto-assignment: " + ownership.path("autoAssignment").asString());
        OwnershipRules.parse(OwnershipRules.RULES).forEach(rule -> out.println("    " + rule));
        out.println("W UI: Settings > Projects > " + api.project() + " > Ownership Rules: "
                + api.webUrl("/settings/" + org + "/projects/" + api.project() + "/ownership/"));
    }

    public static void cleanup(SentryRestApi api, PrintStream out) throws IOException, InterruptedException {
        String org = api.org();
        api.put("/projects/" + org + "/" + api.project() + "/ownership/", ownershipBody(""));
        out.println("Ownership Rules wyczyszczone");
        for (String team : new String[]{OwnershipRules.PAYMENTS_TEAM, OwnershipRules.CHECKOUT_TEAM}) {
            if (exists(api, "/teams/" + org + "/" + team + "/")) {
                // Usunięcie zespołu usuwa też jego przypisania do issues (issue zostaje bez
                // assignee). Przypisania do osób i wpisy Activity zostają.
                api.delete("/teams/" + org + "/" + team + "/");
                out.println("Zespół usunięty:       #" + team + " (razem z jego przypisaniami do issues)");
            }
        }
    }

    /** Treść żądania zespołu ({@code POST /organizations/{org}/teams/}). */
    static ObjectNode teamBody(String slug) {
        return SentryRestApi.JSON.createObjectNode().put("slug", slug).put("name", slug);
    }

    /** Treść żądania Ownership Rules ({@code PUT /projects/{org}/{project}/ownership/}). */
    static ObjectNode ownershipBody(String rules) {
        ObjectNode body = SentryRestApi.JSON.createObjectNode();
        body.put("raw", rules);
        // Tylko Ownership Rules i CODEOWNERS. Tryb z suspect commits wymaga integracji
        // repozytorium, której lokalne Sentry nie ma.
        body.put("autoAssignment", AUTO_ASSIGN_TO_OWNER);
        return body;
    }

    /** Identyfikator członka organizacji po e-mailu albo {@code me} dla właściciela tokenu. */
    static Optional<String> memberId(SentryRestApi api, String emailOrMe) throws IOException, InterruptedException {
        if ("me".equals(emailOrMe)) {
            return Optional.of(api.get("/organizations/" + api.org() + "/members/me/").path("id").asString());
        }
        for (JsonNode member : api.get("/organizations/" + api.org() + "/members/?query="
                + SentryRestApi.encode("email:" + emailOrMe))) {
            if (emailOrMe.equalsIgnoreCase(member.path("email").asString())) {
                return Optional.of(member.path("id").asString());
            }
        }
        return Optional.empty();
    }

    private static boolean exists(SentryRestApi api, String path) throws IOException, InterruptedException {
        try {
            api.get(path);
            return true;
        } catch (SentryRestApi.ApiException notFound) {
            if (notFound.status() == 404) {
                return false;
            }
            throw notFound;
        }
    }
}
