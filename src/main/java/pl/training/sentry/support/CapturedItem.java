package pl.training.sentry.support;

import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryMetricsEvent;
import io.sentry.protocol.SentryTransaction;

import java.util.List;

/**
 * Jeden element envelope, który SDK przekazało do transportu, odczytany do postaci obiektu.
 *
 * <p>SDK nie wysyła do Sentry „eventów” wprost, tylko envelope: kopertę z jednym lub
 * kilkoma itemami (event błędu, transakcja, paczka logów, check-in, raport klienta).
 * Ten typ pozwala demom i testom oglądać dokładnie to, co opuściłoby aplikację, już po
 * wszystkich procesorach, {@code beforeSend} i samplingu.</p>
 */
public sealed interface CapturedItem {

    /** Event błędu albo wiadomości, czyli to, co w Sentry grupuje się w issue. */
    record Event(SentryEvent event) implements CapturedItem {
    }

    /** Transakcja, czyli span wejściowy usługi razem z zakończonymi spanami podrzędnymi. */
    record Transaction(SentryTransaction transaction) implements CapturedItem {
    }

    /** Paczka Structured Logs. SDK wysyła logi partiami, a nie pojedynczo. */
    record Logs(List<SentryLogEvent> logs) implements CapturedItem {
    }

    /** Paczka Application Metrics. SDK buforuje metryki i wysyła je partiami, jak logi. */
    record Metrics(List<SentryMetricsEvent> metrics) implements CapturedItem {
    }

    /** Stan sesji Release Health: start, aktualizacja po błędzie albo zakończenie. */
    record SessionUpdate(io.sentry.Session session) implements CapturedItem {
    }

    /** Check-in Cron Monitora opisujący wynik jednego uruchomienia zadania. */
    record CheckIn(io.sentry.CheckIn checkIn) implements CapturedItem {
    }

    /** Raport klienta: ile danych SDK odrzuciło lokalnie i z jakiego powodu. */
    record ClientReport(io.sentry.clientreport.ClientReport report) implements CapturedItem {
    }

    /** Pozostałe typy itemów (np. załączniki) jako surowy JSON. */
    record Other(String type, String json) implements CapturedItem {
    }
}
