# Wspólna infrastruktura przykładów
[`TrainingSentry`](TrainingSentry.java) inicjalizuje SDK w trybie online (z `SENTRY_DSN`) albo offline (wydruk na konsolę), a [`ConsoleEnvelopeTransport`](ConsoleEnvelopeTransport.java) pokazuje, co SDK faktycznie wysyła. Klasy tego pakietu nie są częścią materiału merytorycznego modułów: to narzędzia, dzięki którym efekt każdego przykładu widać od razu, bez konta Sentry.

Uruchomienie demo dowolnego modułu:

```bash
# offline
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo
# online: lokalne Sentry z docker/sentry albo dowolny DSN
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=...
# pełny JSON każdego itemu envelope
./mvnw -q compile exec:java -Dexec.mainClass=... -Dsentry.demo.json=true
```
