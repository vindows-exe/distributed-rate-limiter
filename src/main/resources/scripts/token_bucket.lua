-- src/main/resources/scripts/token_bucket.lua
-- Atomare Token-Bucket-Implementierung in Redis
-- ARGV[3] (now_ms) muss in Millisekunden übergeben werden

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local requested = tonumber(ARGV[4]) or 1

-- Einheitliche Zeitbasis in Sekunden (Fließkomma)
local now_sec = now_ms / 1000.0

-- 1. Zustand aus Redis abrufen
local data = redis.call("HMGET", key, "tokens", "last_refreshed")
local current_tokens = tonumber(data[1])
local last_refreshed = tonumber(data[2])

-- 2. Initialisierung (Kaltstart) oder Auffüllung (Lazy Refill)
if current_tokens == nil or last_refreshed == nil then
	current_tokens = capacity
	last_refreshed = now_sec
else
	local delta = math.max(0, now_sec - last_refreshed)
	local generated_tokens = delta * refill_rate
	current_tokens = math.min(capacity, current_tokens + generated_tokens)
	last_refreshed = now_sec
end

-- 3. Kapazitätsprüfung & Token-Abzug
local allowed = 0
local retry_after = 0

if current_tokens >= requested then
	allowed = 1
	current_tokens = current_tokens - requested
else
	allowed = 0
	local missing_tokens = requested - current_tokens
	retry_after = math.ceil(missing_tokens / refill_rate)
end

-- 4. Persistierung & TTL-Setzung
redis.call("HSET", key, "tokens", tostring(current_tokens), "last_refreshed", tostring(last_refreshed))
local ttl = math.max(math.ceil(capacity / refill_rate) * 2, 60)
redis.call("EXPIRE", key, ttl)

-- 5. Rückgabe: { allowed (1/0), verbleibende Tokens, Wartezeit in Sekunden }
return { allowed, math.floor(current_tokens), retry_after }
