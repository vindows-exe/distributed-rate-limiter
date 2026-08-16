# Architecture Decision Records (ADR)

Dieses Dokument dokumentiert die zentralen Architekturentscheidungen, evaluierte Alternativen und Trade-offs des Distributed Rate Limiter & Audit Systems.

---

## ADR-001: Verteilte Zustandshaltung & Atomizität via Redis Lua-Scripting

- **Status:** Akzeptiert
- **Datum:** 2026-08-16
- **Kontext:**
  Das System muss Anfragelimits über mehrere Serverinstanzen hinweg mit Sub-Millisekunden-Latenz (< 5ms) durchsetzen. Ein lokaler JVM-Speicher (`AtomicInteger`) skaliert nicht über mehrere Knoten, da Instanzen keinen geteilten Arbeitsspeicher besitzen.
- **Evaluierte Optionen:**
  1. _In-Memory JVM State (`AtomicInteger`):_ Unbrauchbar für verteilte Systeme, da Clients durch Routing auf verschiedene Knoten das Gesamtklimit umgehen können.
  2. _Relationale Persistenz (PostgreSQL mit Row Locks):_ Zu hohe Festplatten- und Lock-Latenz (10–50ms) bei hohem Durchsatz.
  3. _Redis Read-Then-Write (Java-Client):_ Anfällig für Race Conditions (Check-then-Act), wodurch parallele Anfragen trotz Token-Mangel zugelassen werden.
  4. _Redis In-Memory mit atomarem Lua-Skript:_ Token-Refill und Dekrementierung laufen in einer einzigen atomaren Server-Operation im Single-Threaded-Execution-Modell von Redis.
- **Entscheidung:** Option 4. Wir nutzen den **Token-Bucket-Algorithmus**, der direkt via Lua-Skript in Redis ausgeführt wird.
- **Konsequenzen & Trade-offs:**
  - Race Conditions werden ohne relationale Locks vollständig eliminiert.
  - Netzwerklatenz sinkt auf einen einzigen Roundtrip zwischen Gateway und Redis.
  - Logik ist an die Redis-Laufzeitumgebung gebunden.

---

## ADR-002: Asynchrone Audit-Protokollierung & Partitionierung via Apache Kafka

- **Status:** Akzeptiert
- **Datum:** 2026-08-16
- **Kontext:**
  Jede Entscheidung (`ALLOWED` / `BLOCKED`) muss revisionssicher für Abrechnung und Analyse protokolliert werden. Ein synchrones Schreiben in eine Datenbank würde die API-Antwortzeit massiv verlangsamen und bei Datenbankausfällen zu kaskadierenden Systemabstürzen führen.
- **Evaluierte Optionen:**
  1. _Synchrones DB-Logging im Request-Pfad:_ Erzeugt 10–50ms Overhead und sabotiert den Schutz vor Überlastung.
  2. _In-Memory Java Queue:_ Nicht ausfallsicher; Daten gehen bei Server-Neustarts verloren.
  3. _Verteilter Event-Broker (Apache Kafka):_ Hoher Durchsatz, Persistenz auf Festplatte und Entkopplung von Producer und Consumer.
- **Entscheidung:** Option 3. Der Rate Limiter fungiert als Kafka-Producer und übergibt Audit-Events via Fire-and-Forget. Als Partition-Key wird der `api_key` verwendet.
- **Konsequenzen & Trade-offs:**
  - Sub-Millisekunden-Antwortzeiten im API-Gateway bleiben erhalten.
  - Der `api_key` als Partition-Key garantiert strikte chronologische Reihenfolge (FIFO) aller Events eines Mandanten innerhalb derselben Partition.
  - Eventual Consistency: Audit-Logs sind nicht sofort in PostgreSQL sichtbar, sondern mit minimalem Verarbeitungsverzug.

---

## ADR-003: Trennung von Rate-Limiter- und Audit-Consumer-Services (Separation of Concerns)

- **Status:** Akzeptiert
- **Datum:** 2026-08-16
- **Kontext:**
  Der Rate Limiter benötigt extrem geringe CPU-Last bei hohem I/O-Durchsatz. Der Audit-Consumer benötigt CPU- und Speicherressourcen für Batch-Inserts und Datenaufbereitung.
- **Entscheidung:**
  Aufteilung in zwei getrennte Prozesse:
  1. _Rate Limiter Service:_ Schlankes Gateway mit Fokus auf Latenz und Redis-Anbindung.
  2. _Audit Consumer Service:_ Hintergrund-Worker, der Kafka-Topics abarbeitet und Batches in PostgreSQL schreibt.
- **Konsequenzen & Trade-offs:**
  - Unabhängige Skalierung bei Lastspitzen.
  - Fehlerisolation: Ein Datenbankausfall oder Worker-Absturz beeinträchtigt die Verfügbarkeit des Rate Limiters nicht.
  - Geringfügig höherer administrativer Betriebsaufwand durch zwei Deployments.

---

## ADR-004: Resilienzstrategie – Fail-Open bei In-Memory-Ausfall

- **Status:** Akzeptiert
- **Datum:** 2026-08-16
- **Kontext:**
  Entscheidung über das Systemverhalten, wenn der Redis-Cluster temporär nicht erreichbar ist.
- **Evaluierte Optionen:**
  1. _Fail-Closed:_ Blockiert alle eingehenden Anfragen (HTTP 503 / 429).
  2. _Fail-Open:_ Lässt alle Anfragen durch und generiert einen Systemalarm (`CRITICAL_LOG`).
- **Entscheidung:** Option 2 (Fail-Open).
- **Konsequenzen & Trade-offs:**
  - Verfügbarkeit (Availability) hat Vorrang vor Ratenbegrenzung (Protection).
  - Verhindert einen Totalausfall der Geschäftsprozesse für reguläre Kunden bei Infrastrukturproblemen im Cache.
  - Temporär erhöhtes Risiko für ungedrosselten Traffic während des Ausfallfensters.

---

## ADR-005: Qualitätssicherung via Testcontainers und P99-Latenz-Benchmarking

- **Status:** Akzeptiert
- **Datum:** 2026-08-16
- **Kontext:**
  Mocks bilden das reale Verhalten von Concurrency-Effekten, Lua-Skripten und Kafka-Rebalancings nicht ab. Zudem sind Durchschnittswerte (Average Latency) bei Lasttests ungeeignet, da sie Ausreißer verschleiern.
- **Entscheidung:**
  - **Integrationstests:** Nutzung von **Testcontainers** zum Starten echter Redis-, Kafka- und PostgreSQL-Instanzen während des Test-Builds.
  - **Performance-Metriken:** Nutzung von **k6** mit Fokus auf die **P99-Latenz** (99 % aller Requests müssen unter dem SLA-Schwellenwert von 5ms liegen).
- **Konsequenzen & Trade-offs:**
  - 100 % realitätsnahe Testabdeckung ohne manuelle Infrastruktur-Einrichtung.
  - Längere Build-Zeiten in CI/CD-Pipelines durch Container-Starts.
  - Präzise Identifikation von Flaschenhälsen und Latenz-Spitzen unter Last.
