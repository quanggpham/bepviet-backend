# BepViet Backend

REST API for BepViet, a Vietnamese food-ordering storefront. Built with Spring Boot 3.5 and Java 21 on
PostgreSQL. It covers the full shopping flow (catalog, cart, coupons, checkout, Stripe and SePay
payments, order tracking), an admin back office, and an AI food-advisor chat backed by Gemini and
Pinecone.

The second half of this document records the reliability and performance work done on the codebase:
each problem was reproduced by an automated test before it was fixed, and every number below comes
from a real run.

## Contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Testing](#testing)
- [Reliability and concurrency](#reliability-and-concurrency)
- [Performance](#performance)
- [AI food advisor: current state](#ai-food-advisor-current-state)
- [Project structure](#project-structure)
- [API overview](#api-overview)

## Features

**Accounts and security**
- JWT authentication with `USER` and `ADMIN` roles
- Password reset by e-mailed code (`SecureRandom`, limited verification attempts)
- Rate limiting on login, password reset and the AI chat (HTTP 429 with `Retry-After`)

**Shopping**
- Product catalog with name, category and price filters, pagination and sorting
- Cart, product likes, reviews and ratings with moderation
- Coupons scoped to the whole order, a category or a product, with usage limits and one use per customer

**Orders and payments**
- Order lifecycle `PENDING` → `CONFIRMED` → `SHIPPING` → `COMPLETED`, or `CANCELLED`
- Cash on delivery, Stripe Checkout and SePay bank transfer
- Idempotent Stripe and SePay webhooks
- Unpaid Stripe orders expire automatically and release their stock and coupon

**Administration**
- Revenue statistics and top products
- Product, category, banner, promotion and coupon management; Excel product import
- Image upload to Cloudinary; review moderation

**Notifications**
- HTML order-confirmation and password-reset e-mails (Thymeleaf, sent asynchronously)
- Scheduled abandoned-cart reminders

**AI**
- Food-advisor chat (`/api/v1/ai/chat`, streaming variant `/api/v1/ai/chat/stream`) that retrieves
  matching dishes from Pinecone and answers with Gemini. See [current state](#ai-food-advisor-current-state).

## Tech stack

| Area | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.5 |
| Persistence | PostgreSQL 17, Spring Data JPA, Hibernate 6 |
| Security | Spring Security, JWT (jjwt) |
| Caching | Spring Cache with Caffeine |
| AI | Spring AI 1.1, Google Gemini (chat and embeddings), Pinecone |
| Payments | Stripe, SePay |
| Integrations | Cloudinary, Spring Mail, Thymeleaf |
| Mapping | MapStruct, Lombok |
| Testing | JUnit 5, Spring MockMvc, Testcontainers, k6 |
| Packaging | Docker multi-stage build |

Design notes:
- Layered controller → service → repository structure with request/response DTOs mapped by MapStruct
- Centralised error handling in a `@RestControllerAdvice`
- JPA Specifications for dynamic product and review filters
- Application events for asynchronous review-statistics updates
- Stock, coupon usage and order-state changes are made with conditional updates and row locks instead
  of read-modify-write, so they stay correct under concurrent requests

## Getting started

Prerequisites: JDK 21 and Docker (Docker Desktop on Windows/macOS).

```bash
docker compose up -d            # PostgreSQL 17 on localhost:5433, database "ecom"
```

Set the variables listed under [Configuration](#configuration), then:

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

The API listens on port 8080. On an empty database the schema is created by Hibernate and a seeder
adds an admin account, sample customers, three categories, fifteen products, coupons and sample orders.
The seeded admin uses a well-known password; change it before exposing the instance.

Endpoint reference: [docs/API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md).
Production deployment steps: [docs/DEPLOY.md](docs/DEPLOY.md).

## Configuration

All secrets are read from environment variables. Required variables:

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL connection (JDBC URL) |
| `JWT_SECRET` | Base64-encoded HMAC key for signing tokens |
| `cloudinary-cloud-name`, `cloudinary-api-key`, `cloudinary-api-secret` | Image uploads |
| `stripe_secret_key`, `stripe_publishable_key`, `stripe_webhook_secret` | Stripe Checkout and webhook signature |
| `gemini_api_key` | Gemini chat and embeddings |
| `pinecone_api_key` | Pinecone vector store (index `food-menu`) |

Optional variables:

| Variable | Default | Purpose |
|---|---|---|
| `RATE_LIMIT_TRUSTED_PROXIES` | `0` | Number of reverse proxies that append to `X-Forwarded-For`. Set to `1` behind a single proxy (for example Render); with `0` every client behind the proxy shares one rate-limit bucket. |
| `SEPAY_WEBHOOK_API_KEY` | empty | API key SePay sends with webhooks |
| `SEPAY_SECRET_KEY` | empty | SePay merchant secret |
| `SEPAY_BANK_NAME`, `SEPAY_BANK_BIN`, `SEPAY_BANK_ACCOUNT`, `SEPAY_ACCOUNT_NAME` | empty | Bank details shown for transfers |
| `email`, `password` | empty | SMTP account for outgoing mail |
| `RESEND_API_KEY`, `RESEND_FROM`, `RESEND_TEST_RECIPIENT` | empty | Resend e-mail provider |
| `SERVER_PORT` | `8080` | HTTP port |

Tunable application properties (defaults shown):

| Property | Default | Meaning |
|---|---|---|
| `app.order-expiry.ttl-minutes` | `60` | Unpaid Stripe orders older than this are cancelled |
| `app.order-expiry.fixed-delay-ms` | `60000` | How often the expiry job runs |
| `app.rate-limit.login.max-failures` | `5` | Failed logins per IP and e-mail before 429 |
| `app.rate-limit.ai.per-ip-per-minute` | `10` | Chat requests per client per minute |
| `app.rate-limit.ai.daily-budget` | `1000` | Chat requests per instance per day |

## Testing

Integration tests boot the full application against a throwaway PostgreSQL container
(Testcontainers), so Docker must be running. No API keys or local database are needed: external
services receive dummy values and are never called.

```bash
./mvnw test                                        # full suite, 249 tests
./mvnw test -Dtest=OrderConcurrencyTest            # prints [CONCURRENCY] lines
./mvnw test -Dtest=OrderCancellationConcurrencyTest   # prints [LIFECYCLE] lines
./mvnw test -Dtest=ProductQueryCountTest           # prints [N+1] lines
./mvnw test -Dtest=CatalogCacheTest                # prints [CACHE] lines
```

Tests for race conditions release all threads at the same instant with a `CountDownLatch` and are
repeated 30 times (`@RepeatedTest`).

## Reliability and concurrency

Each problem below was first reproduced by a test that failed on the original code, then fixed with
the smallest change that made it pass. Environment: PostgreSQL 17 (Testcontainers `postgres:17-alpine`,
HikariCP pool of 10), Windows 11, JDK 21.

### 1. Authorization and input validation (`SecurityHardeningTest`)

| Request | Before | After |
|---|---|---|
| `USER` creates, updates or deletes a product | `201` / `200` | `403` |
| Checkout with `quantity: -1` | `201`, total reduced, stock increased | `400`, stock unchanged |
| Checkout with an empty body | `500` | `400` |
| Add to cart with `quantity: -5` | `200` | `400` |
| Wrong password or unknown e-mail | `500` | `401` |
| Malformed JWT | `500` | `401` |
| No token on a protected endpoint | `403` | `401` |

Fix: role rules for product writes, `@Valid` request bodies, authentication failures mapped to 401,
invalid tokens treated as anonymous.

### 2. Overselling stock and coupons (`OrderConcurrencyTest`)

| Scenario | Before (10 of 10 runs failed) | After (30 of 30 runs passed) |
|---|---|---|
| 50 buyers, stock 10 | 50 orders accepted, final stock 3–4 (lost updates) | 10 orders, final stock 0 |
| Coupon limited to 5 uses, 50 buyers | redeemed 42–46 times | redeemed 5 times |
| Same customer, same coupon, 10 parallel checkouts | redeemed 10 times | redeemed once |
| 10 cancellations and 20 new orders at once (expected stock 30) | final stock 35–40 | 30 |
| 50 two-item orders in opposite product order | 6–16 of 50 succeeded; 351 `deadlock detected` errors over 10 runs* | 50 of 50 succeeded, no deadlocks |

\* Written after the fix; its "before" was measured by running it against the original code.

Fix: stock and coupon usage change through a single conditional `UPDATE … WHERE stock >= :qty`
(`… used_count < usage_limit`), and the affected-row count decides success. Rows are updated in
ascending product id order to prevent deadlocks, and a unique `(coupon_id, user_id)` constraint
enforces one use per customer. Pessimistic locking and optimistic `@Version` with retries were
considered; the conditional update needs no retry loop and holds row locks for the shortest time.

### 3. Stripe webhook idempotency (`StripeWebhookTest`)

Payloads are signed with the webhook secret exactly as Stripe signs them.

| Scenario | Before | After |
|---|---|---|
| Same event delivered 10 times concurrently | `1×200, 9×500` (10 of 10 runs) | `10×200`, one payment row (30 of 30 runs) |
| Two event types for one paid order, 10 deliveries | 10 confirmation e-mails | 1 e-mail |
| Paid event for a cancelled order | order becomes `CONFIRMED` | stays `CANCELLED` |
| Paid event for a cash-on-delivery order | order becomes `CONFIRMED` | stays `PENDING` |
| `amount_total` differs from the order total | order becomes `CONFIRMED` | stays `PENDING` |
| `payment_status: unpaid` | order becomes `CONFIRMED` | stays `PENDING` |

Fix: every event id is recorded with `INSERT … ON CONFLICT DO NOTHING` inside the handling
transaction, and the order row is locked (`SELECT … FOR UPDATE`) while the `PENDING → CONFIRMED`
transition is checked.

### 4. Rate limiting (`RateLimitingTest`)

| Scenario | Before | After |
|---|---|---|
| 6 wrong passwords, then the correct one | `401 ×6`, then `200` | `401 ×5`, then `429` with `Retry-After` |
| 6 wrong passwords from another IP, then the real user | `200` | `200` (the real user is not locked out) |
| Forgot-password for an unknown vs. a known e-mail | `400` vs. `200` (reveals accounts) | `200` vs. `200` |
| 50 parallel wrong reset codes, then the real code | all 50 evaluated, real code accepted (10 of 10 runs) | 5 evaluated, real code refused (30 of 30 runs) |
| 12 chat requests from one client within a minute | `200 ×12` | `200 ×10`, `429 ×2` |
| 40 chat requests from different clients, daily budget 30 | 40 answered | 30 answered, 10 refused |
| Chat message of 2,000 characters | `200` | `400` |

Fix: an in-memory sliding-window limiter (no new dependency), configurable under `app.rate-limit.*`.
Reset codes come from `SecureRandom` and allow five attempts, counted with a conditional `UPDATE` so
parallel guesses cannot exceed it. Limits are kept per instance.

### 5. Order cancellation, expiry and payment races (`OrderCancellationConcurrencyTest`, `SepayWebhookTest`, `OrderExpiryTest`)

| Scenario (30 repetitions each) | Before | After |
|---|---|---|
| 10 concurrent cancellations of one order | all 10 succeeded; stock restored to 37 instead of 10 | 1 succeeds; stock 10 |
| Cancelled order gives its coupon use back | coupon stays "used up" | coupon can be used again |
| Customer cancels while the Stripe webhook confirms payment | 15 of 30 runs ended `CANCELLED` with the payment captured | 30 of 30 consistent (19 cancelled, 11 confirmed) |
| Expiry job runs while the Stripe webhook confirms payment | job did not exist | 30 of 30 consistent (21 cancelled, 9 confirmed) |
| Same SePay transfer delivered 10 times concurrently | `1×200, 9×500` | `10×200`, one payment row, one e-mail |
| SePay transfer for a cancelled order | order becomes `CONFIRMED` | stays `CANCELLED`, logged for manual refund |
| Unpaid Stripe order, two hours old | stays `PENDING`, holds stock | cancelled by the job, stock released |
| Stripe checkout for a cancelled or nearly expired order | `500` | `400` |

The cancel-versus-webhook "before" figure was measured without random start jitter; the "after"
figures use 0–20 ms jitter so that both orderings occur.

Fix: every path that changes an order's status (customer cancel, expiry job, Stripe and SePay
webhooks) first locks the order row and re-checks that it is still `PENDING`. Cancelling releases
stock and the coupon use in the same transaction. Unpaid Stripe orders are cancelled after 60 minutes
by a scheduled job; Stripe Checkout sessions expire after 31 minutes and are only created while the
order has at least 35 minutes left, so a session cannot outlive its order. Whether Stripe actually
refuses an expired session was not tested, because it requires the live Stripe API.

### 6. Hidden products (`InactiveProductVisibilityTest`)

| Scenario | Before | After |
|---|---|---|
| Hidden product in the public list (anonymous or signed-in shopper) | listed | not listed |
| Admin hides a product that is already in the cached list | still listed | removed immediately |
| Hidden product detail page | `200` | `404` (admins still get `200`) |
| Checkout of a hidden product | `201`, stock reduced | `400`, stock unchanged |

Admins still see hidden products in the list so they can make them visible again. Rows with a `NULL`
flag are treated as visible.

## Performance

### Query count (N+1)

The product list loaded each product's category, like count, the viewer's like and the review
statistics with separate queries. Counted with Hibernate statistics (`ProductQueryCountTest`):

| Endpoint | Before | After |
|---|---|---|
| `GET /api/v1/products`, page of 2 vs. 10 products | 11 vs. 43 SQL statements | 6 vs. 6 |
| `GET /api/v1/products/liked`, 2 vs. 10 likes | 11 vs. 35 SQL statements | 5 vs. 5 |

Fix: the category is fetched with the page query (`@EntityGraph`); like counts, the viewer's likes and
review statistics are loaded once per page with `IN (…)` queries.

### Caching

Public reads are cached in-process with Caffeine (`CatalogCacheTest`):

| Cache | Time to live | Evicted when |
|---|---|---|
| Categories | 10 minutes | an admin creates, renames or deletes a category |
| Active banners | 60 seconds | an admin changes a banner |
| Anonymous product list pages | 30 seconds (max 1,000 entries) | an admin changes a product or category, or imports products |

A repeated request for any of the three is served with 0 SQL statements (before: 1, 1 and 3).
Signed-in product lists are not cached because they contain the viewer's own likes. Evictions run
after the transaction commits, and entries cached while the startup seeder runs are discarded when
it finishes.

### Load test

k6 against 5,000 products, 20 categories and 10,000 likes; 50 virtual users, 15 s warm-up, then 60 s
measured. Traffic mix: 55% random product pages, 15% category-filtered pages, 15% categories,
15% banners. Two interleaved rounds per build, no failed requests.

| Build | Requests/s | p50 | p95 | p95 of `/products` |
|---|---|---|---|---|
| Before (N+1, no cache) | 93 / 101 | 541 / 498 ms | 687 / 634 ms | 717 / 662 ms |
| Batched queries | 706 / 761 | 67 / 63 ms | 117 / 101 ms | 121 / 104 ms |
| Batched queries + cache | 7,233 / 7,538 | 5.0 / 4.9 ms | 12.1 / 11.5 ms | 12.5 / 11.9 ms |

Before the fix even the categories endpoint, which has no N+1 problem, had a p95 of about 500 ms,
because product requests held all ten database connections.

Limitations of these numbers:
- The application, PostgreSQL (in Docker) and k6 ran on the same Windows machine (16 logical CPUs,
  16 GB RAM) and competed for it. Compare the builds with each other; absolute values will differ on
  a server.
- The scenario uses about 70 distinct URLs, so the cache hit rate is very high. Traffic with many
  distinct searches would benefit less.
- Anonymous visitors may see stock, like counts and ratings up to 30 seconds old. This cannot cause
  overselling, because checkout reserves stock with a conditional update. Scheduled banners can
  appear or disappear up to 60 seconds late.
- Caches live in each instance's memory. With several instances, an admin change evicts only the
  instance that handled it; the others catch up when their entries expire.

### Re-running the load test

```bash
docker run -d --name bepviet-perf-pg -e POSTGRES_PASSWORD=perf -e POSTGRES_DB=bepviet -p 55432:5432 postgres:17-alpine
./mvnw -DskipTests package

# Terminal 1: start the app against this database (Hibernate creates the schema, the seeder runs).
# SQL logging is switched off so that it does not dominate the measurement.
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/bepviet SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=perf java -jar target/demo-*.jar --spring.jpa.show-sql=false \
  --logging.level.org.hibernate.SQL=INFO --logging.level.org.hibernate.type.descriptor.sql.BasicBinder=INFO
# (the remaining required variables from Configuration can be dummy values)

# Terminal 2, once the app is up:
docker exec -i bepviet-perf-pg psql -U postgres -d bepviet < perf/seed-5k.sql
k6 run -e DURATION=15s perf/catalog.js                  # warm-up
k6 run -e DURATION=60s -e VUS=50 perf/catalog.js        # measured run
```

To measure an older build, check it out in a separate `git worktree`, build it there and start that
jar against the same database.

### Reproducing a "before" number

Check out the fix commit in a separate worktree, restore the production code from its parent and run
the test that came with the fix:

```bash
git worktree add ../bepviet-before <fix-commit>
cd ../bepviet-before
git checkout <fix-commit>^ -- src/main
./mvnw test -Dtest=OrderConcurrencyTest
cd - && git worktree remove --force ../bepviet-before
```

`RateLimitingTest` does not compile against the pre-fix code (it resets the limiter between tests),
so its "before" column was recorded before that hook existed.

## AI food advisor: current state

The chat endpoint embeds the question with `gemini-embedding-001`, retrieves the top 4 dishes from
Pinecone with a similarity threshold of 0.3, and asks `gemini-2.5-flash-lite` to answer from them.
Products are indexed by an opt-in startup job (`app.ai.init-data=true`).

What exists today:
- The endpoint, streaming variant and per-client and daily rate limits described above.

What does not exist yet:
- No evaluation of answer or retrieval quality has been done: there is no golden question set and no
  hit-rate or latency measurement.
- Vectors are written with random ids, so re-indexing duplicates entries, and product edits or
  deletions are not propagated to Pinecone.
- No metrics are recorded for retrieval time, model latency or token usage.
- A Pinecone failure is logged and treated as "no matching dishes", so the chat still answers with
  HTTP 200 instead of reporting the error.

These are the next planned work items.

## Project structure

```
src/main/java/com/example/demo/
├── config/        Security, cache, AI, Cloudinary, OpenAPI configuration; startup seeder
├── controller/    REST controllers (public and /api/v1/admin)
├── dto/           Request DTOs with validation, response DTOs
├── entity/        JPA entities
├── enums/         Roles, order and payment states
├── event/         Application events
├── exception/     Exception types and global handler
├── mapper/        MapStruct mappers
├── repository/    Spring Data repositories, specifications, projections
├── security/      JWT filter, rate limiter, client IP resolution
├── service/       Business logic and scheduled jobs
└── utils/
src/test/java/com/example/demo/   Integration tests (Testcontainers)
perf/                             k6 scenario and load-test seed data
docs/                             API documentation
```

## API overview

| Module | Base path | Notes |
|---|---|---|
| Authentication | `/api/v1/auth` | Register, login, forgot and reset password |
| Profile and addresses | `/api/v1/profile`, `/api/v1/addresses` | Profile, shipping addresses |
| Products | `/api/v1/products` | Search, detail, likes; admin create, update, delete, Excel import |
| Categories | `/api/v1/categories` | Public read; admin write |
| Cart | `/api/v1/carts` | Add, update, remove, clear |
| Orders | `/api/v1/orders`, `/api/v1/admin/orders` | Checkout, history, detail, cancel; admin status updates |
| Payments | `/api/v1/payments` | Stripe Checkout, SePay, webhooks |
| Coupons | `/api/v1/coupons` | Validate and calculate; admin management |
| Reviews | `/api/v1/reviews`, `/api/v1/admin/reviews` | Submit, list, moderate, reply |
| Banners | `/api/v1/banners`, `/api/v1/promotion-banners`, `/api/v1/admin/...` | Storefront and promotion banners |
| Users | `/api/v1/users` | Admin user management |
| Media | `/api/v1/media` | Image upload |
| Statistics | `/api/v1/admin/statistics` | Revenue, top products, overview |
| AI chat | `/api/v1/ai/chat`, `/api/v1/ai/chat/stream` | Food advisor |

## License

Educational and portfolio project.
