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
                "http://whatsapp_bot:3035",
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
                "http://whatsapp_bot:3035",
                "my-secret-key",
                "",
                "/api/messages/send",
                30,
                null
        );

        assertFalse(sender.sendMessage("Hello"));
    }
}
