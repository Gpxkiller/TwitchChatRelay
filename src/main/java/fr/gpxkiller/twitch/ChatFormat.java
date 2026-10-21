package fr.gpxkiller.twitch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Literal text only: Twitch content cannot inject formatting or chat actions. */
final class ChatFormat {
    private ChatFormat() { }

    static Component message(String channel, String author, String text) {
        return Component.text("[Twitch]", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(" [" + RelayRules.plain(channel, 25) + "] ", NamedTextColor.GRAY))
            .append(Component.text(RelayRules.plain(author, 32) + " : ", NamedTextColor.YELLOW))
            .append(Component.text(RelayRules.plain(text, 300), NamedTextColor.YELLOW));
    }
}
