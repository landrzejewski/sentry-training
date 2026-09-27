import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    <header>
      <strong>Sklep szkoleniowy</strong>
      <span>checkout-web</span>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
})
export class App {}
