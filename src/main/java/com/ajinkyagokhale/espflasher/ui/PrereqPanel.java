package com.ajinkyagokhale.espflasher.ui;

import com.ajinkyagokhale.espflasher.service.PrereqChecker;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Mostra onde estão o Python e o esptool, se estão instalados, e oferece
 * botões para instalar ou selecionar o caminho manualmente.
 *
 * <p>Várias instâncias podem existir (tela principal e Configurações); todas
 * leem o mesmo {@link PrereqChecker}. Depois de qualquer mudança, o painel chama
 * {@code onChanged}, e o dono deve chamar {@link #refresh()} em todas.</p>
 */
public class PrereqPanel extends VBox {

    private static final String OK_COLOR = "#43a047";
    private static final String ERR_COLOR = "#e53935";
    private static final String WAIT_COLOR = "#9e9e9e";

    private final PrereqChecker checker;
    private final Window owner;
    private final Consumer<String> openUrl;
    private final Runnable onChanged;

    private final Label pythonIcon = icon();
    private final Label pythonPath = pathLabel();
    private final Button pythonInstall = new Button("Instalar");
    private final Button pythonSelect = new Button("Selecionar...");

    private final Label esptoolIcon = icon();
    private final Label esptoolPath = pathLabel();
    private final Button esptoolInstall = new Button("Instalar");
    private final Button esptoolSelect = new Button("Selecionar...");

    private final Button recheckBtn = new Button("↻ Verificar");
    private final Button autoBtn = new Button("Detecção automática");

    private static final java.util.concurrent.atomic.AtomicBoolean CHECKING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Painel precisa da janela dona e do verificador compartilhado")
    public PrereqPanel(PrereqChecker checker, Window owner, Consumer<String> openUrl,
                       Runnable onChanged, boolean showAutoButton) {
        super(6);
        this.checker = checker;
        this.owner = owner;
        this.openUrl = openUrl;
        this.onChanged = onChanged;

        getStyleClass().add("config-card");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        Label pythonName = new Label("Python");
        pythonName.setMinWidth(60);
        Label esptoolName = new Label("esptool");
        esptoolName.setMinWidth(60);

        GridPane.setHgrow(pythonPath, Priority.ALWAYS);
        GridPane.setHgrow(esptoolPath, Priority.ALWAYS);

        grid.addRow(0, pythonIcon, pythonName, pythonPath, pythonInstall, pythonSelect);
        grid.addRow(1, esptoolIcon, esptoolName, esptoolPath, esptoolInstall, esptoolSelect);

        for (Button btn : new Button[]{pythonInstall, pythonSelect, esptoolInstall, esptoolSelect, recheckBtn, autoBtn}) {
            btn.setMinWidth(Region.USE_PREF_SIZE);
        }

        pythonInstall.setOnAction(e -> installPython());
        esptoolInstall.setOnAction(e -> installEsptool());
        pythonSelect.setOnAction(e -> selectPython());
        esptoolSelect.setOnAction(e -> selectEsptool());
        recheckBtn.setOnAction(e -> recheck());
        autoBtn.setOnAction(e -> {
            PrereqChecker.clearCustomPaths();
            recheck();
        });

        pythonSelect.setTooltip(new Tooltip("Escolher manualmente o python.exe a ser usado"));
        esptoolSelect.setTooltip(new Tooltip(
                "Escolher manualmente o esptool (esptool.exe standalone ou esptool.py)"));
        autoBtn.setTooltip(new Tooltip("Esquecer os caminhos escolhidos manualmente e procurar de novo"));

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        if (showAutoButton) footer.getChildren().add(autoBtn);
        footer.getChildren().add(recheckBtn);

        getChildren().addAll(grid, footer);
        refresh();
    }

    // ── Estado ──────────────────────────────────────────────────

    /** Atualiza rótulos e botões a partir do último resultado do {@link PrereqChecker}. */
    public void refresh() {
        boolean busy = CHECKING.get();
        recheckBtn.setDisable(busy);
        autoBtn.setDisable(busy);
        pythonSelect.setDisable(busy);
        esptoolSelect.setDisable(busy);

        if (busy) {
            setState(pythonIcon, pythonPath, null, "Procurando...", null);
            setState(esptoolIcon, esptoolPath, null, "Procurando...", null);
            show(pythonInstall, false);
            show(esptoolInstall, false);
            return;
        }

        // Python
        String customPython = PrereqChecker.getCustomPythonPath();
        if (checker.isPythonFound()) {
            String text = checker.getPythonExe() + "  (" + checker.getPythonVersion() + ")";
            if (!customPython.isEmpty()) text += "  [manual]";
            setState(pythonIcon, pythonPath, true, text, checker.getPythonExe());
            show(pythonInstall, false);
        } else if (!customPython.isEmpty()) {
            setState(pythonIcon, pythonPath, false, "Inválido: " + customPython, customPython);
            show(pythonInstall, true);
        } else if (checker.isReady()) {
            // esptool standalone funciona sem Python
            setState(pythonIcon, pythonPath, null, "Não encontrado (não é necessário para o esptool escolhido)", null);
            show(pythonInstall, true);
        } else {
            setState(pythonIcon, pythonPath, false, "Não instalado", null);
            show(pythonInstall, true);
        }

        // esptool
        String customEsptool = PrereqChecker.getCustomEsptoolPath();
        if (checker.isReady()) {
            String where = checker.getEsptoolLocation() != null ? checker.getEsptoolLocation() : "";
            String ver = checker.getEsptoolVersion() != null ? "  (v" + checker.getEsptoolVersion() + ")" : "";
            String text = where + ver + (checker.isEsptoolFromCustomPath() ? "  [manual]" : "");
            setState(esptoolIcon, esptoolPath, true, text, where);
            show(esptoolInstall, false);
        } else {
            String text = !customEsptool.isEmpty()
                    ? "Inválido: " + customEsptool
                    : "Não instalado";
            setState(esptoolIcon, esptoolPath, false, text, customEsptool.isEmpty() ? null : customEsptool);
            show(esptoolInstall, true);
            esptoolInstall.setDisable(!checker.isPythonFound());
            esptoolInstall.setTooltip(new Tooltip(checker.isPythonFound()
                    ? "Instalar o esptool com o pip do Python acima"
                    : "Instale o Python primeiro"));
        }

        pythonInstall.setTooltip(new Tooltip(PrereqChecker.canAutoInstallPython()
                ? "Instalar o Python 3.13 para este usuário (via winget)"
                : "Abrir o site python.org"));
    }

    /** Roda a detecção em segundo plano e avisa o dono quando terminar. */
    public void recheck() {
        if (!CHECKING.compareAndSet(false, true)) return;
        onChanged.run();
        Thread t = new Thread(() -> {
            try {
                checker.checkAll();
            } finally {
                CHECKING.set(false);
                Platform.runLater(onChanged);
            }
        }, "prereq-check");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isChecking() {
        return CHECKING.get();
    }

    // ── Ações ───────────────────────────────────────────────────

    private void selectPython() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Selecionar o Python");
        if (PrereqChecker.isWindows()) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Python (python.exe)", "python*.exe"));
        }
        initialDir(fc, checker.getPythonExe());
        File f = fc.showOpenDialog(owner);
        if (f == null) return;
        PrereqChecker.setCustomPythonPath(f.getAbsolutePath());
        recheck();
    }

    private void selectEsptool() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Selecionar o esptool");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("esptool (esptool.exe, esptool.py)", "esptool*"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*")
        );
        initialDir(fc, checker.getEsptoolLocation());
        File f = fc.showOpenDialog(owner);
        if (f == null) return;
        PrereqChecker.setCustomEsptoolPath(f.getAbsolutePath());
        recheck();
    }

    private void installPython() {
        if (!PrereqChecker.canAutoInstallPython()) {
            openUrl.accept("https://www.python.org/downloads/");
            return;
        }
        runInstall("Instalando o Python",
                "Instalando o Python 3.13 via winget. Isso pode levar alguns minutos...",
                checker::installPython,
                ok -> ok ? "✔ Python instalado." : "✖ Não foi possível instalar o Python. "
                        + "Instale manualmente em python.org e clique em Verificar.",
                ok -> {
                    if (!ok) openUrl.accept("https://www.python.org/downloads/");
                });
    }

    private void installEsptool() {
        runInstall("Instalando o esptool",
                "Instalando o esptool com o pip...",
                checker::installEsptool,
                ok -> ok ? "✔ esptool instalado." : "✖ Falha ao instalar o esptool. Veja a saída acima.",
                ok -> { });
    }

    /** Janela com saída ao vivo de um processo de instalação. */
    private void runInstall(String title, String header,
                            Predicate<Consumer<String>> task,
                            java.util.function.Function<Boolean, String> resultText,
                            Consumer<Boolean> after) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title);

        Label heading = new Label(header);
        heading.setStyle("-fx-font-weight: bold;");
        heading.setWrapText(true);

        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);

        TextArea output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setPrefSize(560, 240);
        output.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        Label status = new Label("Aguarde...");
        status.setWrapText(true);

        Button closeBtn = new Button("Fechar");
        closeBtn.setDisable(true);
        closeBtn.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, heading, bar, output, status, buttons);
        root.setPadding(new Insets(16));
        dialog.setScene(new Scene(root));
        if (owner.getScene() != null) {
            dialog.getScene().getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        dialog.setOnCloseRequest(e -> {
            if (closeBtn.isDisabled()) e.consume(); // não fechar no meio da instalação
        });

        if (!CHECKING.compareAndSet(false, true)) return;
        onChanged.run();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                ok = task.test(line -> Platform.runLater(() -> output.appendText(line + "\n")));
            } finally {
                CHECKING.set(false);
                boolean result = ok;
                Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    status.setText(resultText.apply(result));
                    status.setStyle("-fx-text-fill: " + (result ? OK_COLOR : ERR_COLOR) + ";");
                    closeBtn.setDisable(false);
                    onChanged.run();
                    after.accept(result);
                });
            }
        }, "prereq-install");
        t.setDaemon(true);
        t.start();

        dialog.show();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static void initialDir(FileChooser fc, String path) {
        if (path == null || path.isBlank()) return;
        File dir = new File(path);
        if (!dir.isDirectory()) dir = dir.getParentFile();
        if (dir != null && dir.isDirectory()) fc.setInitialDirectory(dir);
    }

    private static Label icon() {
        Label l = new Label();
        l.setMinWidth(16);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Label pathLabel() {
        Label l = new Label();
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinWidth(120);
        l.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        l.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        return l;
    }

    private static void show(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
        b.setDisable(false);
    }

    /** ok: true = verde, false = vermelho, null = neutro. */
    private static void setState(Label iconLabel, Label pathLabel, Boolean ok, String text, String tooltip) {
        String color = ok == null ? WAIT_COLOR : (ok ? OK_COLOR : ERR_COLOR);
        iconLabel.setText(ok == null ? "•" : (ok ? "✔" : "✖"));
        iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";");
        pathLabel.setText(text);
        pathLabel.setTooltip(tooltip == null || tooltip.isBlank() ? null : new Tooltip(tooltip));
        if (ok != null && !ok) {
            pathLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: " + ERR_COLOR + ";");
        } else {
            pathLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        }
    }
}
