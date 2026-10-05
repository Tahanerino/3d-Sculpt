package com.tahanerino.sculpt3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Simple action-stack undo/redo. Each user-visible edit (a sculpt stroke, a paint stroke, a
 * fill, a transform drag, adding a new object) is recorded as one {@link Action} with its own
 * undo()/redo() logic; App builds these from before/after snapshots taken on EditableMesh.
 */
public class UndoManager {

    public interface Action {
        void undo();
        void redo();
    }

    private static final int MAX_HISTORY = 50;
    private final Deque<Action> undoStack = new ArrayDeque<>();
    private final Deque<Action> redoStack = new ArrayDeque<>();

    public void push(Action action) {
        undoStack.push(action);
        while (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
        redoStack.clear();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void undo() {
        if (undoStack.isEmpty()) return;
        Action a = undoStack.pop();
        a.undo();
        redoStack.push(a);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Action a = redoStack.pop();
        a.redo();
        undoStack.push(a);
    }
}
