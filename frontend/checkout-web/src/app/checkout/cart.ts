/** Dane koszyka i katalogi, z których korzysta checkout. Kod domenowy bez Sentry. */

export type DeliveryMode = 'COURIER' | 'PICKUP_POINT';

export interface CartLine {
  readonly name: string;
  readonly quantity: number;
  readonly price: number;
}

export interface Coupon {
  readonly percent: number;
}

export const CART: readonly CartLine[] = [
  { name: 'Kubek termiczny 450 ml', quantity: 2, price: 59 },
  { name: 'Kawa ziarnista 1 kg', quantity: 1, price: 89 },
];

/**
 * Punkty odbioru z katalogu frontendu. KRK-031 dodano tu wcześniej niż w magazynie,
 * więc backend odrzuca zamówienie do tego punktu (błąd backendu w tym samym trace).
 */
export const PICKUP_POINTS: ReadonlyArray<{ id: string; label: string }> = [
  { id: 'WAW-114', label: 'Warszawa, Prosta 1' },
  { id: 'GDA-007', label: 'Gdańsk, Długa 5' },
  { id: 'KRK-031', label: 'Kraków, Rynek 12 (nowy)' },
];

/** Aktywne kody rabatowe. Klucz to kod wpisany przez klienta. */
export const COUPONS: Record<string, Coupon> = {
  KAWA10: { percent: 10 },
  LATO15: { percent: 15 },
};

/** Zapis koszyka „na później”. Symuluje pełną pamięć przeglądarki. */
export class CartDrafts {
  async save(lines: readonly CartLine[]): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 50));
    throw new Error(`Nie udało się zapisać szkicu koszyka (${lines.length} pozycje): brak miejsca w pamięci przeglądarki`);
  }
}
