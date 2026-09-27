import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { runtimeConfig } from '../sentry/runtime-config';
import { DeliveryMode } from './cart';

export interface CheckoutRequest {
  readonly deliveryMode: DeliveryMode;
  readonly pickupPointId: string | null;
  readonly couponCode: string | null;
}

export interface CheckoutResult {
  readonly orderId: string;
  readonly status: string;
}

/**
 * Którędy idzie request. Właściwa ścieżka i dwie pułapki propagacji, które zachowują się inaczej
 * mimo tego samego backendu i tego samego kontrolera.
 */
export type Route = 'api' | 'poza-targets' | 'legacy-cors';

/** Klient checkout-api. Instrumentację fetch dodaje browserTracingIntegration, nie ten kod. */
@Injectable({ providedIn: 'root' })
export class CheckoutApi {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = runtimeConfig().apiUrl;

  placeOrder(request: CheckoutRequest, route: Route = 'api'): Promise<CheckoutResult> {
    return firstValueFrom(this.http.post<CheckoutResult>(this.url(route), request));
  }

  private url(route: Route): string {
    switch (route) {
      case 'api':
        return `${this.apiUrl}/api/checkout`;
      case 'poza-targets':
        // PUŁAPKA: ten sam backend pod adresem 127.0.0.1 nie pasuje do tracePropagationTargets.
        // Request dochodzi, ale bez sentry-trace i baggage, więc backend zaczyna nowy trace.
        return `${this.apiUrl.replace('localhost', '127.0.0.1')}/api/checkout`;
      case 'legacy-cors':
        // PUŁAPKA: adres pasuje do tracePropagationTargets, ale polityka CORS tej ścieżki
        // nie dopuszcza nagłówków trace. Przeglądarka blokuje request po preflight.
        return `${this.apiUrl}/legacy-api/checkout`;
    }
  }
}
