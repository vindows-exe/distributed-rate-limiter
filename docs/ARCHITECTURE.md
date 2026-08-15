### Übersicht und Entwurfsprinzipien

Das System folgt einer zweistufigen Architektur

1. Synchrone Fast Path Entscheidungen, durch In Memory Token Bucket Prüfung in Redis die geringste Latenz erhalten (unter 5ms)
2. Asynchroner Event Driven Audit Path, vollständige Entkopplung der Audit Persistierung über Apache Kafka und PostgreSQL (Transactional Output Pattern)

### C4 - Level 1: System Context

```mermaid
flowchart TD
    Client["API Client<br/><i>(Konsument)</i>"]
    RateLimiter["Distributed Rate Limiter<br/><i>(Core Gateway)</i>"]
    TargetAPI["Downstream API<br/><i>(Geschützter Fachservice)</i>"]
    AuditStorage[("Audit & Compliance Storage<br/><i>(PostgreSQL)</i>")]

    Client -->|"1. HTTPS Request (mit API-Key)"| RateLimiter
    RateLimiter -->|"2a. Weiterleitung (wenn ALLOWED)"| TargetAPI
    RateLimiter -->|"2b. HTTP 429 (wenn BLOCKED)"| Client
    RateLimiter -.->|"3. Asynchrone Audit-Events"| AuditStorage

	subgraph External["Client & Downstream"]
		Client["API Client"]
		TargetAPI["Target Service API"]
	end

	subgraph SystemBoundary["Distributed Rate Limiting Infrastructure"]
        LimiterService["Rate Limiter Service - Java Spring Boot"]
        RedisDB[("Redis State Store - In-Memory")]
        KafkaBus["Apache Kafka - Event Streaming"]
        AuditService["Audit Consumer Service - Java Spring Boot"]
        PostgresDB[("PostgreSQL Storage - Audit DB")]
    end

	Client -->|"1. HTTP Request (X-API-Key)"| LimiterService
    LimiterService <-->|"2. Atomare Token-Prüfung (Lua)"| RedisDB
    LimiterService -->|"3a. Forward Request (ALLOWED)"| TargetAPI
    LimiterService -->|"3b. Return HTTP 429 (BLOCKED)"| Client
    LimiterService -.->|"4. Produce AuditEvent"| KafkaBus
    KafkaBus -.->|"5. Consume Event"| AuditService
    AuditService -->|"6. Batch Insert Log"| PostgresDB
```

### Kernkomponenten & Algorithmen

#### Token Bucket via Redis Lua-Script

Um Race Conditions bei parallelen Anfragen auszuschließen, läuft die Berechnung atomar in Redis:

- Key Schema: `rateLimit{api_key}`(Hash mit Feldern `tokens`und `last_updated`)
- Logik: Dynamischer Refill basierend auf der Zeitdifferenz vor dem Token Abzug

#### Asynchrones Audit Logging

- Der synchrone Request Pfad wartet nicht auf relationale Datenbank Schreibvorgänge
- Events werden mit dem Partitionierungs Key `api_key`an Kafka übergeben, um die chronologische Reihenfolge pro Client zu sichern

#### Resilienz Strategie (Fail-Open)

- Bei Nicht-Erreichbarkeit von Redis greift ein Circuit Breaker: Anfragen werden durchgelassen, um Kaskadeneffekte in der Infrastruktur zu vermeiden
- Ein System Alarm (Critical) wird geloggt
