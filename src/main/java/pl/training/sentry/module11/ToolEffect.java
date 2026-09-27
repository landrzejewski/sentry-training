package pl.training.sentry.module11;

/**
 * Lokalna klasyfikacja skutku narzędzia MCP: podstawa decyzji allowlisty.
 *
 * <p>Adnotacje {@code readOnlyHint}
 * i {@code destructiveHint} są informacją od serwera, a nie lokalną polityką. Ta klasa zamienia
 * je na klasyfikację zachowawczą: odczytem jest tylko to, co serwer jawnie oznaczył jako odczyt
 * i nie oznaczył jako destrukcyjne.</p>
 */
public enum ToolEffect {

    /** Odczyt bez zmiany stanu. */
    READ_ONLY,
    /** Kosztowne lub stanowe przetwarzanie, np. nowa analiza Seer. Wymaga imiennej zgody. */
    COMPUTE,
    /** Zmiana zasobu lub konfiguracji. */
    WRITE,
    /** Efekt nieustalony: brak adnotacji albo wrapper bez wskazanego celu. */
    UNKNOWN;

    /**
     * Klasyfikacja z adnotacji serwera.
     *
     * <p>PUŁAPKA: adnotacje nie odróżniają obliczenia od zapisu. W katalogu 0.42.0
     * {@code analyze_issue_with_seer} i {@code create_project} mają te same wartości
     * ({@code readOnlyHint: false}, {@code destructiveHint: false}), a {@code create_project}
     * tworzy projekt. Dlatego {@code readOnlyHint: false} daje tu {@link #WRITE}, a {@link #COMPUTE}
     * pochodzi wyłącznie z lokalnego przeglądu narzędzia ({@link ToolPolicy}).</p>
     */
    public static ToolEffect fromAnnotations(Boolean readOnlyHint, Boolean destructiveHint) {
        if (readOnlyHint == null) {
            // Specyfikacja MCP przyjmuje dla brakującej adnotacji wartości domyślne, ale brak
            // deklaracji to także brak informacji. Fail closed: nie zgadujemy.
            return UNKNOWN;
        }
        if (readOnlyHint && !Boolean.TRUE.equals(destructiveHint)) {
            return READ_ONLY;
        }
        return readOnlyHint ? UNKNOWN : WRITE;
    }
}
