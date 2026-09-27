package pl.training.sentry.module07;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Zamienia odpowiedź REST API Sentry (issue i event) na pakiet dowodów dla agenta.
 *
 * <p>Minimalizacja poprzedza redakcję ({@link Redactor}). Event z API ma kilkanaście kilobajtów:
 * wszystkie ramki stosu, dane SDK, tagi i contexts infrastruktury, metadane przetwarzania,
 * a zależnie od eventu także user i request. Kolektor wybiera tylko pola potrzebne do pytania
 * diagnostycznego i nadaje każdemu dowodowi ID. Czego nie wybrał, wypisuje
 * w {@link EvidencePackage#omitted()}, żeby decyzja o pominięciu była jawna.</p>
 *
 * <p>PUŁAPKA: przekazanie modelowi całego eventu „bo może się przydać”. Każde dodatkowe pole
 * to kolejny nośnik prompt injection i kolejna porcja danych osobowych do redakcji, a regex
 * na całym evencie nie jest równoważny pominięciu niepotrzebnego pola.</p>
 */
public final class EvidenceCollector {

    private static final int MAX_FRAMES = 5;
    private static final int MAX_BREADCRUMBS = 5;
    /** Contexts dodawane automatycznie przez SDK i Sentry. Nic nie mówią o błędzie domenowym. */
    private static final Set<String> SDK_CONTEXTS = Set.of("runtime", "os", "trace", "device", "app", "culture");

    private static final Pattern META_ENTRY = Pattern.compile("^entries/(\\d+)/data/(.*)$");

    private final Set<String> tagAllowlist;

    /**
     * @param tagAllowlist tagi potrzebne do pytania. Pozostałe (np. {@code server_name},
     *                     {@code runtime}) kolektor pomija i odnotowuje jako pominięte.
     */
    public EvidenceCollector(Set<String> tagAllowlist) {
        this.tagAllowlist = Set.copyOf(tagAllowlist);
    }

    /**
     * Pakiet dowodów: dopuszczone pozycje, pominięte pola i znane braki danych.
     *
     * @param items   dowody z ID nadanymi przez hosta, w stałej kolejności
     * @param omitted pola dostępne w API, których świadomie nie przekazujemy
     * @param gaps    ograniczenia materiału, które model musi znać, żeby nie wyciągał zbyt
     *                mocnych wniosków
     */
    public record EvidencePackage(List<Evidence> items, List<String> omitted, List<String> gaps) {

        public Set<String> ids() {
            return items.stream().map(Evidence::id).collect(Collectors.toUnmodifiableSet());
        }
    }

    public EvidencePackage collect(Object issue, Object event) {
        List<Evidence> items = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        String eventId = Json.text(event, "eventID");
        String eventRef = "event:" + eventId;

        items.add(evidence(items, Evidence.Kind.ISSUE, "issue:" + Json.text(issue, "id"),
                Json.text(issue, "shortId") + " | " + Json.text(issue, "title")
                        + " | culprit=" + Json.text(issue, "culprit")
                        + " | status=" + Json.text(issue, "status") + "/" + Json.text(issue, "substatus")
                        + " | priority=" + Json.text(issue, "priority")
                        + " | events=" + Json.text(issue, "count") + " | users=" + Json.text(issue, "userCount")
                        + " | first_seen=" + Json.text(issue, "firstSeen") + " | last_seen=" + Json.text(issue, "lastSeen")));

        // Jeden event to przykład, nie całe issue: wniosek z niego nie dowodzi, że wybrany event
        // reprezentuje wszystkie wystąpienia.
        long count = Json.number(issue, "count");
        if (count > 1) {
            gaps.add("Pakiet zawiera 1 event z " + count + ". Pozostałe mogą mieć inne dane wejściowe albo inną przyczynę.");
        }

        Object exceptionEntry = entry(event, "exception");
        List<?> exceptions = Json.list(exceptionEntry, "data", "values");
        if (!exceptions.isEmpty()) {
            // API zapisuje łańcuch od najgłębszej przyczyny; ostatni jest wyjątek zewnętrzny.
            String chain = exceptions.reversed().stream()
                    .map(value -> Json.text(value, "type") + ": " + Json.text(value, "value"))
                    .collect(Collectors.joining(" | caused by "));
            items.add(evidence(items, Evidence.Kind.EXCEPTION, eventRef + "/exception", chain));
            items.add(evidence(items, Evidence.Kind.STACK_TRACE, eventRef + "/stacktrace",
                    frames(exceptions.getLast(), gaps)));
        } else {
            gaps.add("Event nie ma wyjątku ani stack trace.");
        }

        Map<String, String> tags = tags(event);
        items.add(evidence(items, Evidence.Kind.RELEASE, eventRef + "/release",
                "release=" + tags.getOrDefault("release", "[BRAK]")
                        + " | environment=" + tags.getOrDefault("environment", "[BRAK]")
                        + " | event_id=" + eventId
                        + " | trace_id=" + Json.text(event, "contexts", "trace", "trace_id")
                        + " | time=" + Json.text(event, "dateCreated")));

        Map<String, String> allowedTags = new TreeMap<>();
        tags.forEach((key, value) -> {
            if (tagAllowlist.contains(key)) {
                allowedTags.put(key, value);
            } else if (!key.equals("release") && !key.equals("environment")) {
                omitted.add("tag " + key);
            }
        });
        if (!allowedTags.isEmpty()) {
            items.add(evidence(items, Evidence.Kind.TAGS, eventRef + "/tags", allowedTags.toString()));
        }

        if (Json.at(event, "contexts") instanceof Map<?, ?> contexts) {
            for (Map.Entry<?, ?> context : new TreeMap<>(contexts).entrySet()) {
                String name = String.valueOf(context.getKey());
                if (SDK_CONTEXTS.contains(name)) {
                    omitted.add("context " + name);
                } else {
                    items.add(evidence(items, Evidence.Kind.CONTEXT, eventRef + "/contexts/" + name,
                            name + "=" + withoutType(context.getValue())));
                }
            }
        }

        List<?> breadcrumbs = Json.list(entry(event, "breadcrumbs"), "data", "values");
        int from = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS);
        if (from > 0) {
            omitted.add(from + " starszych breadcrumbs");
        }
        for (int index = from; index < breadcrumbs.size(); index++) {
            Object crumb = breadcrumbs.get(index);
            String data = Json.at(crumb, "data") instanceof Map<?, ?> map && !map.isEmpty() ? " " + new TreeMap<>(map) : "";
            items.add(evidence(items, Evidence.Kind.BREADCRUMB, eventRef + "/breadcrumbs/" + index,
                    "[" + Json.text(crumb, "category") + "] " + Json.text(crumb, "message") + data));
        }

        // Scrubbing serwera Sentry podczas ingestu zastępuje wartości tekstem „[Filtered]”
        // i zapisuje regułę w _meta. Agent musi wiedzieć, że wartość usunięto, a nie że była pusta.
        List<String> scrubbed = new ArrayList<>();
        scrubbedPaths(event, Json.at(event, "_meta"), "", scrubbed);
        for (String path : scrubbed) {
            gaps.add("Sentry zamaskował podczas ingestu: " + path + ".");
        }

        // User i request pomijamy w całości. Do pytania „dlaczego kod rzuca wyjątek” nie są
        // potrzebne, a zawierają dane osobowe (e-mail, IP) i nagłówki (cookie, Authorization).
        if (Json.at(event, "user") != null) {
            omitted.add("user");
        }
        if (entry(event, "request") != null) {
            omitted.add("request");
        }
        return new EvidencePackage(List.copyOf(items), List.copyOf(omitted), List.copyOf(gaps));
    }

    private static Evidence evidence(List<Evidence> existing, Evidence.Kind kind, String source, String content) {
        // Kolejne numery w stałej kolejności pól: te same dane zawsze dają te same ID.
        return new Evidence("EV-" + (existing.size() + 1), kind, source, content);
    }

    private static String frames(Object exception, List<String> gaps) {
        List<?> frames = Json.list(exception, "stacktrace", "frames");
        List<?> inApp = frames.stream().filter(frame -> Boolean.TRUE.equals(Json.at(frame, "inApp"))).toList();
        List<?> selected = inApp;
        if (inApp.isEmpty()) {
            // Bez inAppIncludes w konfiguracji SDK żadna ramka nie jest oznaczona jako kod
            // aplikacji. Agent dostaje wtedy ostatnie ramki bez gwarancji, że to kod zespołu.
            gaps.add("Brak ramek in-app: SDK nie ma skonfigurowanych pakietów aplikacji, ramki wybrano po pozycji.");
            selected = frames;
        }
        // Ramki są zapisane od najstarszego wywołania; miejsce błędu jest na końcu listy.
        List<?> last = selected.subList(Math.max(0, selected.size() - MAX_FRAMES), selected.size());
        return last.reversed().stream()
                .map(frame -> Json.text(frame, "module") + "." + Json.text(frame, "function")
                        + "(" + Json.text(frame, "filename") + ":" + Json.text(frame, "lineNo") + ")")
                .collect(Collectors.joining(" <- "));
    }

    /** Ścieżki w {@code _meta} z regułą usunięcia ({@code rem}), np. {@code breadcrumbs/values/1/message}. */
    private static void scrubbedPaths(Object event, Object meta, String path, List<String> found) {
        if (!(meta instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> child : map.entrySet()) {
            String key = String.valueOf(child.getKey());
            if (key.isEmpty()) {
                List<?> rules = Json.list(child.getValue(), "rem");
                if (!rules.isEmpty() && rules.getFirst() instanceof List<?> rule && !rule.isEmpty()) {
                    found.add(readable(event, path) + " (reguła " + rule.getFirst() + ")");
                }
            } else {
                scrubbedPaths(event, child.getValue(), path.isEmpty() ? key : path + "/" + key, found);
            }
        }
    }

    /** {@code entries/1/data/values/1/message} zamienia na {@code breadcrumbs/values/1/message}. */
    private static String readable(Object event, String path) {
        Matcher entry = META_ENTRY.matcher(path);
        if (entry.matches()) {
            int index = Integer.parseInt(entry.group(1));
            List<?> entries = Json.list(event, "entries");
            if (index < entries.size()) {
                return Json.text(entries.get(index), "type") + "/" + entry.group(2);
            }
        }
        return path;
    }

    private static Object entry(Object event, String type) {
        return Json.list(event, "entries").stream()
                .filter(entry -> type.equals(Json.text(entry, "type")))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> tags(Object event) {
        Map<String, String> tags = new TreeMap<>();
        for (Object tag : Json.list(event, "tags")) {
            tags.put(Json.text(tag, "key"), Json.text(tag, "value"));
        }
        return tags;
    }

    private static Map<Object, Object> withoutType(Object context) {
        Map<Object, Object> copy = new TreeMap<>(context instanceof Map<?, ?> map ? map : Map.of());
        copy.remove("type");
        return copy;
    }
}
