package br.com.playbox;

import br.com.playbox.client.WhatsAppSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppSenderTest {

    @Test
    void testSenderProperties() {
        WhatsAppSender sender = new WhatsAppSender(
                "http://whatsapp_bot:3035",
                "my-secret-key",
                "120363430600021348@g.us",
                "/api/messages/send",
                30,
                null
        );

        assertEquals("120363430600021348@g.us", sender.getChatId());
        assertEquals("/api/messages/send", sender.getSendPath());
    }

    @Test
    void testSenderEmptyMessage() {
        WhatsAppSender sender = new WhatsAppSender(
                "http://whatsapp-bot:3035",
                "my-secret-key",
                "120363430600021348@g.us",
                "/api/messages/send",
                30,
                null
        );

        assertFalse(sender.sendMessage(""));
        assertFalse(sender.sendMessage("   "));
        assertFalse(sender.sendMessage(null));
    }

    @Test
    void testSenderEmptyChatId() {
        WhatsAppSender sender = new WhatsAppSender(
                "http://whatsapp-bot:3035",
                "my-secret-key",
                "",
                "/api/messages/send",
                30,
                null
        );

        assertFalse(sender.sendMessage("Hello"));
    }

    @Test
    void testHostnameSanitizationDoesNotThrow() {
        // Verifies that a hostname with an underscore (like docker container name whatsapp_bot)
        // is sanitized and does not throw IllegalArgumentException ("unsupported URI")
        WhatsAppSender sender = new WhatsAppSender(
                "http://whatsapp_bot:3035",
                "my-secret-key",
                "120363430600021348@g.us",
                "/api/messages/send",
                1,
                null
        );

        // sendMessage will fail to connect (since no server is running at that address during unit tests),
        // but it must return false gracefully and NOT throw IllegalArgumentException!
        assertFalse(sender.sendMessage("Hello test"));
    }
}
