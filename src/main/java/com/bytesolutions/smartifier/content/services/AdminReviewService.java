package com.bytesolutions.smartifier.content.services;

import com.bytesolutions.smartifier.content.models.ContentStack;
import com.bytesolutions.smartifier.content.repositories.ContentStackRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.content.models.ContentStack.Revision;
import com.bytesolutions.smartifier.content.models.ContentStack.Status;
import com.bytesolutions.smartifier.account.repositories.AccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Review frozen submissions without editing their content or exposing personal stacks publicly. */
@Service
public class AdminReviewService {
    private final ContentStackRepository stacks;
    private final AccountRepository accounts;
    private final AdminContentService content;

    public AdminReviewService(ContentStackRepository stacks, AccountRepository accounts, AdminContentService content) {
        this.stacks = stacks;
        this.accounts = accounts;
        this.content = content;
    }

    public record Entry(String stackId, long revision, String memberName, String memberEmail, Revision version, String contributionId) {
        public Entry(String stackId, long revision, String memberName, String memberEmail, Revision version) {
            this(stackId, revision, memberName, memberEmail, version, null);
        }
    }

    public List<Entry> pending() {
        var queue = new ArrayList<Entry>();
        for (var stack : stacks.findPendingReviews()) {
            if (stack.defaultStack() || stack.archived() || stack.ownerId() == null) continue;
            var owner = accounts.findById(stack.ownerId()).filter(this::premiumMember);
            if (owner.isEmpty()) continue;
            for (var version : stack.versions()) {
                if (version.status() == Status.PENDING_REVIEW) {
                    queue.add(new Entry(stack.id(), stack.revision(), owner.get().name(), owner.get().email(), version));
                }
            }
        }
        for (var stack : stacks.findPendingContributions()) {
            if (!stack.defaultStack() || stack.ownerId() != null) continue;
            for (var contribution : stack.contributions()) {
                if (contribution.status() != Status.PENDING_REVIEW) continue;
                var owner = accounts.findById(contribution.contributorId());
                // Premium is checked at submission. An expiry must not hide pending history.
                var snapshot = new Revision(0, stack.latest().name(), "Suggested item for shared stack"
                        + (stack.archived() ? " (archived)" : ""), List.of(contribution.item()), Status.PENDING_REVIEW,
                        contribution.submittedAt(), contribution.submittedAt(), null);
                queue.add(new Entry(stack.id(), stack.revision(), owner.map(Account::name).orElse("Former member"),
                        owner.map(Account::email).orElse(""), snapshot, contribution.id()));
            }
        }
        queue.sort(Comparator.comparing(entry -> entry.version().submittedAt(), Comparator.nullsLast(Comparator.naturalOrder())));
        return queue;
    }

    public void decideContribution(String id, String contributionId, long expectedRevision, boolean approved, String note, String email) {
        var reviewer = accounts.findByEmail(email)
                .filter(a -> a.role() == Account.Role.ADMIN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required."));
        content.reviewContribution(id, contributionId, expectedRevision, approved, note, reviewer.id());
    }

    public void decide(String id, int number, long expectedRevision, boolean approved, String note) {
        if (!approved && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A denial reason is required. Select a reason or write an explanation.");
        }
        var stack = stacks.findById(id)
                .filter(s -> !s.defaultStack() && !s.archived() && s.ownerId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted stack not found."));
        if (accounts.findById(stack.ownerId()).filter(this::premiumMember).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only premium member submissions can be reviewed.");
        }
        if (stack.revision() == null || stack.revision() != expectedRevision) throw conflict();
        var versions = new ArrayList<>(stack.versions());
        int index = stack.versionIndex(number);
        if (index < 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted version not found.");
        var submitted = versions.get(index);
        if (submitted.status() != Status.PENDING_REVIEW) throw conflict();
        versions.set(index, submitted.withReview(
                approved ? Status.APPROVED : Status.REJECTED, submitted.submittedAt(),
                note == null || note.isBlank() ? null : note.strip()));
        // A decision updates only this submission. Newer drafts, ownership and publication remain intact.
        try {
            stacks.save(stack.withVersions(versions));
        } catch (OptimisticLockingFailureException exception) { throw conflict(); }
    }

    private boolean premiumMember(Account account) { return account.role() == Account.Role.MEMBER && account.premium(); }
    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "This submission has changed or was already reviewed. Refresh the queue before deciding.");
    }
}
