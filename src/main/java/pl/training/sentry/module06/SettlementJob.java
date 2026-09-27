package pl.training.sentry.module06;

import io.sentry.MonitorConfig;
import io.sentry.MonitorSchedule;
import io.sentry.util.CheckInUtils;
import pl.training.sentry.module06.SettlementService.SettlementReport;

import java.time.LocalDate;

/**
 * Pokazuje {@code CheckInUtils.withCheckIn} z konfiguracją monitora na przykładzie nocnego
 * uzgodnienia płatności (scenariusz 1).
 *
 * <p>Wrapper wysyła {@code IN_PROGRESS} przed pracą i {@code OK} albo {@code ERROR} po niej.
 * Check-in końcowy ma ten sam identyfikator i czas trwania. Konfiguracja jedzie tylko w pierwszym
 * check-inie. Na jej podstawie Sentry tworzy monitor o danym slugu przy pierwszym uruchomieniu
 * i aktualizuje go przy każdym kolejnym (upsert).</p>
 *
 * <p>PRODUKCJA: konfiguracja monitora ma jednego właściciela. Tutaj jest nim kod: próg
 * zmieniony ręcznie w UI może zostać nadpisany przez kolejny check-in z {@code MonitorConfig}.
 * Zespół, który woli stroić monitor w UI, wysyła check-iny bez konfiguracji
 * ({@code withCheckIn(slug, callable)}), a monitor zakłada wcześniej w UI albo przez API.</p>
 */
public final class SettlementJob {

    public static final String MONITOR_SLUG = "settlement-reconciliation";

    private final SettlementService settlement;

    public SettlementJob(SettlementService settlement) {
        this.settlement = settlement;
    }

    /** Harmonogram i tolerancje monitora: dane, które opisują oczekiwane zachowanie joba. */
    public static MonitorConfig monitorConfig() {
        // Codziennie o 2:00. Bez strefy czasowej Sentry interpretuje crontab w UTC, więc
        // oczekiwałby check-inu o 2:00 UTC, czyli o 3:00 albo 4:00 czasu polskiego,
        // zależnie od pory roku.
        MonitorConfig config = new MonitorConfig(MonitorSchedule.crontab("0 2 * * *"));
        config.setTimezone("Europe/Warsaw");
        // Margines startu: scheduler może ruszyć z opóźnieniem (restart, kolejka). Dopiero brak
        // IN_PROGRESS po 2:10 oznacza missed.
        config.setCheckinMargin(10L);
        // Limit czasu wykonania rozpoczętego check-inem. Uzgodnienie trwa zwykle kilka minut;
        // IN_PROGRESS bez zakończenia po 30 minutach oznacza timeout (scenariusz 3).
        config.setMaxRuntime(30L);
        // Job nocny: przy progu 2 Issue powstałoby dopiero drugiej nocy, po dobie bez
        // rozliczeń. Pierwsza porażka krytycznego rozliczenia ma więc tworzyć Issue.
        config.setFailureIssueThreshold(1L);
        // Recovery po pierwszym udanym uruchomieniu: kolejne czekałoby dobę.
        config.setRecoveryThreshold(1L);
        return config;
    }

    public SettlementReport run(LocalDate day) throws Exception {
        // Environment check-inu pochodzi z opcji SDK, a Sentry prowadzi stan monitora osobno dla
        // każdego środowiska. PUŁAPKA: ten sam job na stagingu bez ustawionego environment
        // wysyła check-iny jako production (domyślna wartość SDK) i miesza się z produkcją.
        // Przeciążenie withCheckIn(slug, environment, config, callable) ustawia je jawnie.
        // PUŁAPKA: status wynika wyłącznie z tego, czy callback rzucił wyjątek. Raport
        // z nieuzgodnionymi płatnościami to normalny powrót, więc check-in będzie OK. Jeśli
        // taki wynik ma oznaczać porażkę zadania, kod musi rzucić wyjątek wewnątrz callbacku.
        return CheckInUtils.withCheckIn(MONITOR_SLUG, monitorConfig(), () -> settlement.reconcile(day));
    }
}
