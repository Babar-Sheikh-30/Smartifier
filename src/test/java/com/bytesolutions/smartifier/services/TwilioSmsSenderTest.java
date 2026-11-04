
package com.bytesolutions.smartifier.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TwilioSmsSenderTest {

    @Test
    void doesNotSendWhenSmsIsDisabled() {
        TwilioSmsSender sender = new TwilioSmsSender(
                false, "", "", ""
        );

        assertThrows(
                IllegalStateException.class,
                () -> sender.send(
                        "+14165551234",
                        "Smartifier test message"
                )
        );
    }

    @Test
    void rejectsMissingTwilioConfiguration() {
        TwilioSmsSender sender = new TwilioSmsSender(
                true, "", "", ""
        );

        assertThrows(
                IllegalStateException.class,
                () -> sender.send(
                        "+14165551234",
                        "Smartifier test message"
                )
        );
    }
}
