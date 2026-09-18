package com.jovanstar.unstablecore;

import com.jovanstar.unstablecore.util.MessageUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Security coverage for {@link MessageUtil#escapeUserInput}, which is the single barrier stopping a
 * player-supplied string (names, bounty targets, search terms) from carrying live MiniMessage tags
 * or legacy colour codes into a broadcast.
 */
class UserInputEscapeTest {

    @Test
    void stripsMiniMessageTagOpener() {
        // A raw '<' is what makes a MiniMessage tag live; neutralising it makes the rest inert.
        assertFalse(MessageUtil.escapeUserInput("<red>hi").contains("<"));
        assertFalse(MessageUtil.escapeUserInput("<click:run_command:'/op me'>x").contains("<"));
        assertFalse(MessageUtil.escapeUserInput("<<<").contains("<"));
    }

    @Test
    void stripsLegacySectionSign() {
        assertFalse(MessageUtil.escapeUserInput("§cRED").contains("§"));
        assertFalse(MessageUtil.escapeUserInput("a§b§c").contains("§"));
    }

    @Test
    void nullAndEmptyBecomeEmptyRatherThanThrowing() {
        assertEquals("", MessageUtil.escapeUserInput(null));
        assertEquals("", MessageUtil.escapeUserInput(""));
    }

    @Test
    void ordinaryTextSurvivesUnchanged() {
        // Escaping must not mangle normal names, or every message using one looks broken.
        assertEquals("SkilledSeeker", MessageUtil.escapeUserInput("SkilledSeeker"));
        assertEquals("a_b-c123", MessageUtil.escapeUserInput("a_b-c123"));
    }

    @Test
    void ampersandCodesAreNotResurrectedIntoColours() {
        // '&' alone is not stripped, so confirm the escaped form still contains no live opener
        // that a downstream legacy parse could turn into formatting.
        String escaped = MessageUtil.escapeUserInput("&cRED<green>");
        assertFalse(escaped.contains("<"));
        assertFalse(escaped.contains("§"));
    }
}
