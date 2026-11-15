package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.ProjectFiles.FolderPickerUnavailable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/** 在控制端的 Windows 桌面打开系统目录对话框，只有确认后才将路径返回给浏览器。 */
final class WindowsFolderPicker {
    private static final String DIALOG = """
            $ErrorActionPreference = 'Stop'
            if (-not [Environment]::UserInteractive) { exit 1 }
            Add-Type -AssemblyName System.Windows.Forms
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
            $dialog = [Windows.Forms.FolderBrowserDialog]::new()
            $owner = [Windows.Forms.Form]::new()
            try {
                $dialog.Description = 'DelveForge · 选择要导入的项目文件夹'
                $dialog.ShowNewFolderButton = $false
                $owner.TopMost = $true
                $owner.ShowInTaskbar = $false
                if ($dialog.ShowDialog($owner) -eq [Windows.Forms.DialogResult]::OK) {
                    [Console]::Write($dialog.SelectedPath)
                }
            } finally {
                $dialog.Dispose()
                $owner.Dispose()
            }
            """;

    private WindowsFolderPicker() {}

    static Optional<String> choose() {
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) {
                throw new IOException("需要可交互的 Windows 桌面");
            }
            Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            String script = Base64.getEncoder().encodeToString(DIALOG.getBytes(StandardCharsets.UTF_16LE));
            Process dialog = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-STA",
                    "-WindowStyle", "Hidden", "-EncodedCommand", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = dialog.getInputStream()) {
                String path = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (dialog.waitFor() != 0) {
                    throw new IOException("系统目录对话框未正常退出");
                }
                return path.isEmpty() ? Optional.empty() : Optional.of(path);
            } finally {
                dialog.destroy();
            }
        } catch (IOException e) {
            throw new FolderPickerUnavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FolderPickerUnavailable(e);
        }
    }
}
