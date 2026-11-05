package com.example.githubwebhook.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscordTitleTest {

    @Test
    void title_short() {
        assertThat(DiscordClient.composeTitle(42, "Fix login")).isEqualTo("#42 Fix login");
    }

    @Test
    void title_exact_256() {
        String full = "#1 " + "a".repeat(253);
        assertThat(full).hasSize(256);
        assertThat(DiscordClient.composeTitle(1, "a".repeat(253))).isEqualTo(full);
    }

    @Test
    void title_too_long() {
        String result = DiscordClient.composeTitle(1, "a".repeat(254));
        assertThat(result).hasSize(256).endsWith("…");
    }

    @Test
    void title_surrogate_boundary() {
        // "#1 " is 3 units; 251 filler units put the emoji's high surrogate at index 254, its low one at 255.
        String emoji = new String(Character.toChars(0x1F600));
        String result = DiscordClient.composeTitle(1, "a".repeat(251) + emoji + "tail");
        assertThat(result.length()).isLessThanOrEqualTo(256);
        assertThat(result).endsWith("…");
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 2))).isFalse();
        assertThat(result).doesNotContain(emoji);
    }
}
