-- Token bucket, atomic in Redis.
-- KEYS[1] bucket key; ARGV: capacity, refill tokens per second, cost
-- Time comes from the Redis server, so API instances with skewed clocks share one bucket correctly.
-- Returns {allowed (1/0), tokens left (floor), ms until one token is available}
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cost = tonumber(ARGV[3])

local state = redis.call('HMGET', KEYS[1], 't', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now
end

local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000.0)

local allowed = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
end

redis.call('HSET', KEYS[1], 't', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)

local wait = 0
if allowed == 0 then
  wait = math.ceil((cost - tokens) / rate * 1000)
end
return {allowed, math.floor(tokens), wait}
