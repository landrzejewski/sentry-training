import { Routes } from '@angular/router';
import { CheckoutPage } from './checkout/checkout-page';


export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'koszyk' },
  { path: 'koszyk', component: CheckoutPage },
  // Trasa ładowana leniwie, więc build ma osobny chunk: source maps i debug ID muszą objąć
  // każdy plik JS, nie tylko main.
  {
    path: 'zamowienie/:orderId',
    loadComponent: () => import('./checkout/order-confirmation').then((m) => m.OrderConfirmation),
  },
];
