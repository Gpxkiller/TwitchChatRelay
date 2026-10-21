package fr.gpxkiller.twitch;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatFormatTest {
    @Test void authorAndBodyAreYellowButTagStaysPurple() {
        var message=ChatFormat.message("examplechannel", "Viewer", "Bonjour");
        assertEquals(NamedTextColor.LIGHT_PURPLE,message.color());
        assertEquals(NamedTextColor.YELLOW,message.children().get(1).color());
        assertEquals(NamedTextColor.YELLOW,message.children().get(2).color());
    }
    @Test void startsWithExactPurpleTwitchTag() {
        var message = ChatFormat.message("examplechannel", "Viewer", "Bonjour");
        assertEquals("[Twitch]", ((TextComponent) message).content());
        assertEquals(NamedTextColor.LIGHT_PURPLE, message.color());
        assertNull(message.clickEvent());
        assertEquals(" [examplechannel] ", ((TextComponent) message.children().get(0)).content());
    }

    @Test void contentRemainsLiteralAndSanitized() {
        var message = ChatFormat.message("examplechannel", "A§c", "<click:run_command:/op A>salut\n");
        assertEquals("Ac : ", ((TextComponent) message.children().get(1)).content());
        var body = (TextComponent) message.children().get(2);
        assertEquals("<click:run_command:/op A>salut", body.content());
        assertNull(body.clickEvent());
    }
}
