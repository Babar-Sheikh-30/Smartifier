# Smartifier by Byte Solutions

Smartifier delivers bite-sized knowledge by text message. The current application is a responsive landing page with membership information, example knowledge stacks, and an interactive text-message preview.

Members can register and log in, and members/admins have separate protected dashboards. Accounts, subscriptions, and versioned content stacks are stored in local MongoDB. Free members subscribe to three default stacks. Premium members can also create mixed-content stacks, customize independent copies of defaults, view version history, and submit drafts for approval. The premium button opens hosted Stripe Checkout when configured. SMS delivery is not implemented yet.

## Requirements

- Java JDK 25, available on your PATH. If set, `JAVA_HOME` must point to your JDK installation.
- Node.js matching `package.json`: Node 20.19+, 22.12+, or 24.x within those major versions. This workspace has been run with Node 22.19.0.
- npm, included with Node.js.
- MongoDB Community Server running locally (this workspace was verified against MongoDB 9.0.2). MongoDB Compass is optional for viewing accounts.
- Internet access for the first dependency installation.

The included Maven wrapper downloads Maven 3.9.11. You do not need to install Maven separately or install Angular globally.

## First-time setup

Open PowerShell in the repository root (the folder containing `pom.xml` and `package.json`):

```powershell
java -version
node --version
npm.cmd --version
npm.cmd ci

# Create the initial admin and start the application.
.\start-local.ps1 -SetupAdmin
```

Enter the admin email and password when prompted. Password input is hidden and is never saved to a file. The script temporarily supplies `SMARTIFIER_ADMIN_EMAIL` and `SMARTIFIER_ADMIN_PASSWORD` to the backend. The backend creates the admin only if that email is absent; it never resets an existing admin password or promotes a member. Remember your credentials; password recovery is not implemented yet.

The script uses JDK 25 from the Java installation on PATH, even if `JAVA_HOME` points to an older JDK. Supply `-JavaHome 'C:\path\to\jdk-25'` if needed. Maven dependencies are cached in `.m2/repository`; the wrapper distribution uses its normal user cache unless `MAVEN_USER_HOME` is set.

## Run the complete application

From the repository root:

```powershell
.\start-local.ps1
```

Open http://localhost:8080. Stop the application with Ctrl+C in its terminal.

Spring Boot serves a Thymeleaf HTML shell, and Angular renders the pages. Maven builds Angular into `frontend/dist/` and copies the files to `target/classes/static/app/` before starting the server.

After changing frontend files, stop the server and run the command again, then refresh your browser. Use Ctrl+F5 if it still displays an older version. Running `npm.cmd run build` alone only updates `frontend/dist/`; it does not copy the new files into Spring Boot's asset folder.

## Frontend development with live reload

```powershell
npm.cmd start
```

Keep Spring Boot running on port 8080, then open http://localhost:4200. Angular reloads the page when you save frontend changes and proxies `/api/**` to Spring Boot. Use one browser origin consistently during a session.

## Frontend tests

Run `npm test` (or `npm.cmd test` in PowerShell) from the repository root. The suite uses Angular's Vitest integration with jsdom and mocked HTTP requests; MongoDB and the backend do not need to be running.

Tests cover login and registration forms, password and phone validation, session state, CSRF-protected requests, and stack revision conflicts. GitHub Actions runs this suite in the frontend job.

For watch mode, run `npm run ng -- test --watch`.

## Stripe payments

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
.\start-local.ps1
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

## Free and premium member stacks

Both member tiers can subscribe to active published shared stacks. The three initial defaults are Everyday science, Words and language, and Learning habits; admins can add more. Each stack can contain any mixture of `FACT`, `QUOTE`, `JOKE`, and `VOCABULARY` items. Names are starting points, not restrictions on content types. Defaults are seeded with example content on startup, preserving their existing IDs and subscriptions. Restarting never overwrites existing stacks.

Premium members have a personal workspace on the same member dashboard:

- **Create a stack** opens an editor for its name, description, and mixed content items. Each item has a type, body, optional answer/definition/punchline, and optional attribution/source. Choose **Create stack** in the editor to save it into **Your personal stacks**; the editor closes and the saved card is highlighted. Each personal card has **Subscribe/Unsubscribe** controls.
- **Make a personal copy** copies the published default into an independent, privately owned draft. The original remains unchanged. Later changes to the original do not automatically change the copy.
- **Save changes** always saves a new content version for an existing personal stack. Previous content stays available under **Content and version history**. **Use this version as a draft** lets a member start a new version from an older snapshot.
- **Submit for approval** marks the saved version `PENDING_REVIEW`. Its content becomes read-only; further edits save another draft version. Submitting never approves or publishes content.

Only the owning premium member can access a personal stack through these APIs. Free members receive default summaries, without their content or personal editing controls. Server-side checks enforce membership, ownership, session authentication, and CSRF protection. New and legacy accounts default to free; registration cannot grant premium access. Conflicting saves return an error instead of overwriting another saved version.

Admin stack/content management and the premium-member review queue are available on the admin dashboard. Premium members can subscribe to their own saved personal stacks, including drafts, using the same subscription controls as defaults. Subscriptions stay private and require a mobile number; subscribing does not publish a stack or submit it for approval. Other members' personal stacks cannot be subscribed to. Text delivery is not implemented yet.

### Admin knowledge management

Sign in as an admin at `/login`, then use **Stack Management** on `/admin/dashboard` to create, select, edit, or delete shared stacks. New stacks begin empty and unpublished. Under **Content Management**, create or edit content with a type, body, optional answer/context, and optional attribution. Individual content has no title or description fields in the editor or cards; stack names and descriptions remain. Older stored item metadata is retained in history but not displayed. Content belongs to the selected stack.

**Save** retains draft changes. **Publish** publishes only the selected saved item and the current stack name/description; unrelated draft items and edits remain unpublished. **Publish stack details** publishes changed stack metadata without publishing draft items. Both free and premium members can subscribe to active published shared stacks; premium members retain their independent-copy and personal-editing features. Stripe and premium activation are unchanged.

Removal requires confirmation. Removing content takes it out of both working and published content while retaining historical snapshots. Removing the last published item makes the stack unavailable until content is published again. Deleting a stack archives it, preserving history and independent personal copies; seeded stacks therefore stay removed across restarts. Existing subscription IDs may remain stored but unavailable stacks cannot receive new subscriptions or copies; members can still stop an existing subscription. Future message delivery must check current availability.

Stack/content admin APIs use `/api/admin/stacks`, require ADMIN authorization, and require CSRF on mutations. Updates include the loaded `expectedRevision`; conflicting edits return `409` and require reloading. Stacks retain the existing 50-item/40-version limits; publishing generally stores a published snapshot plus a working draft and consumes two versions. Stack archival remains possible at the history limit. Legacy content without IDs remains readable; IDs are persisted when the admin next saves the stack. No database migration is required.

### Removing personal stacks and cancelling approval requests

Creating or saving a personal stack requires a nonblank name (up to 100 characters) and description (up to 500 characters). The editor explains missing fields and other validation failures without discarding your changes. The backend enforces these requirements too.

Premium members can choose **Remove stack** on a personal stack, then confirm **Remove stack** or choose **Keep stack**. Removal archives the stack and its version history, hides it from the workspace and admin review inbox, and stops its subscription. It does not change the shared default stack that a personal copy came from. Archived stacks cannot be edited, resubmitted, or subscribed to, and there is no restore control.

Choose **Cancel approval request** on a pending stack to return that submitted version to Draft. Pending older versions have their own cancellation button in version history. Cancellation preserves the content and newer versions; the cancelled version leaves the admin queue. The latest draft can be submitted again. If an admin decision or another edit already changed the stack, reload before retrying.

### Premium-member review inbox

Premium members use **Submit for approval** on a saved draft. The admin **Review inbox** lists pending versions from current premium members, oldest submissions first. Expand a card to inspect its submitted content, member, version, and submission date. Approvals can include optional feedback. Denials require a standard reason (inappropriate content, inaccurate information, unreliable sources, incomplete content, duplicates, or off-topic content) or a custom explanation; **Other** requires an explanation. The selected reason and explanation are saved together as member-visible feedback, up to 500 characters total. The reviewed version becomes `APPROVED` or `REJECTED`, leaves the queue, and displays the decision/feedback in the member's version history after reloading. A denied member can edit a new draft and submit again.

Stack/content editor errors appear within the active form and receive keyboard focus with automatic scrolling, including backend save failures. Entered values are retained so administrators can correct them.

Decisions target the submitted version, including when a newer draft already exists. They preserve content snapshots, newer drafts, ownership, subscriptions, and publication pointers. Approval does not publish a personal stack to the shared catalog; it stays private. Downgraded members' submissions are excluded from the active queue and cannot be decided until premium access is restored. Reviews change an existing version's status rather than consuming another history slot.

`GET /api/admin/reviews` lists the inbox. `POST /api/admin/reviews/{stackId}/versions/{number}/decision` accepts `{expectedRevision, approved, note}` and returns `204`. Both endpoints require ADMIN; mutations also require CSRF. Repeat/stale decisions return `409`, invalid fields return `400`, and missing/private-ineligible targets are rejected. The existing member submission endpoint still enforces premium membership and ownership.

### Preview premium locally

Until Stripe membership webhooks are implemented, use MongoDB Compass to provision a local test member:

1. Register a normal member in the app.
2. In Compass, connect to your local MongoDB and open the application's `smartifier` database, then the `users` collection (adjust the database name if you configured a different one).
3. Find that exact account by email. Add or update `premium` to the **Boolean** value `true`, then save. Keep its `role` as `MEMBER`.
4. Refresh the member dashboard. The personal stack editor should appear. Set `premium` back to Boolean `false` to test free access; saved personal stacks are retained but inaccessible while free.

This is a local testing step, not an automatic result of Stripe payment. Never use a client-side premium switch to grant access.

### MongoDB structure

`users` keeps account data, the trusted `premium` flag, and the existing default subscription IDs. `content_stacks` stores one document per default or personal stack:

| Field | Purpose |
| --- | --- |
| `_id` | Stable stack ID |
| `ownerId` / `defaultStack` | Member ownership or a shared default |
| `sourceStackId` / `sourceVersion` | The original default and version used for a personal copy |
| `publishedVersion` | Approved version available for publication; personal drafts start without one |
| `versions[]` | Numbered content snapshots with name, description, items, status, timestamps, and review note |
| `versions[].items[]` | Ordered content with `type`, `body`, `answer`, and `attribution` |
| `revision` | Optimistic locking counter to prevent concurrent updates from losing history |

Versions and items are embedded so each save is atomic without MongoDB multi-document transactions. Limits of 40 versions, 50 items per version, and bounded text fields keep documents below MongoDB's document size limit, including with multilingual content. Content text allows 1,000 characters per body/answer, attribution allows 300, stack names allow 100, and descriptions allow 500. Review status supports `DRAFT`, `PENDING_REVIEW`, `APPROVED`, and `REJECTED`; member endpoints only create drafts and submit them for review.

Member stack APIs are under `/api/member/stacks`: `GET /defaults`, `GET /`, `POST /` to create, `POST /{id}/copy`, `POST /{id}/versions`, `POST /{id}/submit`, `POST /{id}/versions/{number}/cancel-submission`, and `POST /{id}/remove`. Save, submit, cancellation, and removal requests include `expectedRevision` from the last loaded stack. Cancellation and removal require this field and reject missing or negative revisions. Removal returns HTTP 204. All mutations require a CSRF token; personal stack management requires premium membership and ownership.

## Local accounts

The default MongoDB connection is `mongodb://localhost:27017/smartifier`. Accounts live in the `users` collection. Override the connection with `MONGODB_URI` if needed. Compass can connect to `mongodb://localhost:27017` to inspect the data.

Open `/register` to create a member with name, email, mobile number including country code (such as `14165551234`), password, and confirmation. The leading `+` is optional in registration and the dashboard; the backend adds it when storing the number. For Canada/US, include the `1` before the ten-digit number. Registration redirects to `/login`. Members and admins share the same login page and are directed to `/member/dashboard` and `/admin/dashboard`, respectively. Each dashboard displays the account information and a logout button. Members can update their mobile number and subscribe or unsubscribe from the three free stacks. Premium members also have the personal stack workspace described above. Existing accounts without a mobile number must add one before subscribing.

The backend stores BCrypt password hashes and creates a unique index on normalized emails. Registration always assigns `MEMBER`, even if a client supplies an admin role. Resolve any pre-existing duplicate emails before startup so the index can be created.

New passwords must contain 12–64 characters, including an uppercase letter, a lowercase letter, a number, and a special character, and at most 72 UTF-8 bytes. Existing passwords continue to work at login. Password confirmation is validated and never stored. Authentication uses HTTP-only session cookies, CSRF tokens, and backend role enforcement. Sessions expire after 30 minutes of inactivity and are lost when the backend restarts; accounts persist on disk. Set `SESSION_COOKIE_SECURE=true` when deploying behind HTTPS.

## Build and check

```powershell
# Frontend build and formatting check
npm.cmd run build
npm.cmd run format:check

# Maven commands require JAVA_HOME to point to JDK 25 (adjust this path).
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'
# Unit tests do not need MongoDB.
.\mvnw.cmd -B -ntp test
# Include HTTP account tests against local MongoDB.
.\mvnw.cmd -B -ntp '-Dsmartifier.mongo.tests=true' test
# Build both parts, run unit tests, and create the executable JAR.
.\mvnw.cmd clean package

# Run the packaged application
java -jar target/smartifier-0.0.1-SNAPSHOT.jar
```

Use `npm.cmd run format` to apply Prettier formatting. Generated files in `frontend/dist/` and `target/` are ignored by Git.

MongoDB integration tests use uniquely named `smartifier_auth_test_*` and `smartifier_stacks_test_*` databases and remove them afterward. They do not use the application's `smartifier` database. Stack tests cover seeding, persistence, premium restrictions, CSRF, independent copies, ownership, stale edits, and approval submission.

## Project layout

```text
Smartifier/
  frontend/
    src/app/app.ts         Router shell
    src/app/landing.*      Landing page and knowledge-stack preview
    src/app/auth-page.*    Registration and login forms
    src/app/auth.service.ts Session and authentication requests
    src/app/routes.ts      Routes and dashboard guards
    src/app/dashboard.ts   Member/admin dashboard placeholders
    src/styles.scss       Styling and responsive layouts
    src/main.ts           Angular entry point
    src/index.html        Frontend development HTML shell
    angular.json          Angular build and development server settings
  src/main/
    java/com/bytesolutions/smartifier/
      SmartifierApplication.java          Spring Boot entry point
      controllers/          HTML shell, account API, and validation errors
      config/               Spring Security configuration
      models/               MongoDB account document
      repositories/         Account storage
      services/             Registration and authentication
      bootstrap/            Unique email index and initial admin setup
    resources/
      templates/landing.html  Thymeleaf HTML shell
      application.properties  Spring configuration
  .mvn/                   Maven wrapper configuration
  mvnw / mvnw.cmd         Maven wrapper scripts
  start-local.ps1         Local startup and optional initial admin prompts
  pom.xml                 Java dependencies and combined build
  package.json            Frontend and formatting commands
  package-lock.json       Locked npm dependency versions
```

Additional empty folders are placeholders for future features. Angular uses version 20.3.33 and Spring Boot uses version 4.1.1, as configured in the package files and `pom.xml`.

## Troubleshooting

- **PowerShell blocks `npm`:** use `npm.cmd`, as shown above.
- **Java compilation reports an unsupported release:** check `java -version` and `.\mvnw.cmd --version`; Maven must use JDK 25.
- **PowerShell blocks `start-local.ps1`:** use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1 -SetupAdmin` for the first launch. Omit `-SetupAdmin` on later launches. The policy override applies to that process only.
- **Port 8080 is already in use:** stop the other server or run `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'` and open http://localhost:8081.
- **`mvn.cmd` reports Maven is missing:** use `.\mvnw.cmd` instead. The `mvn.cmd` shortcut depends on an ignored local installation in `.tools/`.
- **MongoDB connection refused or startup hangs:** ensure the MongoDB Windows service is running and Compass can connect to the configured URI.

See [frontend/README.md](frontend/README.md) for frontend-specific instructions.
