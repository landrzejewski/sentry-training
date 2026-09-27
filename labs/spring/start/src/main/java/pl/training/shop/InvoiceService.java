package pl.training.shop;

/** Klient zewnętrznego serwisu faktur. Na potrzeby ćwiczenia zawsze zgłasza niedostępność. */
public class InvoiceService {

    public static class InvoiceUnavailableException extends RuntimeException {
        public InvoiceUnavailableException(String message) {
            super(message);
        }
    }

    public String render(String orderId) {
        throw new InvoiceUnavailableException("Serwis faktur nie odpowiedział dla zamówienia " + orderId);
    }
}
