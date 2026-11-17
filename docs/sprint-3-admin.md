# Sprint 3 admin and shared content implementation

## Current state found

The current repository already had Premium personal stacks, independent shared-stack copies, saved content versions, frozen personal submissions, admin review with denial reasons, admin shared-stack draft editing, selected-item publication, archival, CSRF protection, role restrictions, expected revisions, and MongoDB optimistic locking. Personal approval did not publish or share a personal stack. These behaviors are preserved.

SMS additions already included saved phone numbers, independent SMS opt-in, followed stacks, Twilio integration, a daily scheduler (10 a.m. America/Toronto by default), published-content selection, archived-stack exclusion, atomic member/date and member/content claims, and PENDING/SENT/FAILED records. Sending and scheduling are disabled by default. SENT records provider acceptance, not confirmed handset delivery.

**Knowledge SMS delivery is incomplete:** `TwilioSmsSender.send` currently puts the literal `sms_account_alerts` in the provider's Body field instead of its supplied knowledge message. No SMS implementation files were changed in this work. The website explicitly says knowledge texts are not ready yet. Existing backend tests cover selection, preferences, formatting, and claims; this implementation did not perform live Twilio/handset testing.

Shared-item suggestions, their review/history UI, and an explicit publication confirmation were missing. The landing page also contained unimplemented claims about Premium receiving a higher message allowance.

## Implemented stories

1. Stack actions now say **Get Daily Text** and **Stop Daily Texts**. Shared cards identify enrollment as delivery pending. Help and success messages explain that this saves a choice for future knowledge delivery. Existing subscription endpoints and data are unchanged.
2. The landing page adds a clear value proposition, why small discoveries fit daily routines, accurate setup steps, examples consistent with built-in stacks, supported Free/Premium benefits, and register/login actions. It keeps existing styles, colors, preview behavior, and responsive grids. Unsupported higher-message-allowance claims were removed. Pricing remains in configured Stripe Checkout; this scope does not change prices.
3. Current Premium members can suggest one validated existing `ContentStack.Item` to an active published shared stack. The server records contributor, destination, original item, timestamp, and pending status. The shared versions and live content do not change at submission. Existing or pending duplicate content is rejected, ignoring case and outer whitespace in type/body/answer comparison.
4. The existing Review inbox shows both personal submissions and shared contributions. Contributor, destination, item, timestamp, feedback, and approve/deny actions use existing patterns. Approving a contribution accepts an admin draft, not public content. Archived destinations reject approval but still allow denial so pending records can be resolved.
5. Admin item publication and stack-detail publication now require an explicit shared-content confirmation. Canceling sends no publication request. The warning identifies the selected item, shared availability, future delivery eligibility, admin control of removal, and the fact that unrelated drafts remain unpublished. It makes no public-domain or copyright-transfer claim. Members cannot publish or delete shared content. The suggestion form explains potential future sharing before submission; personal submissions remain private and do not trigger a misleading publication warning.
6. Members can refresh their own contribution history and see Pending Review, Approved (accepted as an admin draft; publication separate), Denied, feedback, submitted content, and archived destination status. History remains available after Premium expires. Other contributors and unpublished admin content are not returned to the member.

## Contribution design and safeguards

Contributions are embedded on their destination `ContentStack` in the existing `content_stacks` collection. Missing fields on older documents read as empty lists; existing constructors remain compatible. No migration or extra Mongo collection is needed.

Submission appends the contribution using the destination's Mongo `@Version`. Review uses the existing admin controller/service and queue projection, extending entries with an optional contribution ID. The contribution ID selects the contribution decision endpoint; personal version endpoints and behavior are unchanged. Existing denial choices and feedback fields are reused.

**Premium suggestion -> Pending Review -> Admin approval -> Draft -> explicit selected-item Publish.** Approval and draft insertion use the existing admin version append operation in one document save. The contribution keeps its original snapshot even if an administrator later edits the draft. A failed optimistic save or version/item capacity check cannot persist a partial approval. A stale or repeated decision fails with a conflict. Publication and archival preserve contribution history.

Only explicitly published snapshots enter the existing SMS selection service. Tests verify that pending suggestions and approved drafts are excluded, publishing the selected suggestion adds only that item, and unrelated drafts remain excluded. The provider limitation described above remains separate from eligibility.

New submissions check Premium at submission time. Pending contributions remain reviewable after Premium expires. Member history is filtered server-side to the signed-in contributor; there is no caller-supplied contributor selector. Admin review endpoints keep the existing ADMIN/CSRF protections. Approval also resolves the reviewer as an ADMIN account.

## Tests and builds actually run

- `npm.cmd test`: final isolated run **67 passed across 9 files**. Covers terminology/enrollment, Premium suggestion availability, validation/submission, private history after expiry, admin review routing/actions, confirmation/cancel/stale handling, landing content and interactive previews, and existing regressions.
- `.\mvnw.cmd -B '-Dexec.skip=true' test`: **128 tests, 0 failures/errors, 4 skipped** before the new HTTP integration scenario was added. The skips were the opt-in Mongo integration tests.
- `.\mvnw.cmd -B '-Dexec.skip=true' '-Dsmartifier.mongo.tests=true' test`: **129 tests, 0 failures/errors/skips**, including the new persisted contribution scenario and existing personal review, selected-item publishing, account, payment, and SMS tests. Integration tests used isolated temporary databases, not the application's database.
- `.\mvnw.cmd -B '-DskipTests' package`: final run **BUILD SUCCESS**, including the production Angular build and executable Spring Boot JAR. This packaging command skips tests; test results are from the separate commands above.
- Prettier was applied to changed frontend files (the existing dashboard template retained its formatting to keep its change limited to wording). `git diff --check` passed.

Initial sandbox-only test attempts were blocked by filesystem/network permissions and were rerun with the required access. One later frontend attempt failed because concurrent production packaging cleared its generated test directory. The test suite was then rerun on its own and all 67 tests passed. Browser visual inspection and actual SMS delivery were not performed.

## Manual follow-up and limits

- The SMS teammate confirmed trial-account testing with verified recipient numbers. The fixed Body may be intentional for trial restrictions. Actual knowledge delivery needs an appropriate Twilio account/configuration, use of the supplied knowledge message, and real-delivery verification before it is advertised as available. Deployment also needs explicit sending/scheduling enablement. No credentials or enablement flags were changed here.
- The team/client should approve final public-content policy wording. This notice describes sharing within Smartifier and admin control; it does not claim content enters the legal public domain.
- Existing shared stacks retain their 50-item and 40-version limits. Approval fails safely when either limit prevents draft insertion. Contributions have a 500-record per-stack cap to bound embedded history growth; history is not automatically discarded. Larger-scale history retention would need a separate design.
- Contribution status records the review decision, not a permanent current-publication indicator. An approved item may later be published, edited, or removed by an admin.
- No Stripe Dashboard action is required by these six stories. No price or Stripe/webhook behavior was changed.
- Implementation branch: `feature/admin-revisions`. Local Docker containers were rebuilt with `docker compose up -d --build`; both services started and the homepage returned HTTP 200. No AWS deployment or merge to main was performed. Run the same command after future code changes to update the local containers.

## Files changed

All paths are relative to the repository root.

| File | Reason |
| --- | --- |
| `frontend/src/app/admin-content/admin-content.html` | Shared-content confirmation for publication. |
| `frontend/src/app/admin-content/admin-content.ts` | Require confirmation before sending publication requests. |
| `frontend/src/app/admin-content/admin-content.spec.ts` | Confirmation, cancellation, stale requests, and selected-item regression. |
| `frontend/src/app/admin-review/admin-review.html` | Shared-contribution labels and draft-only approval explanation. |
| `frontend/src/app/admin-review/admin-review.ts` | Contribution queue keys and decision feedback. |
| `frontend/src/app/admin-review/admin-review.service.ts` | Contribution decisions through the existing review API. |
| `frontend/src/app/admin-review/admin-review.spec.ts` | Contribution review display and approval behavior. |
| `frontend/src/app/admin-review/admin-review.service.spec.ts` | Decision URL, revision, and CSRF coverage. |
| `frontend/src/app/dashboard/dashboard.html` | Stack terminology and accurate incomplete-delivery wording only. |
| `frontend/src/app/landing/landing.html` | Value proposition, accurate benefits and availability, setup, and calls to action. |
| `frontend/src/app/landing/landing.scss` | Existing-theme value cards, call to action, and mobile wrapping. |
| `frontend/src/app/landing/landing.ts` | Preview quote matching built-in content rather than an unsupported scientific claim. |
| `frontend/src/app/landing/landing.spec.ts` (new) | Landing content, account links, and preview interaction. |
| `frontend/src/app/member-stacks/member-stacks.html` | Enrollment terminology, suggestion form, and private contribution history. |
| `frontend/src/app/member-stacks/member-stacks.ts` | Suggestion state, validation/submission, history loading, and action guards. |
| `frontend/src/app/member-stacks/member-stacks.spec.ts` | Enrollment, Premium visibility, validation, submission, and history regressions. |
| `frontend/src/app/member-stacks/stack.service.ts` | Contribution result types, submission, and own-history API calls. |
| `frontend/src/app/member-stacks/stack.service.spec.ts` | Contribution HTTP payload, CSRF, and history request tests. |
| `frontend/src/index.html` | Accurate landing-page metadata. |
| `src/main/java/com/bytesolutions/smartifier/content/models/ContentStack.java` | Backward-compatible embedded contributions and history-preserving helpers. |
| `src/main/java/com/bytesolutions/smartifier/content/repositories/ContentStackRepository.java` | Pending contribution and contributor-history queries. |
| `src/main/java/com/bytesolutions/smartifier/content/controllers/ContentStackController.java` | Validated member submission and own-history endpoints. |
| `src/main/java/com/bytesolutions/smartifier/content/controllers/AdminReviewController.java` | Contribution decision endpoint under existing admin protection. |
| `src/main/java/com/bytesolutions/smartifier/content/services/ContentStackService.java` | Premium suggestions, destination checks, duplicate protection, and private history. |
| `src/main/java/com/bytesolutions/smartifier/content/services/AdminReviewService.java` | Merge contribution entries into the existing queue and identify admin reviewer. |
| `src/main/java/com/bytesolutions/smartifier/content/services/AdminContentService.java` | Atomic approval plus draft insertion; retain history across publishing and archival. |
| `src/main/java/com/bytesolutions/smartifier/web/ApiExceptionHandler.java` | Contribution-specific validation feedback. |
| `src/test/java/com/bytesolutions/smartifier/content/unit/SharedContributionTest.java` (new) | Eligibility, persistence, duplicate/stale/racing decisions, archival, privacy, and SMS publication eligibility. |
| `src/test/java/com/bytesolutions/smartifier/content/unit/AdminReviewServiceTest.java` | Wire the reused admin content service into existing personal-review regressions. |
| `src/test/java/com/bytesolutions/smartifier/content/integration/AdminContentIntegrationTest.java` | Real HTTP/Mongo contribution flow with security, privacy, draft-only approval, publication, and archival. |
| `docs/sprint-3-admin.md` (new) | This implementation report, limitations, validation, and file inventory. |

## Git diff review

Every changed file belongs to the requested terminology, landing page, shared contributions, review/history, publication confirmation, tests, or this report. SMS implementation files, security configuration, deployment workflow, dependency manifests, Stripe/payment code, and backend subscription APIs are unchanged. Publishing the feature branch for review does not merge it into main or publish a production image through the current workflow.
