package com.wsteam.wandscape.shared.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * Regression: MC's translatable network codec only accepts Number / Boolean / String / Component
 * arg values ({@code TranslatableContents#filterAllowedArguments}); raw objects like {@link Path}
 * or {@link BlockPos} previously failed the server&rarr;client chat encode and kicked the player
 * (see 1.9.2 export-builder crash). {@link I18n#sanitize} must coerce them to literal components.
 */
class I18nTest {

    @Test
    void primitivesAndStringsPassThroughUnchanged() {
        Integer n = 42;
        Boolean flag = Boolean.TRUE;
        String text = "text";

        Object[] out = I18n.sanitize(new Object[]{n, flag, text});

        assertSame(n, out[0]);
        assertSame(flag, out[1]);
        assertSame(text, out[2]);
    }

    @Test
    void componentArgsPassThroughByIdentity() {
        Component comp = Component.literal("hello");

        Object[] out = I18n.sanitize(new Object[]{comp});

        assertSame(comp, out[0]);
    }

    @Test
    void rawBlockPosIsCoercedToLiteralComponent() {
        BlockPos pos = new BlockPos(1, 2, 3);

        Object[] out = I18n.sanitize(new Object[]{pos});

        assertTrue(out[0] instanceof Component);
        assertEquals(String.valueOf(pos), ((Component) out[0]).getString());
    }

    @Test
    void rawPathIsCoercedToLiteralComponent() {
        Path path = Path.of("datapacks", "build.json");

        Object[] out = I18n.sanitize(new Object[]{path});

        assertTrue(out[0] instanceof Component);
        assertEquals(String.valueOf(path), ((Component) out[0]).getString());
    }

    @Test
    void mixedArgsKeepSafeValuesAndWrapOnlyOffendingOnes() {
        Component comp = Component.literal("keep");
        BlockPos pos = new BlockPos(4, 5, 6);
        Object[] out = I18n.sanitize(new Object[]{"id", comp, pos});

        assertSame("id", out[0]);
        assertSame(comp, out[1]);
        assertTrue(out[2] instanceof Component);
        assertEquals(String.valueOf(pos), ((Component) out[2]).getString());
    }
}