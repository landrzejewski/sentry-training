package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Narzędzie szkoleniowe: wypisuje pola eventu, których nie pokazuje wydruk transportu.
 *
 * <p>Dla każdego wyjątku w łańcuchu: pierwszą ramkę z {@code in_app=true} i liczbę takich ramek,
 * a dla breadcrumbs: ich liczbę w evencie i najstarszy wpis. To te same informacje, których
 * w Issue Details szuka się w sekcjach Stack Trace i Breadcrumbs.</p>
 *
 * <p>Działa jako {@code beforeSend} opakowujący callback ustawiony wcześniej, więc widzi event po
 * scope, event processorach i tym callbacku. Event odrzucony wcześniej (np. przez deduplikację)
 * nie zostanie wypisany. PRODUKCJA: nie loguje się treści eventów, bo zawierają dane użytkowników.</p>
 */
public final class EventPreview implements SentryOptions.BeforeSendCallback {

    private final SentryOptions.BeforeSendCallback delegate;
    private final PrintStream out;

    public EventPreview(SentryOptions.BeforeSendCallback delegate, PrintStream out) {
        this.delegate = delegate;
        this.out = out;
    }

    /** Dokłada podgląd do {@code beforeSend} ustawionego przez wcześniejszą konfigurację. */
    public static Consumer<SentryOptions> install() {
        return options -> options.setBeforeSend(new EventPreview(options.getBeforeSend(), System.out));
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        SentryEvent result = delegate == null ? event : delegate.execute(event, hint);
        if (result != null) {
            printInAppFrames(result);
            printBreadcrumbs(result);
        }
        return result;
    }

    private void printInAppFrames(SentryEvent event) {
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions == null) {
            return;
        }
        // Protokół zapisuje łańcuch od najgłębszej przyczyny, a wypisujemy go jak stack trace Javy.
        List<SentryException> chain = new ArrayList<>(exceptions);
        Collections.reverse(chain);
        for (SentryException exception : chain) {
            List<SentryStackFrame> frames = exception.getStacktrace() == null
                    ? List.of()
                    : exception.getStacktrace().getFrames();
            if (frames == null) {
                frames = List.of();
            }
            long inApp = frames.stream().filter(frame -> Boolean.TRUE.equals(frame.isInApp())).count();
            out.printf("  · in-app      %s: %s (in_app=true: %d z %d ramek)%n",
                    exception.getType(), firstInAppFrame(frames), inApp, frames.size());
        }
    }

    /** Ramka najbliższa miejscu zgłoszenia; SDK zapisuje ramki od najstarszej, więc szukamy od końca. */
    private static String firstInAppFrame(List<SentryStackFrame> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            SentryStackFrame frame = frames.get(i);
            if (Boolean.TRUE.equals(frame.isInApp())) {
                return "pierwsza in-app " + frame.getModule() + "." + frame.getFunction();
            }
        }
        return "brak ramek in-app";
    }

    private void printBreadcrumbs(SentryEvent event) {
        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs == null || breadcrumbs.isEmpty()) {
            out.println("  · breadcrumbs 0 w evencie");
            return;
        }
        Breadcrumb oldest = breadcrumbs.getFirst();
        out.printf("  · breadcrumbs %d w evencie, najstarszy: [%s] %s%n",
                breadcrumbs.size(), oldest.getCategory(),
                oldest.getMessage() != null ? oldest.getMessage() : oldest.getData("method") + " " + oldest.getData("url"));
    }
}
