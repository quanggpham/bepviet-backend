# 🛒 E-Commerce Backend API

A full-featured **RESTful backend** for an e-commerce platform (food delivery), built with **Spring Boot 3** and **Java 21**. This project demonstrates a production-ready architecture with JWT authentication, online payment integration, AI-powered product recommendations, and comprehensive admin management features.

> **Live Demo:** [bepviet.io.vn](https://www.bepviet.io.vn)

---

## ✨ Key Features

### 🔐 Authentication & Security
- JWT-based authentication with Spring Security
- Role-based access control (`USER`, `ADMIN`)
- Password reset via email with token-based verification

### 🛍️ Shopping Experience
- Product catalog with advanced search & filtering (name, category, price range)
- Pagination & sorting support across all list endpoints
- Shopping cart management (add, update, remove, clear)
- Product wishlist / like system
- Product review & rating system with moderation and rate limiting

### 📦 Order Management
- Full order lifecycle: `PENDING` → `CONFIRMED` → `SHIPPING` → `COMPLETED` / `CANCELLED`
- Multiple payment methods: COD, Stripe, SePay (Vietnamese bank transfer)
- Stripe Checkout integration with webhook verification
- Coupon / discount code system (order-wide, category-specific, or product-specific)
- Order confirmation emails with HTML templates (Thymeleaf)

### 🤖 AI-Powered Features
- AI product recommendation using **Spring AI** + **Google Gemini**
- Vector-based semantic search via **Pinecone** vector store
- Food advisor chatbot for personalized suggestions

### 📊 Admin Dashboard APIs
- Revenue statistics & analytics (overview, revenue by date, top products)
- Order management & status updates
- Product CRUD with image upload (Cloudinary)
- Banner & promotion banner management
- Coupon management with business rule validation
- Review moderation (approve, reject, reply)

### 📧 Email & Notifications
- Transactional emails: order confirmation, password reset
- Abandoned cart reminder (scheduled background job)
- Async email sending for non-blocking performance

---

## 🏗️ Tech Stack

| Layer              | Technology                                        |
|--------------------|---------------------------------------------------|
| **Framework**      | Spring Boot 3.5, Java 21                          |
| **Security**       | Spring Security, JWT (jjwt 0.11.5)                |
| **Database**       | PostgreSQL, Spring Data JPA, Hibernate             |
| **AI**             | Spring AI, Google Gemini, Pinecone Vector Store    |
| **Payment**        | Stripe API, SePay                                 |
| **File Upload**    | Cloudinary                                        |
| **Email**          | Spring Mail, Thymeleaf templates                  |
| **Validation**     | Spring Validation, Bean Validation                |

| **Mapping**        | MapStruct, Lombok                                 |
| **Containerization**| Docker (multi-stage build)                       |

---

## 📁 Project Structure

```
src/main/java/com/example/demo/
├── config/             # App configuration (Security, Cloudinary, AI, OpenAPI)
├── controller/         # REST API controllers (23 controllers)
├── dto/
│   ├── request/        # Request DTOs with validation
│   └── response/       # Response DTOs
├── entity/             # JPA entities (19 entities)
├── enums/              # Business enums (Role, OrderStatus, PaymentMethod, etc.)
├── event/              # Spring application events
├── exception/          # Global exception handling
├── mapper/             # MapStruct mappers
├── repository/         # Spring Data JPA repositories & specifications
├── security/           # JWT filter, service & user principal
├── service/            # Business logic layer (22 services)
└── utils/              # Utility classes
```

---

## 📖 API Overview

| Module                | Endpoints | Description                          |
|-----------------------|-----------|--------------------------------------|
| **Authentication**    | 4         | Register, login, password reset      |
| **Profile**           | 2         | View & update user profile           |
| **Address**           | 6         | CRUD + set default address           |
| **Products**          | 8+        | Search, CRUD, like                   |
| **Categories**        | 4         | CRUD categories                      |
| **Cart**              | 5         | Add, update, remove, clear cart      |
| **Orders**            | 5         | Create, list, detail, cancel orders  |
| **Payments**          | 3         | Stripe checkout, SePay, webhooks     |
| **Coupons**           | 7         | CRUD, activate, calculate discount   |
| **Reviews**           | 6         | Submit, list, moderate, reply        |
| **Banners**           | 4         | CRUD banners for storefront          |
| **Promotions**        | 4         | CRUD promotion banners               |
| **Admin Statistics**  | 4         | Revenue, top products, overview      |
| **AI Chat**           | 2         | Product recommendations, food advice |
| **Media**             | 1         | Image upload                         |
| **Users (Admin)**     | 4         | User management                      |

---

## 🔧 Architecture Highlights

- **Layered Architecture**: Controller → Service → Repository with clear separation of concerns
- **DTO Pattern**: Request/Response DTOs to decouple API contracts from entity models
- **MapStruct Mappers**: Compile-time, type-safe object mapping (zero reflection overhead)
- **Global Exception Handling**: Centralized error responses via `@RestControllerAdvice`
- **Spring Specifications**: Dynamic query building for flexible product & review filtering
- **Event-Driven**: Spring Application Events for async review stats recalculation
- **Scheduled Tasks**: Background jobs for abandoned cart reminders
- **Multi-stage Docker Build**: Optimized container image with build and runtime separation

---

## 🛡️ Hardening & Concurrency

Each problem below was first reproduced by an automated test that failed, then fixed with the smallest
change that made it pass. All numbers come from real runs of those tests against PostgreSQL 17
(Testcontainers `postgres:17-alpine`, default HikariCP pool of 10) on Windows 11, JDK 21, Docker Desktop 29.
"Before" = 10 repetitions on the unfixed code; "after" = 30 repetitions (3 runs × `@RepeatedTest(10)`).

### 1. Authorization & input validation — `SecurityHardeningTest`

| Request | Before | After |
|---|---|---|
| `USER` creates / updates / deletes a product | `201` / `200` | `403` |
| Checkout with `quantity: -1` | `201`, total reduced, stock **increased** | `400`, stock unchanged |
| Checkout with empty body `{}` | `500` | `400` |
| Add to cart with `quantity: -5` | `200` | `400` |
| Wrong password / unknown email | `500` | `401` |
| Malformed JWT | `500` (`MalformedJwtException`) | `401` |
| No token on protected endpoint | `403` | `401` |

Fix: role rules for product writes, `@Valid` on request bodies, `AuthenticationException` → 401,
invalid JWTs treated as anonymous.

### 2. Overselling stock and coupons — `OrderConcurrencyTest`

All threads are released at the same instant with a `CountDownLatch`.

| Scenario | Before (10/10 runs failed) | After (30/30 runs passed) |
|---|---|---|
| 50 buyers, stock = 10 | 50 orders accepted, final stock 3–4 (lost updates) | 10 orders, final stock 0 |
| Coupon with usage limit 5, 50 buyers | redeemed **42–46** times, `used_count` = 5 | redeemed 5 times, `used_count` = 5 |
| Same user, same coupon, 10 parallel checkouts | redeemed 10 times | redeemed once |
| 10 cancellations + 20 new orders at once (expected stock 30) | final stock 35–40 | 30 |
| 50 two-item orders in opposite order `[A,B]` / `[B,A]` | only 6–16 of 50 succeeded; PostgreSQL logged `deadlock detected` 351 times over 10 runs* | 50/50 succeeded, no deadlock |

\* This test was written after the fix; its "before" was measured later by running it against the original
code with the reproduction steps below.

Fix: stock and coupon usage are changed by a single conditional `UPDATE … WHERE stock >= :qty`
(`… used_count < usage_limit`); the affected-row count decides success. Rows are updated in ascending
product id order to avoid deadlocks, and a unique `(coupon_id, user_id)` constraint enforces one
redemption per user. Pessimistic locking and `@Version` + retry were considered; the conditional update
needs no retry loop and holds row locks for the shortest time.

### 3. Stripe webhook idempotency — `StripeWebhookTest`

Payloads are signed with the webhook secret exactly as Stripe signs them.

| Scenario | Before | After |
|---|---|---|
| Same event delivered 10× concurrently | 10/10 runs: `1×200, 9×500` | 30/30 runs: `10×200`, one payment row |
| `checkout.session.completed` + `payment_intent.succeeded` for one order (10 deliveries) | **10** confirmation e-mails (10/10 runs) | 1 e-mail (30/30 runs) |
| Paid event for a `CANCELLED` order | order → `CONFIRMED` | stays `CANCELLED` |
| Paid event for a COD order | order → `CONFIRMED` | stays `PENDING` |
| `amount_total` ≠ order total | order → `CONFIRMED` | stays `PENDING` |
| `payment_status: unpaid` | order → `CONFIRMED` | stays `PENDING` |
| Invalid signature | `400` | `400` |

Fix: every event id is recorded with `INSERT … ON CONFLICT DO NOTHING` inside the handling transaction,
and the order row is locked (`SELECT … FOR UPDATE`) while the `PENDING → CONFIRMED` transition is checked.

### 4. Rate limiting — `RateLimitingTest`

Each test uses its own client IP and e-mail. "Before" is one run on the unfixed code (10 repetitions for the
parallel test); "after" is 3 full-suite runs (30 repetitions for the parallel test).

| Scenario | Before | After |
|---|---|---|
| 6 wrong passwords, then the correct one (same IP + e-mail) | `401 ×6`, then `200` | `401 ×5`, `429`, then `429` with `Retry-After` |
| 6 wrong passwords from another IP, then the real user logs in | `200` | `200` (no lockout of the real user) |
| Forgot-password for an unknown vs. a known e-mail | `400` vs `200` (reveals accounts) | `200` vs `200` |
| 4 forgot-password requests for one e-mail | `200 ×4` | `200 ×3`, `429` |
| 5 wrong reset codes, then the real code | real code accepted (`200`) | refused (`429`) |
| 50 parallel wrong reset codes, then the real code | 10/10 runs: all 50 evaluated, real code accepted | 30/30 runs: exactly 5 evaluated, 45 refused, real code refused |
| 12 chat requests from one IP within a minute | `200 ×12` | `200 ×10`, `429 ×2` |
| `/chat/stream` after 10 chat requests (`Accept: text/event-stream`) | `200` | `429` |
| 40 chat requests from different IPs, daily budget 30 | 40 answered | 30 answered, 10 refused |
| Chat message of 2,000 characters | `200` | `400` |

Fix: an in-memory sliding-window limiter (no new dependency) with limits configurable under
`app.rate-limit.*`. Login failures are keyed by IP + e-mail plus a per-IP cap; each reset code allows five
verification attempts, counted with a conditional `UPDATE` so parallel guesses cannot exceed it, and codes
come from `SecureRandom`. Limits are per instance; behind a reverse proxy set `RATE_LIMIT_TRUSTED_PROXIES`.

### Re-running the tests

Docker must be running (Testcontainers starts PostgreSQL automatically; no local database or API keys needed).

```bash
./mvnw test                                   # full suite (Windows: mvnw.cmd test)
./mvnw test -Dtest=SecurityHardeningTest
./mvnw test -Dtest=OrderConcurrencyTest       # prints one [CONCURRENCY] line per repetition
./mvnw test -Dtest=StripeWebhookTest          # prints one [WEBHOOK] line per concurrent repetition
./mvnw test -Dtest=RateLimitingTest           # prints [RATELIMIT] lines
```

To reproduce a "before" number, check out the fix commit in a separate worktree (so only the tests that
existed at that point are compiled), restore the production code from its parent, and run the test:

```bash
git worktree add ../bepviet-before <fix-commit>      # e.g. the "fix(orders): ..." commit
cd ../bepviet-before
git checkout <fix-commit>^ -- src/main
./mvnw test -Dtest=OrderConcurrencyTest
cd - && git worktree remove --force ../bepviet-before
```

This works for sections 1–3. `RateLimitingTest` resets the new `RateLimiter` between tests, so it does not
compile against the pre-fix code; its "before" column was recorded before that reset hook was added.

For local development, `docker compose up -d` starts PostgreSQL on port `5433`.

---

## 📝 License

This project is for educational and portfolio purposes.
