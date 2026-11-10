[Back to project README](../README.md)

# Free and premium member stacks

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

To preview premium without completing a Stripe test payment, use MongoDB Compass to provision a local test member:

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
