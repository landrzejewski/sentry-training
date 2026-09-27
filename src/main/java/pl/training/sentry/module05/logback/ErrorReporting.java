package pl.training.sentry.module05.logback;

/**
 * Kto i jak raportuje nieudany zwrot. Trzy wersje tego samego kodu, które zespół mógłby wdrożyć
 * (scenariusz 10). Jedna awaria powinna mieć jednego właściciela raportu: granicę, która loguje
 * ERROR z wyjątkiem niosącym pełny łańcuch {@code cause}.
 */
public enum ErrorReporting {

    /**
     * Wersja docelowa: niższe warstwy opakowują wyjątek z {@code cause} i rzucają dalej, a jedynym
     * wpisem ERROR z wyjątkiem jest log granicy ({@link RefundEndpoint}). Ponowienia bramki zostają
     * logami WARN, czyli breadcrumbami i Structured Logs.
     */
    SINGLE_OWNER,

    /**
     * PUŁAPKA: granica loguje ERROR z wyjątkiem i dodatkowo woła {@code Sentry.captureException}
     * z tym samym obiektem wyjątku. Deduplikacja SDK odrzuca drugi event, więc issue powstaje z tego
     * wywołania, które było pierwsze.
     */
    LOG_AND_CAPTURE,

    /**
     * PUŁAPKA: każda warstwa loguje ten sam błąd po swojemu. Bramka loguje ERROR z wyjątkiem,
     * serwis loguje ERROR z samym komunikatem i rzuca nowy wyjątek bez {@code cause}, a granica
     * loguje ERROR z tym nowym wyjątkiem. Deduplikacja SDK nie ma czego porównać: trzy eventy,
     * trzy issues dla jednej awarii.
     */
    LOG_ON_EVERY_LAYER
}
