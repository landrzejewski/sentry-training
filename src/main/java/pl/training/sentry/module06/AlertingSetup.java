package pl.training.sentry.module06;

import pl.training.sentry.module06.PaymentsAlertPolicy.Alert;
import pl.training.sentry.support.SentryRestApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pokazuje konfigurację {@link PaymentsAlertPolicy} w Sentry przez REST API: utworzenie, stan
 * i sprzątanie.
 *
 * <p>Monitor należy do projektu, a Alert do organizacji i wskazuje Monitor jako źródło.
 * Konfiguracja z kodu ma te same zalety co {@code MonitorConfig} w check-inach: jest
 * w repozytorium, przechodzi review i da się ją odtworzyć po awarii albo w nowej organizacji.</p>
 *
 * <p>Tryby ({@code args[0]}):</p>
 * <ul>
 *   <li>{@code setup} (domyślny): Internal Integration z webhookiem, Metric Monitor i trzy
 *   Alerty. Idempotentny: obiekt o tej samej nazwie jest aktualizowany, a nie tworzony drugi raz;</li>
 *   <li>{@code status}: kiedy i dla których Issues Alerty wykonały akcję (historia Alertu);</li>
 *   <li>{@code cleanup}: usuwa Alerty, otwarte Issue Monitora, Monitor i integrację.</li>
 * </ul>
 *
 * <p>Zakresy tokenu (zmienna {@code SENTRY_AUTH_TOKEN}): {@code setup} potrzebuje
 * {@code org:read}, {@code project:read}, {@code alerts:write} i {@code org:write} (tylko do
 * utworzenia Internal Integration); {@code status} {@code alerts:read}; {@code cleanup}
 * {@code alerts:write}, {@code project:read} (identyfikator projektu do listy Monitorów),
 * {@code event:admin} (otwarte Issue Metric Monitora) i {@code org:admin} (usunięcie integracji).</p>
 *
 * <p>PUŁAPKA: lokalne self-hosted nie dostarczy webhooka pod adres prywatny, także
 * {@code host.docker.internal} i adresy sieci Docker. Alert zadziała, a dostawa zakończy się
 * w logu taskworkera wpisem {@code restricted_ip}. Szczegóły i opcja zmiany konfiguracji:
 * {@code README.md}, „Alerty end-to-end”.</p>
 */
public final class AlertingSetup {

    /** Adres odbiornika widziany z kontenerów Sentry (Docker Desktop). */
    public static final String DEFAULT_WEBHOOK_URL =
            "http://host.docker.internal:" + WebhookReceiver.DEFAULT_PORT + WebhookReceiver.PATH;

    private AlertingSetup() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "setup";
        Optional<SentryRestApi> configured = SentryRestApi.fromEnv(System.getenv());
        if (configured.isEmpty()) {
            System.out.println("Brak SENTRY_AUTH_TOKEN. Zakresy tokenu i uruchomienie: src/main/java/pl/training/sentry/module06/README.md, „Alerty end-to-end”.");
            return;
        }
        SentryRestApi api = configured.get();
        switch (mode) {
            case "setup" -> setup(api, System.getenv().getOrDefault("ALERT_WEBHOOK_URL", DEFAULT_WEBHOOK_URL), System.out);
            case "status" -> status(api, System.out);
            case "cleanup" -> cleanup(api, System.out);
            default -> System.out.println("Nieznany tryb " + mode + ". Dostępne: setup, status, cleanup.");
        }
    }

    /** Identyfikatory obiektów polityki po {@link #setup}. */
    public record Installed(String webhookSlug, long issueStreamMonitorId, long paymentErrorsMonitorId,
                            Map<String, Long> alertIds) {
    }

    /** Tworzy albo aktualizuje integrację, Monitor i Alerty. Zwraca ich identyfikatory. */
    public static Installed setup(SentryRestApi api, String webhookUrl, PrintStream out)
            throws IOException, InterruptedException {
        String projectId = api.get("/projects/" + api.org() + "/" + api.project() + "/").path("id").asString();

        // 1. Kanał: Internal Integration z adresem odbiornika. Alert nie zna adresu URL, tylko
        //    slug integracji, więc zmiana odbiornika nie wymaga edycji Alertów.
        String webhookSlug = upsertWebhookIntegration(api, webhookUrl, out);

        // 2. Detekcja: Metric Monitor w projekcie. Issue Stream Monitor istnieje w każdym
        //    projekcie i jest źródłem „wszystkich Issues projektu” dla Alertów na błędy.
        JsonNode detectors = api.get("/organizations/" + api.org() + "/detectors/?project=" + projectId + "&per_page=100");
        long issueStreamId = findFirst(detectors, "type", "issue_stream")
                .orElseThrow(() -> new IllegalStateException("Projekt nie ma Issue Stream Monitora"))
                .path("id").asLong();
        ObjectNode monitorBody = PaymentsAlertPolicy.monitorBody(PaymentsAlertPolicy.PAYMENT_ERRORS, projectId);
        Optional<JsonNode> existingMonitor = findFirst(detectors, "name", PaymentsAlertPolicy.PAYMENT_ERRORS.name());
        long monitorId;
        if (existingMonitor.isPresent()) {
            monitorId = existingMonitor.get().path("id").asLong();
            api.put("/organizations/" + api.org() + "/detectors/" + monitorId + "/", monitorBody);
            out.println("Monitor zaktualizowany: " + PaymentsAlertPolicy.PAYMENT_ERRORS.name() + " (id " + monitorId + ")");
        } else {
            monitorId = api.post("/organizations/" + api.org() + "/projects/" + api.project() + "/detectors/", monitorBody)
                    .path("id").asLong();
            out.println("Monitor utworzony:     " + PaymentsAlertPolicy.PAYMENT_ERRORS.name() + " (id " + monitorId + ")");
        }

        // 3. Reakcja: Alerty. Alert jest zasobem organizacji, a projekt wskazuje źródło (Monitor).
        JsonNode workflows = listAlerts(api);
        Map<String, Long> alertIds = new LinkedHashMap<>();
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            long source = alert.source() == PaymentsAlertPolicy.Source.PROJECT_ISSUES ? issueStreamId : monitorId;
            ObjectNode body = PaymentsAlertPolicy.alertBody(alert, source, webhookSlug);
            Optional<JsonNode> existing = findFirst(workflows, "name", alert.name());
            long id;
            if (existing.isPresent()) {
                id = existing.get().path("id").asLong();
                // PUT zastępuje triggery, bloki If/Then i akcje. PUŁAPKA: aktualizacja Alertu
                // nie zeruje throttlingu dla Issues, które już dostały powiadomienie.
                api.put("/organizations/" + api.org() + "/workflows/" + id + "/", body);
                out.println("Alert zaktualizowany:  " + alert.name() + " (id " + id + ")");
            } else {
                id = api.post("/organizations/" + api.org() + "/workflows/", body).path("id").asLong();
                out.println("Alert utworzony:       " + alert.name() + " (id " + id + ")");
            }
            out.println("    " + PaymentsAlertPolicy.describe(alert));
            alertIds.put(alert.name(), id);
        }
        out.println("W UI: Monitors > Alerts oraz Monitors > Metric: " + api.webUrl("/organizations/" + api.org() + "/monitors/alerts/"));
        return new Installed(webhookSlug, issueStreamId, monitorId, alertIds);
    }

    /** Dla każdego Alertu polityki: ostatnie wykonanie akcji i Issues z historii z 24 godzin. */
    public static void status(SentryRestApi api, PrintStream out) throws IOException, InterruptedException {
        JsonNode workflows = listAlerts(api);
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            Optional<JsonNode> found = findFirst(workflows, "name", alert.name());
            if (found.isEmpty()) {
                out.println(alert.name() + ": nie istnieje (uruchom setup)");
                continue;
            }
            long id = found.get().path("id").asLong();
            List<Firing> firings = firings(api, id);
            out.println(alert.name() + " (id " + id + "): ostatnio " + found.get().path("lastTriggered").asString("nigdy"));
            firings.forEach(firing -> out.println("    " + firing));
        }
    }

    /** Usuwa Alerty, Monitor i integrację polityki. Bezpieczne przy wielokrotnym uruchomieniu. */
    public static void cleanup(SentryRestApi api, PrintStream out) throws IOException, InterruptedException {
        JsonNode workflows = listAlerts(api);
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            Optional<JsonNode> found = findFirst(workflows, "name", alert.name());
            if (found.isPresent()) {
                api.delete("/organizations/" + api.org() + "/workflows/" + found.get().path("id").asLong() + "/");
                out.println("Alert usunięty:   " + alert.name());
            }
        }
        String projectId = api.get("/projects/" + api.org() + "/" + api.project() + "/").path("id").asString();
        JsonNode detectors = api.get("/organizations/" + api.org() + "/detectors/?project=" + projectId + "&per_page=100");
        Optional<JsonNode> monitor = findFirst(detectors, "name", PaymentsAlertPolicy.PAYMENT_ERRORS.name());
        if (monitor.isPresent()) {
            // Issue Monitora zostałoby otwarte po jego usunięciu, bez niczego, co je zamknie
            // (tak samo jak Cron Issues w „Sprzątaniu po demo”). Zwykle zamyka je recovery.
            JsonNode latestGroup = monitor.get().path("latestGroup");
            if (latestGroup.has("id") && !"resolved".equals(latestGroup.path("status").asString())) {
                closeMonitorIssue(api, latestGroup.path("id").asString(), latestGroup.path("shortId").asString(), out);
            }
            api.delete("/organizations/" + api.org() + "/detectors/" + monitor.get().path("id").asLong() + "/");
            out.println("Monitor usunięty: " + PaymentsAlertPolicy.PAYMENT_ERRORS.name());
        }
        Optional<JsonNode> integration = activeIntegration(api.get("/organizations/" + api.org() + "/sentry-apps/"));
        if (integration.isPresent()) {
            String slug = integration.get().path("slug").asString();
            try {
                api.delete("/sentry-apps/" + slug + "/");
                out.println("Integracja usunięta: " + slug + " (Sentry kończy usuwanie w tle)");
            } catch (SentryRestApi.ApiException forbidden) {
                if (forbidden.status() != 403) {
                    throw forbidden;
                }
                out.println("Integracja " + slug + " została: usunięcie wymaga zakresu org:admin. "
                        + "Bez Alertów nie wysyła niczego; można ją usunąć w Settings > Custom Integrations.");
            }
        }
    }

    /**
     * Zamyka otwarte Issue Metric Monitora przed usunięciem Monitora.
     *
     * <p>PUŁAPKA (sprawdzone na self-hosted 26.9.0): Issue Metric Monitora nie da się rozwiązać
     * ręcznie, API odpowiada 400 „Cannot manually resolve one or more issues”, bo stan należy do
     * Monitora (recovery). Po usunięciu Monitora nic go już nie zamknie, dlatego sprzątanie usuwa
     * takie Issue (zakres {@code event:admin}). To dane demo, nie historia produkcyjnego incydentu.</p>
     */
    private static void closeMonitorIssue(SentryRestApi api, String issueId, String shortId, PrintStream out)
            throws IOException, InterruptedException {
        String path = "/organizations/" + api.org() + "/issues/" + issueId + "/";
        try {
            api.put(path, SentryRestApi.JSON.createObjectNode().put("status", "resolved"));
            out.println("Issue Monitora rozwiązane: " + shortId);
        } catch (SentryRestApi.ApiException manualResolveRejected) {
            if (manualResolveRejected.status() != 400) {
                throw manualResolveRejected;
            }
            try {
                api.delete(path);
                out.println("Issue Monitora usunięte: " + shortId + " (ręczne Resolve niedostępne dla Metric Issue)");
            } catch (SentryRestApi.ApiException forbidden) {
                if (forbidden.status() != 403) {
                    throw forbidden;
                }
                out.println("Issue Monitora " + shortId + " zostaje otwarte: usunięcie wymaga zakresu event:admin.");
            }
        }
    }

    /** Jedno Issue z historii Alertu: ile razy Alert wykonał dla niego akcję i kiedy ostatnio. */
    public record Firing(String issueId, String shortId, String title, int count, String lastTriggered,
                         String eventId) {

        @Override
        public String toString() {
            return shortId + " x" + count + " (ostatnio " + lastTriggered + "): " + title;
        }
    }

    /** Historia Alertu z ostatnich 24 godzin ({@code GET .../workflows/{id}/group-history/}). */
    public static List<Firing> firings(SentryRestApi api, long alertId) throws IOException, InterruptedException {
        return parseFirings(api.get("/organizations/" + api.org() + "/workflows/" + alertId + "/group-history/?statsPeriod=24h"));
    }

    /** Parsowanie historii Alertu; osobno, żeby test sprawdził je na zapisanej odpowiedzi. */
    public static List<Firing> parseFirings(JsonNode history) {
        List<Firing> firings = new ArrayList<>();
        for (JsonNode row : history) {
            JsonNode group = row.path("group");
            String title = group.path("title").asString();
            firings.add(new Firing(
                    group.path("id").asString(),
                    group.path("shortId").asString(),
                    title.length() > 70 ? title.substring(0, 70) + "..." : title,
                    row.path("count").asInt(),
                    row.path("lastTriggered").asString(),
                    row.path("eventId").asString("")));
        }
        return firings;
    }

    static JsonNode listAlerts(SentryRestApi api) throws IOException, InterruptedException {
        return api.get("/organizations/" + api.org() + "/workflows/?query=module06&per_page=100");
    }

    /**
     * Integracja polityki, z pominięciem usuwanej. PUŁAPKA: po {@code DELETE} Sentry usuwa
     * integrację w tle, a do tego czasu lista zwraca ją ze statusem
     * {@code deletion_in_progress}. Alert podłączony do takiej integracji nic by nie wysłał.
     */
    static Optional<JsonNode> activeIntegration(JsonNode apps) {
        for (JsonNode app : apps) {
            if (PaymentsAlertPolicy.WEBHOOK_INTEGRATION_NAME.equals(app.path("name").asString(null))
                    && !"deletion_in_progress".equals(app.path("status").asString())) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }

    static Optional<JsonNode> findFirst(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asString(null))) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private static String upsertWebhookIntegration(SentryRestApi api, String webhookUrl, PrintStream out)
            throws IOException, InterruptedException {
        JsonNode apps = api.get("/organizations/" + api.org() + "/sentry-apps/");
        Optional<JsonNode> existing = activeIntegration(apps);
        if (existing.isPresent()) {
            String slug = existing.get().path("slug").asString();
            if (!webhookUrl.equals(existing.get().path("webhookUrl").asString())) {
                api.put("/sentry-apps/" + slug + "/", PaymentsAlertPolicy.webhookIntegrationBody(api.org(), webhookUrl));
                out.println("Integracja zaktualizowana: " + slug + " -> " + webhookUrl);
            } else {
                out.println("Integracja bez zmian:  " + slug + " -> " + webhookUrl);
            }
            return slug;
        }
        String slug = api.post("/sentry-apps/", PaymentsAlertPolicy.webhookIntegrationBody(api.org(), webhookUrl))
                .path("slug").asString();
        out.println("Integracja utworzona:  " + slug + " -> " + webhookUrl);
        // Sekret do weryfikacji podpisu jest w UI (Settings > Custom Integrations), celowo nie
        // na konsoli: wydruk trafia do historii terminala i logów CI.
        out.println("    podpis webhooka: Client Secret integracji w SENTRY_WEBHOOK_SECRET odbiornika");
        return slug;
    }
}
