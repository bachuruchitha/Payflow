-- Token bucket rate limiter, one atomic Redis round trip.
--
-- KEYS[1] = the bucket key, e.g. rate_limit:transfer:42
-- ARGV[1] = capacity        (max tokens, e.g. 10)
-- ARGV[2] = refillPerSecond (e.g. 0.1667)
-- ARGV[3] = now             (current time in ms)
--
-- Returns { allowed, tokensLeft } where allowed is 1 or 0 and tokensLeft is a
-- STRING -- Redis truncates Lua numbers to integers on the way out, and the
-- fractional part is the whole point of a lazy-refill bucket.
--
-- `now` is passed in rather than read from redis.call('TIME') on purpose: a script
-- that reads the clock is non-deterministic, which makes it unsafe to replicate
-- verbatim and unusable inside MULTI on older Redis. Passing it keeps the script a
-- pure function of its inputs. The cost is that the caller's clock decides the
-- refill, so all callers must use the same source of time.

local capacity        = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local now             = tonumber(ARGV[3])

-- 1. Read the stored bucket. Both fields come back as strings, or false when the
--    field (or the whole key) is missing.
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

-- 2. First ever request from this caller: start from a FULL bucket.
if tokens == nil or ts == nil then
  tokens = capacity
  ts     = now
end

-- 3. Lazy refill: credit whatever accrued since the last request, capped at capacity.
--    elapsed is clamped at 0 so a clock that jumps backwards (NTP correction, a
--    caller on a skewed host) can never drain the bucket by refilling it negatively.
local elapsedSeconds = (now - ts) / 1000
if elapsedSeconds < 0 then
  elapsedSeconds = 0
end
tokens = math.min(capacity, tokens + elapsedSeconds * refillPerSecond)

-- 4. Spend one token if there is one.
local allowed = 0
if tokens >= 1 then
  tokens  = tokens - 1
  allowed = 1
end

-- 5. Persist the new state. ts moves to now even on a rejected request -- the refill
--    above was already folded into tokens, so leaving ts behind would credit that
--    same elapsed time again on the next call.
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)

-- Idle buckets clean themselves up. The TTL is how long a fully drained bucket needs
-- to refill to capacity, plus a second of slack; expiring any earlier would hand the
-- caller a fresh full bucket and silently forgive the tokens they had spent.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refillPerSecond * 1000) + 1000)

-- 6. allowed as an integer, tokens as a string so the caller sees the fraction.
return { allowed, tostring(tokens) }
