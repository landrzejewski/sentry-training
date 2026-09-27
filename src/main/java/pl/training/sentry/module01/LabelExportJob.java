package pl.training.sentry.module01;

import io.sentry.Sentry;

import java.util.Map;

/**
 * Pokazuje błąd nieobsłużony w wątku tła i podwójne raportowanie na przykładzie nocnego eksportu
 * etykiet do przewoźnika.
 *
 * <p>Warianty {@link ErrorReporting} pokazują, kiedy deduplikacja SDK odrzuca drugi event tego
 * samego błędu, a kiedy powstaje duplikat w osobnym issue.</p>
 *
 * <p>W czystej Javie nieobsłużony wyjątek raportuje {@code UncaughtExceptionHandlerIntegration}.
 * SDK rejestruje ją przy {@code Sentry.init} jako domyślny handler JVM, więc widzi tylko wyjątki,
 * które opuściły wątek. Wyjątek z zadania przekazanego przez {@code ExecutorService.submit()}
 * zostaje w {@code Future}, a framework webowy zamienia wyjątek z requestu na odpowiedź HTTP 500.
 * W obu przypadkach ten handler nic nie dostanie. W aplikacji webowej nieobsłużone wyjątki
 * raportuje integracja frameworka, np. Spring Boot.</p>
 *
 * <p>PRODUKCJA: wątek z nieobsłużonym wyjątkiem kończy pracę. Jeśli był jedynym konsumentem
 * kolejki, przetwarzanie staje. Gdy przed Sentry nie było innego handlera domyślnego, JVM nie
 * wypisze też stack trace na stderr (SDK robi to tylko przy
 * {@code options.setPrintUncaughtStackTrace(true)}), więc event w Sentry bywa jedynym śladem.</p>
 */
public final class LabelExportJob {

    /** Jak kod joba reaguje na błąd generowania etykiety. */
    public enum ErrorReporting {
        /** Nie przechwytuje: wyjątek opuszcza wątek i raportuje go handler SDK. */
        NONE,
        /** PUŁAPKA „zaraportuj i rzuć dalej”: ręczny capture i ponowne rzucenie tego samego obiektu. */
        CAPTURE_AND_RETHROW,
        /** PUŁAPKA: ręczny capture i nowy wyjątek bez {@code cause}. */
        CAPTURE_AND_THROW_NEW,
        /** Jeden właściciel raportowania: opakowanie z {@code cause}, raportuje tylko handler SDK. */
        WRAP_WITH_CAUSE
    }

    private final ShippingLabelService labels = new ShippingLabelService();

    /** Uruchamia eksport jednego zamówienia w wątku {@code label-export} i czeka na jego koniec. */
    public void runInBackground(Order order, ErrorReporting reporting) throws InterruptedException {
        Thread.Builder worker = Thread.ofPlatform().name("label-export");
        // PUŁAPKA: JVM przekazuje nieobsłużony wyjątek do handlera wątku, a gdy go nie ma,
        // do ThreadGroup. Dopiero standardowa ThreadGroup woła handler domyślny, czyli Sentry.
        // mvn exec:java uruchamia main we własnej ThreadGroup, która przejmuje wyjątek
        // (loguje go, a na końcu przerywa build błędem) i handlera domyślnego nie woła, więc
        // Sentry nic nie dostaje (sprawdzone z exec-maven-plugin 3.5.1). Ten sam efekt daje
        // fabryka wątków z własnym handlerem, który nie deleguje dalej. Jawne wskazanie handlera
        // domyślnego daje ten sam wynik w IDE i pod Maven. Warunek zostawia zachowanie JVM,
        // gdy integracja jest wyłączona (setEnableUncaughtExceptionHandler(false)).
        Thread.UncaughtExceptionHandler sentryHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (sentryHandler != null) {
            worker.uncaughtExceptionHandler(sentryHandler);
        }
        worker.start(() -> export(order, reporting)).join();
    }

    private void export(Order order, ErrorReporting reporting) {
        // Job nie jest użytkownikiem. Jego nazwa trafia do tagu (kilka jobów, wymiar filtrowania),
        // a user context zostaje pusty, żeby nie zawyżać liczby dotkniętych użytkowników.
        Sentry.setTag("job.name", "label-export");
        Sentry.configureScope(scope -> scope.setContexts("label_export", Map.of("order_id", order.id())));
        try {
            labels.createLabel(order);
        } catch (RuntimeException exception) {
            switch (reporting) {
                // Efekt jak bez try/catch: wyjątek opuszcza wątek.
                case NONE -> throw exception;
                case CAPTURE_AND_RETHROW -> {
                    Sentry.captureException(exception);
                    // Handler SDK dostanie za chwilę ten sam obiekt. Przy domyślnym
                    // enableDeduplication=true DuplicateEventDetectionEventProcessor odrzuci
                    // drugi event. Zostaje pierwszy, ręczny, oznaczony jako obsłużony, choć
                    // wyjątek zakończył wątek.
                    throw exception;
                }
                case CAPTURE_AND_THROW_NEW -> {
                    Sentry.captureException(exception);
                    // Nowy obiekt bez cause nie ma związku z przechwyconym, więc deduplikacja go
                    // nie rozpozna: powstaje drugi event bez przyczyny źródłowej, w innym issue.
                    throw new IllegalStateException("Eksport etykiety nie powiódł się");
                }
                // Raportuje tylko handler SDK, a cause zachowuje przyczynę źródłową w jednym
                // evencie. Gdyby przed rzuceniem był jeszcze ręczny capture oryginału,
                // deduplikacja odrzuciłaby ten event, bo sprawdza także łańcuch przyczyn.
                case WRAP_WITH_CAUSE -> throw new IllegalStateException("Eksport etykiety nie powiódł się", exception);
            }
        }
    }
}
