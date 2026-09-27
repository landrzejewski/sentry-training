package pl.training.sentry.module05;

import io.sentry.ISpan;
import io.sentry.NoOpSpan;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Ręczny child span wokół jednej operacji: status, throwable i zakończenie w {@code finally}.
 *
 * <p>Span jest dzieckiem operacji, która już trwa (transakcji requestu albo joba). Klasa nie
 * tworzy root transaction i nie wysyła error eventu: przechwycenie wyjątku należy do granicy
 * requestu albo joba, więc jeden wyjątek nie trafia do Sentry dwa razy.</p>
 *
 * <p>Tracing Sentry-native. W wariancie OTLP ({@code sentry-opentelemetry-otlp}) spany tworzy
 * OpenTelemetry, a ta klasa nie jest ścieżką tracingu (moduł 9).</p>
 *
 * <p>PRODUKCJA: automatyczne integracje (Spring MVC, klienty HTTP, JDBC) tworzą szkielet trace
 * same. Ręczny span dodaje się tylko tam, gdzie automatyka nie widzi operacji istotnej dla
 * diagnozy, a nie dla każdej metody.</p>
 */
public final class ChildSpans {

    /** Praca mierzona spanem. Dostaje span, żeby zapisać w nim bezpieczne dane wyniku. */
    @FunctionalInterface
    public interface SpanWork<T, E extends Exception> {
        T run(ISpan span) throws E;
    }

    private ChildSpans() {
    }

    /**
     * Child bieżącego spanu, czyli tego, który zwraca {@code Sentry.getSpan()}.
     *
     * <p>PUŁAPKA: {@code Sentry.getSpan()} zwraca ostatni jeszcze niezakończony span transakcji
     * ze scope, a nie span „tego wątku”. W tracingu Sentry-native {@code ISpan.makeCurrent()}
     * niczego nie zmienia. Przy pracy równoległej span z innego wątku mógłby więc zostać
     * rodzicem, dlatego równoległe gałęzie dostają rodzica jawnie (druga metoda).</p>
     */
    public static <T, E extends Exception> T trace(String operation, String description, SpanWork<T, E> work) throws E {
        return trace(Sentry.getSpan(), operation, description, work);
    }

    /** Child jawnie wskazanego rodzica. Bez rodzica praca wykonuje się bez spanu. */
    public static <T, E extends Exception> T trace(
            ISpan parent,
            String operation,
            String description,
            SpanWork<T, E> work
    ) throws E {
        if (parent == null) {
            // Brak rodzica: kod biznesowy działa dalej, ale w waterfall nie będzie tej operacji.
            // Nie zakładamy tu nowej transakcji, bo powstałby drugi korzeń albo osobny trace.
            // NoOpSpan pozwala wywołującemu zapisywać dane bez sprawdzania null.
            return work.run(NoOpSpan.getInstance());
        }
        // PUŁAPKA: rodzic musi jeszcze trwać. Po finish() transakcji startChild zwraca NoOpSpan,
        // więc praca rozpoczęta później w ogóle nie trafi do tej transakcji.
        ISpan span = parent.startChild(operation, description);
        try {
            T result = work.run(span);
            // Status ustawiony przez pracę (np. z kodu HTTP) ma pierwszeństwo przed domyślnym OK.
            if (span.getStatus() == null) {
                span.setStatus(SpanStatus.OK);
            }
            return result;
        } catch (Throwable failure) {
            // setThrowable nie wysyła eventu. Przy finish() SDK zapamiętuje powiązanie wyjątku
            // (po przyczynie źródłowej) z tym spanem, więc późniejszy captureException na granicy
            // requestu wskaże w trace ten span, a nie całą transakcję.
            span.setThrowable(failure);
            span.setStatus(statusFor(failure));
            throw failure;
        } finally {
            span.finish();
        }
    }

    /**
     * Status spanu z kodu HTTP.
     *
     * <p>PUŁAPKA: {@code SpanStatus.fromHttpStatusCode(int)} mapuje zakres 0-399 na OK, a z błędów
     * zna tylko wybrane kody (np. 400, 401, 403, 404, 409, 429, 500, 503, 504). Dla pozostałych
     * zwraca null. Dla 422 span bez statusu
     * zostałby potem oznaczony jako OK. Dlatego nieznany kod 4xx to {@code invalid_argument},
     * a nieznany 5xx to {@code internal_error}.</p>
     */
    public static SpanStatus statusForHttp(int httpStatus) {
        SpanStatus fallback = httpStatus >= 500 ? SpanStatus.INTERNAL_ERROR
                : httpStatus >= 400 ? SpanStatus.INVALID_ARGUMENT
                : SpanStatus.OK;
        return SpanStatus.fromHttpStatusCode(httpStatus, fallback);
    }

    /**
     * Status tylko dla znanej kategorii porażki. Timeout ma własny status, reszta to błąd
     * wewnętrzny: nie każdy wyjątek zdalnego wywołania jest timeoutem.
     */
    static SpanStatus statusFor(Throwable failure) {
        if (failure instanceof TimeoutException
                || failure instanceof HttpTimeoutException
                || failure instanceof SocketTimeoutException) {
            return SpanStatus.DEADLINE_EXCEEDED;
        }
        return SpanStatus.INTERNAL_ERROR;
    }
}
