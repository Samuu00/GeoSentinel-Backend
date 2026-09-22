-- KEYS[1]: Redis key (es. rl:user:{id} o rl:ip:{ip})
-- ARGV[1]: capacity (massimo numero di token accumulabili)
-- ARGV[2]: refillPerSecond (tasso di riempimento token al secondo)
-- ARGV[3]: requested (token richiesti per la chiamata, solitamente 1)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Orario del server Redis in millisecondi: unico per tutte le istanze dell'app, niente sfasamenti tra orologi
local time = redis.call("TIME")
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Calcolo TTL massimo per la chiave: tempo per riempire il bucket
local fillTime = math.ceil(capacity / refillRate)
local ttl = fillTime * 2

local data = redis.call("HMGET", key, "tokens", "lastRefillTime")
local tokens = tonumber(data[1])
local lastRefillTime = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    lastRefillTime = now
else
    local elapsedSeconds = math.max(0, (now - lastRefillTime) / 1000)
    tokens = math.min(capacity, tokens + (elapsedSeconds * refillRate))
    lastRefillTime = now
end

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