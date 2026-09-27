package pl.training.sentry.module07;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sprawdza odpowiedź modelu z kontraktem RCA i z rejestrem dowodów, zanim ktokolwiek jej użyje.
 *
 * <p>Fakt musi wskazywać co najmniej jeden
 * dowód z pakietu. Hipoteza musi mieć dowody wspierające, nie może używać tego samego dowodu
 * po obu stronach i musi mieć krok weryfikacji, który może ją obalić. ID spoza pakietu to
 * błąd, bo oznacza, że model powołuje się na coś, czego host mu nie dał. Tekst poza
 * sekcjami kontraktu też jest błędem: tak zwykle wygląda odpowiedź modelu, który
 * zaczął wykonywać polecenie z telemetrii.</p>
 *
 * <p>PUŁAPKA: pozytywny wynik oznacza poprawną strukturę, a nie prawdziwą diagnozę. Walidator
 * nie wie, czy twierdzenie wynika z dowodu, na który wskazuje, ani czy event jest
 * reprezentatywny. To robi człowiek w przeglądzie. Nie sprawdza też, czy odpowiedź ma wszystkie
 * sekcje kontraktu: brak sekcji bez punktów nie jest błędem. Walidator nie ocenia też rekomendacji:
 * zakaz mutacji egzekwuje {@link ActionPolicy}, a nie analiza tekstu.</p>
 */
public final class ReportValidator {

    private static final List<String> SECTIONS = List.of("FAKTY", "HIPOTEZY", "BRAKI DANYCH", "WERYFIKACJA", "REKOMENDACJE");
    private static final Pattern CITATION = Pattern.compile("\\[([^\\]]*)]\\s*$");
    private static final Pattern EVIDENCE_ID = Pattern.compile("EV-\\d+");
    private static final Pattern HYPOTHESIS = Pattern.compile(
            "^(H\\d+):\\s*(.+?)\\s*\\|\\s*confidence:\\s*(.+?)\\s*\\|\\s*za:\\s*(.*?)\\s*\\|\\s*przeciw:\\s*(.*)$");
    private static final Pattern VERIFICATION = Pattern.compile("^(H\\d+):\\s*\\S.*$");

    /**
     * @param errors     powody odrzucenia; pusta lista oznacza raport do przeglądu przez człowieka
     * @param facts      liczba faktów z poprawnymi odwołaniami
     * @param hypotheses liczba poprawnych hipotez
     * @param stopReason powód z odpowiedzi „STOP: ...”, gdy model zatrzymał się zgodnie z kontraktem
     */
    public record Result(List<String> errors, int facts, int hypotheses, String stopReason) {

        public boolean accepted() {
            return errors.isEmpty() && stopReason == null;
        }
    }

    public Result validate(String response, Set<String> admittedIds) {
        String trimmed = response.strip();
        if (trimmed.startsWith("STOP:")) {
            // Zatrzymanie to poprawna odpowiedź: decyzję przejmuje człowiek.
            return new Result(List.of(), 0, 0, trimmed.substring("STOP:".length()).strip());
        }

        List<String> errors = new ArrayList<>();
        Set<String> hypothesisLabels = new HashSet<>();
        Set<String> verifiedLabels = new HashSet<>();
        int facts = 0;
        int hypotheses = 0;
        String section = null;

        for (String rawLine : trimmed.lines().toList()) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (SECTIONS.contains(line)) {
                section = line;
                continue;
            }
            if (section == null || !line.startsWith("- ")) {
                errors.add("tekst poza formatem kontraktu: " + abbreviate(line));
                continue;
            }
            String item = line.substring(2).strip();
            switch (section) {
                case "FAKTY" -> {
                    Matcher citation = CITATION.matcher(item);
                    Set<String> ids = citation.find() ? ids(citation.group(1)) : Set.of();
                    if (ids.isEmpty()) {
                        errors.add("fakt bez dowodu: " + abbreviate(item));
                    } else if (allAdmitted(ids, admittedIds, item, errors)) {
                        facts++;
                    }
                }
                case "HIPOTEZY" -> {
                    Matcher hypothesis = HYPOTHESIS.matcher(item);
                    if (!hypothesis.matches()) {
                        errors.add("hipoteza niezgodna z formatem: " + abbreviate(item));
                        continue;
                    }
                    Set<String> supporting = ids(hypothesis.group(4));
                    Set<String> opposing = ids(hypothesis.group(5));
                    hypothesisLabels.add(hypothesis.group(1));
                    if (supporting.isEmpty()) {
                        errors.add(hypothesis.group(1) + " nie ma dowodów wspierających");
                    }
                    Set<String> both = new HashSet<>(supporting);
                    both.retainAll(opposing);
                    if (!both.isEmpty()) {
                        errors.add(hypothesis.group(1) + " używa tych samych dowodów za i przeciw: " + both);
                    }
                    Set<String> all = new HashSet<>(supporting);
                    all.addAll(opposing);
                    if (allAdmitted(all, admittedIds, hypothesis.group(1), errors) && !supporting.isEmpty() && both.isEmpty()) {
                        hypotheses++;
                    }
                }
                case "WERYFIKACJA" -> {
                    Matcher verification = VERIFICATION.matcher(item);
                    if (verification.matches()) {
                        verifiedLabels.add(verification.group(1));
                    }
                }
                default -> {
                    // BRAKI DANYCH i REKOMENDACJE: dowolny tekst w punktach.
                }
            }
        }

        for (String label : hypothesisLabels.stream().sorted().toList()) {
            if (!verifiedLabels.contains(label)) {
                errors.add(label + " nie ma kroku weryfikacji, który mógłby ją obalić");
            }
        }
        if (facts == 0 && hypotheses == 0 && errors.isEmpty()) {
            errors.add("pusty raport: brak faktów i hipotez");
        }
        return new Result(List.copyOf(errors), facts, hypotheses, null);
    }

    /** Zwraca {@code true}, gdy wszystkie ID są w pakiecie; w przeciwnym razie dopisuje błąd. */
    private static boolean allAdmitted(Set<String> ids, Set<String> admittedIds, String where, List<String> errors) {
        Set<String> missing = ids.stream().filter(id -> !admittedIds.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            errors.add("dowód spoza pakietu " + missing + " w: " + abbreviate(where));
            return false;
        }
        return true;
    }

    private static Set<String> ids(String text) {
        Matcher matcher = EVIDENCE_ID.matcher(text);
        Set<String> ids = new HashSet<>();
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private static String abbreviate(String text) {
        return text.length() <= 90 ? text : text.substring(0, 90) + "...";
    }
}
