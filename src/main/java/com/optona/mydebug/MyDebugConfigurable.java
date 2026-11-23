package com.optona.mydebug;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.util.Objects;

/** Settings › Tools › MyDebug HotReload */
public final class MyDebugConfigurable implements Configurable {
    private JBCheckBox enabled;
    private JBCheckBox onlySaved;
    private JSpinner debounce;
    private JBCheckBox notifySuccess;
    private JBCheckBox enhanced;
    private TextFieldWithBrowseButton agentJar;

    @Override
    public @Nls String getDisplayName() {
        return "MyDebug HotReload";
    }

    @Override
    public JComponent createComponent() {
        enabled = new JBCheckBox("Reload into the debug session on save (HotSwap)");
        onlySaved = new JBCheckBox("Compile only the saved files (faster, does not recompile dependent classes)");
        debounce = new JSpinner(new SpinnerNumberModel(300, 0, 5000, 50));
        notifySuccess = new JBCheckBox("Show successful reloads in the status bar");
        enhanced = new JBCheckBox("Add -XX:+AllowEnhancedClassRedefinition to debug runs when the JVM supports it");
        agentJar = new TextFieldWithBrowseButton();
        agentJar.addBrowseFolderListener(null,
                FileChooserDescriptorFactory.createSingleFileDescriptor("jar").withTitle("HotswapAgent jar"));

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addComponent(onlySaved)
                .addLabeledComponent("Batch delay (ms):", debounce)
                .addComponent(notifySuccess)
                .addSeparator()
                .addComponent(enhanced)
                .addComponentToRightColumn(hint(
                        "JetBrains Runtime (JBR) and DCEVM allow adding/removing methods and fields and changing signatures. "
                                + "The flag is added only after the JVM is verified to support it."))
                .addLabeledComponent("HotswapAgent jar (optional):", agentJar)
                .addComponentToRightColumn(hint(
                        "Reloads Spring beans, Hibernate mappings, resources and more. "
                                + "Download it from github.com/HotswapProjects/HotswapAgent releases."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static JBLabel hint(String text) {
        JBLabel l = new JBLabel("<html>" + text + "</html>");
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setFontColor(UIUtil.FontColor.BRIGHTER);
        return l;
    }

    @Override
    public boolean isModified() {
        MyDebugSettings.State s = MyDebugSettings.settings();
        return enabled.isSelected() != s.enabled
                || onlySaved.isSelected() != s.compileOnlySavedFiles
                || (int) debounce.getValue() != s.debounceMs
                || notifySuccess.isSelected() != s.notifyOnSuccess
                || enhanced.isSelected() != s.patchEnhancedRedefinition
                || !Objects.equals(agentJar.getText().trim(), s.hotswapAgentJar);
    }

    @Override
    public void apply() {
        MyDebugSettings.State s = MyDebugSettings.settings();
        s.enabled = enabled.isSelected();
        s.compileOnlySavedFiles = onlySaved.isSelected();
        s.debounceMs = (int) debounce.getValue();
        s.notifyOnSuccess = notifySuccess.isSelected();
        s.patchEnhancedRedefinition = enhanced.isSelected();
        s.hotswapAgentJar = agentJar.getText().trim();
    }

    @Override
    public void reset() {
        MyDebugSettings.State s = MyDebugSettings.settings();
        enabled.setSelected(s.enabled);
        onlySaved.setSelected(s.compileOnlySavedFiles);
        debounce.setValue(s.debounceMs);
        notifySuccess.setSelected(s.notifyOnSuccess);
        enhanced.setSelected(s.patchEnhancedRedefinition);
        agentJar.setText(s.hotswapAgentJar);
    }
}
