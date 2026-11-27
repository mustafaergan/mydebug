import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { execFile } from 'child_process';

const AGENT_MARKER = 'mydebug-agent';
const ENHANCED_FLAG = '-XX:+AllowEnhancedClassRedefinition';
const probeCache = new Map<string, Promise<boolean>>();

export function activate(context: vscode.ExtensionContext): void {
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    status.command = 'mydebug.toggle';
    const refresh = () => {
        const on = settings().get<boolean>('enabled', true);
        status.text = on ? '$(zap) MyDebug' : '$(circle-slash) MyDebug';
        status.tooltip = on
            ? 'MyDebug HotReload is attached to Java launches. Click to turn off.'
            : 'MyDebug HotReload is off. Click to turn on.';
        status.show();
    };
    refresh();

    context.subscriptions.push(
        status,
        vscode.debug.registerDebugConfigurationProvider('java', new AgentConfigurationProvider(context)),
        vscode.commands.registerCommand('mydebug.toggle', async () => {
            const on = settings().get<boolean>('enabled', true);
            await settings().update('enabled', !on, vscode.ConfigurationTarget.Global);
            vscode.window.showInformationMessage(
                `MyDebug HotReload ${!on ? 'on' : 'off'}. Takes effect on the next Run or Debug.`);
        }),
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('mydebug')) refresh();
        }),
    );
}

export function deactivate(): void {
    // nothing to clean up
}

function settings(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('mydebug');
}

/**
 * Adds the bundled agent to Java launch configurations of the Java Debugger extension.
 * Run ("noDebug") launches: the agent reloads classes itself from the build output that the Java
 * language server writes on save. Debug launches with hot code replace set to "auto": VS Code reloads
 * the classes and the agent refreshes Spring and MyBatis.
 */
class AgentConfigurationProvider implements vscode.DebugConfigurationProvider {
    constructor(private readonly context: vscode.ExtensionContext) {}

    async resolveDebugConfigurationWithSubstitutedVariables(
        _folder: vscode.WorkspaceFolder | undefined,
        config: vscode.DebugConfiguration,
    ): Promise<vscode.DebugConfiguration> {
        if (config.request !== 'launch' || !settings().get<boolean>('enabled', true)) return config;

        const agent = this.context.asAbsolutePath(path.join('agent', 'mydebug-agent.jar'));
        if (!fs.existsSync(agent)) return config;

        let vmArgs: string = Array.isArray(config.vmArgs) ? config.vmArgs.join(' ') : (config.vmArgs ?? '');
        if (vmArgs.includes(AGENT_MARKER)) return config;

        const hotCodeReplace = vscode.workspace.getConfiguration('java.debug.settings')
            .get<string>('hotCodeReplace', 'manual');
        const mode = !config.noDebug && hotCodeReplace === 'auto' ? 'debug' : 'run';

        const extra: string[] = [`"-javaagent:${agent}=mode=${mode}"`];
        if (settings().get<boolean>('verbose', false)) extra.push('-Dmydebug.verbose=true');
        if (!vmArgs.includes('AllowEnhancedClassRedefinition') && await this.enhanced(config)) {
            extra.push(ENHANCED_FLAG);
        }
        config.vmArgs = `${vmArgs} ${extra.join(' ')}`.trim();
        return config;
    }

    private async enhanced(config: vscode.DebugConfiguration): Promise<boolean> {
        const mode = settings().get<string>('enhancedRedefinition', 'auto');
        if (mode === 'always') return true;
        if (mode === 'never') return false;
        // "auto": only when the exact JVM is known, so a JVM that rejects the flag is never started with it.
        const javaExec: string | undefined = config.javaExec;
        return javaExec ? supportsEnhancedRedefinition(javaExec) : false;
    }
}

function supportsEnhancedRedefinition(javaExec: string): Promise<boolean> {
    let probe = probeCache.get(javaExec);
    if (!probe) {
        probe = new Promise<boolean>(resolve => {
            execFile(javaExec, [ENHANCED_FLAG, '-version'], { timeout: 10000 }, error => resolve(!error));
        });
        probeCache.set(javaExec, probe);
    }
    return probe;
}
