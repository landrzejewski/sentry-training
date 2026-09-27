package pl.training.sentry.support;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Wybór scenariuszy demo z argumentów programu.
 *
 * <p>Bez numerów demo uruchamia wszystkie scenariusze, z numerami tylko wskazane:
 * {@code -Dexec.args=1} albo {@code -Dexec.args="2 4"}. Jeden scenariusz na uruchomienie
 * pozwala sprawdzić w Sentry UI, które dane pochodzą z którego przykładu, zwłaszcza po
 * {@code ./docker/sentry/sentry.sh clean}. Argumenty, które nie są liczbami (np. {@code --plan}),
 * zostają dla samego demo.</p>
 */
public final class DemoScenarios {

    private final Set<Integer> selected;

    private DemoScenarios(Set<Integer> selected) {
        this.selected = selected;
    }

    /**
     * @param first numer pierwszego scenariusza demo
     * @param last  numer ostatniego scenariusza demo
     * @throws IllegalArgumentException gdy argumenty wskazują scenariusz spoza zakresu
     */
    public static DemoScenarios from(String[] args, int first, int last) {
        Set<Integer> selected = new TreeSet<>();
        Arrays.stream(args)
                .flatMap(arg -> Arrays.stream(arg.split("[,\\s]+")))
                .filter(part -> part.matches("\\d+"))
                .map(Integer::parseInt)
                .forEach(selected::add);
        List<Integer> unknown = selected.stream().filter(number -> number < first || number > last).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Nie ma scenariusza " + unknown + ": to demo ma scenariusze "
                    + first + "-" + last);
        }
        return new DemoScenarios(selected);
    }

    /** Czy uruchomić scenariusz: bez wyboru wszystkie, z wyborem tylko wskazane. */
    public boolean includes(int number) {
        return selected.isEmpty() || selected.contains(number);
    }

    /** Czy uruchomić którykolwiek scenariusz z zakresu, np. przed wspólną inicjalizacją SDK. */
    public boolean includesAny(int first, int last) {
        return IntStream.rangeClosed(first, last).anyMatch(this::includes);
    }
}
