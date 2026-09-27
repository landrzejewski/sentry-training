package pl.training.sentry.module02;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;

import java.net.URI;

/**
 * Pierwsza wersja {@code beforeSend} z przeglądu kodu: napisana i przetestowana tylko na eventach
 * z requestów HTTP.
 *
 * <p>Scenariusz 6 zestawia ją z {@link FilteringBeforeSend}. Dla requestu działa poprawnie, więc
 * testy endpointu przechodzą, a na evencie joba rzuca wyjątek, po którym SDK odrzuca cały event.</p>
 */
public final class HttpOnlyBeforeSend implements SentryOptions.BeforeSendCallback {

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        // PUŁAPKA: event z zadania w tle nie ma requestu, więc getRequest() zwraca null i callback
        // rzuca NullPointerException. SDK łapie wyjątek i odrzuca event: błąd joba nie dociera do
        // Sentry, a w logach aplikacji nie ma nic. Komunikat SDK widać tylko przy debug=true i jest
        // mylący: „It will be added as breadcrumb and continue”, choć event został odrzucony.
        String path = URI.create(event.getRequest().getUrl()).getPath();
        if (path.startsWith("/internal/synthetic-check")) {
            return null;
        }
        event.getRequest().setQueryString(null);
        return event;
    }
}
