import type { BrowserOptions } from '@sentry/angular';

/**
 * Transport szkoleniowy: wypisuje w konsoli przeglądarki każdy envelope, który SDK chce wysłać.
 * Odpowiednik ConsoleEnvelopeTransport z Javy (pakiet support).
 *
 * Transport to ostatni etap potoku SDK: envelope przeszedł już przez scope, integracje,
 * beforeSend, sampling i maskowanie Replay. Wydruk pokazuje więc to, co trafiłoby do Sentry,
 * a nie to, co kod próbował wysłać. Z delegatem (online) envelope idzie dalej do Sentry,
 * bez delegata (offline) nic nie opuszcza przeglądarki.
 *
 * PRODUKCJA: narzędzie do nauki i debugowania, nie do aplikacji produkcyjnej.
 */
type TransportFactory = NonNullable<BrowserOptions['transport']>;
type Transport = ReturnType<TransportFactory>;
type Envelope = Parameters<Transport['send']>[0];
// Payloady itemów różnią się typem; wydruk czyta z nich tylko kilka pól.
type Json = Record<string, any>;

/**
 * Oryginalne metody konsoli zapisane przy imporcie modułu, czyli przed Sentry.init. Integracja
 * breadcrumbs owija console.*, a wydruk transportu nie powinien stawać się breadcrumbem
 * kolejnych eventów.
 */
export const trainingConsole = {
  info: console.info.bind(console),
  groupCollapsed: console.groupCollapsed.bind(console),
  groupEnd: console.groupEnd.bind(console),
  log: console.log.bind(console),
};

export function makeConsoleTransport(delegate?: TransportFactory): TransportFactory {
  return (options) => {
    const next = delegate?.(options);
    return {
      send(envelope: Envelope) {
        print(envelope);
        // Offline odpowiadamy jak Sentry, które przyjęło envelope. Replay w trybie bufora
        // wysyła nagranie dopiero po udanym wysłaniu eventu błędu (status 2xx).
        return next ? next.send(envelope) : Promise.resolve({ statusCode: 200 });
      },
      flush(timeout?: number) {
        return next ? next.flush(timeout) : Promise.resolve(true);
      },
    };
  };
}

function print(envelope: Envelope): void {
  const [, items] = envelope as unknown as [Json, Array<[Json, unknown]>];
  for (const [header, payload] of items) {
    const line = describe(String(header['type']), payload);
    if (line === null) {
      continue;
    }
    // Grupa zwinięta: jedna czytelna linia, a pełny payload po rozwinięciu.
    trainingConsole.groupCollapsed(`%cSentry ▸ ${header['type']}%c ${line}`, 'color:#6c5fc7;font-weight:bold', '');
    trainingConsole.log(typeof payload === 'string' || payload instanceof Uint8Array ? `(${size(payload)} B)` : payload);
    trainingConsole.groupEnd();
    if (header['type'] === 'replay_recording') {
      void printRecordedTexts(payload);
    }
  }
}

/**
 * Teksty i wartości pól zapisane w nagraniu Replay, czyli to, co zobaczy osoba oglądająca
 * nagranie w Sentry. Nagranie jest skompresowane w przeglądarce, więc trzeba je rozpakować.
 */
async function printRecordedTexts(payload: unknown): Promise<void> {
  if (!(payload instanceof Uint8Array)) {
    return;
  }
  // Pierwsza linia to nagłówek segmentu w JSON, reszta to skompresowane zdarzenia rrweb.
  const body = payload.slice(payload.indexOf(10) + 1);
  for (const format of ['deflate', 'gzip'] as const) {
    try {
      const stream = new Blob([body]).stream().pipeThrough(new DecompressionStream(format));
      const events: unknown = JSON.parse(await new Response(stream).text());
      const texts = new Set<string>();
      collectTexts(events, texts);
      trainingConsole.log(`%cSentry ▸ replay%c teksty w nagraniu: ${[...texts].slice(0, 12).map((t) => `"${t}"`).join(', ')}`,
        'color:#6c5fc7;font-weight:bold', '');
      return;
    } catch {
      // Inny format kompresji: próbujemy następnego.
    }
  }
}

function collectTexts(node: unknown, texts: Set<string>): void {
  if (Array.isArray(node)) {
    node.forEach((child) => collectTexts(child, texts));
  } else if (typeof node === 'object' && node !== null) {
    for (const [key, value] of Object.entries(node)) {
      if ((key === 'textContent' || key === 'value' || key === 'text') && typeof value === 'string' && value.trim().length > 2 && !value.includes('{')) {
        texts.add(value.trim());
      } else {
        collectTexts(value, texts);
      }
    }
  }
}

function describe(type: string, payload: unknown): string | null {
  const data = (typeof payload === 'object' && payload !== null ? payload : {}) as Json;
  switch (type) {
    case 'event':
      return describeEvent(data);
    case 'transaction':
      return `${data['transaction']} | op=${data['contexts']?.trace?.op} | trace=${data['contexts']?.trace?.trace_id} | spany=${data['spans']?.length ?? 0}`;
    case 'span':
      return describeSpans(data['items'] ?? []);
    case 'replay_event':
      return `replay=${data['replay_id']} | typ=${data['replay_type']} | segment=${data['segment_id']} | błędy=${(data['error_ids'] ?? []).join(',') || 'brak'}` +
        ` | trace=${(data['trace_ids'] ?? []).join(',') || 'brak'}`;
    case 'replay_recording':
      return `nagranie DOM po maskowaniu, ${size(payload)} B (skompresowane)`;
    case 'client_report':
    case 'session':
      // Release Health i statystyki odrzuconych danych: pomijane, żeby nie zaciemniać wydruku.
      return null;
    default:
      return '';
  }
}

/**
 * SDK 11 domyślnie wysyła spany strumieniowo, paczkami, zamiast jednej transakcji. Segment to
 * korzeń fragmentu trace z tej przeglądarki (pageload, nawigacja, akcja użytkownika).
 * Wydruk pokazuje segmenty i spany HTTP, a spany zasobów i web vitals tylko zlicza.
 */
function describeSpans(spans: Json[]): string {
  const op = (span: Json) => String(span['attributes']?.['sentry.op']?.value ?? '?');
  const shown = spans.filter((span) => span['is_segment'] || /^(http|ui\.action)/.test(op(span)));
  const lines = shown.map(
    (span) =>
      `${span['is_segment'] ? '[segment] ' : ''}${span['name']} (${op(span)}) trace=${span['trace_id']}` +
      ` span=${span['span_id']} parent=${span['parent_span_id'] ?? 'brak'}`,
  );
  const hidden = spans.length - shown.length;
  if (hidden > 0) {
    lines.push(`+${hidden} innych spanów (zasoby, web vitals, zdarzenia przeglądarki)`);
  }
  return lines.join('\n    ');
}

function describeEvent(event: Json): string {
  const exception = event['exception']?.values?.at(-1);
  const frames: Json[] = exception?.stacktrace?.frames ?? [];
  const top = frames.at(-1);
  const images: Json[] = event['debug_meta']?.images ?? [];
  const tags = Object.entries(event['tags'] ?? {}).map(([key, value]) => `${key}=${value}`).join(', ');
  return [
    exception ? `${exception.type}: ${exception.value}` : event['message'],
    `event_id=${event['event_id']}`,
    `handled=${exception?.mechanism?.handled === false ? 'nie' : 'tak'} mechanism=${exception?.mechanism?.type ?? '-'} level=${event['level']}`,
    `release=${event['release']} environment=${event['environment']}`,
    `trace=${event['contexts']?.trace?.trace_id} replay=${event['contexts']?.replay?.replay_id ?? 'brak'}`,
    `user=${JSON.stringify(event['user'] ?? {})} tags={${tags}}`,
    // Ramka, w której poleciał wyjątek, tak jak widzi ją przeglądarka: w buildzie produkcyjnym
    // zminifikowany plik, linia i kolumna. Odtworzenie kodu źródłowego robi dopiero Sentry.
    top ? `ramka=${top['function'] ?? '?'} ${top['filename']}:${top['lineno']}:${top['colno']}` : 'ramka=brak',
    `debug_meta=${images.length ? images.map((image) => image['debug_id']).join(',') : 'brak (build bez debug ID)'}`,
  ].join('\n    ');
}

function size(payload: unknown): number {
  if (payload instanceof Uint8Array) {
    return payload.byteLength;
  }
  return typeof payload === 'string' ? payload.length : 0;
}
