package com.ajinkyagokhale.espflasher.ui;

import com.ajinkyagokhale.espflasher.listener.FlashListener;
import com.ajinkyagokhale.espflasher.listener.PortListener;
import com.ajinkyagokhale.espflasher.model.AppSettings;
import com.ajinkyagokhale.espflasher.model.FirmwareDefinition;
import com.ajinkyagokhale.espflasher.model.FlashConfig;
import com.ajinkyagokhale.espflasher.model.FlashResult;
import com.ajinkyagokhale.espflasher.service.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.AudioClip;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlasherApp extends Application implements FlashListener, PortListener {

    // ── UI controls ─────────────────────────────────────────
    private TextField binPathField;
    private ComboBox<String> chipCombo;
    private ComboBox<String> portCombo;
    private ComboBox<String> baudCombo;
    private TextField flashOffsetField;
    private ProgressBar progressBar;
    private TextArea logArea;
    private Button flashButton;
    private Button stopButton;
    private Button factoryButton;
    private Label statusLabel;
    private Label flashCountLabel;
    private Label flashCountNumber;

    // ── Services ────────────────────────────────────────────
    private EsptoolRunner esptoolRunner;
    private PortWatcher portWatcher;
    private PrereqChecker prereqChecker;
    private SettingsManager settingsManager;
    private FlashLogger flashLogger;

    // ── Audio ───────────────────────────────────────────────
    private AudioClip successSound;
    private AudioClip failSound;

    // ── Flash state ─────────────────────────────────────────
    private int flashCount = 0;
    private boolean isFactoryMode = false;
    private String currentFlashPort;
    private String currentFlashMac;

    private RadioButton myBinaryRadio;
    private RadioButton popularFirmwareRadio;
    private ComboBox<FirmwareDefinition> firmwareCombo;
    private Label firmwareDescLabel;
    private Label firmwareVersionLabel;
    private VBox popularFirmwareRow;
    private FirmwareDownloader firmwareDownloader;
    private FirmwareDefinition selectedFirmware;

    // Top-level views (toggled by toolbar / Explore button)
    private VBox flasherView;
    private VBox settingsView;
    private VBox exploreView;
    private Button toolbarFlasherBtn;
    private Button toolbarSettingsBtn;

    /** Velocidade pré-selecionada ao abrir: a maior da lista. */
    private static final String BAUD_DEFAULT = "921600";

    // Painéis de pré-requisitos (tela principal + Configurações > Caminhos)
    private final List<PrereqPanel> prereqPanels = new ArrayList<>();
    private Stage mainStage;


    // ════════════════════════════════════════════════════════
    // Application lifecycle
    // ════════════════════════════════════════════════════════

    @Override
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "A janela principal é guardada para abrir diálogos")
    public void start(Stage primaryStage) throws Exception {
        mainStage = primaryStage;
        checkForUpdates();


        initIcon(primaryStage);
        initService();
        initSound();

        primaryStage.setTitle("ESP Flasher");
        primaryStage.setWidth(780);
        primaryStage.setHeight(740);
        primaryStage.setMinWidth(780);
        primaryStage.setMinHeight(680);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20));

        // Flasher view container — all existing build methods feed into this
        flasherView = new VBox(10);
        VBox.setVgrow(flasherView, Priority.ALWAYS);

        flasherView.getChildren().add(newPrereqPanel(false));
        buildConfigCard(primaryStage, flasherView);
        buildButtonRow(primaryStage, flasherView);
        addProgressBar(flasherView);
        checkPrerequisites();
        applyLastSettings();
        buildLogArea(flasherView);

        // Settings view — initially hidden
        settingsView = buildSettingsView(primaryStage);
        VBox.setVgrow(settingsView, Priority.ALWAYS);
        settingsView.setVisible(false);
        settingsView.setManaged(false);

        // Explore view — initially hidden
        exploreView = buildExploreView();
        VBox.setVgrow(exploreView, Priority.ALWAYS);
        exploreView.setVisible(false);
        exploreView.setManaged(false);

        HBox toolbar = buildToolbar();
        root.getChildren().addAll(toolbar, flasherView, settingsView, exploreView);

        // Footer
        buildFooter(root);

        Scene scene = new Scene(root);
        if (isDarkMode()) {
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());
        }
        primaryStage.setScene(scene);

        primaryStage.show();
        if (isDarkMode()) applyDarkTitleBar(primaryStage);
    }

    private HBox buildToolbar() {
        toolbarFlasherBtn = new Button("Gravador");
        toolbarSettingsBtn = new Button("Configurações");

        toolbarFlasherBtn.getStyleClass().addAll("toolbar-btn", "toolbar-btn-active");
        toolbarSettingsBtn.getStyleClass().add("toolbar-btn");

        toolbarFlasherBtn.setOnAction(e -> showView(flasherView));
        toolbarSettingsBtn.setOnAction(e -> showView(settingsView));

        HBox toolbar = new HBox(2, toolbarFlasherBtn, toolbarSettingsBtn);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER);

        HBox toolbarWrapper = new HBox(toolbar);
        toolbarWrapper.setAlignment(Pos.CENTER);
        toolbarWrapper.setPadding(new Insets(4, 0, 4, 0));
        return toolbarWrapper;
    }

    /** Show one of flasher/settings/explore; hide the others. Updates toolbar active state. */
    private void showView(VBox target) {
        VBox[] all = { flasherView, settingsView, exploreView };
        for (VBox v : all) {
            boolean show = v == target;
            v.setVisible(show);
            v.setManaged(show);
        }
        toolbarFlasherBtn.getStyleClass().remove("toolbar-btn-active");
        toolbarSettingsBtn.getStyleClass().remove("toolbar-btn-active");
        if (target == flasherView) {
            toolbarFlasherBtn.getStyleClass().add("toolbar-btn-active");
        } else if (target == settingsView) {
            toolbarSettingsBtn.getStyleClass().add("toolbar-btn-active");
        }
        // explore view leaves both toolbar buttons inactive (deliberate)
    }

    private void initIcon(Stage primaryStage) {
        // set app icon — JavaFX Image only supports PNG/JPEG/BMP, not .ico/.icns
        var iconUrl = getClass().getResource("/icons/icon.png");
        if (iconUrl != null) {
            primaryStage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
        }
    }

    private void buildFooter(VBox root) {
        Label footer = new Label("Criado com ❤ por Ajinkya Gokhale  •  Versão pt-BR: Verin");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("footer");
        footer.setOnMouseClicked(e -> showAboutDialog());
        footer.setStyle("-fx-cursor: hand;");
        root.getChildren().add(footer);
    }

    private void applyLastSettings() {
        // Apply last-used settings
        AppSettings settings = settingsManager.getSettings();
        if (!settings.getLastChip().isEmpty()) chipCombo.setValue(settings.getLastChip());
        // Velocidade não é restaurada: o app sempre abre na maior (BAUD_DEFAULT)
        if (!settings.getLastBinPath().isEmpty()) binPathField.setText(settings.getLastBinPath());
    }

    private void initSound() {
        var successUrl = getClass().getResource("/sounds/success.wav");
        var failUrl = getClass().getResource("/sounds/failure.wav");
        if (successUrl != null && failUrl != null) {
            successSound = new AudioClip(successUrl.toExternalForm());
            failSound = new AudioClip(failUrl.toExternalForm());
        } else {
            System.out.println("Sound files not found at /sounds/success.wav or /sounds/failure.wav");
        }
    }

    private void initService() {
        esptoolRunner = new EsptoolRunner();
        portWatcher = new PortWatcher();
        prereqChecker = new PrereqChecker();
        settingsManager = new SettingsManager();
        settingsManager.load();
        flashLogger = new FlashLogger(settingsManager.getSettings());
        firmwareDownloader = new FirmwareDownloader();
    }

    private void buildLogArea(VBox root) {
        // Log area
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        root.getChildren().add(logArea);

        // Route firmware-downloader diagnostics here (thread-safe via Platform.runLater)
        firmwareDownloader.setLogger(msg ->
                Platform.runLater(() -> logArea.appendText(msg + "\n"))
        );
    }

    private void addProgressBar(VBox root) {
        // Progress + status
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        statusLabel = new Label("Pronto.");
        root.getChildren().addAll(progressBar, statusLabel);
    }

    private void buildButtonRow(Stage primaryStage, VBox root) {

        // Action buttons + flash counter
        flashButton = new Button("Gravar");
        flashButton.setOnAction(e -> startFlash(null));

        factoryButton = new Button("Modo Produção");
        factoryButton.setOnAction(e -> startFactoryMode());

        stopButton = new Button("Parar");
        stopButton.setOnAction(e -> stopAll());
        stopButton.setDisable(true);

        flashCountNumber = new Label("0");
        flashCountNumber.getStyleClass().add("flash-count-number");
        Label flashCountSub = new Label("gravados");
        flashCountSub.getStyleClass().add("flash-count-sub");
        flashCountLabel = new Label();
        flashCountLabel.getStyleClass().add("flash-count-card");
        VBox flashCard = new VBox(0, flashCountNumber, flashCountSub);
        flashCard.setAlignment(Pos.CENTER);
        flashCountLabel.setGraphic(flashCard);

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonRow = new HBox(10, flashButton, factoryButton, spacer, flashCountLabel);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(buttonRow);
    }
    private void buildConfigCard(Stage primaryStage, VBox root) {

        // ── Firmware source selector ────────────────────────
        ComboBox<String> firmwareSourceCombo = new ComboBox<>();
        firmwareSourceCombo.getItems().add("Binário próprio");
        FirmwareCatalog.getCatalog().forEach(f ->
                firmwareSourceCombo.getItems().add(f.getName())
        );
        firmwareSourceCombo.setValue("Binário próprio");

        // ── Inline firmware description (next to Source dropdown) ──
        firmwareDescLabel = new Label("");
        firmwareDescLabel.getStyleClass().add("config-label");
        firmwareDescLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        firmwareDescLabel.setWrapText(true);
        HBox.setHgrow(firmwareDescLabel, Priority.ALWAYS);

        Button exploreBtn = new Button("Explorar projetos populares →");
        exploreBtn.getStyleClass().add("link-button");
        exploreBtn.setOnAction(e -> showView(exploreView));

        HBox sourceRow = new HBox(10, new Label("Origem"), firmwareSourceCombo, firmwareDescLabel, exploreBtn);
        sourceRow.setAlignment(Pos.CENTER_LEFT);

        // ── Custom binary file row ──────────────────────────
        binPathField = new TextField();
        binPathField.setPromptText("Selecione o arquivo .bin do firmware...");
        binPathField.setEditable(false);
        HBox.setHgrow(binPathField, Priority.ALWAYS);

        Button browseButton = new Button("Procurar...");
        browseButton.setOnAction(e -> browseBin(primaryStage));

        HBox fileRow = new HBox(10, new Label("Firmware"), binPathField, browseButton);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        // ── Popular firmware version row ────────────────────
        firmwareVersionLabel = new Label("");
        firmwareVersionLabel.setStyle("-fx-text-fill: #66bb6a; -fx-font-size: 11px;");

        popularFirmwareRow = new VBox(4, firmwareVersionLabel);
        popularFirmwareRow.setVisible(false);
        popularFirmwareRow.setManaged(false);

        // ── Source toggle behavior ──────────────────────────
        firmwareSourceCombo.setOnAction(e -> {
            String selected = firmwareSourceCombo.getValue();
            boolean isCustom = selected.equals("Binário próprio");

            fileRow.setVisible(isCustom);
            fileRow.setManaged(isCustom);
            popularFirmwareRow.setVisible(!isCustom);
            popularFirmwareRow.setManaged(!isCustom);

            if (isCustom) {
                firmwareDescLabel.setText("");
                selectedFirmware = null;
                updateChipListForFirmware(null);
                return;
            }

            // find matching FirmwareDefinition
            FirmwareDefinition def = FirmwareCatalog.getCatalog().stream()
                    .filter(f -> f.getName().equals(selected))
                    .findFirst()
                    .orElse(null);

            selectedFirmware = def;
            updateChipListForFirmware(def);

            if (def != null) {
                firmwareDescLabel.setText(def.getDescription());
                firmwareVersionLabel.setText("Buscando versão...");
                new Thread(() -> {
                    boolean online = firmwareDownloader.isOnline();
                    if (!online) {
                        Platform.runLater(() ->
                                firmwareVersionLabel.setText("⚠ Sem conexão com a internet")
                        );
                        return;
                    }
                    String version = firmwareDownloader.fetchLatestVersion(def);
                    Platform.runLater(() ->
                            firmwareVersionLabel.setText(("unknown".equals(version) ? "Versão mais recente: desconhecida" : "Versão mais recente: v" + version))
                    );
                }).start();
            }
        });

        // ── Chip row ────────────────────────────────────────
        chipCombo = new ComboBox<>();
        chipCombo.getItems().addAll(
                "auto", "esp32c6", "esp32", "esp32s2",
                "esp32s3", "esp32c3", "esp32h2", "esp8266"
        );
        chipCombo.setValue("auto");
        HBox chipRow = new HBox(10, new Label("Chip"), chipCombo);
        chipRow.setAlignment(Pos.CENTER_LEFT);

        // ── Port row ────────────────────────────────────────
        portCombo = new ComboBox<>();
        portCombo.setEditable(true);
        portCombo.setPromptText("Selecione a porta...");
        refreshPorts();
        Button refreshButton = new Button("Atualizar");
        refreshButton.setOnAction(e -> refreshPorts());
        HBox portRow = new HBox(10, new Label("Porta"), portCombo, refreshButton);
        portRow.setAlignment(Pos.CENTER_LEFT);

        // ── Baud row ────────────────────────────────────────
        baudCombo = new ComboBox<>();
        // Da maior para a menor; 921600 é o máximo que os conversores comuns (CP2102, CH340, FTDI) aceitam
        baudCombo.getItems().addAll(BAUD_DEFAULT, "460800", "230400", "115200");
        baudCombo.setValue(BAUD_DEFAULT);
        HBox baudRow = new HBox(10, new Label("Velocidade (baud)"), baudCombo);
        baudRow.setAlignment(Pos.CENTER_LEFT);

        // ── Offset row ──────────────────────────────────────
        flashOffsetField = new TextField("0x0");
        flashOffsetField.setPrefWidth(120);
        HBox offsetRow = new HBox(10, new Label("Offset de gravação"), flashOffsetField);
        offsetRow.setAlignment(Pos.CENTER_LEFT);

        // ── Config card ─────────────────────────────────────
        VBox configCard = new VBox(10);
        configCard.getStyleClass().add("config-card");
        configCard.setMaxWidth(Double.MAX_VALUE);
        configCard.setFillWidth(true);
        configCard.getChildren().addAll(
                sourceRow,
                fileRow,
                popularFirmwareRow,
                chipRow, portRow, baudRow, offsetRow
        );
        root.getChildren().add(configCard);
    }

    @Override
    public void stop() {
        AppSettings settings = settingsManager.getSettings();
        settings.setLastChip(chipCombo.getValue());
        settings.setLastBaudRate(baudCombo.getValue());
        settings.setLastBinPath(binPathField.getText());
        settings.setLastPort(portCombo.getValue());
        settingsManager.save();

        portWatcher.stopWatching();
        esptoolRunner.stopFlashing();
    }


    // ════════════════════════════════════════════════════════
    // Listener callbacks (FlashListener, PortListener)
    // ════════════════════════════════════════════════════════

    @Override
    public void onProgress(int percent) {
        Platform.runLater(() -> progressBar.setProgress(percent / 100.0));
    }

    @Override
    public void onLog(String line) {
        if (line.contains("MAC:")) {
            String[] parts = line.split("MAC:");
            if (parts.length > 1) currentFlashMac = parts[1].trim();
        }
        Platform.runLater(() -> logArea.appendText(line + "\n"));
    }

    @Override
    public void onComplete(boolean success, String message) {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        flashLogger.append(new FlashResult(success, message, currentFlashMac, currentFlashPort, timestamp));

        Platform.runLater(() -> {
            statusLabel.setText(message);
            stopButton.setDisable(true);
            updateReadyState(false);
            if (success) {
                flashCount++;
                flashCountNumber.setText(String.valueOf(flashCount));
                flashCountLabel.setStyle("-fx-background-color: rgba(102,187,106,0.25); -fx-border-color: #66bb6a;");
                new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(800),
                                ev -> flashCountLabel.setStyle(""))
                ).play();
                progressBar.setStyle("-fx-accent: green;");
                progressBar.setProgress(1.0);
                successSound.play();
                statusLabel.setStyle("-fx-text-fill: green;");
            } else {
                failSound.play();
                progressBar.setStyle("-fx-accent: red;");
                progressBar.setProgress(1.0);
                statusLabel.setStyle("-fx-text-fill: red;");
            }

            if (isFactoryMode) {
                statusLabel.setText("Aguardando o próximo dispositivo...");
                statusLabel.setStyle("-fx-text-fill: #66bb6a;");
                flashButton.setDisable(true);
                stopButton.setDisable(false);
            }
        });
    }

    @Override
    public void onNewPort(String portName) {
        Platform.runLater(() -> {
            statusLabel.setText("Dispositivo detectado: " + portName + " — gravando...");
            startFlash(portName);
        });
    }


    // ════════════════════════════════════════════════════════
    // Flash actions
    // ════════════════════════════════════════════════════════

    private void startFlash(String overridePort) {
        if (!prereqChecker.isReady()) {
            statusLabel.setText("esptool não encontrado. Instale ou selecione o esptool acima.");
            return;
        }

        String port = overridePort != null ? overridePort : portCombo.getValue();
        if (port == null || port.isEmpty()) {
            statusLabel.setText("Selecione uma porta.");
            return;
        }

        if (selectedFirmware != null) {
            downloadAndFlash(selectedFirmware, port);
            return;
        }

        String binPath = binPathField.getText();
        if (binPath.isEmpty()) {
            statusLabel.setText("Selecione um arquivo de firmware.");
            return;
        }

        runFlash(port, binPath);
    }

    private void runFlash(String port, String binPath) {
        FlashConfig config = new FlashConfig(
                chipCombo.getValue(),
                Integer.parseInt(baudCombo.getValue()),
                port,
                binPath,
                flashOffsetField.getText(),
                prereqChecker.getEsptoolCommand()
        );

        flashButton.setDisable(true);
        stopButton.setDisable(false);
        progressBar.setStyle("");
        progressBar.setProgress(0);
        statusLabel.setText("Gravando...");

        currentFlashPort = port;
        currentFlashMac = null;
        esptoolRunner.startFlashing(config, this);
    }

    private void downloadAndFlash(FirmwareDefinition fw, String port) {
        flashButton.setDisable(true);
        stopButton.setDisable(false);
        progressBar.setStyle("");
        progressBar.setProgress(0);
        statusLabel.setText("Baixando " + fw.getName() + "...");

        String chip = chipCombo.getValue();
        String binName = fw.getBinForChip(chip);
        if (binName == null) {
            Platform.runLater(() -> {
                statusLabel.setText("Nenhum binário definido para " + chip);
                statusLabel.setStyle("-fx-text-fill: red;");
                flashButton.setDisable(false);
                stopButton.setDisable(true);
            });
            return;
        }

        new Thread(() -> {
            try {
                String url = firmwareDownloader.fetchDownloadUrl(fw, chip);
                if (url == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Não foi possível encontrar o link de download de " + binName);
                        statusLabel.setStyle("-fx-text-fill: red;");
                        flashButton.setDisable(false);
                        stopButton.setDisable(true);
                    });
                    return;
                }

                FirmwareDownloader.DownloadListener listener = (downloaded, total) ->
                        Platform.runLater(() -> {
                            if (total > 0) progressBar.setProgress((double) downloaded / total);
                        });

                String version = firmwareDownloader.fetchLatestVersion(fw);
                String localPath = binName.toLowerCase().endsWith(".zip")
                        ? firmwareDownloader.downloadAndExtract(url, binName, chip, version, listener)
                        : firmwareDownloader.download(url, binName, listener);

                Platform.runLater(() -> {
                    binPathField.setText(localPath);
                    progressBar.setProgress(0);
                    runFlash(port, localPath);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    logArea.appendText("[download] " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "\n");
                    statusLabel.setText("Falha no download.");
                    statusLabel.setStyle("-fx-text-fill: red;");
                    flashButton.setDisable(false);
                    stopButton.setDisable(true);
                });
            }
        }).start();
    }

    private void startFactoryMode() {
        if (binPathField.getText().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Nenhum firmware selecionado");
            alert.setHeaderText(null);
            alert.setContentText("Selecione um arquivo .bin de firmware antes de iniciar o modo produção.");
            alert.showAndWait();
            return;
        }
        if (!prereqChecker.isReady()) {
            statusLabel.setText("esptool não está pronto.");
            return;
        }

        // Confirmation dialog
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Iniciar modo produção");
        confirm.setHeaderText("Pronto para gravar?");

        Label binLabel = new Label(new File(binPathField.getText()).getName());
        binLabel.getStyleClass().add("confirm-bin-name");

        Label chipLabel = new Label("Chip: " + chipCombo.getValue() + "   Baud: " + baudCombo.getValue());
        chipLabel.getStyleClass().add("confirm-details");

        VBox content = new VBox(8, binLabel, chipLabel);
        confirm.getDialogPane().setContent(content);
        confirm.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm()
        );
        confirm.setGraphic(null);

        ButtonType startBtn = new ButtonType("Iniciar Produção", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(startBtn, cancelBtn);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != startBtn) return;

        isFactoryMode = true;
        flashButton.setDisable(true);
        factoryButton.setText("Parar Produção");
        factoryButton.setOnAction(e -> stopAll());
        stopButton.setDisable(false);
        statusLabel.setText("Modo produção — aguardando dispositivo...");
        statusLabel.setStyle("-fx-text-fill: #66bb6a;");

        portWatcher.startWatching(this);
    }

    private void stopAll() {
        portWatcher.stopWatching();
        esptoolRunner.stopFlashing();
        isFactoryMode = false;
        flashButton.setDisable(false);
        factoryButton.setText("Modo Produção");
        factoryButton.setOnAction(e -> startFactoryMode());
        stopButton.setDisable(true);
        statusLabel.setText("Parado.");
        statusLabel.setStyle("");
        updateReadyState(false);
    }


    // ════════════════════════════════════════════════════════
    // Settings dialog
    // ════════════════════════════════════════════════════════

    private VBox buildSettingsView(Stage parentStage) {
        // Left menu
        VBox leftMenu = new VBox(0);
        leftMenu.getStyleClass().add("settings-menu");
        leftMenu.setPrefWidth(150);
        leftMenu.setMinWidth(150);

        Label menuTitle = new Label("Configurações");
        menuTitle.getStyleClass().add("settings-menu-title");
        menuTitle.setPadding(new Insets(16));

        Button generalBtn = new Button("Geral");
        generalBtn.getStyleClass().add("settings-menu-item");
        generalBtn.setMaxWidth(Double.MAX_VALUE);

        Button pathsBtn = new Button("Caminhos");
        pathsBtn.getStyleClass().add("settings-menu-item");
        pathsBtn.setMaxWidth(Double.MAX_VALUE);

        Button logFileBtn = new Button("Arquivo de log");
        logFileBtn.getStyleClass().add("settings-menu-item");
        logFileBtn.setMaxWidth(Double.MAX_VALUE);

        leftMenu.getChildren().addAll(menuTitle, generalBtn, pathsBtn, logFileBtn);

        // Right panels
        VBox generalPane = buildGeneralPane();
        VBox pathsPane = buildPathsPane(parentStage);
        VBox logFilePane = buildLogFilePane(parentStage);

        pathsPane.setVisible(false);
        logFilePane.setVisible(false);

        StackPane rightContent = new StackPane(generalPane, pathsPane, logFilePane);
        rightContent.setPadding(new Insets(20));
        HBox.setHgrow(rightContent, Priority.ALWAYS);

        generalBtn.setOnAction(e -> {
            generalPane.setVisible(true);
            pathsPane.setVisible(false);
            logFilePane.setVisible(false);
            setActiveMenu(generalBtn, pathsBtn, logFileBtn);
        });
        pathsBtn.setOnAction(e -> {
            generalPane.setVisible(false);
            pathsPane.setVisible(true);
            logFilePane.setVisible(false);
            setActiveMenu(pathsBtn, generalBtn, logFileBtn);
        });
        logFileBtn.setOnAction(e -> {
            generalPane.setVisible(false);
            pathsPane.setVisible(false);
            logFilePane.setVisible(true);
            setActiveMenu(logFileBtn, generalBtn, pathsBtn);
        });

        generalBtn.getStyleClass().add("settings-menu-item-active");

        HBox mainLayout = new HBox(leftMenu, rightContent);
        VBox.setVgrow(mainLayout, Priority.ALWAYS);

        VBox view = new VBox(mainLayout);
        view.getStyleClass().add("settings-root");
        return view;
    }

    private void setActiveMenu(Button active, Button... others) {
        active.getStyleClass().add("settings-menu-item-active");
        for (Button b : others) b.getStyleClass().remove("settings-menu-item-active");
    }

    private VBox buildGeneralPane() {
        VBox pane = new VBox(12);

        Label heading = new Label("Geral");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label info = new Label("As últimas configurações usadas são salvas automaticamente.");
        info.setStyle("-fx-text-fill: #888888;");

        pane.getChildren().addAll(heading, info);
        return pane;
    }

    private VBox buildPathsPane(Stage dialog) {
        VBox pane = new VBox(12);

        Label heading = new Label("Python e esptool");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label info = new Label("O ESP Flasher procura o Python e o esptool automaticamente. "
                + "Use \"Instalar\" para instalar o que faltar, ou \"Selecionar...\" para "
                + "apontar um caminho manualmente. Um esptool.exe standalone (sem Python) "
                + "também funciona.");
        info.setWrapText(true);
        info.setStyle("-fx-text-fill: #888888;");

        pane.getChildren().addAll(heading, info, newPrereqPanel(true));

        // A lista de instalações pode crescer: rolagem dentro do painel
        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildLogFilePane(Stage dialog) {
        VBox pane = new VBox(12);
        AppSettings settings = settingsManager.getSettings();

        Label heading = new Label("Arquivo de log");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label infoBtn = new Label("ℹ");
        infoBtn.setStyle(
                "-fx-border-color: #666; -fx-border-radius: 50%; -fx-background-radius: 50%; " +
                        "-fx-background-color: #2a2a2a; -fx-font-size: 12px; -fx-text-fill: #cccccc; " +
                        "-fx-min-width: 22px; -fx-min-height: 22px; -fx-max-width: 22px; -fx-max-height: 22px; " +
                        "-fx-alignment: center; -fx-cursor: hand;");
        infoBtn.setOnMouseClicked(e -> {
            Alert info = new Alert(Alert.AlertType.NONE);
            info.setTitle("Por que usar um arquivo de log?");
            info.getButtonTypes().add(ButtonType.OK);

            Label title = new Label("Auditoria de provisionamento por endereço MAC");
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

            Label recordedLabel = new Label("Cada tentativa de gravação é registrada com:");
            recordedLabel.setStyle("-fx-font-size: 12px;");

            VBox bullets = new VBox(4,
                    bullet("Data e hora"),
                    bullet("Porta serial"),
                    bullet("Endereço MAC do dispositivo"),
                    bullet("Status  (sucesso / falha)")
            );
            bullets.setStyle("-fx-padding: 0 0 0 12;");

            Label useCaseLabel = new Label("Caso de uso — conferência de provisionamento no backend:");
            useCaseLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8 0 0 0;");

            Label useCaseBody = new Label(
                    "Envie o flash-log.csv para o seu backend e cruze os endereços MAC " +
                            "com o cadastro de dispositivos provisionados. Assim você confirma " +
                            "que todo dispositivo gravado foi cadastrado, encontra dispositivos gravados " +
                            "mas nunca provisionados e identifica duplicados (mesmo MAC gravado duas vezes)."
            );
            useCaseBody.setStyle("-fx-font-size: 12px;");
            useCaseBody.setWrapText(true);
            useCaseBody.setMaxWidth(380);

            VBox content = new VBox(8, title, recordedLabel, bullets, useCaseLabel, useCaseBody);
            content.setStyle("-fx-padding: 4;");

            info.getDialogPane().setContent(content);
            info.getDialogPane().getStylesheets().addAll(dialog.getScene().getStylesheets());
            info.showAndWait();
        });

        HBox headingRow = new HBox(8, heading, infoBtn);
        headingRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox enableToggle = new CheckBox("Ativar arquivo de log");
        enableToggle.setSelected(settings.isLogFileEnabled());
        enableToggle.setStyle(settings.isLogFileEnabled() ? "-fx-text-fill: #66bb6a;" : "");

        Label pathLabel = new Label("Pasta do log:");
        TextField pathField = new TextField(settings.getLogFilePath());
        pathField.setDisable(!settings.isLogFileEnabled());
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button browseBtn = new Button("Procurar...");
        browseBtn.setDisable(!settings.isLogFileEnabled());
        browseBtn.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Selecionar pasta do log");
            File dir = dc.showDialog(dialog);
            if (dir != null) {
                pathField.setText(dir.getAbsolutePath());
                settingsManager.save();
            }
        });

        Label formatInfo = new Label("Formato: data e hora, porta, endereço MAC, status");
        formatInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        enableToggle.setOnAction(e -> {
            boolean enabled = enableToggle.isSelected();
            pathField.setDisable(!enabled);
            browseBtn.setDisable(!enabled);
            settings.setLogFileEnabled(enabled);
            enableToggle.setStyle(enabled ? "-fx-text-fill: #66bb6a;" : "");
            settingsManager.save();
        });

        // Persist path on focus loss to avoid saving on every keystroke
        pathField.textProperty().addListener((obs, old, val) -> settings.setLogFilePath(val));
        pathField.focusedProperty().addListener((obs, was, isFocused) -> {
            if (!isFocused) settingsManager.save();
        });

        pane.getChildren().addAll(
                headingRow,
                enableToggle,
                pathLabel,
                new HBox(8, pathField, browseBtn),
                formatInfo
        );
        return pane;
    }


    // ════════════════════════════════════════════════════════
    // Explore Popular Projects view
    // ════════════════════════════════════════════════════════

    private VBox buildExploreView() {
        Label title = new Label("Explorar projetos populares");
        title.getStyleClass().add("explore-title");

        Label subtitle = new Label("Clique em um projeto para abrir o repositório no GitHub.");
        subtitle.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

        Button backBtn = new Button("← Voltar ao gravador");
        backBtn.getStyleClass().add("link-button");
        backBtn.setOnAction(e -> showView(flasherView));

        HBox header = new HBox(12, backBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(18);
        content.setPadding(new Insets(4, 4, 16, 4));
        content.getChildren().addAll(header, title, subtitle);

        Map<String, List<FirmwareDefinition>> grouped = groupByCategory();
        for (Map.Entry<String, List<FirmwareDefinition>> entry : grouped.entrySet()) {
            Label catLabel = new Label(entry.getKey());
            catLabel.getStyleClass().add("explore-category");

            FlowPane cards = new FlowPane(12, 12);
            for (FirmwareDefinition fw : entry.getValue()) {
                cards.getChildren().add(buildFirmwareCard(fw));
            }
            content.getChildren().addAll(catLabel, cards);
        }

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox view = new VBox(scroll);
        view.getStyleClass().add("explore-root");
        return view;
    }

    private Node buildFirmwareCard(FirmwareDefinition fw) {
        VBox card = new VBox(8);
        card.getStyleClass().add("firmware-card");
        card.setPrefWidth(210);
        card.setMinWidth(210);
        card.setMaxWidth(210);

        String repo = fw.getGithubRepo();
        String org = repo != null && repo.contains("/") ? repo.split("/")[0] : null;

        ImageView logo = new ImageView();
        logo.setFitWidth(48);
        logo.setFitHeight(48);
        logo.setPreserveRatio(true);

        String localIcon = localLogoFor(fw.getName());
        try {
            if (localIcon != null) {
                var url = getClass().getResource(localIcon);
                if (url != null) logo.setImage(new Image(url.toExternalForm()));
            } else if (org != null) {
                logo.setImage(new Image(
                        "https://github.com/" + org + ".png?size=96",
                        true
                ));
            }
        } catch (Exception ignored) {
        }

        Label name = new Label(fw.getName());
        name.getStyleClass().add("firmware-card-title");
        name.setWrapText(true);

        Label desc = new Label(fw.getDescription());
        desc.getStyleClass().add("firmware-card-desc");
        desc.setWrapText(true);

        HBox header = new HBox(10, logo, name);
        header.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(header, desc);

        card.setOnMouseClicked(e -> {
            String url = (fw.getGithubRepo() != null && !fw.getGithubRepo().isBlank())
                    ? "https://github.com/" + fw.getGithubRepo()
                    : fw.getWebsiteUrl();
            if (url != null && !url.isBlank()) {
                getHostServices().showDocument(url);
            }
        });
        card.setStyle("-fx-cursor: hand;");

        return card;
    }

    private String localLogoFor(String firmwareName) {
        if (firmwareName == null) return null;
        if (firmwareName.startsWith("Tasmota")) return "/icons/firmware/tasmota.png";
        return null;
    }

    private Map<String, List<FirmwareDefinition>> groupByCategory() {
        Map<String, String> categoryOf = new LinkedHashMap<>();
        categoryOf.put("Tasmota", "Casa inteligente e automação");
        categoryOf.put("Tasmota SML (ottelo9)", "Casa inteligente e automação");

        Map<String, List<FirmwareDefinition>> grouped = new LinkedHashMap<>();
        grouped.put("Casa inteligente e automação", new ArrayList<>());
        grouped.put("Outros", new ArrayList<>());

        for (FirmwareDefinition fw : FirmwareCatalog.getCatalog()) {
            String cat = categoryOf.getOrDefault(fw.getName(), "Outros");
            grouped.get(cat).add(fw);
        }

        grouped.values().removeIf(List::isEmpty);
        return grouped;
    }


    // ════════════════════════════════════════════════════════
    // Prerequisite checks & esptool install
    // ════════════════════════════════════════════════════════

    private PrereqPanel newPrereqPanel(boolean showAutoButton) {
        PrereqPanel panel = new PrereqPanel(
                prereqChecker,
                mainStage,
                url -> getHostServices().showDocument(url),
                this::onPrereqChanged,
                showAutoButton
        );
        prereqPanels.add(panel);
        return panel;
    }

    private void checkPrerequisites() {
        if (!prereqPanels.isEmpty()) prereqPanels.get(0).recheck();
    }

    /** Chamado quando a detecção/instalação começa ou termina. */
    private void onPrereqChanged() {
        for (PrereqPanel panel : prereqPanels) panel.refresh();
        updateReadyState(true);
    }

    /**
     * Habilita os botões de gravação conforme o esptool foi encontrado e
     * mostra o motivo na barra de status quando não está pronto.
     */
    private void updateReadyState(boolean touchStatus) {
        if (statusLabel == null || flashButton == null) return;
        boolean idle = !isFactoryMode && stopButton.isDisabled();
        if (!idle) return;

        if (PrereqPanel.isChecking()) {
            flashButton.setDisable(true);
            factoryButton.setDisable(true);
            if (touchStatus) {
                statusLabel.setText("Verificando Python e esptool...");
                statusLabel.setStyle("");
            }
            return;
        }

        boolean ready = prereqChecker.isReady();
        flashButton.setDisable(!ready);
        factoryButton.setDisable(!ready);
        if (!touchStatus) return;
        if (ready) {
            statusLabel.setText("Pronto.");
            statusLabel.setStyle("");
        } else if (!prereqChecker.isPythonFound()) {
            statusLabel.setText("Python e esptool não encontrados. Use os botões Instalar ou Selecionar acima.");
            statusLabel.setStyle("-fx-text-fill: #e53935;");
        } else {
            statusLabel.setText("esptool não encontrado. Use os botões Instalar ou Selecionar acima.");
            statusLabel.setStyle("-fx-text-fill: #e53935;");
        }
    }


    // ════════════════════════════════════════════════════════
    // About dialog
    // ════════════════════════════════════════════════════════

    private void showAboutDialog() {
        Stage dialog = new Stage();
        dialog.setTitle("Sobre");
        dialog.setResizable(false);

        VBox content = new VBox(12);
        content.setPadding(new Insets(24));
        content.setAlignment(Pos.CENTER);
        content.getStyleClass().add("about-dialog");

        var iconUrl = getClass().getResource("/icons/icon.png");
        if (iconUrl != null) {
            javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(iconUrl.toExternalForm()));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            logo.setPreserveRatio(true);
            content.getChildren().add(logo);
        }

        Label title = new Label("ESP Flasher");
        title.getStyleClass().add("about-title");

        String appVersion = new UpdateService().currentVersion();
        Label version = new Label("v" + appVersion);
        version.getStyleClass().add("about-version");

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #3a3a3c;");

        Label author = new Label("Criado por Ajinkya Gokhale");
        author.getStyleClass().add("about-author");

        Label email = new Label("✉  hi@ajinkyagokhale.com");
        email.getStyleClass().add("about-link");
        email.setOnMouseClicked(e -> getHostServices().showDocument("mailto:hi@ajinkyagokhale.com"));

        Label github = new Label("⚡  github.com/ajinkyagokhale");
        github.getStyleClass().add("about-link");
        github.setOnMouseClicked(e -> getHostServices().showDocument("https://github.com/ajinkyagokhale"));

        Label fork = new Label("Versão em português: Verin");
        fork.getStyleClass().add("about-author");

        Label forkLink = new Label("⚡  github.com/VerinEng3/esp-flasher-java");
        forkLink.getStyleClass().add("about-link");
        forkLink.setOnMouseClicked(e -> getHostServices().showDocument("https://github.com/VerinEng3/esp-flasher-java"));

        Label license = new Label("Licença MIT — 2026");
        license.getStyleClass().add("about-license");

        Button closeBtn = new Button("Fechar");
        closeBtn.setOnAction(e -> dialog.close());
        closeBtn.setPrefWidth(100);

        content.getChildren().addAll(
                title, version, sep,
                author, email, github,
                fork, forkLink,
                license, closeBtn
        );

        Scene scene = new Scene(content, 320, 440);
        dialog.setScene(scene);
        dialog.show();
    }


    // ════════════════════════════════════════════════════════
    // Auto-update flow
    // ════════════════════════════════════════════════════════

    private void checkForUpdates() {
        Thread worker = new Thread(() -> {
            UpdateService updates = new UpdateService();
            String current = updates.currentVersion();
            updates.latestRelease()
                    .filter(release -> updates.isNewer(release.version(), current))
                    .ifPresent(release ->
                            Platform.runLater(() -> promptForcedUpdate(updates, release, current)));
        }, "update-check");
        worker.setDaemon(true);
        worker.start();
    }

    @SuppressFBWarnings("DM_EXIT")
    private void promptForcedUpdate(UpdateService updates, UpdateService.Release release, String current) {
        ButtonType updateNow = new ButtonType("Atualizar agora", ButtonBar.ButtonData.OK_DONE);
        ButtonType quit = new ButtonType("Sair", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Atualização necessária");
        alert.setHeaderText("Uma nova versão do ESP Flasher está disponível");
        alert.setContentText("Instalada: " + current + "\nNova: " + release.version()
                + "\n\nÉ preciso atualizar para continuar.");
        alert.getButtonTypes().setAll(updateNow, quit);
        alert.initModality(Modality.APPLICATION_MODAL);

        Optional<ButtonType> choice = alert.showAndWait();
        while (choice.isEmpty()) {
            choice = alert.showAndWait();
        }

        if (choice.get() == updateNow) {
            downloadUpdate(updates, release);
        } else {
            System.exit(0);
        }
    }

    private void downloadUpdate(UpdateService updates, UpdateService.Release release) {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(380);
        Label info = new Label("Iniciando download...");
        Label hint = new Label("O aplicativo será fechado para instalar a atualização.");
        hint.getStyleClass().add("footer");
        VBox box = new VBox(10, info, bar, hint);
        box.setPadding(new Insets(16));

        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle("Baixando atualização");
        dialog.setHeaderText("Atualizando para " + release.version());
        dialog.getDialogPane().setContent(box);
        dialog.getButtonTypes().setAll(cancelType);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setOnHidden(e -> cancelled.set(true));

        Thread worker = new Thread(() -> {
            try {
                updates.downloadAndLaunch(release,
                        (downloaded, total) -> Platform.runLater(
                                () -> updateProgress(bar, info, downloaded, total)),
                        cancelled);
                Platform.runLater(() -> {
                    dialog.close();
                    promptForcedUpdate(updates, release, updates.currentVersion());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    dialog.close();
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("Falha na atualização");
                    error.setHeaderText("Não foi possível baixar a atualização");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                    promptForcedUpdate(updates, release, updates.currentVersion());
                });
            }
        }, "update-download");
        worker.setDaemon(true);
        worker.start();

        dialog.show();
    }

    private void updateProgress(ProgressBar bar, Label info, long downloaded, long total) {
        double mb = downloaded / 1048576.0;
        if (total > 0) {
            double fraction = (double) downloaded / total;
            bar.setProgress(fraction);
            info.setText(String.format("%.1f MB / %.1f MB  (%d%%)",
                    mb, total / 1048576.0, (int) (fraction * 100)));
        } else {
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            info.setText(String.format("%.1f MB baixados", mb));
        }
    }


    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    private void browseBin(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selecionar firmware");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Arquivos binários (*.bin)", "*.bin")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            binPathField.setText(file.getAbsolutePath());
        }
    }

    private void updateChipListForFirmware(FirmwareDefinition def) {
        String previous = chipCombo.getValue();
        chipCombo.getItems().clear();

        if (def == null) {
            chipCombo.getItems().addAll(
                    "auto", "esp32c6", "esp32", "esp32s2",
                    "esp32s3", "esp32c3", "esp32h2", "esp8266"
            );
        } else {
            for (String chip : def.getChipBinMap().keySet()) {
                if (!"default".equals(chip)) chipCombo.getItems().add(chip);
            }
        }

        if (previous != null && chipCombo.getItems().contains(previous)) {
            chipCombo.setValue(previous);
        } else if (!chipCombo.getItems().isEmpty()) {
            chipCombo.getSelectionModel().selectFirst();
        }
    }

    private void refreshPorts() {
        portCombo.getItems().clear();
        List<String> espPorts = PortWatcher.listEsp32Ports();

        if (espPorts.isEmpty()) {
            portCombo.setPromptText("Nenhum ESP detectado...");
        } else {
            portCombo.getItems().addAll(espPorts);
            portCombo.getSelectionModel().selectFirst();
        }
    }

    private Label bullet(String text) {
        Label l = new Label("• " + text);
        l.setStyle("-fx-font-size: 12px;");
        return l;
    }

    private boolean isDarkMode() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"}
                );
                String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                return result.equalsIgnoreCase("dark");
            } else if (os.contains("windows")) {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme"
                });
                String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return result.contains("0x0");
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @SuppressFBWarnings("DE_MIGHT_IGNORE")
    private void applyDarkTitleBar(Stage stage) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    com.sun.jna.platform.win32.User32.INSTANCE.FindWindow(null, stage.getTitle());
            if (hwnd == null) return;
            com.sun.jna.ptr.IntByReference dark = new com.sun.jna.ptr.IntByReference(1);
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, dark, 4); // Windows 10 20H1+
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, dark, 4); // older Win10 builds
        } catch (Exception ignored) {
        }
    }

    private interface Dwmapi extends com.sun.jna.Library {
        Dwmapi INSTANCE = com.sun.jna.Native.load("dwmapi", Dwmapi.class);

        void DwmSetWindowAttribute(com.sun.jna.platform.win32.WinDef.HWND hwnd, int attr,
                                   com.sun.jna.ptr.IntByReference value, int size);
    }
}
