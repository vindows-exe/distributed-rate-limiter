### Systemübersicht

Ein verteilter, hochperformanter Rate-Limiting Service zum Schutz von APIs vor Missbrauch, Überlastung und unkontrollierbaren Lastspitzen

### Funktionale Anforderungen

1. Das System muss anhand von verschiedenen Merkmalen die Anfragen eindeutig identifizieren (HTTP-Header, Client IP)
2. Token Bucket Algorithmus zur Ratenbegrenzung muss implementiert werden. Standardkonfiguration: 10 Tokens Kapazität, 5 Tokens pro Sekunde Refill Rate
3. Entscheidungslogik: Wenn Tokens verfügbar sind, wird die Anfrage zugelassen (200 OK), die Token werden dann dekrementiert
   Wenn keine Tokens verfügbar sind, wird die Anfrage abgelehnt (429 Too Many Requests)
4. Standard Header: Jede Antwort muss einen Standard Header enthalten
   - 'X-RateLimit-Limit': Maximale Anzahl erlaubter Requests im Zeitfenster
   - 'X-RateLimit-Remaining': Verbleibende Anzahl Tokens
   - 'X-RatelimitReset': Zeit in Sekunden bis zum vollständigen Refill
5. Jede Entscheidung (Allowed / Blocked) muss als Event asynchron an Apache Kafka gesendet werden (Asynchronous Audit Logging)

### Nicht-Funktionale Anforderungen

1. Latenz: Der zusätzliche Overhead durch Rate-Limiter Prüfung darf im P99-Perzentil unter 5 Millisekunden liegen
2. Verteilter Zustand: Der Zählerzustand wird zentral in Redis gehalten, damit mehrere Instanzen des Rate-Limiters denselben Zustand teilen
3. Concurrency & Atomizität: Zählerprüfungen und aktualisierungen müssen atomar, mit Lua Scripting, erfolgen, um Race Conditions auszuschließen
4. Resilienz & Fail Open: Ist Redis temporär nicht erreichbar, schaltet das System in den Modus "Fail-Open" (Anfragen durchlassen, Fehler loggen). Damit wird der Ausfall des Gesamtsystems verhindert

### Ziel Infrastruktur

- Backend: Java (Spring Boot)
- State Store: Redis
- Event Bus: Apache Kafka
- Persistenz: PostgreSQL für Audit Logs
