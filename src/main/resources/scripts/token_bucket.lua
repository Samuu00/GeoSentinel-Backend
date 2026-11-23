-- Script Lua eseguito da Redis per il rate limit con l'algoritmo token bucket (lo carica RedisRateLimiter).
-- Ogni chiave ha un secchio con al massimo "capacity" gettoni che si ricarica nel tempo; ogni richiesta
-- consuma dei gettoni. Lo script gira in modo atomico dentro Redis, quindi due istanze del backend non
-- possono leggere e scrivere lo stesso secchio nello stesso momento.
--
-- Parametri:
-- KEYS[1]: chiave Redis del secchio (per esempio rl:user:{id} oppure rl:ip:{ip})
-- ARGV[1]: capacity, numero massimo di gettoni accumulabili
-- ARGV[2]: refillPerSecond, gettoni aggiunti ogni secondo
-- ARGV[3]: requested, gettoni richiesti dalla chiamata (di solito 1)
--
-- Restituisce { permesso (1 o 0), gettoni rimasti, millisecondi da aspettare prima di riprovare }.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Ora del server Redis in millisecondi: è la stessa per tutte le istanze del backend, quindi non ci sono
-- problemi di orologi non sincronizzati.
local time = redis.call("TIME")
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Durata della chiave in secondi: il doppio del tempo necessario a riempire il secchio. Una chiave
-- inutilizzata per così tanto tempo sarebbe comunque piena, quindi si può lasciar scadere.
local fillTime = math.ceil(capacity / refillRate)
local ttl = fillTime * 2

local data = redis.call("HMGET", key, "tokens", "lastRefillTime")
local tokens = tonumber(data[1])
local lastRefillTime = tonumber(data[2])

-- Secchio nuovo: parte pieno. Altrimenti si aggiungono i gettoni maturati dall'ultima richiesta.
if tokens == nil then
    tokens = capacity
    lastRefillTime = now
else
    local elapsedSeconds = math.max(0, (now - lastRefillTime) / 1000)
    tokens = math.min(capacity, tokens + (elapsedSeconds * refillRate))
    lastRefillTime = now
end

-- Se i gettoni bastano la richiesta passa; altrimenti si calcola quanto aspettare per averne abbastanza.
if tokens >= requested then
    tokens = tokens - requested
    redis.call("HSET",key, "tokens", tokens, "lastRefillTime", lastRefillTime)
    redis.call("EXPIRE", key, ttl)
    return { 1, math.floor(tokens), 0 }
else
    local deficit = requested - tokens
    local retryAfterMillis = math.ceil((deficit / refillRate) * 1000)
    redis.call("HSET",key, "tokens", tokens, "lastRefillTime", lastRefillTime)
    redis.call("EXPIRE", key, ttl)
    return { 0, math.floor(tokens), retryAfterMillis }
end
