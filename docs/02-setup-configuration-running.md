# 02 — Setup, Configuration & Running

## 2.1 Prerequisites

| Tool | Why |
|---|---|
| JDK 21 | The project targets Java 21 |
| Docker Desktop | Runs PostgreSQL, Redis and Kafka (`docker-compose.yml`). Also needed by Testcontainers tests |
| (Optional) IntelliJ IDEA | The repo contains `.idea/` settings |

You don't need Maven installed. Use the wrapper: `./mvnw` (Git Bash / macOS / Linux) or `mvnw.cmd` (PowerShell / cmd).

## 2.2 Infrastructure: `docker-compose.yml`

`docker compose up -d` starts three containers:

| Service | Image | Port | Purpose in PayFlow |
|---|---|---|---|
| `postgres` (`payflow-postgres`) | `postgres:16` | 5432 | Main database. User/password/db are all `payflow`. `TZ`/`PGTZ` pinned to UTC. Data persists in the named volume `payflow_pgdata` |
| `redis` (`payflow-redis`) | `redis:7.4` | 6379 | Rate-limit buckets and the derived-balance cache. No persistence configured (data lost on restart, which is acceptable for both uses) |
| `kafka` (`payflow-kafka`) | `apache/kafka:3.8.0` | 9092 | Event bus for `transaction-events` |

### Kafka settings

The Kafka service runs in **KRaft mode** (no ZooKeeper). One node is both the *broker* and the *controller*:

- `KAFKA_PROCESS_ROLES: broker,controller`: this single node does both jobs.
- `KAFKA_LISTENERS`: port 9092 for clients (`PLAINTEXT`), port 9093 for the internal controller quorum.
- `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092`: the address the broker tells clients to connect to. It
  says `localhost`, so it works for an app running **on your laptop**. It would **not** work for another container, which
  would need the service name `kafka:9092`.
- Replication factors of `1`: only safe with a single broker. Fine for local development, never for production.

Topic `transaction-events` is not created explicitly. The broker's default `auto.create.topics.enable=true` creates it on
first use.

### Useful commands

```bash
docker compose up -d                 # start everything
docker compose ps                    # check status
docker compose down                  # stop (data kept)
docker compose down -v               # stop AND wipe the Postgres volume (fresh DB)
docker exec -it payflow-postgres psql -U payflow -d payflow     # SQL shell
docker exec -it payflow-redis redis-cli                         # Redis shell (try: KEYS rate_limit:*)
docker exec -it payflow-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic transaction-events --from-beginning   # watch events
```

## 2.3 Configuration: `application.yaml` explained

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 1s           # command timeout, and the handshake bound
      connect-timeout: 1s   # how long to wait for the socket
```

- **`timeout: 1s`** is a deliberate, documented decision (see `DECISIONS.md`, "fails open"). Lettuce's default is **60 s**.
  With a fail-open rate limiter, a hung Redis would stall every transfer for a minute before being bypassed. 250 ms was
  tried first and was **too tight**: it also bounds the connection handshake, so healthy cold connections timed out and the
  limiter silently stopped limiting. 1 s leaves room for the handshake and is still 60× better than the default.

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow
    username: payflow
    password: payflow
```

- These match the docker-compose credentials. Tests that use Testcontainers override them via `@DynamicPropertySource`.

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
```

- **`ddl-auto: validate`**: Hibernate **never** creates or alters tables. At startup it checks that entities match the
  schema Flyway built, and refuses to start if they don't. The SQL migrations are the only source of the schema.
- **`show-sql: true`**: prints every SQL statement to stdout. Useful for learning (you can watch `FOR UPDATE` and
  `… AND version = ?` appear), but it slows concurrency tests down (stdout is synchronised). The benchmark test turns it off.

```yaml
  flyway:
    enabled: true
logging:
  level:
    org.flywaydb: DEBUG
```

- Flyway runs pending migrations at startup, before Hibernate validation.

```yaml
payflow:
  jwt:
    secret: ${JWT_SECRET:ixpXEOCg...=}
    expiration-ms: 3600000   # 1 hour
  ratelimit:
    capacity: 10
    refill-per-second: 0.16666666667    # 10 per minute
```

- **`payflow.jwt.secret`**: base64-encoded HMAC key. `${JWT_SECRET:default}` means "use env var `JWT_SECRET` if set,
  otherwise the default". **The default is committed to git**, so anyone can forge tokens for a deployment that doesn't set
  the env var (chapter 17).
- **`payflow.jwt.expiration-ms`**: token lifetime, 1 hour.
- **`payflow.ratelimit.*`**: token bucket settings. Burst of 10 transfers, then 1 every 6 s (chapter 10).

### What is *not* configured (Spring Boot defaults apply)

| Area | Default in effect |
|---|---|
| HTTP port | `8080` |
| Kafka bootstrap servers | `localhost:9092` |
| Kafka (de)serializers | `StringSerializer` / `StringDeserializer` for key and value; JSON is produced and parsed by hand |
| Kafka consumer offset reset | `latest` (a brand-new consumer group only sees messages sent after it joins) |
| Kafka listener error handling | Spring Kafka `DefaultErrorHandler`: up to 10 delivery attempts, then log and skip the record |
| DB connection pool | HikariCP, max 10 connections (this limit matters in the locking benchmark, chapter 08) |
| Scheduler | One thread (`@EnableScheduling` with the default single-threaded scheduler) |

## 2.4 Running the application

```bash
docker compose up -d
./mvnw spring-boot:run            # PowerShell: .\mvnw.cmd spring-boot:run
```

At startup you should see, in order:
1. Flyway validates and applies `V1`–`V9` (DEBUG logs).
2. Hibernate validates the entities against the schema.
3. Kafka consumer `notification-service` joins the group and gets `transaction-events` assigned.
4. `Tomcat started on port 8080`.
5. Every 5 s, `OutboxPublisher` runs a `select … from outbox_events where is_published = false …` (visible because `show-sql` is on).

Health check: `curl http://localhost:8080/health` → `OK`.

Swagger UI: <http://localhost:8080/swagger-ui.html>. Click **Authorize** and paste the JWT (without the word `Bearer`).
Every endpoint then sends it. This works because `OpenApiConfig` declares a global `bearerAuth` scheme.

## 2.5 End-to-end walkthrough with curl

```bash
# 1. Register two users (each gets a USD wallet with balance 0)
curl -s -X POST localhost:8080/api/users/register -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"secret"}'
curl -s -X POST localhost:8080/api/users/register -H 'Content-Type: application/json' \
     -d '{"email":"bob@example.com","password":"secret"}'

# 2. Log in as Alice and keep the token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"secret"}' | sed 's/.*"jwtToken":"\([^"]*\)".*/\1/')

# 3. Alice's wallet
curl -s localhost:8080/api/wallets/me -H "Authorization: Bearer $TOKEN"

# 4. Top up 500
curl -s -X POST localhost:8080/api/wallets/me/topup -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"amount": 500}'

# 5. Get Bob's WALLET id (log in as Bob, GET /api/wallets/me), then transfer 100 to it.
#    Idempotency-Key is REQUIRED; reuse the same key to safely retry.
curl -s -X POST localhost:8080/api/transfers -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: order-001' \
     -d '{"toWalletId":"<bob-wallet-uuid>","amount":100}'

# 6. History, newest first
curl -s 'localhost:8080/api/transactions?page=0&size=10&sort=createdAt,desc' -H "Authorization: Bearer $TOKEN"
```

Within about 5 seconds of step 5, check the event pipeline:

```sql
select * from outbox_events order by created_at desc limit 1;   -- is_published flips to true
select * from notifications   order by created_at desc limit 1; -- "Transfer <id> completed"
select * from processed_events order by processed_at desc limit 1;
```

> Note that transfers take a **wallet id** as the destination, not an email or user id. There is no "look up a wallet by
> email" endpoint yet, so the receiver has to share their wallet id.

## 2.6 Running the tests

```bash
docker compose up -d               # most tests use the local Postgres/Redis, see chapter 16
./mvnw test                        # everything
./mvnw test -Dtest=ConcurrentTransferTest
./mvnw test -Dtest=LockingStrategyComparisonTest    # prints the benchmark table (lines starting with COMPARE)
./mvnw test -Dtest=RateLimiterTest
```

Details of what each test needs and proves: chapter 16.

## 2.7 Windows gotcha: the timezone alias

On Windows the JVM may report the timezone as a legacy alias such as `Asia/Calcutta`. The PostgreSQL JDBC driver forwards it to the
server, and PostgreSQL 16 rejects it (`FATAL: invalid value for parameter "TimeZone"`), so every connection fails, Flyway's
first one included. The Testcontainers tests work around it with `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` in a
static block. If you hit this when running the app itself, start it with `-Duser.timezone=UTC`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Duser.timezone=UTC"
```
