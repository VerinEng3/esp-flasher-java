package com.ajinkyagokhale.espflasher.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>Com caminho escolhido à mão, ele é usado. Sem isso, entre todas as
 * instalações encontradas é escolhida a de versão mais recente; em empate,
 * vale a ordem abaixo.</p>
 * <ol>
 *   <li>módulo {@code esptool} de cada Python encontrado (launcher py, PATH,
 *       pastas padrão, conda);</li>
 *   <li>{@code esptool.exe}/{@code esptool.py} em qualquer pasta do PATH;</li>
 *   <li>ESP-IDF ({@code C:\Espressif}, {@code ~/.espressif});</li>
 *   <li>PlatformIO ({@code ~/.platformio/packages});</li>
 *   <li>Arduino IDE ({@code Arduino15/packages});</li>
 *   <li>pipx ({@code ~/.local/bin}).</li>
 * </ol>
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

    /**
     * Saída de "esptool version": "esptool v5.6.0" ou "esptool.py v4.12.0".
     * Versões 0.x são o esptool-ck antigo, que tem outra linha de comando.
     */
    private static final Pattern ESPTOOL_VERSION =
            Pattern.compile("esptool(?:\\.py)?\\s+v([1-9]\\d*\\.\\d+(?:\\.\\d+)?)");

    private static final String[] ESPTOOL_FILE_NAMES = {"esptool.exe", "esptool", "esptool.py"};

    /** Uma instalação do esptool que respondeu ao comando "version". */
    public record EsptoolInstall(
            List<String> command,   // comando para gravar (ex.: [python, -m, esptool] ou [esptool.exe])
            String location,        // caminho mostrado ao usuário
            String version,
            String source,          // de onde veio (Python, PATH, ESP-IDF, PlatformIO...)
            String modulePython     // Python que contém o módulo, ou null se for executável/script
    ) {
        public EsptoolInstall {
            command = List.copyOf(command);
        }
    }

    // Estado da última verificação (lido pela UI em outra thread)
    private volatile String pythonExe;
    private volatile String pythonVersion;
    private volatile boolean pipAvailable;
    private volatile EsptoolInstall esptool;
    private volatile boolean esptoolFromCustomPath;
    private volatile boolean customPythonInvalid;
    private volatile boolean customEsptoolInvalid;

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

    /** Passa a usar a instalação escolhida (da lista de instalações encontradas). */
    public static void useInstall(EsptoolInstall install) {
        if (install.modulePython() != null) {
            setCustomPythonPath(install.modulePython());
            setCustomEsptoolPath("");
        } else {
            setCustomEsptoolPath(install.location());
        }
    }

    // ── Resultado da verificação ───────────────────────────────

    /** Caminho absoluto do Python em uso, ou null se não encontrado. */
    public String getPythonExe()        { return pythonExe; }
    public String getPythonVersion()    { return pythonVersion; }
    public boolean isPipAvailable()     { return pipAvailable; }
    public boolean isCustomPythonInvalid()  { return customPythonInvalid; }
    public boolean isCustomEsptoolInvalid() { return customEsptoolInvalid; }
    public boolean isEsptoolFromCustomPath() { return esptoolFromCustomPath; }

    /** Instalação do esptool em uso, ou null. */
    public EsptoolInstall getEsptool()  { return esptool; }

    public String getEsptoolVersion() {
        EsptoolInstall e = esptool;
        return e == null ? null : e.version();
    }

    public String getEsptoolLocation() {
        EsptoolInstall e = esptool;
        return e == null ? null : e.location();
    }

    /** Comando (tokens) usado para chamar o esptool, ou null se não encontrado. */
    public List<String> getEsptoolCommand() {
        EsptoolInstall e = esptool;
        return e == null ? null : e.command();
    }

    public boolean isPythonFound() {
        return pythonExe != null;
    }

    public boolean isReady() {
        return esptool != null;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Instalar o Python automaticamente só é suportado no Windows (via winget). */
    public static boolean canAutoInstallPython() {
        return isWindows();
    }

    // ── Verificação ─────────────────────────────────────────────

    /** Detecta o Python e o primeiro esptool que funcionar. */
    public synchronized void checkAll() {
        List<String[]> pythons = new ArrayList<>();
        boolean pyInvalid = false;

        // Python escolhido pelo usuário vem primeiro; se for inválido, segue a detecção automática
        String[] customPy = null;
        String customPython = getCustomPythonPath();
        if (!customPython.isEmpty()) {
            customPy = identifyPython(customPython);
            if (customPy != null) pythons.add(customPy);
            else pyInvalid = true;
        }
        addUnique(pythons, discoverPythons());

        String customEsptool = getCustomEsptoolPath();
        EsptoolInstall found = null;
        boolean fromCustom = false;
        boolean esInvalid = false;

        if (!customEsptool.isEmpty()) {
            found = probeEsptoolFile(new File(customEsptool), "Manual", pythons);
            fromCustom = found != null;
            esInvalid = found == null;
        }
        // Python escolhido à mão que já tem o esptool: respeita a escolha
        if (found == null && customPy != null) {
            found = probePythonModule(customPy);
        }
        // Detecção automática: entre todas as instalações, a de versão mais recente
        if (found == null) {
            found = newest(collectAll(pythons));
        }

        // Python mostrado: o que tem o esptool, ou o primeiro encontrado
        String[] chosen = null;
        if (found != null && found.modulePython() != null) {
            for (String[] py : pythons) {
                if (py[0].equalsIgnoreCase(found.modulePython())) chosen = py;
            }
        }
        if (chosen == null && !pythons.isEmpty()) chosen = pythons.get(0);

        pythonExe = chosen == null ? null : chosen[0];
        pythonVersion = chosen == null ? null : chosen[1];
        pipAvailable = chosen != null
                && runCommand(List.of(chosen[0], "-m", "pip", "--version")) != null;
        esptool = found;
        esptoolFromCustomPath = fromCustom;
        customPythonInvalid = pyInvalid;
        customEsptoolInvalid = esInvalid;
    }

    /** Procura todas as instalações do esptool (usado na tela de Caminhos). */
    public List<EsptoolInstall> findAllEsptools() {
        List<String[]> pythons = new ArrayList<>();
        String customPython = getCustomPythonPath();
        if (!customPython.isEmpty()) {
            String[] id = identifyPython(customPython);
            if (id != null) pythons.add(id);
        }
        addUnique(pythons, discoverPythons());
        return collectAll(pythons);
    }

    /** Todas as instalações que respondem, sem repetir caminho, em ordem de prioridade. */
    private List<EsptoolInstall> collectAll(List<String[]> pythons) {
        Map<String, EsptoolInstall> byLocation = new LinkedHashMap<>();
        for (String[] py : pythons) {
            EsptoolInstall e = probePythonModule(py);
            if (e != null) byLocation.putIfAbsent(key(e.location()), e);
        }
        for (FileCandidate c : esptoolFileCandidates()) {
            if (byLocation.containsKey(key(c.file().getAbsolutePath()))) continue;
            EsptoolInstall e = probeEsptoolFile(c.file(), c.source(), pythons);
            if (e != null) byLocation.putIfAbsent(key(e.location()), e);
        }
        return new ArrayList<>(byLocation.values());
    }

    /** A de versão mais recente; em empate, a primeira (maior prioridade). */
    static EsptoolInstall newest(List<EsptoolInstall> installs) {
        EsptoolInstall best = null;
        for (EsptoolInstall e : installs) {
            if (best == null || compareVersions(e.version(), best.version()) > 0) best = e;
        }
        return best;
    }

    /** Compara "5.6.0" com "4.12.0" numericamente; versões ilegíveis contam como 0. */
    static int compareVersions(String a, String b) {
        String[] pa = a == null ? new String[0] : a.split("[^0-9]+");
        String[] pb = b == null ? new String[0] : b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length && !pa[i].isEmpty() ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length && !pb[i].isEmpty() ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String key(String path) {
        return path == null ? "" : new File(path).getAbsolutePath().toLowerCase(Locale.ROOT);
    }

    private static void addUnique(List<String[]> into, List<String[]> more) {
        for (String[] py : more) {
            boolean dup = false;
            for (String[] existing : into) {
                if (existing[0].equalsIgnoreCase(py[0])) dup = true;
            }
            if (!dup) into.add(py);
        }
    }

    // ── Python ──────────────────────────────────────────────────

    /** Lista Pythons distintos (caminho absoluto, versão), em ordem de preferência. */
    private List<String[]> discoverPythons() {
        Set<String> candidates = new LinkedHashSet<>();
        String home = System.getProperty("user.home", "");
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
            for (String conda : List.of("miniconda3", "anaconda3")) {
                File exe = new File(home, conda + "\\python.exe");
                if (exe.isFile()) candidates.add(exe.getAbsolutePath());
            }
        } else {
            candidates.addAll(List.of(
                    "/opt/homebrew/bin/python3",
                    "/usr/local/bin/python3",
                    "/usr/bin/python3",
                    "python3",
                    "python"
            ));
            for (String conda : List.of("miniconda3", "anaconda3")) {
                File exe = new File(home, conda + "/bin/python3");
                if (exe.isFile()) candidates.add(exe.getAbsolutePath());
            }
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

    // ── esptool ─────────────────────────────────────────────────

    private record FileCandidate(File file, String source) {}

    /** esptool como módulo de um Python: comando [python, -m, esptool]. */
    private EsptoolInstall probePythonModule(String[] py) {
        String probe = runCommand(List.of(py[0], "-c", PY_ESPTOOL_PROBE));
        if (probe == null) return null;
        String line = lastLine(probe);
        if (!line.startsWith("OK|")) return null;
        String[] parts = line.split("\\|", 3);
        String version = parts.length > 1 ? parts[1] : null;
        String location = parts.length > 2 ? parts[2] : py[0];
        return new EsptoolInstall(List.of(py[0], "-m", "esptool"), location, version,
                "Python " + py[1], py[0]);
    }

    /** esptool como executável ou esptool.py; valida com "version". */
    private EsptoolInstall probeEsptoolFile(File file, String source, List<String[]> pythons) {
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();
        List<List<String>> attempts = new ArrayList<>();
        if (path.toLowerCase(Locale.ROOT).endsWith(".py")) {
            // esptool.py precisa de um Python com pyserial; tenta o do próprio pacote primeiro
            File localPython = pythonNextTo(file);
            if (localPython != null) attempts.add(List.of(localPython.getAbsolutePath(), path));
            for (String[] py : pythons) attempts.add(List.of(py[0], path));
        } else {
            attempts.add(List.of(path));
        }
        for (List<String> cmd : attempts) {
            List<String> versionCmd = new ArrayList<>(cmd);
            versionCmd.add("version");
            String out = runCommand(versionCmd);
            if (out == null) continue;
            Matcher m = ESPTOOL_VERSION.matcher(out);
            if (!m.find()) continue;
            return new EsptoolInstall(cmd, path, m.group(1), source, null);
        }
        return null;
    }

    /** PlatformIO: ~/.platformio/penv tem o Python que roda o esptool.py do pacote. */
    private static File pythonNextTo(File esptoolPy) {
        String home = System.getProperty("user.home", "");
        String p = esptoolPy.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (p.contains(".platformio")) {
            File penv = new File(home, isWindows()
                    ? ".platformio\\penv\\Scripts\\python.exe"
                    : ".platformio/penv/bin/python");
            if (penv.isFile()) return penv;
        }
        return null;
    }

    /** Arquivos do esptool fora de um Python: PATH, ESP-IDF, PlatformIO, Arduino, pipx. */
    private static List<FileCandidate> esptoolFileCandidates() {
        List<FileCandidate> out = new ArrayList<>();
        String home = System.getProperty("user.home", "");

        // PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                addEsptoolFiles(out, new File(dir.trim()), "PATH");
            }
        }

        // ESP-IDF: <ferramentas>/python_env/<ambiente>/Scripts/esptool.exe
        String idfPyEnv = System.getenv("IDF_PYTHON_ENV_PATH");
        if (idfPyEnv != null) addScriptsEsptool(out, new File(idfPyEnv), "ESP-IDF");
        List<File> idfTools = new ArrayList<>();
        String idfToolsEnv = System.getenv("IDF_TOOLS_PATH");
        if (idfToolsEnv != null) idfTools.add(new File(idfToolsEnv));
        idfTools.add(new File(home, ".espressif"));
        if (isWindows()) {
            String systemDrive = System.getenv("SystemDrive");
            idfTools.add(new File((systemDrive != null ? systemDrive : "C:") + "\\Espressif\\tools"));
        }
        for (File tools : idfTools) {
            for (File env : newestFirst(new File(tools, "python_env"))) {
                addScriptsEsptool(out, env, "ESP-IDF");
            }
        }

        // PlatformIO: packages/tool-esptool* (executável oficial ou esptool.py)
        File pioPackages = new File(home, ".platformio" + File.separator + "packages");
        File[] pioDirs = pioPackages.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).startsWith("tool-esptool"));
        if (pioDirs != null) {
            for (File d : pioDirs) addEsptoolFiles(out, d, "PlatformIO");
        }

        // Arduino IDE / arduino-cli
        List<File> arduinoRoots = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) arduinoRoots.add(new File(localAppData, "Arduino15"));
        arduinoRoots.add(new File(home, ".arduino15"));
        arduinoRoots.add(new File(home, "Library" + File.separator + "Arduino15"));
        for (File root : arduinoRoots) {
            File esp32Tools = new File(root, path("packages", "esp32", "tools", "esptool_py"));
            for (File v : newestFirst(esp32Tools)) addEsptoolFiles(out, v, "Arduino (ESP32)");

            File esp8266Cores = new File(root, path("packages", "esp8266", "hardware", "esp8266"));
            for (File core : newestFirst(esp8266Cores)) {
                File f = new File(core, path("tools", "esptool", "esptool.py"));
                if (f.isFile()) out.add(new FileCandidate(f, "Arduino (ESP8266)"));
            }
        }

        // pipx
        addEsptoolFiles(out, new File(home, path(".local", "bin")), "pipx");

        // Remove repetidos mantendo a ordem
        Map<String, FileCandidate> unique = new LinkedHashMap<>();
        for (FileCandidate c : out) unique.putIfAbsent(key(c.file().getAbsolutePath()), c);
        return new ArrayList<>(unique.values());
    }

    private static String path(String... parts) {
        return String.join(File.separator, parts);
    }

    /** Subpastas de {@code dir}, nomes em ordem decrescente (versões mais novas primeiro). */
    private static List<File> newestFirst(File dir) {
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) return List.of();
        List<File> sorted = new ArrayList<>(List.of(subs));
        sorted.sort((a, b) -> b.getName().compareToIgnoreCase(a.getName()));
        return sorted;
    }

    private static void addEsptoolFiles(List<FileCandidate> out, File dir, String source) {
        for (String name : ESPTOOL_FILE_NAMES) {
            File f = new File(dir, name);
            if (f.isFile()) out.add(new FileCandidate(f, source));
        }
    }

    private static void addScriptsEsptool(List<FileCandidate> out, File pythonEnv, String source) {
        addEsptoolFiles(out, new File(pythonEnv, isWindows() ? "Scripts" : "bin"), source);
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
}
