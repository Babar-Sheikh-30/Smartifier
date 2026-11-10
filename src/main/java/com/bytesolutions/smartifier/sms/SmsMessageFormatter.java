package com.bytesolutions.smartifier.sms;

import com.bytesolutions.smartifier.content.ContentStack.Item;

public class SmsMessageFormatter {

    public String format(Item item) {
        StringBuilder message = new StringBuilder("Smartifier - ");

        message.append(item.type().name()).append("\n");
        message.append(item.body());

        if (item.answer() != null && !item.answer().isBlank()) {
            message.append("\nAnswer: ").append(item.answer());
        }

        if (item.attribution() != null && !item.attribution().isBlank()) {
            message.append("\n- ").append(item.attribution());
        }

        return message.toString();
    }
}