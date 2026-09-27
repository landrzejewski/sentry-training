package pl.training.sentry.module07;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Poranny briefing dyżurnego: deterministyczny wybór i ranking issues, zanim model cokolwiek streści.
 *
 * <p>Model nie powinien jednocześnie wybierać
 * danych, definiować priorytetu i pisać narracji. Ta klasa robi dwie pierwsze rzeczy
 * w kodzie: filtruje listę issues z API do projektu sesji i nierozwiązanych, deduplikuje po
 * ID (zostaje migawka z najnowszym {@code lastSeen}) i sortuje stabilnie według polityki
 * modułu: regresja, priorytet, liczba użytkowników, ostatnie wystąpienie, liczba eventów.
 * Model dostaje gotową listę i ma ją tylko streścić.</p>
 *
 * <p>Briefing nie wymyśla właściciela ani przyczyny. Issue bez przypisania dostaje
 * ostrzeżenie, a nie „prawdopodobnego właściciela” z tytułu.</p>
 *
 * <p>PUŁAPKA: ranking tylko po liczbie eventów. Pętla retry potrafi wygenerować tysiące eventów
 * jednego nieszkodliwego błędu, a regresja w płatnościach ma ich kilka.</p>
 */
public final class DailyBriefing {

    /**
     * Jedna pozycja briefingu, już po minimalizacji: tylko pola potrzebne do rankingu i streszczenia.
     *
     * @param owner przypisanie z Sentry, np. {@code team:payments}, albo {@code null}, gdy go nie ma
     */
    public record Entry(String issueId, String shortId, String title, boolean regressed, String priority,
                        long users, long events, String lastSeen, String owner) {
    }

    /**
     * @param omittedByLimit pozycje, które zmieściły się w zakresie, ale nie w limicie; briefing
     *                       mówi o nich wprost, żeby „top 5” nie wyglądało jak „wszystko”
     */
    public record Briefing(AgentSession scope, Instant generatedAt, int limit, List<Entry> entries,
                           int omittedByLimit, List<String> warnings) {
    }

    private static final Comparator<Entry> RANKING = Comparator
            .comparing(Entry::regressed).reversed()
            .thenComparing(Comparator.comparingInt((Entry entry) -> priorityRank(entry.priority())).reversed())
            .thenComparing(Comparator.comparingLong(Entry::users).reversed())
            .thenComparing(Comparator.comparing(Entry::lastSeen).reversed())
            .thenComparing(Comparator.comparingLong(Entry::events).reversed())
            // Ostatni klucz tylko dla stabilności: ten sam zbiór danych zawsze daje tę samą kolejność.
            .thenComparing(Entry::issueId);

    public Briefing build(AgentSession session, List<?> issues, int limit, Instant generatedAt) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Object issue : issues) {
            // Zakres sprawdza host, nawet jeśli zapytanie do API już filtrowało po projekcie:
            // lista mogła przyjść z innego narzędzia albo z pliku.
            if (!session.project().equals(Json.text(issue, "project", "slug"))
                    || !"unresolved".equals(Json.text(issue, "status"))) {
                continue;
            }
            Entry entry = new Entry(
                    Json.text(issue, "id"),
                    Json.text(issue, "shortId"),
                    Json.text(issue, "title"),
                    "regressed".equals(Json.text(issue, "substatus")),
                    String.valueOf(Json.text(issue, "priority")),
                    Json.number(issue, "userCount"),
                    Json.number(issue, "count"),
                    Json.text(issue, "lastSeen"),
                    Json.at(issue, "assignedTo") == null ? null
                            : Json.text(issue, "assignedTo", "type") + ":" + Json.text(issue, "assignedTo", "name"));
            byId.merge(entry.issueId(), entry,
                    (older, newer) -> newer.lastSeen().compareTo(older.lastSeen()) >= 0 ? newer : older);
        }

        List<Entry> ranked = byId.values().stream().sorted(RANKING).toList();
        List<Entry> selected = ranked.subList(0, Math.min(limit, ranked.size()));
        List<String> warnings = new ArrayList<>();
        long withoutOwner = selected.stream().filter(entry -> entry.owner() == null).count();
        if (withoutOwner > 0) {
            warnings.add("bez właściciela: " + withoutOwner + " z " + selected.size() + " pozycji; briefing go nie przypisuje");
        }
        long zeroUsers = selected.stream().filter(entry -> entry.users() == 0).count();
        if (zeroUsers > 0) {
            // „0 users” nie dowodzi braku wpływu. Może oznaczać brak identyfikatora użytkownika,
            // błąd przed uwierzytelnieniem albo przepływ bez użytkownika (job).
            warnings.add("users=0 w " + zeroUsers + " z " + selected.size() + " pozycji: to nie dowód braku wpływu, "
                    + "eventy mogą nie mieć identyfikatora użytkownika");
        }
        if (ranked.size() > selected.size()) {
            warnings.add("poza limitem " + limit + ": " + (ranked.size() - selected.size()) + " z " + ranked.size()
                    + " issues w zakresie");
        }
        return new Briefing(session, generatedAt, limit, selected, ranked.size() - selected.size(), List.copyOf(warnings));
    }

    /** Pozycje briefingu jako dowody z ID, żeby streszczenie modelu dało się sprawdzić tak jak raport RCA. */
    public static List<Evidence> asEvidence(Briefing briefing) {
        List<Evidence> items = new ArrayList<>();
        for (Entry entry : briefing.entries()) {
            items.add(new Evidence("EV-" + (items.size() + 1), Evidence.Kind.ISSUE, "issue:" + entry.issueId(),
                    entry.shortId() + " | " + entry.title() + " | regressed=" + entry.regressed()
                            + " | priority=" + entry.priority() + " | users=" + entry.users()
                            + " | events=" + entry.events() + " | last_seen=" + entry.lastSeen()
                            + " | owner=" + (entry.owner() == null ? "[BRAK]" : entry.owner())));
        }
        return items;
    }

    private static int priorityRank(String priority) {
        return switch (priority) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
