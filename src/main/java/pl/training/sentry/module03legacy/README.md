# module03legacy: biblioteka innego zespołu

Biblioteka innego zespołu używana w przykładach modułu 3 (scenariusz 2 w [`module03`](../module03/README.md)).

Pakiet celowo zaczyna się tak samo jak `pl.training.sentry.module03`. Prefiks in-app bez kropki na końcu obejmuje także ten pakiet, a prefiks `pl.training.sentry.module03.` już nie, bo SDK porównuje nazwę klasy z prefiksem przez `String.startsWith`.
