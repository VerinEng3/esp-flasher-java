package com.ajinkyagokhale.espflasher.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza o Python e o esptool na máquina, informa os caminhos encontrados
 * e instala o que estiver faltando.
 *
 * <p>Ordem de busca do esptool: caminho escolhido pelo usuário, depois o módulo
 * {@code esptool} de qualquer Python encontrado. Ordem de busca do Python: caminho
 * escolhido pelo usuário, launcher {@code py}, PATH e pastas padrão de instalação.</p>
 */
public class PrereqChecker {
    private static final Preferences PREFS = Preferences.userNodeForPackage(PrereqChecker.class);
    private static final String KEY_PYTHON = "custom.python.path";
    private static final String KEY_ESPTOOL = "custom.esptool.path";

    private static final long PROBE_TIMEOUT_SECONDS = 30;
    private static final String PYTHON_WINGET_ID = "Python.Python.3.13";

    /** Imprime "caminho absoluto|versão" do interpretador. */
    private static final String PY_IDENTITY =
            "import sys;print(sys.executable+'|'+'.'.join(map(str,sys.version_info[:3])))";

    /** Imprime "OK|versão|local" se o módulo esptool estiver instalado; falha caso contrário. */
    private static final String PY_ESPTOOL_PROBE =
            "import os,sysconfig as s;import esptool as e;n=s.get_scheme_names();"
            + "d=[s.get_path('scripts')]+[s.get_path('scripts',x) for x in (os.name+'_user',) if x in n];"
            + "f=[os.path.join(p,b) for p in d if p for b in ('esptool.exe','esptool','esptool.py')"
            + " if os.path.isfile(os.path.join(p,b))];"
            + "print('OK|'+str(getattr(e,'__version__','?'))+'|'+(f[0] if f else os.path.dirname(e.__file__)))";

    private static final Pattern VERSION_IN_TEXT = Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)");

    // Estado da última verificação (lido pela UI em outra thread)
    private volatile String pythonExe;
    private volatile String pythonVersion;
    private volatile boolean pipAvailable;
    private volatile List<String> esptoolCommand;
    private volatile String esptoolVersion;
    private volatile String esptoolLocation;
    private volatile boolean esptoolFromCustomPath;

    // ── Caminhos escolhidos pelo usuário ───────────────────────

    public static String getCustomPythonPath() {
        return PREFS.get(KEY_PYTHON, "");
    }

    public static String getCustomEsptoolPath() {
        return PREFS.get(KEY_ESPTOOL, "");
    }

    public static void setCustomPythonPath(String path) {
        PREFS.put(KEY_PYTHON, path == null ? "" : path.trim());
    }

    public static void setCustomEsptoolPath(String path) {
        PREFS.put(KEY_ESPTOOL, path == null ? "" : path.trim());
    }

    public static void clearCustomPaths() {
        setCustomPythonPath("");
        setCustomEsptoolPath("");
    }

    // ── Resultado da verificação ───────────────────────────────

    /** Caminho absoluto do Python em uso, ou null se não encontrado. */
    public String getPythonExe()        { return pythonExe; }
    public String getPythonVersion()    { return pythonVersion; }
    public boolean isPipAvailable()     { return pipAvailable; }
    /** Versão do esptool encontrada, ou null. */
    public String getEsptoolVersion()   { return esptoolVersion; }
    /** Caminho do executável/módulo do esptool, ou null. */
    public String getEsptoolLocation()  { return esptoolLocation; }
    public boolean isEsptoolFromCustomPath() { return esptoolFromCustomPath; }

    /** Comando (tokens) usado para chamar o esptool, ou null se não encontrado. */
    public List<String> getEsptoolCommand() {
        List<String> cmd = esptoolCommand;
        return cmd == null ? null : Collections.unmodifiableList(cmd);
    }

    public boolean isPythonFound() {
        return pythonExe != null;
    }

    public boolean isReady() {
        return esptoolCommand != null;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Instalar o Python automaticamente só é suportado no Windows (via winget). */
    public static boolean canAutoInstallPython() {
        return isWindows();
    }

    // ── Verificação ─────────────────────────────────────────────

    public synchronized void checkAll() {
        String foundPython = null;
        String foundPythonVersion = null;
        List<String> foundEsptool = null;
        String foundEsptoolVersion = null;
        String foundEsptoolLocation = null;
        boolean fromCustom = false;

        // 1. Python escolhido pelo usuário tem prioridade absoluta
        List<String[]> pythons = new ArrayList<>();
        String customPython = getCustomPythonPath();
        if (!customPython.isEmpty()) {
            String[] id = identifyPython(customPython);
            if (id != null) pythons.add(id);
        } else {
            pythons.addAll(discoverPythons());
        }

        // 2. esptool escolhido pelo usuário (executável standalone ou esptool.py)
        String customEsptool = getCustomEsptoolPath();
        if (!customEsptool.isEmpty()) {
            List<String> cmd = new ArrayList<>();
            if (customEsptool.toLowerCase(Locale.ROOT).endsWith(".py")) {
                if (!pythons.isEmpty()) {
                    cmd.add(pythons.get(0)[0]);
                    cmd.add(customEsptool);
                }
            } else {
                cmd.add(customEsptool);
            }
            if (!cmd.isEmpty()) {
                List<String> versionCmd = new ArrayList<>(cmd);
                versionCmd.add("version");
                String out = runCommand(versionCmd);
                if (out != null) {
                    foundEsptool = cmd;
                    foundEsptoolVersion = extractVersion(out);
                    foundEsptoolLocation = customEsptool;
                    fromCustom = true;
                }
            }
        }

        // 3. Primeiro Python que já tenha o esptool; senão, o primeiro Python encontrado
        if (!pythons.isEmpty()) {
            String[] chosen = pythons.get(0);
            if (foundEsptool == null) {
                for (String[] py : pythons) {
                    String probe = runCommand(List.of(py[0], "-c", PY_ESPTOOL_PROBE));
                    if (probe != null && probe.startsWith("OK|")) {
                        String[] parts = probe.split("\\|", 3);
                        chosen = py;
                        foundEsptool = new ArrayList<>(List.of(py[0], "-m", "esptool"));
                        foundEsptoolVersion = parts.length > 1 ? parts[1] : null;
                        foundEsptoolLocation = parts.length > 2 ? parts[2] : null;
                        break;
                    }
                }
            }
            foundPython = chosen[0];
            foundPythonVersion = chosen[1];
        }

        pythonExe = foundPython;
        pythonVersion = foundPythonVersion;
        pipAvailable = foundPython != null
                && runCommand(List.of(foundPython, "-m", "pip", "--version")) != null;
        esptoolCommand = foundEsptool;
        esptoolVersion = foundEsptoolVersion;
        esptoolLocation = foundEsptoolLocation;
        esptoolFromCustomPath = fromCustom;
    }

    /** Lista Pythons distintos (caminho absoluto, versão), em ordem de preferência. */
    private List<String[]> discoverPythons() {
        Set<String> candidates = new LinkedHashSet<>();
        if (isWindows()) {
            // Launcher oficial: "py -0p" lista todos os Pythons registrados
            String list = runCommand(List.of("py", "-0p"));
            if (list != null) {
                Matcher m = Pattern.compile("([A-Za-z]:\\\\.+?\\.exe)\\s*$", Pattern.MULTILINE).matcher(list);
                while (m.find()) candidates.add(m.group(1).trim());
            }
            candidates.add("python");
            candidates.add("python3");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                addPythonDirs(candidates, new File(localAppData, "Programs\\Python"));
            }
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String base = System.getenv(env);
                if (base != null) addPythonDirs(candidates, new File(base));
            }
            String systemDrive = System.getenv("SystemDrive");
            if (systemDrive != null) addPythonDirs(candidates, new File(systemDrive + File.separator));
        } else {
            candidates.addAll(List.of(
                    "/opt/homebrew/bin/python3",
                    "/usr/local/bin/python3",
                    "/usr/bin/python3",
                    "python3",
                    "python"
            ));
        }

        List<String[]> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String cand : candidates) {
            String[] id = identifyPython(cand);
            if (id == null) continue;
            if (seen.add(id[0].toLowerCase(Locale.ROOT))) result.add(id);
        }
        return result;
    }

    /** Adiciona python.exe de subpastas "Python3*" de {@code root}, versões mais novas primeiro. */
    private static void addPythonDirs(Set<String> out, File root) {
        File[] dirs = root.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith("python3"));
        if (dirs == null) return;
        List<File> sorted = new ArrayList<>(List.of(dirs));
        sorted.sort((a, b) -> b.getName().compareToIgnoreCase(a.getName()));
        for (File d : sorted) {
            File exe = new File(d, "python.exe");
            if (exe.isFile()) out.add(exe.getAbsolutePath());
        }
    }

    /** Retorna {caminho absoluto, versão} ou null se não for um Python 3 funcional. */
    private String[] identifyPython(String cmd) {
        String out = runCommand(List.of(cmd, "-c", PY_IDENTITY));
        if (out == null) return null;
        String line = lastLine(out);
        int sep = line.lastIndexOf('|');
        if (sep <= 0) return null;
        String exe = line.substring(0, sep).trim();
        String ver = line.substring(sep + 1).trim();
        if (exe.isEmpty() || !ver.startsWith("3.")) return null;
        return new String[]{exe, ver};
    }

    // ── Instalação ──────────────────────────────────────────────

    /** Instala/atualiza o esptool com o pip do Python encontrado. */
    public boolean installEsptool(Consumer<String> onLine) {
        String py = pythonExe;
        if (py == null) {
            emit(onLine, "Python não encontrado. Instale o Python primeiro.");
            return false;
        }
        if (!pipAvailable) {
            emit(onLine, "> " + py + " -m ensurepip --upgrade");
            runStreaming(List.of(py, "-m", "ensurepip", "--upgrade"), onLine);
        }

        List<String> cmd = new ArrayList<>(List.of(py, "-m", "pip", "install", "--upgrade", "esptool"));
        if (!isWindows()) cmd.add("--break-system-packages");
        emit(onLine, "> " + String.join(" ", cmd));
        int exit = runStreaming(cmd, onLine);

        if (exit != 0) {
            // Python instalado para todos os usuários sem permissão de administrador
            List<String> userCmd = new ArrayList<>(cmd);
            userCmd.add("--user");
            emit(onLine, "");
            emit(onLine, "Tentando novamente na pasta do usuário (--user)...");
            emit(onLine, "> " + String.join(" ", userCmd));
            runStreaming(userCmd, onLine);
        }

        checkAll();
        return isReady();
    }

    /** Instala o Python 3.13 para o usuário atual via winget (somente Windows). */
    public boolean installPython(Consumer<String> onLine) {
        if (!canAutoInstallPython()) return false;
        List<String> cmd = List.of(
                "winget", "install", "--exact", "--id", PYTHON_WINGET_ID,
                "--source", "winget", "--scope", "user", "--silent",
                "--accept-package-agreements", "--accept-source-agreements"
        );
        emit(onLine, "> " + String.join(" ", cmd));
        int exit = runStreaming(cmd, onLine);
        if (exit == Integer.MIN_VALUE) {
            emit(onLine, "winget não está disponível neste computador.");
            return false;
        }
        checkAll();
        return isPythonFound();
    }

    // ── Execução de processos ───────────────────────────────────

    private static void emit(Consumer<String> onLine, String line) {
        if (onLine != null) onLine.accept(line);
    }

    /** Executa e repassa a saída linha a linha. Retorna o código de saída, ou MIN_VALUE se não iniciou. */
    private static int runStreaming(List<String> cmd, Consumer<String> onLine) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String clean = line.replace("\r", "").strip();
                    if (!clean.isEmpty()) emit(onLine, clean);
                }
            }
            return p.waitFor();
        } catch (IOException e) {
            emit(onLine, "Erro: " + e.getMessage());
            return Integer.MIN_VALUE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Integer.MIN_VALUE;
        }
    }

    /** Executa com tempo limite; retorna a saída se o código de saída for 0 e houver saída. */
    private static String runCommand(List<String> cmd) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            return null;
        }
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = p.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = output.get(5, TimeUnit.SECONDS);
            return (p.exitValue() == 0 && !out.isEmpty()) ? out : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String lastLine(String s) {
        String[] lines = s.split("\\R");
        return lines[lines.length - 1];
    }

    private static String extractVersion(String output) {
        Matcher m = VERSION_IN_TEXT.matcher(output);
        return m.find() ? m.group(1) : null;
    }
}
