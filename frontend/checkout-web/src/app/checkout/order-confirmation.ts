import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Potwierdzenie zamówienia. Wejście na tę trasę to nawigacja, czyli nowy trace w przeglądarce. */
@Component({
  selector: 'app-order-confirmation',
  imports: [RouterLink],
  template: `
    <h1>Dziękujemy za zamówienie</h1>
    <p>Numer zamówienia: <strong id="order-id">{{ orderId() }}</strong></p>
    <a routerLink="/koszyk">Wróć do koszyka</a>
  `,
})
export class OrderConfirmation {
  readonly orderId = input.required<string>();
}
