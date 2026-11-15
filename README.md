# 📡 TeenRadar

**Know what your kid is really saying.** Parents paste a text, caption or screenshot from their 10–17 year old.
TeenRadar translates the slang, flags real danger (fentanyl pill codes, sextortion openers, self-harm talk),
and tells the parent what to say. No spyware on the kid's phone.

- **Decoder**: AI (Gemini) explains every term. A curated list of known danger codes always overrides the AI,
  so a known code is flagged even when the model misses or underrates it.
- **Radar**: a daily job asks Gemini, grounded in live Google Search, what teens started saying; every decode
  also counts toward "asked N× by parents this week", our own trend signal.
- **SEO pages**: one indexable page per term (`/t/{slug}`) for "what does X mean" searches. Sitemap at `/sitemap.xml`.
- **Weekly brief** by email. **Premium** ($5.99/mo, Stripe Checkout): unlimited decodes and screenshots.
- No sign-up: an anonymous `vid` cookie tracks the free limit and Premium.

## Stack

Kotlin 2.4, Spring Boot 4.1 (Web MVC, Data JPA, Flyway, Thymeleaf), PostgreSQL, Tailwind (CDN) + vanilla JS.
External: Gemini API, Stripe (test mode), Resend.

```
src/main/kotlin/com/teenradar/
  decode/   DecodeController, DecodeService (limits, cache, AI call), KnownCodes (the safety-net logic)
  trends/   Term + repository (feed SQL), TrendService (feed, daily ingest)
  ai/       GeminiClient (REST, JSON schema output, search grounding, daily cap)
  billing/  BillingController (Stripe Checkout, no webhook: session verified on return)
  digest/   subscribe + weekly email
  web/      pages, anonymous visitor cookie
src/main/resources/db/migration/  schema + curated codes (V2)
```

## Run locally

```bash
docker run -d --name teenradar-pg -e POSTGRES_PASSWORD=pg -p 127.0.0.1:5433:5432 postgres:16
cp .env.example .env                 # fill in GEMINI_API_KEY (https://aistudio.google.com/apikey)
./gradlew bootRun                    # http://localhost:8080
./gradlew test
```
`.env` is gitignored and loaded by Spring automatically (`spring.config.import`); real env vars win over it.

| Env var | Purpose |
|---|---|
| `DATABASE_URL`, `DB_USER`, `DB_PASSWORD` | JDBC Postgres, e.g. Neon: `jdbc:postgresql://<host>/<db>?sslmode=require` |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | AI (default `gemini-3.5-flash-lite`, falls back to `GEMINI_FALLBACK_MODELS` on 503/429/timeout) |
| `STRIPE_SECRET_KEY` | `sk_test_...` |
| `RESEND_API_KEY`, `MAIL_FROM` | weekly brief |
| `APP_BASE_URL` | public URL, used in Stripe redirects, emails, sitemap |
| `ADMIN_TOKEN` | `POST /api/admin/ingest` with header `X-Admin-Token` refreshes the radar now |

## Deploy

Render (Docker, free) + Neon Postgres (free). The `Dockerfile` is tuned for 512 MB.
A 5-minute uptime ping on `/actuator/health` keeps the free instance warm.

Safety: if a child may be in danger call 911; crisis line: call or text 988. TeenRadar can be wrong; context matters.
