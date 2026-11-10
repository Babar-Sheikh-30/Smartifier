[Back to project README](../README.md)

Run commands in this guide from the repository root.

# Stripe payments

To set up a local test payment:

1. Sign in to the [Stripe Dashboard](https://dashboard.stripe.com/) and use the account picker to create or select a **sandbox** (or select **test mode** if available). Stay in this environment for both the key and product setup.
2. Open **API keys** in that environment and copy the secret key beginning with `sk_test_`. This integration uses the backend secret key; a `pk_test_` publishable key is not needed. Keep the secret key private, including test keys: never put it in this README, commit it, or share it in chat.
3. Open **Product catalog**, choose **Add product**, and name it `Smartifier Premium`. Add a **One time** price, for example CAD 5.00, and save. This is simulated money in the sandbox. For a recurring membership instead, choose **Recurring** and use `subscription` for the checkout mode below.
4. Open the saved product's price details and copy its **Price ID**, beginning with `price_`. Use the price ID, not the product ID beginning with `prod_`.
5. Stop the running backend with Ctrl+C. In PowerShell at the repository root, replace the placeholder values below with your sandbox key and price ID, then restart from that same terminal. These environment variables apply to that terminal and child processes; set them again when using a new terminal.

```powershell
$env:STRIPE_SECRET_KEY = 'sk_test_your_key'
$env:STRIPE_PRICE_ID = 'price_your_price_id'
$env:STRIPE_CHECKOUT_MODE = 'payment'
$env:SMARTIFIER_PUBLIC_URL = 'http://localhost:8080'
.\scripts\start-local.ps1
```

Use `payment` for a one-time price or `subscription` for a recurring price. The amount, currency, and billing interval come from the Stripe price; clients cannot supply a price or amount. Use a key and price from the same Stripe account and environment. Keep the secret key on the backend and out of source control. Environment variables must be set in the backend process; this project does not automatically load `.env` files.

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

Successful paid Checkout sessions automatically grant premium through the signed webhook at `/api/payments/stripe/webhook`. The handler accepts `checkout.session.completed` and `checkout.session.async_payment_succeeded`, verifies the raw payload signature with `STRIPE_WEBHOOK_SECRET` (including a five-minute timestamp tolerance), and requires `payment_status=paid` and the configured Smartifier price metadata. The authenticated member email is attached by the server when creating Checkout. Repeated deliveries safely set the same premium flag without replacing other account fields. Return URLs never grant access. Sessions created before this integration lack the metadata; start a new checkout to test automatic activation.

This implements initial activation only. Subscription cancellations, failed renewals, refunds, and premium revocation are not automated yet. Use one-time `payment` mode for the current premium purchase flow. See [Stripe payment fulfillment](https://docs.stripe.com/checkout/fulfillment).

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

In Stripe, select a sandbox. Copy its secret API key, create a premium product with a **one-time** price, and copy that price's ID. Both must belong to the same sandbox.

Edit your local `.env`:

```dotenv
STRIPE_SECRET_KEY=sk_test_your_sandbox_key
STRIPE_PRICE_ID=price_your_sandbox_price
STRIPE_WEBHOOK_SECRET=
STRIPE_CHECKOUT_MODE=payment
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
| Webhook returns HTTP 503 | `STRIPE_WEBHOOK_SECRET` or `STRIPE_PRICE_ID` is missing in the backend environment. |
| Webhook returns HTTP 409 | The Checkout member could not be found in the backend database. |
| HTTP 200 but account stays free | Start a new Checkout from the app with a fresh free member. Confirm the payment succeeded and the configured price has not changed since Checkout began. |

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

This integration activates premium after a successful payment. SMS delivery, refunds, subscription cancellations, and failed-renewal revocation are not implemented. The guide uses one-time payment mode.

References: [Stripe local webhook testing](https://docs.stripe.com/checkout/fulfillment#test-your-event-handler-locally), [webhook signature secrets](https://docs.stripe.com/events/manage-webhook-endpoints#signature-errors), and [Stripe test cards](https://docs.stripe.com/testing#cards).

### Stripe in Docker

Docker Compose passes Stripe settings into the backend at runtime. Open the local `.env` file in the repository root and fill in `STRIPE_SECRET_KEY` with your sandbox key (`sk_test_...`) and `STRIPE_PRICE_ID` with the matching sandbox price (`price_...`). Use `payment` for a one-time price or `subscription` for a recurring price. `.env.example` contains the safe template; `.env` is ignored by Git and excluded from the Docker build context. Keep your key private.

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d backend
```

Run that command again after changing `.env` so Compose recreates the backend with the new values. Use `up --build -d` when application code has changed. A bare restart does not reload environment values. The local override avoids a port conflict with a separately installed MongoDB. Docker uses its own database volume, so local MongoDB accounts are separate from Docker accounts.

If **Pay for premium** reports that payments are not configured, the key or price is missing. If checkout cannot open, check that both values belong to the same Stripe sandbox and that the checkout mode matches the price. A public test card alone cannot start checkout; a configured Stripe account is required.
