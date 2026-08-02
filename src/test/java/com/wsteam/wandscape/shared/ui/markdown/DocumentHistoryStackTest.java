package com.wsteam.wandscape.shared.ui.markdown;

import com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentHistoryStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DocumentHistoryStackTest {

    @Test
    void testNavigationAndBackForward() {
        DocumentHistoryStack stack = new DocumentHistoryStack("doc_main");
        assertEquals("doc_main", stack.getCurrentDocument());
        assertFalse(stack.canGoBack());
        assertFalse(stack.canGoForward());

        // Navigate to doc_townhall
        stack.navigateTo("doc_townhall");
        assertEquals("doc_townhall", stack.getCurrentDocument());
        assertTrue(stack.canGoBack());
        assertFalse(stack.canGoForward());

        // Navigate to doc_warehouse
        stack.navigateTo("doc_warehouse");
        assertEquals("doc_warehouse", stack.getCurrentDocument());

        // Go Back -> doc_townhall
        String back1 = stack.goBack();
        assertEquals("doc_townhall", back1);
        assertTrue(stack.canGoBack());
        assertTrue(stack.canGoForward());

        // Go Back -> doc_main
        String back2 = stack.goBack();
        assertEquals("doc_main", back2);
        assertFalse(stack.canGoBack());
        assertTrue(stack.canGoForward());

        // Go Forward -> doc_townhall
        String fwd1 = stack.goForward();
        assertEquals("doc_townhall", fwd1);
        assertTrue(stack.canGoBack());
        assertTrue(stack.canGoForward());

        // Navigate to new doc_shop -> should clear forward stack
        stack.navigateTo("doc_shop");
        assertEquals("doc_shop", stack.getCurrentDocument());
        assertTrue(stack.canGoBack());
        assertFalse(stack.canGoForward());
    }
}
