package pl.training.sentry.module03;

import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.SentryRestApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Triage jednego issue z demo modułu 3 przez REST API: suggested owners, assignment, komentarz
 * triage i zmiana statusu.
 *
 * <p>Każdy krok zostawia wpis w Activity, więc historia issue pokazuje, kto i dlaczego przejął
 * następny krok. Program pracuje na issue z trybu online {@code Module03Demo}
 * (scenariusz 6, timeout bramki) po {@link TeamSetup}.</p>
 *
 * <p>Zakresy tokenu ({@code SENTRY_AUTH_TOKEN}): {@code event:read}, {@code event:write}
 * (przypisanie, komentarz, status), {@code project:read} (suggested owners to endpoint projektu)
 * i {@code member:read} (konto osoby przypisywanej). Bez konta opiekunki program przypisuje
 * zespół {@code #checkout} i wtedy potrzebuje też {@code team:read}.</p>
 *
 * <p>PUŁAPKA: komentarz triage to wpis Activity widoczny dla każdego, kto ma dostęp do projektu,
 * a przy synchronizacji komentarzy także w podłączonym trackerze. Nie wkleja się do niego stack
 * trace, tokenów ani danych osobowych.</p>
 */
public final class TriageWalkthrough {

    /** Issue z timeoutem bramki; scenariusz 6 wysyła takie zdarzenie dla ORD-6001. */
    static final String ISSUE_QUERY = "is:unresolved training.module:module03 payment.failure_reason:timeout";

    private TriageWalkthrough() {
    }

    public static void main(String[] args) throws Exception {
        Optional<SentryRestApi> configured = SentryRestApi.fromEnv(System.getenv());
        if (configured.isEmpty()) {
            System.out.println("Brak SENTRY_AUTH_TOKEN. Kolejność: TeamSetup setup, Module03Demo online, TriageWalkthrough "
                    + "(src/main/java/pl/training/sentry/module03/README.md, „Współpraca zespołowa”).");
            return;
        }
        SentryRestApi api = configured.get();
        String org = api.org();

        // Scenariusz 1: suggested owners i auto-assignment.
        //
        // O co chodzi: suggested owners to kandydaci ze wszystkich pasujących reguł, a assignee to jedna
        // osoba albo zespół odpowiedzialny za następny krok. Auto-assignment bierze pierwszego ownera
        // ostatniej pasującej reguły, a wpis Activity podaje, która reguła zdecydowała.
        //
        // Co pokazujemy: program przez REST API bierze najnowsze nierozwiązane issue timeoutu bramki
        // z module03 (ISSUE_QUERY, ostatnie 24 h) i wypisuje jego status, liczniki, reguły pasujące
        // do najnowszego eventu (events/{id}/owners/), suggested owners, obecnego assignee i ostatni
        // wpis assigned z Activity.
        //
        // Problem: obecność na liście suggested owners nie oznacza przejęcia odpowiedzialności,
        // a przypisanie nie zmienia statusu issue, więc samo auto-assignment nie jest decyzją triage.
        // Auto-assignment nie nadpisuje istniejącego assignee: ręczne przypisanie z poprzedniego
        // uruchomienia zostaje.
        //
        // Dobra praktyka: odczytać z Activity regułę, która przypisała issue, i potwierdzić, kto
        // naprawdę robi następny krok; sugestie są wejściem do tej decyzji.
        //
        // Na co patrzeć: reguły i suggested owners w kolejności z pliku reguł (#checkout, #payments),
        // przy pierwszym uruchomieniu assignee #payments z regułą tags.payment.failure_reason:*
        // w Activity oraz substatus issue, którego przypisanie nie zmieniło.
        //
        // Uruchomienie (po TeamSetup setup i Module03Demo online, README „Współpraca zespołowa”;
        // token: event:read, event:write, project:read, member:read, bez konta opiekunki też team:read):
        // SENTRY_AUTH_TOKEN=... ./mvnw -q compile exec:java
        //     -Dexec.mainClass=pl.training.sentry.module03.TriageWalkthrough
        DemoConsole.scenario(1, "Suggested owners i auto-assignment",
                "pokazać, kogo Sentry wskazał dla issue timeoutu bramki i dlaczego.");
        JsonNode issues = api.get("/projects/" + org + "/" + api.project() + "/issues/?sort=date&limit=1&statsPeriod=24h&query="
                + SentryRestApi.encode(ISSUE_QUERY));
        if (issues.isEmpty()) {
            DemoConsole.step("Brak issue dla zapytania " + ISSUE_QUERY + ". Najpierw Module03Demo w trybie online.");
            return;
        }
        String issueId = issues.get(0).path("id").asString();
        JsonNode issue = api.get("/organizations/" + org + "/issues/" + issueId + "/");
        String eventId = api.get("/organizations/" + org + "/issues/" + issueId + "/events/latest/").path("eventID").asString();
        DemoConsole.step("Issue " + issue.path("shortId").asString() + ": " + issue.path("title").asString());
        DemoConsole.step("   status " + issue.path("status").asString() + "/" + issue.path("substatus").asString()
                + ", events " + issue.path("count").asString() + ", users " + issue.path("userCount").asInt()
                + ", first seen " + issue.path("firstSeen").asString());

        JsonNode owners = api.get("/projects/" + org + "/" + api.project() + "/events/" + eventId + "/owners/");
        DemoConsole.step("Pasujące reguły dla najnowszego eventu (kolejność z pliku reguł):");
        for (JsonNode rule : owners.path("rules")) {
            DemoConsole.step("   " + describeRule(rule));
        }
        DemoConsole.step("Suggested owners (kolejność reguł z pliku): " + actors(owners.path("owners")));
        DemoConsole.step("Assignee teraz: " + assignee(issue.path("assignedTo")));
        for (JsonNode activity : issue.path("activity")) {
            if ("assigned".equals(activity.path("type").asString())) {
                DemoConsole.step("   ostatnie przypisanie w Activity: " + describeActivity(activity));
                if (!activity.path("data").has("rule")) {
                    // Kolejne uruchomienie: ręczne przypisanie z kroku 2 poprzedniego uruchomienia
                    // zostaje. Auto-assignment nie nadpisuje istniejącego assignee, zadziała znowu
                    // dopiero po usunięciu przypisania.
                    DemoConsole.step("   przypisanie ręczne: auto-assignment go nie nadpisze przy kolejnych eventach");
                }
                break;
            }
        }
        DemoConsole.lookAt("suggested owners to kandydaci ze wszystkich pasujących reguł. Auto-assignment bierze pierwszego "
                + "ownera ostatniej pasującej reguły (wpis Activity podaje regułę). Assignment nie zmienia statusu: "
                + "issue nadal jest " + issue.path("substatus").asString() + ".");

        // Scenariusz 2: przypisanie, komentarz triage i Mark reviewed.
        //
        // O co chodzi: triage kończy się decyzją: kto robi następny krok, dlaczego i jak sprawdzić
        // wynik. Każda z tych decyzji jest osobnym wpisem Activity issue.
        //
        // Co pokazujemy: przypisanie (PUT issues/{id}/ z assignedTo) na opiekunkę biblioteki, a bez
        // jej konta na zespół #checkout; komentarz triage przez notes API w stałym układzie (zakres,
        // fakty z liczników issue, hipoteza, następny krok z właścicielem, kryterium weryfikacji);
        // Mark reviewed (inbox=false). Na końcu stan issue i 5 najnowszych wpisów Activity.
        //
        // Problem: komentarz widzi każdy z dostępem do projektu, a przy synchronizacji komentarzy
        // także podłączony tracker, więc nie wkleja się do niego stack trace, tokenów ani danych
        // osobowych. Mark reviewed oznacza ocenę, a nie diagnozę ani naprawę.
        //
        // Dobra praktyka: jeden assignee na następny krok, komentarz z liczbami z issue, a nie
        // z pamięci, i świadomie wybrana operacja na issue. Ręczne przypisanie zostaje przy
        // kolejnych eventach.
        //
        // Na co patrzeć: w konsoli assignee i status po zmianach (Ongoing po Mark reviewed) oraz
        // wpisy Activity. W UI issue (link w wydruku) sekcja Activity z przypisaniem, komentarzem i Mark
        // reviewed; lista Issues z filtrem assigned:anna.checkout@sentry-training.local to kolejka
        // opiekunki.
        //
        // Uruchomienie: jak scenariusz 1, oba scenariusze wykonują się w jednym przebiegu.
        DemoConsole.scenario(2, "Przypisanie, komentarz triage i Mark reviewed",
                "pokazać trzy decyzje triage jako wpisy Activity, każdą z właścicielem i uzasadnieniem.");
        Optional<String> anna = TeamSetup.memberId(api, OwnershipRules.LOYALTY_OWNER)
                .map(memberId -> userId(api, memberId));
        // orElseGet, nie orElse: argument orElse liczy się zawsze, więc zapytanie o zespół (i zakres
        // team:read) byłoby potrzebne także wtedy, gdy konto opiekunki istnieje.
        String assignTo = anna.map(id -> "user:" + id).orElseGet(() -> "team:" + teamId(api, OwnershipRules.CHECKOUT_TEAM));
        DemoConsole.step("Zespół payments ocenił issue: bramka odpowiada, ale klient checkout ma timeout 250 ms. "
                + "Następny krok należy do checkout, więc przypisanie na " + (anna.isPresent() ? OwnershipRules.LOYALTY_OWNER : "#checkout"));
        api.put("/organizations/" + org + "/issues/" + issueId + "/",
                SentryRestApi.JSON.createObjectNode().put("assignedTo", assignTo));

        String comment = triageComment(issue);
        api.post("/organizations/" + org + "/issues/" + issueId + "/notes/",
                SentryRestApi.JSON.createObjectNode().put("text", comment));
        DemoConsole.step("Komentarz triage (notes API):");
        comment.lines().forEach(line -> DemoConsole.step("   | " + line));

        // Mark reviewed: issue zostaje nierozwiązane, znika z kolejki nowych (status Ongoing).
        api.put("/organizations/" + org + "/issues/" + issueId + "/", markReviewedBody());
        JsonNode after = api.get("/organizations/" + org + "/issues/" + issueId + "/");
        DemoConsole.step("Po zmianach: assignee " + assignee(after.path("assignedTo")) + ", status "
                + after.path("status").asString() + "/" + after.path("substatus").asString());
        DemoConsole.step("Activity (najnowsze wpisy):");
        int shown = 0;
        for (JsonNode activity : after.path("activity")) {
            if (shown++ == 5) {
                break;
            }
            DemoConsole.step("   " + describeActivity(activity));
        }
        DemoConsole.lookAt("w UI issue " + api.webUrl("/organizations/" + org + "/issues/" + issueId + "/")
                + ": sekcja Activity ma przypisanie, komentarz i Mark reviewed; lista Issues z filtrem "
                + "assigned:" + OwnershipRules.LOYALTY_OWNER + " pokazuje kolejkę opiekunki.");
    }

    /**
     * Komentarz triage w stałym układzie: zakres, fakty, hipoteza, następny krok z właścicielem
     * i kryterium weryfikacji, tak żeby kolejna osoba mogła kontynuować bez powtarzania analizy. Liczby pochodzą z issue, nie z pamięci osoby, która pisze.
     */
    static String triageComment(JsonNode issue) {
        return String.join("\n",
                "Zakres: project=" + issue.path("project").path("slug").asString() + ", environment=training, okres 24h",
                "Fakty: " + issue.path("count").asString() + " events, " + issue.path("userCount").asInt()
                        + " users, first seen " + issue.path("firstSeen").asString(),
                "Fakty: przyczyna z tagu payment.failure_reason=timeout, bramka odpowiada po 2 s",
                "Hipoteza: timeout klienta checkout (250 ms) krótszy niż czas odpowiedzi bramki",
                "Następny krok: checkout porównuje timeout z SLA bramki; właściciel: " + OwnershipRules.LOYALTY_OWNER,
                "Kryterium weryfikacji: brak nowych eventów timeout w kolejnym release przy potwierdzonym ruchu");
    }

    /** Mark reviewed w API: pole {@code inbox=false} (issue przechodzi z New do Ongoing). */
    static ObjectNode markReviewedBody() {
        return SentryRestApi.JSON.createObjectNode().put("inbox", false);
    }

    /**
     * Reguła z odpowiedzi {@code events/{id}/owners/}: para {@code [[typ, wzorzec], [[typ ownera,
     * identyfikator], ...]]}, np. {@code [["path", "pl/.../*"], [["team", "checkout"]]]}.
     */
    static String describeRule(JsonNode rule) {
        List<String> owners = new ArrayList<>();
        for (JsonNode owner : rule.path(1)) {
            owners.add(("team".equals(owner.path(0).asString()) ? "#" : "") + owner.path(1).asString());
        }
        return rule.path(0).path(0).asString() + ":" + rule.path(0).path(1).asString() + " " + String.join(" ", owners);
    }

    /** Wpis Activity w jednej linii; przy auto-assignment z danymi, która reguła zdecydowała. */
    static String describeActivity(JsonNode activity) {
        JsonNode data = activity.path("data");
        String who = activity.path("user").path("email").asString("Sentry");
        String line = activity.path("dateCreated").asString() + " " + activity.path("type").asString() + " (" + who + ")";
        return switch (activity.path("type").asString()) {
            case "assigned" -> line + " -> " + ("team".equals(data.path("assigneeType").asString())
                    ? "#" + data.path("assigneeName").asString() : data.path("assigneeEmail").asString())
                    + (data.has("rule") ? ", reguła: " + data.path("rule").asString() : "");
            case "note" -> line + ": " + data.path("text").asString().lines().findFirst().orElse("") + " ...";
            default -> line;
        };
    }

    static String actors(JsonNode actors) {
        List<String> names = new ArrayList<>();
        for (JsonNode actor : actors) {
            names.add(("team".equals(actor.path("type").asString()) ? "#" : "") + actor.path("name").asString());
        }
        return names.isEmpty() ? "(brak)" : String.join(", ", names);
    }

    static String assignee(JsonNode assignedTo) {
        if (assignedTo.isMissingNode() || assignedTo.isNull()) {
            return "(brak)";
        }
        return ("team".equals(assignedTo.path("type").asString()) ? "#" : "") + assignedTo.path("name").asString();
    }

    private static String userId(SentryRestApi api, String memberId) {
        try {
            return api.get("/organizations/" + api.org() + "/members/" + memberId + "/").path("user").path("id").asString();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String teamId(SentryRestApi api, String slug) {
        try {
            return api.get("/teams/" + api.org() + "/" + slug + "/").path("id").asString();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
