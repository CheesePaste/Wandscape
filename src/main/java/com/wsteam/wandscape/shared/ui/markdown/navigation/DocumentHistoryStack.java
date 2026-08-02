package com.wsteam.wandscape.shared.ui.markdown.navigation;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Browser-like back/forward navigation history stack for Markdown documentation.
 * Pure Java 21 implementation.
 */
public class DocumentHistoryStack {

    private final Deque<String> backStack = new ArrayDeque<>();
    private final Deque<String> forwardStack = new ArrayDeque<>();
    private String currentDocument;

    public DocumentHistoryStack(String initialDocument) {
        this.currentDocument = initialDocument;
    }

    /**
     * Navigate to a new document location.
     * Pushes current document onto back stack and clears forward stack.
     */
    public void navigateTo(String newDocument) {
        if (newDocument == null || newDocument.isBlank()) {
            return;
        }
        if (currentDocument != null && !currentDocument.equals(newDocument)) {
            backStack.push(currentDocument);
            forwardStack.clear();
        }
        this.currentDocument = newDocument;
    }

    /**
     * Navigate back to the previous document.
     * @return The previous document path/ID, or current if back stack empty.
     */
    public String goBack() {
        if (!canGoBack()) {
            return currentDocument;
        }
        forwardStack.push(currentDocument);
        currentDocument = backStack.pop();
        return currentDocument;
    }

    /**
     * Navigate forward to the next document.
     * @return The forward document path/ID, or current if forward stack empty.
     */
    public String goForward() {
        if (!canGoForward()) {
            return currentDocument;
        }
        backStack.push(currentDocument);
        currentDocument = forwardStack.pop();
        return currentDocument;
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    public String getCurrentDocument() {
        return currentDocument;
    }

    public void clear() {
        backStack.clear();
        forwardStack.clear();
    }
}
