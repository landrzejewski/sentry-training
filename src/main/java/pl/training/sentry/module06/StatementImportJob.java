package pl.training.sentry.module06;

import io.sentry.CheckIn;
import io.sentry.CheckInStatus;
import io.sentry.ISentryLifecycleToken;
import io.sentry.MonitorConfig;
import io.sentry.MonitorSchedule;
import io.sentry.MonitorScheduleUnit;
import io.sentry.Sentry;
import io.sentry.util.CheckInUtils;
import io.sentry.util.TracingUtils;
import pl.training.sentry.module06.BankGateway.BankTimeoutException;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pokazuje awarie joba cyklicznego (import wyciągów co minutę), których bez Cron Monitora nie
 * widać: połknięty wyjątek, zadanie wstrzymane przez scheduler i zawieszone uruchomienie.
 *
 * <p>Scenariusz 2 pokazuje, co z wyjątkiem robi kod wokół {@code withCheckIn}
 * ({@link FailureHandling}), a scenariusz 3 zadanie, które wisi, oraz heartbeat, który tego
 * nie wykryje. Timeout wymaga modelu start plus koniec: Sentry wie o uruchomieniu dopiero
 * z {@code IN_PROGRESS}.</p>
 *
 * <p>Slug jest parametrem tylko na potrzeby szkolenia: każdy wariant dostaje własny monitor,
 * żeby w Sentry UI warianty nie zlały się w jedną oś czasu i nie przekroczyły limitu
 * 6 check-inów na minutę dla pary monitor i środowisko. W aplikacji slug jest stałą joba.</p>
 */
public final class StatementImportJob {

    public static final String MONITOR_SLUG = "bank-statement-import";

    /** Co kod wokół {@code withCheckIn} robi z wyjątkiem importu. */
    public enum FailureHandling {
        /** PUŁAPKA: wyjątek złapany w callbacku, zalogowany i zamieniony na pusty wynik. */
        SWALLOW,
        /** PUŁAPKA: wyjątek opuszcza zadanie przekazane do {@code ScheduledExecutorService}. */
        PROPAGATE,
        /** Event w trakcie uruchomienia, check-in ERROR i zadanie żyje dalej. */
        REPORT_AND_CONTINUE
    }

    private final BankGateway bank;
    private final String monitorSlug;
    private final AtomicInteger runs = new AtomicInteger();

    public StatementImportJob(BankGateway bank, String monitorSlug) {
        this.bank = bank;
        this.monitorSlug = monitorSlug;
    }

    public static MonitorConfig monitorConfig() {
        MonitorConfig config = new MonitorConfig(MonitorSchedule.interval(1, MonitorScheduleUnit.MINUTE));
        // Harmonogram interwałowy nie zależy od strefy czasowej. Margines 1 minuty (najmniejsza
        // wartość, jaką przyjmuje Sentry): brak check-inu minutę po oczekiwanym terminie to missed.
        config.setCheckinMargin(1L);
        // Import trwa sekundy. IN_PROGRESS bez zakończenia po minucie oznacza zawieszenie.
        config.setMaxRuntime(1L);
        // Job co minutę: jedna porażka to zwykle chwilowy problem banku, więc Issue dopiero
        // po dwóch kolejnych. Recovery też po dwóch, żeby pojedynczy sukces w trakcie awarii
        // nie zamykał problemu.
        config.setFailureIssueThreshold(2L);
        config.setRecoveryThreshold(2L);
        return config;
    }

    /** Liczba uruchomień, które scheduler faktycznie rozpoczął. */
    public int runs() {
        return runs.get();
    }

    /** Jedno uruchomienie objęte check-inami {@code IN_PROGRESS} i {@code OK}/{@code ERROR}. */
    public int importOnce() throws Exception {
        return CheckInUtils.withCheckIn(monitorSlug, monitorConfig(), this::importStatement);
    }

    /** Zadanie dla {@code ScheduledExecutorService.scheduleAtFixedRate}. */
    public Runnable scheduledTask(FailureHandling handling) {
        return switch (handling) {
            case SWALLOW -> () -> withCheckIn(() -> {
                try {
                    return importStatement();
                } catch (RuntimeException failure) {
                    // PUŁAPKA: callback wraca normalnie, więc wrapper wyśle OK. Monitor jest
                    // zielony, a wyciągi nie są importowane. Log z tym wyjątkiem nie trafia do
                    // Sentry i nikt go nie czyta.
                    System.err.println("  (log aplikacji) import nieudany: " + failure.getMessage());
                    return 0;
                }
            });
            // PUŁAPKA: wrapper wyśle ERROR i rzuci wyjątek dalej, a ten opuści zadanie.
            // ScheduledExecutorService wstrzymuje wtedy wszystkie kolejne uruchomienia
            // (kontrakt scheduleAtFixedRate), a wyjątek zostaje w ScheduledFuture, którego nikt
            // nie odczytuje: nie ma eventu, nie ma logu, job po cichu przestaje istnieć.
            // Sentry zobaczy jeden ERROR, a potem wyłącznie brakujące check-iny (missed).
            case PROPAGATE -> () -> withCheckIn(this::importStatement);
            case REPORT_AND_CONTINUE -> () -> {
                try {
                    withCheckIn(() -> {
                        // Capture wewnątrz callbacku: wrapper otwiera dla uruchomienia nowy trace,
                        // więc event ma ten sam trace co check-in i da się go powiązać
                        // z konkretnym uruchomieniem. Check-in ERROR mówi tylko, że uruchomienie
                        // się nie udało; przyczynę niesie event.
                        try {
                            return importStatement();
                        } catch (BankTimeoutException bankDown) {
                            PaymentTelemetry.reportBankTimeout(bankDown, "statement-import",
                                    Map.of("monitor_slug", monitorSlug));
                            throw bankDown;
                        } catch (RuntimeException defect) {
                            Sentry.captureException(defect);
                            throw defect;
                        }
                    });
                } catch (RuntimeException alreadyReported) {
                    // Granica schedulera: event i check-in ERROR już wysłane. Wyjątek nie może
                    // opuścić zadania, bo executor wstrzymałby kolejne uruchomienia.
                }
            };
        };
    }

    /**
     * Wariant heartbeat: jeden check-in po zakończeniu pracy, bez {@code IN_PROGRESS}.
     *
     * <p>Wykryje brak uruchomienia (missed), ale zawieszone zadanie nie wyśle nic: Sentry nie
     * odróżni go od zadania, które nie wystartowało, i nie oznaczy przekroczenia
     * {@code max_runtime}, bo nie wie, że uruchomienie się zaczęło.</p>
     */
    public int importWithHeartbeat() {
        // Własne scopes i nowy trace dla uruchomienia, tak jak robi to withCheckIn. Check-in
        // niesie trace ze scope, a Sentry łączy z uruchomieniem błędy o tym samym trace.
        // PUŁAPKA: bez tego check-in dziedziczy trace wątku i zostaje powiązany z błędami
        // wszystkich innych operacji na tym wątku (sprawdzone na lokalnym Sentry).
        try (ISentryLifecycleToken runScope = Sentry.forkedScopes("statement-import").makeCurrent()) {
            TracingUtils.startNewTrace(Sentry.getCurrentScopes());
            CheckInStatus status = CheckInStatus.ERROR;
            try {
                int imported = importStatement();
                status = CheckInStatus.OK;
                return imported;
            } finally {
                CheckIn heartbeat = new CheckIn(monitorSlug, status);
                heartbeat.setMonitorConfig(monitorConfig());
                Sentry.captureCheckIn(heartbeat);
            }
        }
    }

    private int importStatement() {
        runs.incrementAndGet();
        return bank.statement(LocalDate.now()).size();
    }

    /** {@code withCheckIn} deklaruje {@code Exception}; import rzuca tylko wyjątki niekontrolowane. */
    private <T> T withCheckIn(Callable<T> work) {
        try {
            return CheckInUtils.withCheckIn(monitorSlug, monitorConfig(), work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }
}
