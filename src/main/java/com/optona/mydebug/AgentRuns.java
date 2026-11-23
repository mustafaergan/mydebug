package com.optona.mydebug;

import com.intellij.execution.configurations.RunProfile;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Ajanla, Run modunda başlatılan yapılandırmalar (süreç başlayınca {@link AgentProcessListener} eşler). */
final class AgentRuns {
    private static final Set<RunProfile> MARKED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private AgentRuns() {}

    static void mark(RunProfile profile) {
        if (profile != null) MARKED.add(profile);
    }

    static void unmark(RunProfile profile) {
        if (profile != null) MARKED.remove(profile);
    }

    static boolean isMarked(RunProfile profile) {
        return profile != null && MARKED.contains(profile);
    }
}
