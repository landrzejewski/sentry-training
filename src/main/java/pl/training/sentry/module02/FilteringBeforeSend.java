package pl.training.sentry.module02;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * {@code beforeSend} checkout-api: najpierw decyzja, czy event w ogóle opuści proces, potem
 * czyszczenie tego, co zostaje.
 *
 * <p>Scenariusz 5 pokazuje odrzucanie ruchu pracowników i sond oraz scrubbing. Ta sama klasa działa
 * w czystej Javie (rejestruje ją {@link SentryOptionsConfigurer}) i w Spring Boot (bean typu
 * {@code SentryOptions.BeforeSendCallback}).</p>
 *
 * <p>Kolejność ma znaczenie. Odrzucany event nie wymaga czyszczenia, a reguły klasyfikacji muszą
 * widzieć dane, zanim scrubber je usunie. Callback działa na wątku, który zgłasza błąd, więc jest
 * lokalny i szybki: bez I/O, bez logowania wysyłanego do Sentry, bez kolejnego capture.</p>
 *
 * <p>PUŁAPKA: SDK 8.54.0 łapie wyjątek rzucony w callbacku i odrzuca cały event, bo nie wie,
 * czy dane zdążyły zostać oczyszczone. Aplikacja działa dalej, a błąd po prostu nie dociera do
 * Sentry. SDK loguje to tylko przy {@code debug=true}, i to tekstem „It will be added as breadcrumb
 * and continue”, który sugeruje, że event poszedł dalej. Każdą ścieżkę trzeba przetestować także
 * na eventach bez requestu, np. z zadań w tle (scenariusz 6).</p>
 */
public final class FilteringBeforeSend implements SentryOptions.BeforeSendCallback {

    private final List<String> syntheticCheckPaths;
    private final EventDataScrubber scrubber;

    /**
     * @param syntheticCheckPaths prefiksy ścieżek sond syntetycznych, dopasowywane z granicą segmentu
     */
    public FilteringBeforeSend(List<String> syntheticCheckPaths, EventDataScrubber scrubber) {
        this.syntheticCheckPaths = List.copyOf(syntheticCheckPaths);
        this.scrubber = scrubber;
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        String origin = event.getTag("traffic.origin");

        // Ruch pracowników: tag ustawia zaufana warstwa (TrafficClassifier) w scope requestu.
        if (TrafficClassifier.Origin.INTERNAL.tagValue().equals(origin)) {
            return null;
        }

        // Ruch syntetyczny tylko przy obu warunkach. Sama ścieżka nie wystarcza: ten sam endpoint
        // wywołuje też inny system, a jego błąd to prawdziwa awaria. Sam tag też nie: sonda testująca
        // prawdziwy checkout może wykryć realny incydent i jej błąd musi dotrzeć do zespołu.
        if (TrafficClassifier.Origin.SYNTHETIC.tagValue().equals(origin) && isSyntheticCheck(event.getRequest())) {
            return null;
        }

        return scrubber.scrub(event);
    }

    private boolean isSyntheticCheck(Request request) {
        String path = request == null ? null : pathOf(request.getUrl());
        return path != null && syntheticCheckPaths.stream().anyMatch(prefix -> matchesSegments(path, prefix));
    }

    /**
     * Prefiks z granicą segmentu: {@code /internal/synthetic-check} obejmuje
     * {@code /internal/synthetic-check/payments}, ale nie {@code /internal/synthetic-checkout}.
     */
    private static boolean matchesSegments(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    private static String pathOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url).getPath();
        } catch (URISyntaxException exception) {
            // Nieczytelny URL: nie potwierdzamy sondy, więc event zostaje. Lepszy szum niż zgubiony błąd.
            return null;
        }
    }
}
