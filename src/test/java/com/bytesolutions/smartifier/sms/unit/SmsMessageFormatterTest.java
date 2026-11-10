package com.bytesolutions.smartifier.sms.unit;

import com.bytesolutions.smartifier.sms.services.SmsMessageFormatter;

import com.bytesolutions.smartifier.content.models.ContentStack.Item;
import com.bytesolutions.smartifier.content.models.ContentStack.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmsMessageFormatterTest {

    private final SmsMessageFormatter formatter = new SmsMessageFormatter();

    @Test
    void formatsVocabularyMessage() {
        Item item = new Item(
                Type.VOCABULARY,
                "Eloquent means fluent and persuasive in speaking or writing.",
                null,
                null
        );

        String result = formatter.format(item);

        assertEquals(
                "Smartifier - VOCABULARY\nEloquent means fluent and persuasive in speaking or writing.",
                result
        );
    }

    @Test
    void formatsJokeWithAnswer() {
        Item item = new Item(
                Type.JOKE,
                "Why did the computer get cold?",
                "It left its Windows open.",
                null
        );

        String result = formatter.format(item);

        assertEquals(
                "Smartifier - JOKE\nWhy did the computer get cold?\nAnswer: It left its Windows open.",
                result
        );
    }

    @Test
    void formatsQuoteWithAttribution() {
        Item item = new Item(
                Type.QUOTE,
                "The journey of a thousand miles begins with a single step.",
                null,
                "Lao Tzu"
        );

        String result = formatter.format(item);

        assertEquals(
                "Smartifier - QUOTE\nThe journey of a thousand miles begins with a single step.\n- Lao Tzu",
                result
        );
    }
}