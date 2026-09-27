package pl.training.sentry.module08;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryId;
import pl.training.sentry.module08.ReadinessReport.ControlResult;
import pl.training.sentry.module08.ReadinessReport.Status;
import pl.training.sentry.module08.SentryEventApi.ReceivedEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pokazuje dowód dostarczenia: syntetyczny event odbiorowy wysyłany po wdrożeniu i sprawdzenie,
 * czy dotarł do Sentry.
 *
 * <p>ID eventu trafia do rekordu wdrożenia jako referencja dowodu. Wynik sprawdzenia to kontrola
 * {@code DELIVERY} raportu gotowości.</p>
 *
 * <p>Event odbiorowy przechodzi przez ten sam potok SDK co prawdziwe błędy (scope, integracje,
 * {@code beforeSend}, sampling, transport), więc sprawdza konfigurację w działaniu, a nie na
 * papierze. Jednocześnie nie może nikogo budzić: jest oznaczony, zgrupowany i ma niski poziom.</p>
 */
public final class AcceptanceProbe {

    /** Tag, po którym reguły Alertów i dashboardy wykluczają eventy syntetyczne. */
    public static final String SYNTHETIC_TAG = "synthetic";

    /** Wyszukanie eventu w Sentry po ID; implementacja HTTP to {@link SentryEventApi}. */
    @FunctionalInterface
    public interface EventLookup {
        Optional<ReceivedEvent> find(SentryId eventId);
    }

    /** Wyjątek, który niesie event odbiorowy. Jego ramki należą do kodu aplikacji. */
    public static final class ProbeException extends RuntimeException {
        ProbeException(String message) {
            super(message);
        }
    }

    private AcceptanceProbe() {
    }

    /**
     * Wysyła event odbiorowy i zwraca jego ID, które trafia do rekordu wdrożenia jako dowód.
     *
     * <p>Wyjątek zamiast wiadomości, bo odbiór obejmuje ramki aplikacji: stack trace pokaże,
     * czy {@code inAppIncludes} działa na wdrożonym artefakcie.</p>
     *
     * @return ID eventu albo {@link SentryId#EMPTY_ID}, gdy SDK odrzuciło event lokalnie
     */
    public static SentryId send(String deploymentId) {
        return Sentry.captureException(new ProbeException("Event odbiorowy wdrożenia " + deploymentId), scope -> {
            // Wykluczenie z routingu: reguły Alertów mają filtr „synthetic nie równa się true”.
            // Sam tag niczego nie wycisza, działa tylko razem z takim filtrem w każdej regule.
            scope.setTag(SYNTHETIC_TAG, "true");
            // Identyfikator wdrożenia w contexts, nie w tagu: każde wdrożenie ma inny.
            scope.setContexts("acceptance", Map.of("deployment_id", deploymentId));
            // Stały fingerprint: wszystkie wdrożenia trafiają do jednego issue. Domyślne grupowanie
            // zależy od ramek stack trace, więc refaktoryzacja kodu wywołującego mogłaby otworzyć
            // nowe issue, a reguła „nowe issue” wysłałaby powiadomienie.
            scope.setFingerprint(List.of("acceptance-probe"));
            // Poziom info zamiast domyślnego error: to nie jest błąd. Sentry liczy bazowy priorytet
            // issue z level eventu, a info daje Low.
            scope.setLevel(SentryLevel.INFO);
        });
    }

    /**
     * Ocena kontroli {@code DELIVERY} na podstawie tego, co Sentry faktycznie zapisało.
     *
     * @param lookup API Sentry albo pusto, gdy nie ma czym sprawdzić (tryb offline, brak tokenu)
     */
    public static ControlResult verify(SentryId eventId, String expectedRelease, String expectedEnvironment,
                                       Optional<? extends EventLookup> lookup) {
        if (SentryId.EMPTY_ID.equals(eventId)) {
            // Pusty ID wraca, gdy SDK nie przekazało eventu do transportu: SDK wyłączone,
            // sampleRate, beforeSend. To inny problem niż event zgubiony po drodze do Sentry.
            return result(Status.FAIL, "SDK odrzuciło event lokalnie (wyłączone SDK, sampleRate albo beforeSend)");
        }
        if (lookup.isEmpty()) {
            return result(Status.NOT_VERIFIED, "event " + eventId + " przekazany do transportu, "
                    + "ale nie odczytany z Sentry (tryb offline albo brak SENTRY_AUTH_TOKEN)");
        }
        Optional<ReceivedEvent> received;
        try {
            received = lookup.get().find(eventId);
        } catch (IllegalStateException exception) {
            return result(Status.NOT_VERIFIED, "nie udało się sprawdzić eventu " + eventId + ": " + exception.getMessage());
        }
        if (received.isEmpty()) {
            return result(Status.FAIL, "event " + eventId + " nie dotarł do projektu: transport, DSN innego "
                    + "projektu, limit albo filtr po stronie Sentry");
        }
        ReceivedEvent event = received.get();
        if (!expectedRelease.equals(event.release()) || !expectedEnvironment.equals(event.environment())) {
            return result(Status.FAIL, "event dotarł z release=" + event.release() + ", environment="
                    + event.environment() + ", a wdrożono " + expectedRelease + " na " + expectedEnvironment);
        }
        if (event.inAppFrames() == 0) {
            return result(Status.FAIL, "event dotarł, ale żadna ramka nie jest in-app: sprawdź inAppIncludes");
        }
        return result(Status.PASS, "event " + eventId + " odczytany z Sentry: release=" + event.release()
                + ", environment=" + event.environment() + ", ramki in-app: " + event.inAppFrames());
    }

    private static ControlResult result(Status status, String detail) {
        return new ControlResult(ReadinessAuditor.DELIVERY, status, detail);
    }
}
