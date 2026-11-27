package com.optona.mydebug.eclipse;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/** Runs after the workbench starts: installs the agent into the JREs in a background job. */
public final class Activator implements IStartup {
    static final String ID = "com.optona.mydebug.eclipse";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_ENHANCED = "enhancedRedefinition";

    private static IPreferenceStore store;

    static synchronized IPreferenceStore preferences() {
        if (store == null) {
            store = new ScopedPreferenceStore(InstanceScope.INSTANCE, ID);
            store.setDefault(PREF_ENABLED, true);
            store.setDefault(PREF_ENHANCED, true);
        }
        return store;
    }

    static void applyPreferences() {
        IPreferenceStore p = preferences();
        Job.create("MyDebug: updating JRE settings", monitor -> {
            AgentInstaller.apply(p.getBoolean(PREF_ENABLED), p.getBoolean(PREF_ENHANCED));
        }).schedule();
    }

    @Override
    public void earlyStartup() {
        applyPreferences();
    }
}
