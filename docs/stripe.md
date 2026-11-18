[Back to project README](../README.md)

Run commands in this guide from the repository root.

# Stripe payments

To set up a local test payment:

1. Sign in to the [Stripe Dashboard](https://dashboard.stripe.com/) and use the account picker to create or select a **sandbox** (or select **test mode** if available). Stay in this environment for both the key and product setup.
2. Open **API keys** in that environment and copy the secret key beginning with `sk_test_`. This integration uses the backend secret key; a `pk_test_` publishable key is not needed. Keep the secret key private, including test keys: never put it in this README, commit it, or share it in chat.
3. Open **Product catalog**, choose **Add product**, and name it `Smartifier Premium`. Add a **Recurring** price, for example CAD 5.00 monthly, and save. Sandbox payments use simulated money.
4. Open the saved product's price details and copy its **Price ID**, beginning with `price_`. Use the price ID, not the product ID beginning with `prod_`.
5. Stop the running backend with Ctrl+C. In PowerShell at the repository root, replace the placeholder values below with your sandbox key and price ID, then restart from that same terminal. These environment variables apply to that terminal and child processes; set them again when using a new terminal.

```powershell
$env:STRIPE_SECRET_KEY = 'sk_test_your_key'
$env:STRIPE_PRICE_ID = 'price_your_price_id'
$env:SMARTIFIER_PUBLIC_URL = 'http://localhost:8080'
.\scripts\start-local.ps1
```

Use a recurring price. The amount, currency, and billing interval come from the Stripe price; clients cannot supply a price or amount. Use a key and price from the same Stripe account and environment. Keep the secret key on the backend and out of source control. Environment variables must be set in the backend process; this project does not automatically load `.env` files.

Sign in as a member and click **Pay for premium**. The button creates a Checkout Session through the authenticated, CSRF-protected backend, then redirects to Stripe. Stripe returns the member to the dashboard after checkout or cancellation. For Angular development on port 4200, set `SMARTIFIER_PUBLIC_URL` to `http://localhost:4200`; in production, use your public HTTPS origin. Restart the backend after changing configuration. Missing configuration shows an availability message without contacting Stripe.

### Public test card

Stripe publishes this fake Visa card for testing. It is safe to include these details in documentation; they are not a real person's card or an account credential. Use them only with Stripe test keys in a sandbox/test environment. No real money moves.

| Checkout field | Test value |
| --- | --- |
| Card number | `4242 4242 4242 4242` |
| Expiry | `12/34` (or any future date) |
| CVC | `123` (or any three digits) |
| Cardholder name | `Test Member` |
| Postal code | Any valid postal code, such as `M5V 2T6` for Canada |

Open http://localhost:8080, sign in as a member, click **Pay for premium**, and enter these values on the hosted Stripe page. After submitting, check the same Stripe sandbox's payment list for the simulated successful payment. Keep real card details and all secret API keys out of the README and source control. See [Stripe's official test card documentation](https://docs.stripe.com/testing) and [API key documentation](https://docs.stripe.com/keys).

Successful paid Checkout sessions activate Premium through the signed webhook at `/api/payments/stripe/webhook`. The handler verifies the raw payload signature with `STRIPE_WEBHOOK_SECRET` (including a five-minute timestamp tolerance) and recognizes the initial and saved Smartifier prices. The authenticated member email is attached by the server when creating Checkout; recurring checkouts also put member metadata on the Stripe subscription. Return URLs never grant access.

Recurring access follows current Stripe subscription state. Failed renewals suspend Premium while the subscription is `past_due`, `unpaid`, or otherwise inactive; access returns when it is `active` and its latest invoice is paid. Immediate cancellations remove that subscription's access immediately after webhook processing. A cancellation scheduled for period end keeps access while the paid subscription remains active, then removes it when the cancellation takes effect. Another paid membership continues to provide access. Refunds and disputes are not automated. See [Stripe subscription webhooks](https://docs.stripe.com/billing/subscriptions/webhooks).

### Subscription lifecycle events

In the Stripe Dashboard webhook destination, enable these events for the existing endpoint (keep its signing secret configured on the backend):

```text
checkout.session.completed
checkout.session.async_payment_succeeded
customer.subscription.created
customer.subscription.updated
customer.subscription.deleted
customer.subscription.paused
customer.subscription.resumed
invoice.paid
invoice.payment_succeeded
invoice.payment_failed
invoice.payment_action_required
invoice.marked_uncollectible
invoice.voided
```

The included Docker Stripe listener now forwards this list. Recreate it after updating this checkout:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe up -d stripe-listener
```

Check its current signing secret after recreation and update `STRIPE_WEBHOOK_SECRET` if it changes. Production webhook destinations must have the event list updated separately in Stripe.

Webhook payloads can arrive more than once or out of order. Each event prompts a fresh Stripe SDK retrieval of the subscription and its latest invoice, rather than trusting the event's old status. Per-member locks serialize access changes; each subscription is recorded independently in MongoDB. Stripe or database failures return a non-success response so Stripe can retry. Premium checks read the account's current database flag; a member may need to refresh their page to see a changed badge.

Existing subscriptions can be linked through their original Smartifier Checkout Session's `client_reference_id` and recognized price metadata. On first reconciliation, completed app checkouts matching the member's billing email are imported to recognize overlapping subscriptions. Sessions without Smartifier metadata, manually granted access, or historical purchases using a different billing email need a separate audit; they cannot be reliably inferred from the old Premium boolean. A legacy subscription is first reconciled when one of its events is delivered; replay a relevant event from Stripe for subscriptions already canceled or overdue before deployment. Unrelated Stripe subscriptions and non-subscription invoices never change member access.

To verify in a Stripe sandbox: buy a recurring membership, schedule cancellation and confirm access remains, then cancel it immediately and confirm access is removed. With another test membership, use a Stripe test clock to advance to a failed renewal, confirm access is suspended, then pay the latest invoice and confirm it returns. Replay an old failure/checkout event after recovery and verify it does not override Stripe's current state. Also verify that a second paid subscription retains access when another subscription ends.

### Member billing

In **Membership & billing**, members with a recurring subscription can choose **Update billing info** to open Stripe's payment-method update screen. **Manage or cancel** opens the full billing portal for billing name and address changes, invoices, and cancellation at the end of the current billing period. The app creates its portal configuration on first use. Webhooks reconcile access after Stripe applies a subscription change.

To offer plan switches, configure a Stripe customer portal with payment-method updates, billing name and address updates, cancellation, and the recurring Premium prices members can switch to. Use prices already recognized by Smartifier and offer compatible prices in the same currency. Set `STRIPE_PORTAL_CONFIGURATION_ID` to that configuration's ID and restart the backend. Smartifier's default portal does not publish historical checkout prices as plan choices.

### Admin membership pricing

The admin dashboard's **Membership pricing** section uses the existing Stripe Java SDK on the backend. The secret key stays on the server. All pricing endpoints require an administrator session, and all changes require a CSRF token.

- Set an amount, select CAD, USD, EUR, GBP, or AUD, and choose **Monthly** or **Yearly**. Existing subscriptions are eligible for review only when their currency matches the new price. Fixed, standard prices are supported; custom billing models remain managed in Stripe.
- **New memberships only** creates a new recurring Stripe price and saves it as the checkout price. Existing subscriptions and pending checkouts keep their original terms.
- **New and existing memberships** first saves the new checkout price, then opens a separate review of existing subscriptions. Nothing is changed on existing subscriptions until the admin explicitly confirms that review.
- An admin can also choose **Review existing subscriptions** later to move remaining subscriptions onto the current price. Only subscriptions on prices previously configured or created by this app are considered. One-time purchases are not converted into subscriptions.

Reviews contain up to 25 subscriptions, expire after ten minutes, and belong to the administrator who requested them. Only active, automatically charged, single-item subscriptions with quantity one are eligible. Cancellation plans, schedules, paused collection, and pending updates are left unchanged. Use **Review next batch** to continue through the list, including when a subscription in an earlier batch could not be updated. Moving to the next batch does not apply changes to the current batch. Prices displayed are base rates before taxes and discounts.

Applying a reviewed change uses Stripe's `always_invoice` proration behavior and `error_if_incomplete` payment behavior. Price differences can be invoiced immediately. A monthly/yearly switch resets the billing period and can charge immediately, crediting unused time. Stripe rejects updates requiring an incomplete payment. See [Stripe's subscription update behavior](https://docs.stripe.com/api/subscriptions/update).

Each subscription has its own result. Processing stops on an unexpected request failure; some earlier changes may already have succeeded. Review again to inspect current subscriptions before continuing. The backend checks current terms before updating, uses Stripe idempotency keys, prevents concurrent updates to the same subscription, and records review ownership and outcomes in MongoDB.

Pricing is persisted in MongoDB and takes precedence over `STRIPE_PRICE_ID` after the first admin save. That setting supplies the initial recurring price. Historical prices remain accepted by the payment webhook so a checkout started before a price change can still activate Premium. Use separate databases for Stripe test and live environments; retain the matching account, API key, and webhook signing secret.

Test both scopes in a Stripe sandbox before using live billing. Automated tests use a local fake Stripe HTTP endpoint, not a real Stripe account.

### Receive test webhooks locally

Use this temporary setup to test automatic premium activation on your own computer. You need Docker Desktop running, this repository, and access to a Stripe sandbox. You do not need a public domain, a Dashboard webhook endpoint, or a separate Stripe CLI installation.

#### What the pieces do

- **Checkout** is Stripe's payment page.
- **Webhook** is Smartifier's `/api/payments/stripe/webhook` endpoint. A verified successful payment enables premium for the member who started Checkout.
- **Listener** is the included `stripe-listener` Docker service. It receives sandbox events from Stripe and forwards them to the backend inside Docker. Stripe cannot send requests directly to your computer's `localhost`.

| Setting | Where to get it | Purpose |
| --- | --- | --- |
| `STRIPE_SECRET_KEY` (`sk_test_...`) | Stripe sandbox's API keys page | Lets the backend create Checkout sessions and the listener connect to Stripe |
| `STRIPE_PRICE_ID` (`price_...`) | The sandbox product's price details | Selects the premium price charged at Checkout |
| `STRIPE_WEBHOOK_SECRET` (`whsec_...`) | Your running local listener's logs | Lets the backend verify incoming webhook signatures |

The listener's signing secret does **not** appear in the Stripe Dashboard. Dashboard webhook destinations have separate signing secrets. Each teammate should use the secret printed by their own local listener.

#### 1. Configure sandbox payments

Open PowerShell in the repository root. Create `.env` only if you do not already have one:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

In Stripe, select a sandbox. Copy its secret API key, create a premium product with a **recurring** price, and copy that price's ID. Both must belong to the same sandbox.

Edit your local `.env`:

```dotenv
STRIPE_SECRET_KEY=sk_test_your_sandbox_key
STRIPE_PRICE_ID=price_your_sandbox_price
STRIPE_WEBHOOK_SECRET=
SMARTIFIER_PUBLIC_URL=http://localhost:8080
```

Leave the webhook secret blank for now. `.env` is ignored by Git and excluded from Docker builds. Keep API keys and signing secrets out of commits, screenshots, and chat.

#### 2. Start the app and listener

Run:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build -d backend
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe up -d stripe-listener
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe logs -f stripe-listener
```

The first build can take a few minutes. The local Compose override avoids exposing Docker MongoDB on port 27017, so it can coexist with an installed MongoDB service.

Wait for the listener to print:

```text
Ready! ... Your webhook signing secret is whsec_...
```

Copy the complete `whsec_...` value. Press **Ctrl+C** to leave the log view; the listener continues running in the background because it was started with `-d`.

#### 3. Give the backend the listener's secret

Update the existing line in `.env`:

```dotenv
STRIPE_WEBHOOK_SECRET=whsec_the_value_from_your_listener
```

Recreate the backend so it loads the updated environment:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d backend
```

A plain `docker restart` does not reload changes from `.env`. You do not need another image build just to change the secret.

#### 4. Verify automatic premium activation

1. Open http://localhost:8080 and register a **new free member**. Use a distinct test email for each teammate.
2. Sign in and choose **Pay for premium**.
3. Complete Checkout with Stripe's sandbox Visa:

   | Field | Test value |
   | --- | --- |
   | Card number | `4242 4242 4242 4242` |
   | Expiry | Any future date |
   | CVC | Any three digits |
   | Postal code | For example, `M5V 2T6` |

4. Check the listener logs. The `checkout.session.completed` event should be forwarded with HTTP **200**.
5. Refresh the member dashboard after a few seconds. It should say **Premium member** and show the personal stack editor.

Use a fresh Checkout started by this app. Older sessions created before webhook support lack the Smartifier price metadata and do not activate premium. The generic `stripe trigger checkout.session.completed` fixture also lacks the member reference and price metadata, so it does not upgrade an account.

An account already upgraded manually cannot demonstrate the free-to-premium transition. Docker also uses its own database volume; accounts in a separately installed MongoDB are different accounts.

#### Troubleshooting

| Symptom | Check |
| --- | --- |
| Listener never prints `Ready!` | Docker Desktop is running, network access works, and `STRIPE_SECRET_KEY` is a valid sandbox key. |
| No event appears after paying | The listener is running and Checkout uses the same sandbox as the listener. |
| Webhook returns HTTP 400 | The backend has the exact `whsec_...` from this listener, and you recreated the backend after editing `.env`. An incorrect system clock can also fail timestamp verification. |
| Webhook returns HTTP 503 | Check `STRIPE_WEBHOOK_SECRET`, the backend Stripe API key, database availability, and Stripe connectivity. A concurrent membership update can also return a retryable 503. |
| Webhook returns HTTP 409 | The Checkout member could not be found in the backend database. |
| HTTP 200 but account stays free | Start a new Checkout from the app with a fresh free member. Confirm the payment succeeded and its price is the initial price or one of the app's saved membership prices. |

To inspect logs, keep their secrets private:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe logs --tail 30 stripe-listener
docker compose -f docker-compose.yml -f docker-compose.local.yml logs --tail 50 backend
```

If you recreate the listener or change sandboxes, check its signing secret again and update `.env` if needed. Changing the API key also requires recreating the listener with the `up -d stripe-listener` command above.

#### Stop the temporary setup

Stop just the listener:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe stop stripe-listener
```

Or stop the listener, app, and database while retaining accounts and stacks:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile stripe stop
```

For a later session, repeat the startup commands, check the listener secret, and recreate the backend if it changed.

References: [Stripe local webhook testing](https://docs.stripe.com/checkout/fulfillment#test-your-event-handler-locally), [webhook signature secrets](https://docs.stripe.com/events/manage-webhook-endpoints#signature-errors), and [Stripe test cards](https://docs.stripe.com/testing#cards).

### Stripe in Docker

Docker Compose passes Stripe settings into the backend at runtime. Open the local `.env` file in the repository root and fill in `STRIPE_SECRET_KEY` with your sandbox key (`sk_test_...`) and `STRIPE_PRICE_ID` with the matching sandbox price (`price_...`). Use a recurring price. `.env.example` contains the safe template; `.env` is ignored by Git and excluded from the Docker build context. Keep your key private.

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d backend
```

Run that command again after changing `.env` so Compose recreates the backend with the new values. Use `up --build -d` when application code has changed. A bare restart does not reload environment values. The local override avoids a port conflict with a separately installed MongoDB. Docker uses its own database volume, so local MongoDB accounts are separate from Docker accounts.

If **Pay for premium** reports that payments are not configured, the key or price is missing. If checkout cannot open, check that both values belong to the same Stripe sandbox and that the checkout mode matches the price. A public test card alone cannot start checkout; a configured Stripe account is required.
