package pl.training.sentry.module06;

import io.sentry.CheckIn;
import io.sentry.CheckInStatus;
import io.sentry.MonitorConfig;
import io.sentry.MonitorSchedule;
import io.sentry.Sentry;
import io.sentry.protocol.SentryId;
import io.sentry.util.TracingUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pokazuje ręczne check-iny w listenerze frameworka wsadowego, który uruchamia wypłaty dla
 * sprzedawców, i błąd przy nakładających się uruchomieniach (scenariusz 4).
 *
 * <p>Framework wsadowy (np. Spring Batch) woła osobno
 * {@code beforeExecution} i {@code afterExecution}, więc cyklu życia nie da się objąć jednym
 * callbackiem {@code withCheckIn}. Check-in końcowy musi wtedy użyć {@code SentryId}
 * zwróconego przy {@code IN_PROGRESS}, a listener musi go gdzieś przechować między
 * wywołaniami. Scenariusz 4 pokazuje, co się dzieje, gdy to miejsce jest wspólne dla
 * nakładających się uruchomień.</p>
 *
 * <p>Uruchomienia nakładają się w produkcji częściej, niż się wydaje: dwie repliki aplikacji
 * z tym samym harmonogramem, ręczne uruchomienie w trakcie planowego, uruchomienie dłuższe niż
 * interwał. Cron Monitor tylko obserwuje: nie blokuje równoległych uruchomień, więc przed
 * podwójną wypłatą chroni blokada w aplikacji (np. rekord blokady w bazie), a nie monitoring.</p>
 */
public final class PayoutBatchCheckIns {

    /** Gdzie listener przechowuje identyfikator check-inu między wywołaniami. */
    public enum IdStorage {
        /** PUŁAPKA: jedno pole w singletonie listenera, nadpisywane przez każde uruchomienie. */
        SHARED_FIELD,
        /** Identyfikator przypisany do identyfikatora uruchomienia nadanego przez framework. */
        PER_EXECUTION
    }

    private final String monitorSlug;
    private final IdStorage storage;

    private volatile SentryId currentCheckIn;
    private final Map<Long, SentryId> checkInsByExecution = new ConcurrentHashMap<>();

    public PayoutBatchCheckIns(String monitorSlug, IdStorage storage) {
        this.monitorSlug = monitorSlug;
        this.storage = storage;
    }

    public static MonitorConfig monitorConfig() {
        // Co 15 minut w godzinach 8-19 w dni robocze, czasu polskiego.
        MonitorConfig config = new MonitorConfig(MonitorSchedule.crontab("*/15 8-19 * * 1-5"));
        config.setTimezone("Europe/Warsaw");
        config.setCheckinMargin(5L);
        config.setMaxRuntime(10L);
        config.setFailureIssueThreshold(1L);
        config.setRecoveryThreshold(1L);
        return config;
    }

    public void beforeExecution(long executionId) {
        // Nowy trace dla uruchomienia, jak w withCheckIn: check-iny i błędy z tego uruchomienia
        // dostaną wspólny trace, po którym Sentry łączy błędy z check-inem. Framework wykonuje
        // uruchomienie na własnym wątku, więc trace żyje w scope tego wątku do następnego startu.
        // PUŁAPKA: bez tej linii check-in niesie trace, który wątek miał wcześniej, i Sentry
        // przypisze uruchomieniu błędy zupełnie innych operacji (sprawdzone na lokalnym Sentry).
        TracingUtils.startNewTrace(Sentry.getCurrentScopes());
        CheckIn inProgress = new CheckIn(monitorSlug, CheckInStatus.IN_PROGRESS);
        inProgress.setMonitorConfig(monitorConfig());
        SentryId checkInId = Sentry.captureCheckIn(inProgress);
        switch (storage) {
            // PUŁAPKA: drugie uruchomienie nadpisuje identyfikator pierwszego.
            case SHARED_FIELD -> currentCheckIn = checkInId;
            case PER_EXECUTION -> checkInsByExecution.put(executionId, checkInId);
        }
    }

    public void afterExecution(long executionId, boolean failed) {
        SentryId checkInId = switch (storage) {
            case SHARED_FIELD -> currentCheckIn;
            case PER_EXECUTION -> checkInsByExecution.remove(executionId);
        };
        // Bez identyfikatora (np. restart aplikacji między wywołaniami) lepiej nie wysyłać nic
        // niż check-in z nowym identyfikatorem: otwarte IN_PROGRESS zamieni się w timeout,
        // co odpowiada prawdzie, a samotny OK sugerowałby, że wszystko jest w porządku.
        if (checkInId == null) {
            return;
        }
        Sentry.captureCheckIn(new CheckIn(checkInId, monitorSlug, failed ? CheckInStatus.ERROR : CheckInStatus.OK));
    }
}
