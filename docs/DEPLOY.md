# Deployment checklist

Target: Render web service (Docker) with a Neon PostgreSQL database. Work through the sections in
order; every item is a checkbox so the list can be copied into an issue or PR.

## 1. Before deploying

### Database
- [ ] Take a backup of the current production database, even if it is new:
      `pg_dump "<direct connection string>" -Fc -f bepviet-$(date +%F).dump`
      (Neon also keeps a short point-in-time restore window; check the plan's limit before relying on it.)
- [ ] If the database already contains data from before the coupon fix, look for duplicate coupon
      uses, which would stop the unique constraint `uk_coupon_usage_coupon_user` from being created:
      ```sql
      SELECT coupon_id, user_id, count(*) AS uses
      FROM coupon_usages
      GROUP BY coupon_id, user_id
      HAVING count(*) > 1;
      ```
      Resolve every row this returns (keep the earliest use) before deploying. Hibernate's
      `ddl-auto=update` only logs a warning when it cannot add a constraint, so the app would start
      without the protection.
- [ ] Use Neon's direct endpoint (host without `-pooler`). The application pools connections itself
      with HikariCP.

### Environment variables on Render
Set these under the web service's Environment tab. Names only; never commit the values.

Required:
- [ ] `SPRING_DATASOURCE_URL` (`jdbc:postgresql://<host>/<db>?sslmode=require`)
- [ ] `SPRING_DATASOURCE_USERNAME`
- [ ] `SPRING_DATASOURCE_PASSWORD`
- [ ] `JWT_SECRET` (Base64, at least 32 bytes; a new value signs out every user)
- [ ] `cloudinary-cloud-name`, `cloudinary-api-key`, `cloudinary-api-secret`
- [ ] `stripe_secret_key`, `stripe_publishable_key`, `stripe_webhook_secret`
- [ ] `gemini_api_key`
- [ ] `pinecone_api_key`
- [ ] `RATE_LIMIT_TRUSTED_PROXIES=1`. Render puts one proxy in front of the service. With the
      default `0` every visitor shares one rate-limit bucket, so ten chat messages per minute would
      be the limit for the whole site.

Optional, depending on the features used:
- [ ] `SEPAY_WEBHOOK_API_KEY`, `SEPAY_SECRET_KEY`, `SEPAY_BANK_NAME`, `SEPAY_BANK_BIN`,
      `SEPAY_BANK_ACCOUNT`, `SEPAY_ACCOUNT_NAME`
- [ ] `email`, `password` (SMTP) or `RESEND_API_KEY`, `RESEND_FROM`
- [ ] `JAVA_TOOL_OPTIONS` to keep the JVM inside the free instance's 512 MB, for example
      `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k`. Not yet verified on Render: running
      outside a container the app used about 440 MB with a 350 MB heap limit. Watch the Metrics tab
      after the first deploy and lower the percentage if the instance restarts with out-of-memory.

### Secrets hygiene
- [ ] Rotate the SePay secret key. An earlier version of `application.properties` contained it in
      plain text and it remains in the Git history; the property is now read from `SEPAY_SECRET_KEY`.
- [ ] Confirm `git grep -nE "sk_live|sk_test_[A-Za-z0-9]{10}|whsec_[A-Za-z0-9]{10}"` finds nothing.

### Frontend changes required by this release
- [ ] **401** now means "not signed in or token invalid" (previously some of these returned 403 or
      500). Clear the stored token and redirect to the login page on 401.
- [ ] **403** now only means "signed in but not allowed" (for example a customer calling an admin
      endpoint). Show an error; do not sign the user out.
- [ ] **429** is returned by login, forgot-password, reset-password and the AI chat. Read the
      `Retry-After` header (seconds) and show "try again in N seconds"; disable the submit button
      meanwhile.
- [ ] **400** with a message is returned when checking out a hidden product, paying for an order that
      is no longer pending, or paying for an order that is about to expire. Show the message and offer
      to place a new order.
- [ ] Hidden products now return **404** on the detail endpoint for non-admins.
- [ ] Forgot-password always returns 200, whether or not the e-mail exists. Show a neutral
      "if the account exists, a code has been sent" message.

### Payment providers
- [ ] Stripe dashboard webhook points to `https://<backend>/api/v1/payments/stripe/webhook` with the
      `checkout.session.completed` and `payment_intent.succeeded` events, and its signing secret matches
      `stripe_webhook_secret`.
- [ ] SePay webhook points to `https://<backend>/api/v1/payments/sepay/webhook` and sends the key set in
      `SEPAY_WEBHOOK_API_KEY`.
- [ ] Someone owns the manual reconciliation: a SePay transfer for an order that is no longer pending is
      logged as a warning (`SePay nhan tien cho orderId=... o trang thai ...: can doi soat/hoan tien thu
      cong`) and must be reconciled and refunded by hand.

## 2. Deploying

- [ ] Note the commit currently live on Render (Events tab) so you know what to roll back to.
- [ ] Health check path: `/api/v1/health` (public, no database access).
- [ ] Optional: an uptime monitor (for example UptimeRobot, every 5 minutes) on `/api/v1/health` keeps
      the free instance from sleeping after 15 idle minutes. Do not ping endpoints that query the
      database, or Neon's compute never suspends and the free compute quota runs out. One always-on
      free service uses most of Render's 750 free instance hours per month.
- [ ] Push to the branch Render deploys from, or trigger a manual deploy.
- [ ] Watch the deploy log until `Started EcomApplication` appears.

## 3. After deploying

- [ ] Change the password of the seeded `admin@gmail.com` account immediately. On an empty database
      the seeder also creates sample customers with a shared well-known password and sample orders;
      delete them if the instance is public.
- [ ] Smoke test with the deployed URL:
  - [ ] `GET /api/v1/categories` and `GET /api/v1/products` return 200 with data
  - [ ] Register, log in, place a cash-on-delivery order, cancel it; stock returns to its original value
  - [ ] A protected endpoint without a token returns 401
- [ ] Rate limiting works per visitor: send 11 chat messages within a minute from one network (the 11th
      returns 429) while a second network (for example a phone on mobile data) still gets 200. If both
      are blocked, `RATE_LIMIT_TRUSTED_PROXIES` is wrong.
- [ ] One Stripe test-mode payment completes and the order becomes `CONFIRMED`; resending the same
      event from the Stripe dashboard does not send a second e-mail.
- [ ] Verify the coupon constraint exists:
      `SELECT conname FROM pg_constraint WHERE conname = 'uk_coupon_usage_coupon_user';`
- [ ] AI chat: the Pinecone index still holds vectors whose `productId` values refer to the old
      database. Until the menu is re-indexed, the advisor can recommend dishes that do not exist.
      Re-index (or hide the chat in the frontend) before announcing the site.
- [ ] Check the Metrics tab for memory after an hour of normal use.

## 4. Rolling back

- Application: Render keeps previous deploys. In the service's Events tab choose the last good deploy
  and select Rollback, or revert the commit and push.
- Schema: this release only adds a table (`processed_webhook_events`), a nullable column
  (`password_reset_tokens.failed_attempts`) and a unique constraint on `coupon_usages`. The previous
  version does not read the new table or column, and the constraint only rejects a second use of the
  same coupon by the same customer, so rolling back the application does not require a schema
  rollback.
- Data: if data was damaged, restore the backup taken in step 1 into a new Neon branch or database,
  verify it, then point `SPRING_DATASOURCE_URL` at it:
  `pg_restore --clean --no-owner -d "<connection string>" bepviet-YYYY-MM-DD.dump`
- After any rollback, run the smoke tests in section 3 again.
