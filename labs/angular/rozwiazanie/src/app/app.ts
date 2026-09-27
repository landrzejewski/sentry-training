import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    <h1>Hello, {{ title() }}</h1>
    <button id="break" (click)="breakSomething()">Zepsuj coś</button>
    <button id="load-order" (click)="loadOrder()">Pokaż zamówienie ORD-1</button>
    <p id="result">{{ result() }}</p>

    <router-outlet />
  `,
  styles: [],
})
export class App {
  private readonly http = inject(HttpClient);
  protected readonly title = signal('shop-web');
  protected readonly result = signal('');

  // Krok A3: błąd w handlerze kliknięcia. Angular go przechwytuje i przekazuje do ErrorHandler.
  protected breakSomething(): void {
    throw new Error('Testowy błąd z laboratorium');
  }

  // Krok A4: request do shop-api (Spring Boot z części 1) z nagłówkami sentry-trace i baggage.
  protected loadOrder(): void {
    this.http
      .get<{ id: string; total: number }>('http://localhost:8090/api/orders/ORD-1')
      .subscribe((order) => this.result.set(`${order.id}: ${order.total} zł`));
  }
}
