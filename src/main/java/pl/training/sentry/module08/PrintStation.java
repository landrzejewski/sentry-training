package pl.training.sentry.module08;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import pl.training.sentry.module08.LabelPrinter.OutOfLabelsException;
import pl.training.sentry.module08.LabelPrinter.PrintJob;

import java.util.List;

/**
 * Pokazuje ręczne prowadzenie sesji Release Health: jedna zmiana operatora stanowiska pakowania
 * to jedna sesja, a miejsce {@code endSession} decyduje, czy crash obniży crash-free rate.
 *
 * <p>Czyste Java SDK nie tworzy sesji samo (domyślne {@code enableAutoSessionTracking=true}
 * tego nie zmienia), więc aplikacja wywołuje {@code Sentry.startSession()}
 * i {@code Sentry.endSession()} w punktach swojego cyklu życia. Bez sesji Sentry nie policzy crash-free sessions ani adopcji
 * release, a reguły oparte na odsetku sesji nie mają mianownika.</p>
 *
 * <p>Status sesji wynika z eventów wysłanych w jej trakcie: błąd obsłużony zwiększa licznik
 * {@code errors}, a nieobsłużony ({@code handled=false}) zmienia status na {@code crashed}.
 * Zakończenie przez {@code endSession} zmienia status {@code ok} na {@code exited}.</p>
 */
public final class PrintStation {

    /** Gdzie kod kończy sesję. */
    public enum SessionHandling {
        /** {@code endSession} tylko po normalnym przebiegu zmiany. */
        END_ON_NORMAL_EXIT,
        /** PUŁAPKA: {@code endSession} w {@code finally}, także gdy wyjątek kończy wątek. */
        END_IN_FINALLY
    }

    private final LabelPrinter printer;

    public PrintStation(LabelPrinter printer) {
        this.printer = printer;
    }

    /**
     * Ustawienia SDK, które stanowisko dodaje do konfiguracji bazowej.
     *
     * @param installationId identyfikator instalacji stanowiska, np. {@code station-waw-07}
     */
    public static void configure(SentryOptions options, String installationId) {
        // distinctId trafia do pola did każdej sesji. Po nim Sentry odróżnia, ile różnych
        // instalacji (albo użytkowników) dotknął crash. Identyfikator instalacji, a nie login
        // czy e-mail operatora: to też dane osobowe, a zmiana operatora nie zmienia stanowiska.
        options.setDistinctId(installationId);
    }

    /** Uruchamia zmianę w wątku {@code print-queue} i czeka na jej koniec. */
    public void runShiftInBackground(List<PrintJob> jobs, SessionHandling handling) throws InterruptedException {
        Thread.Builder worker = Thread.ofPlatform().name("print-queue");
        // PUŁAPKA: JVM przekazuje nieobsłużony wyjątek do handlera wątku, a gdy go nie ma,
        // do ThreadGroup. Dopiero standardowa ThreadGroup woła handler domyślny, czyli Sentry.
        // mvn exec:java uruchamia main we własnej ThreadGroup, która przejmuje wyjątek i handlera
        // domyślnego nie woła, więc Sentry nie dostałoby crasha, a sesja zostałaby otwarta.
        // Jawne wskazanie handlera domyślnego daje ten sam wynik w IDE i pod Maven. Warunek
        // zostawia zachowanie JVM, gdy integracja jest wyłączona (setEnableUncaughtExceptionHandler(false)).
        Thread.UncaughtExceptionHandler sentryHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (sentryHandler != null) {
            worker.uncaughtExceptionHandler(sentryHandler);
        }
        worker.start(() -> runShift(jobs, handling)).join();
    }

    /**
     * Jedna zmiana. Sesja powstaje i kończy się w wątku kolejki, więc crash tego wątku zmienia
     * status tej samej sesji: SDK trzyma ją w isolation scope wątku, który ją rozpoczął.
     */
    void runShift(List<PrintJob> jobs, SessionHandling handling) {
        // PUŁAPKA: bez release SDK nie rozpocznie sesji (tylko ostrzeżenie w logu SDK).
        Sentry.startSession();
        if (handling == SessionHandling.END_IN_FINALLY) {
            try {
                printAll(jobs);
            } finally {
                // finally wykonuje się, zanim wyjątek dotrze do handlera wątku. Sesja kończy się
                // jako exited, a event crasha przychodzi już bez sesji, którą mógłby oznaczyć.
                // Release Health pokazuje 100% crash-free, choć wątek padł.
                Sentry.endSession();
            }
        } else {
            printAll(jobs);
            // Tylko normalny przebieg. Przy crashu sesję zamyka SDK: event z handled=false
            // zmienia jej status na crashed i wysyła ją razem z eventem.
            Sentry.endSession();
        }
    }

    private void printAll(List<PrintJob> jobs) {
        for (PrintJob job : jobs) {
            try {
                printer.print(job);
            } catch (OutOfLabelsException exception) {
                // Błąd obsłużony: operator dokłada rolkę i zadanie drukuje się ponownie.
                // Licznik errors rośnie, więc Release Health liczy sesję jako errored, ale nie
                // crashed: crash-free rate jej nie obniża.
                Sentry.captureException(exception);
                printer.reload(500);
                printer.print(job);
            }
        }
    }
}
