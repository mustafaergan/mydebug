package com.optona.mydebug.eclipse;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Window › Preferences › MyDebug HotReload */
public final class MyDebugPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public MyDebugPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.preferences());
        setDescription("Save a Java file and see the change in the running application instantly. "
                + "Changes take effect on the next Run or Debug.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // nothing to initialise
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(Activator.PREF_ENABLED,
                "Attach the MyDebug agent to Java launches (Spring, MyBatis, reload in Run and Debug)",
                getFieldEditorParent()));
        addField(new BooleanFieldEditor(Activator.PREF_ENHANCED,
                "Add -XX:+AllowEnhancedClassRedefinition for JREs that support it (JetBrains Runtime, DCEVM)",
                getFieldEditorParent()));
        Label hint = new Label(getFieldEditorParent(), SWT.WRAP);
        hint.setText("The agent is added to each JRE's default VM arguments (Installed JREs). "
                + "Turning it off removes it again.");
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        Activator.applyPreferences();
        return ok;
    }
}
