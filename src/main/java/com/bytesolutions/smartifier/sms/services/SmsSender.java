package com.bytesolutions.smartifier.sms.services;

/**
 * Defines how Smartifier sends SMS messages.
 * A provider implementation will be added later.
 */
public interface SmsSender {
    void send(String phoneNumber, String message);
}