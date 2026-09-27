package pl.training.sentry.module10;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Pokazuje, jak liczyć wskaźniki względne z dashboardu: failure rate jako iloraz sum
 * i crash-free sessions.
 *
 * <p>Klasa nie zna Sentry: dostaje sumy, które widget odczytał z datasetu, i zwraca wynik albo
 * jawny brak wyniku.</p>
 *
 * <p>Brak wyniku to {@link OptionalDouble#empty()}, a nie 0 ani 1. Okno bez prób nie ma failure
 * rate równego 0 procent, a release bez sesji nie jest w 100 procentach crash-free.</p>
 */
public final class Ratios {

    /**
     * Jeden bucket widgetu, np. godzina.
     *
     * @param matching obserwacje spełniające warunek (licznik), np. suma {@code checkout.failed}
     * @param total    populacja kwalifikująca (mianownik), np. suma {@code checkout.attempted}
     */
    public record Bucket(String label, long matching, long total) {

        public OptionalDouble ratio() {
            return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) matching / total);
        }
    }

    private Ratios() {
    }

    /** Ratio całego zakresu: suma liczników przez sumę mianowników. Każda próba ma tę samą wagę. */
    public static OptionalDouble ratioOfSums(List<Bucket> buckets) {
        long matching = buckets.stream().mapToLong(Bucket::matching).sum();
        long total = buckets.stream().mapToLong(Bucket::total).sum();
        return new Bucket("cały zakres", matching, total).ratio();
    }

    /**
     * PUŁAPKA: średnia z ratio bucketów. Nocna godzina z sześcioma próbami waży tyle samo co
     * godzina szczytu z tysiącem, więc kilka przypadkowych błędów w małym buckecie zawyża wynik
     * całej doby. Buckety bez prób są pomijane, bo nie mają ratio (wstawienie tam 0 zaniżyłoby
     * wynik). Metoda istnieje tylko po to, żeby scenariusz 5 pokazał różnicę.
     */
    public static OptionalDouble averageOfBucketRatios(List<Bucket> buckets) {
        return buckets.stream()
                .map(Bucket::ratio)
                .filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble)
                .average();
    }

    /**
     * Crash-free sessions: udział sesji bez crashu w całej populacji sesji release.
     *
     * <p>Zero sesji daje brak wyniku. Serwerowy Java SDK nie tworzy sesji sam, więc release bez
     * instrumentacji ma zero sesji, a nie zdrowie.</p>
     */
    public static OptionalDouble crashFreeRate(long sessions, long crashedSessions) {
        if (crashedSessions < 0 || crashedSessions > sessions) {
            throw new IllegalArgumentException("Liczba crashy musi mieścić się w populacji sesji");
        }
        return sessions == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) (sessions - crashedSessions) / sessions);
    }
}
