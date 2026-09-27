package pl.training.sentry.module08;

/**
 * Drukarka etykiet na stanowisku pakowania: kod domenowy bez Sentry.
 *
 * <p>Dwa rodzaje błędów, które Release Health liczy inaczej: brak etykiet w drukarce to sytuacja
 * przewidziana (operator dokłada rolkę, zadanie wraca do kolejki), a zadanie bez szablonu to
 * defekt, który kończy wątek kolejki wydruku.</p>
 */
public final class LabelPrinter {

    /** Zlecenie wydruku. Zamówienia z nowego kanału sprzedaży nie mają jeszcze szablonu (null). */
    public record PrintJob(String orderId, String template) {
    }

    /** Przewidziany stan drukarki, który operator usuwa ręcznie. */
    public static final class OutOfLabelsException extends RuntimeException {
        OutOfLabelsException(String message) {
            super(message);
        }
    }

    private int labelsLeft;

    public LabelPrinter(int labelsLeft) {
        this.labelsLeft = labelsLeft;
    }

    public String print(PrintJob job) {
        if (labelsLeft == 0) {
            throw new OutOfLabelsException("Brak etykiet w drukarce przy zamówieniu " + job.orderId());
        }
        // Celowy defekt: brak obsługi zlecenia bez szablonu kończy się NullPointerException.
        String label = job.template().replace("{order}", job.orderId());
        labelsLeft--;
        return label;
    }

    /** Operator dokłada rolkę etykiet. */
    public void reload(int labels) {
        labelsLeft = labels;
    }
}
