package com.bytesolutions.smartifier.sms;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import com.bytesolutions.smartifier.account.Account;
import com.bytesolutions.smartifier.content.ContentStack;
import com.bytesolutions.smartifier.content.ContentStack.Item;
import com.bytesolutions.smartifier.content.ContentStack.Status;
import com.bytesolutions.smartifier.content.ContentStackRepository;

import org.springframework.stereotype.Service;

@Service
public class SmsContentService {

    private final ContentStackRepository stacks;

    public SmsContentService(ContentStackRepository stacks) {
        this.stacks = stacks;
    }

    // Existing method preserved for compatibility.
    public List<Item> eligibleItems(Account account) {
        return eligibleContent(account).stream().map(EligibleContent::item).toList();
    }

    private List<Item> publishedItems(ContentStack stack) {
        return stack.versions().stream()
                .filter(version -> version.number() == stack.publishedVersion()
                        && version.status() == Status.APPROVED)
                .findFirst()
                .map(ContentStack.Revision::items)
                .orElseGet(List::of);
    }

    // SMS delivery metadata, including a stable content identifier.
    public record EligibleContent(String contentKey, Item item) {}

    /**
     * Returns eligible published items with identifiers that can
     * be used to track delivery history.
     */
    public List<EligibleContent> eligibleContent(Account account) {
        if (account == null || account.role() != Account.Role.MEMBER
                || account.subscriptions().isEmpty()) {
            return List.of();
        }

        return account.subscriptions().stream()
                .map(stacks::findById)
                .flatMap(java.util.Optional::stream)
                .filter(stack -> !stack.archived())
                .filter(stack -> stack.publishedVersion() != null)
                .filter(stack -> stack.defaultStack()
                        || (account.premium()
                        && Objects.equals(stack.ownerId(), account.id())))
                .flatMap(stack -> {
                    List<Item> items = publishedItems(stack);

                    return IntStream.range(0, items.size())
                            .mapToObj(index -> new EligibleContent(
                                    stack.id() + ":"
                                            + stack.publishedVersion() + ":"
                                            + index,
                                    items.get(index)
                            ));
                })
                .toList();
    }
}
