package pl.training.sentry.module07;

/**
 * Jeden dowód w pakiecie dla agenta: stabilne ID nadane przez hosta, rodzaj, źródło i treść.
 *
 * <p>ID nadaje host, a nie model, więc raport
 * może się na nie powołać, a walidator ({@link ReportValidator}) sprawdzić, czy dowód istnieje.
 * Źródło wskazuje miejsce w danych Sentry, z którego pochodzi treść, żeby człowiek mógł ją
 * porównać z oryginałem w UI.</p>
 *
 * <p>Treść jest zawsze niezaufanymi danymi: pochodzi z telemetrii, a tę współtworzą klienci
 * aplikacji, zależności i każdy, kto potrafi wywołać błąd.</p>
 *
 * @param id      np. {@code EV-3}
 * @param source  np. {@code event:4f2c.../exception}
 * @param content treść po minimalizacji, jeszcze przed redakcją
 */
public record Evidence(String id, Kind kind, String source, String content) {

    public enum Kind {
        ISSUE,
        EXCEPTION,
        STACK_TRACE,
        RELEASE,
        TAGS,
        CONTEXT,
        BREADCRUMB
    }

    /** Ta sama pozycja z inną treścią, np. po redakcji. ID i źródło zostają bez zmian. */
    public Evidence withContent(String newContent) {
        return new Evidence(id, kind, source, newContent);
    }
}
