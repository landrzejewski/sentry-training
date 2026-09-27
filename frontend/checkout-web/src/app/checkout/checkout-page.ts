import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import * as Sentry from '@sentry/angular';
import { CART, CartDrafts, COUPONS, DeliveryMode, PICKUP_POINTS } from './cart';
import { CheckoutApi, Route } from './checkout-api';

/**
 * Pseudonim klienta z systemu logowania. Wystarcza do liczenia users w issue i do wyszukania
 * klienta w systemie źródłowym, a nie ujawnia w Sentry e-maila ani nazwiska.
 */
const CUSTOMER_ID = 'c-7f3a9c';

@Component({
  selector: 'app-checkout-page',
  templateUrl: './checkout-page.html',
})
export class CheckoutPage {
  private readonly api = inject(CheckoutApi);
  private readonly router = inject(Router);
  private readonly drafts = new CartDrafts();

  protected readonly lines = CART;
  protected readonly pickupPoints = PICKUP_POINTS;
  protected readonly deliveryMode = signal<DeliveryMode>('PICKUP_POINT');
  protected readonly pickupPointId = signal('WAW-114');
  protected readonly couponCode = signal('');
  protected readonly discountPercent = signal(0);
  protected readonly status = signal('');
  protected readonly total = computed(() => {
    const sum = this.lines.reduce((acc, line) => acc + line.quantity * line.price, 0);
    return Math.round(sum * (100 - this.discountPercent())) / 100;
  });

  constructor() {
    // Kontekst na isolation scope przeglądarki, czyli na całą kartę: trafia do każdego
    // kolejnego eventu. W przeglądarce jest jeden użytkownik, więc nie ma wycieku między
    // requestami, który w backendzie wymaga osobnego scope na request.
    Sentry.setUser({ id: CUSTOMER_ID });
    // Tagi o kilku możliwych wartościach: da się po nich filtrować i widać ich rozkład w issue.
    // PUŁAPKA (SDK 11): tagi scope trafiają do błędów, ale nie do spanów. Do filtrowania
    // spanów służą atrybuty (Sentry.setAttributes).
    Sentry.setTag('checkout.variant', 'B');
    Sentry.setTag('delivery.mode', this.deliveryMode());
  }

  protected chooseDelivery(mode: DeliveryMode): void {
    this.deliveryMode.set(mode);
    Sentry.setTag('delivery.mode', mode);
  }

  /** Scenariusz 1: błąd w handlerze kliknięcia komponentu. */
  protected applyCoupon(): void {
    const coupon = COUPONS[this.couponCode().trim().toUpperCase()];
    // PUŁAPKA: typ Record<string, Coupon> obiecuje kupon dla każdego klucza, więc kompilator
    // nie wymusza sprawdzenia. Nieznany kod daje undefined i TypeError. Błąd przechwytuje
    // Angular, a do Sentry przekazuje go ErrorHandler z createErrorHandler (mechanism angular).
    this.discountPercent.set(coupon.percent);
    this.status.set(`Rabat ${coupon.percent}% naliczony.`);
  }

  /** Scenariusz 2: odrzucona obietnica, której nikt nie obsłużył. */
  protected saveForLater(): void {
    this.status.set('Zapisywanie koszyka...');
    // PUŁAPKA: obietnica bez catch. Handler kończy się od razu, więc Angular nie ma czego
    // przechwycić, a odrzucenie trafia do window jako unhandledrejection.
    this.drafts.save(this.lines).then(() => this.status.set('Koszyk zapisany.'));
  }

  /** Scenariusze 3 i 4: zamówienie przez checkout-api, właściwą ścieżką albo przez pułapkę. */
  protected placeOrder(route: Route = 'api'): Promise<void> {
    // Jedno złożenie zamówienia to jeden trace z czytelnym korzeniem. Bez tego request trafia
    // do trace pageloadu, który w przeglądarce trwa aż do następnej nawigacji, razem z każdym
    // innym kliknięciem na tej stronie.
    return Sentry.startNewTrace(() =>
      Sentry.startSpan({ name: 'Złożenie zamówienia', op: 'ui.action.checkout' }, () => this.submitOrder(route)),
    );
  }

  private async submitOrder(route: Route): Promise<void> {
    this.status.set('Wysyłanie zamówienia...');
    try {
      const result = await this.api.placeOrder(
        {
          deliveryMode: this.deliveryMode(),
          pickupPointId: this.deliveryMode() === 'PICKUP_POINT' ? this.pickupPointId() : null,
          couponCode: this.discountPercent() > 0 ? this.couponCode() : null,
        },
        route,
      );
      await this.router.navigate(['/zamowienie', result.orderId]);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 0) {
        // Status 0: request nie wyszedł z przeglądarki albo przeglądarka odrzuciła odpowiedź
        // (sieć, CORS). Backend nic o tym nie wie, więc zgłasza frontend.
        // PUŁAPKA: HttpErrorResponse nie jest obiektem Error. Przekazany wprost daje event
        // oznaczony jako syntetyczny (mechanism.synthetic), a takie issue dostaje w Sentry tytuł
        // od nazwy funkcji w ramce (np. „new Promise”) zamiast typu i komunikatu błędu.
        // Zgłaszamy więc oryginalny błąd fetch z error.error: TypeError z nazwą hosta dopisaną
        // przez SDK, np. „Failed to fetch (localhost:8095)”.
        const cause = error.error instanceof Error ? error.error : error;
        Sentry.captureException(cause, { tags: { 'checkout.route': route } });
        this.status.set('Brak połączenia z serwerem. Spróbuj ponownie.');
      } else {
        // Jeden właściciel raportowania: błąd 5xx zgłosił już backend (event w tym samym trace),
        // a request z kodem odpowiedzi jest w breadcrumbs i w Replay. Drugi event z frontendu
        // byłby duplikatem tego samego problemu.
        this.status.set('Nie udało się złożyć zamówienia. Spróbuj ponownie później.');
      }
    }
  }
}
