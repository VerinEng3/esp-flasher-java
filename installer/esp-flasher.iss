; Instalador do ESP Flasher (Inno Setup 6)
;
; Gerar:
;   mvnw package jpackage:jpackage        (cria target\dist\ESP Flasher)
;   ISCC.exe /DAppVersion=1.2.0 installer\esp-flasher.iss
;
; Ao abrir, o instalador pergunta se a instalação é para todos os usuários
; (Program Files, pede administrador) ou só para o usuário atual
; (%LOCALAPPDATA%\Programs, sem administrador).

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\target\dist\ESP Flasher"
#endif

#define AppName "ESP Flasher"
#define AppExe "ESP Flasher.exe"

[Setup]
; AppId fixo: versões novas atualizam a instalação existente
AppId={{55CAE31C-5583-41FC-B8ED-BD6520431F3E}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Verin
AppPublisherURL=https://github.com/VerinEng3/esp-flasher-java
AppSupportURL=https://github.com/VerinEng3/esp-flasher-java/issues
AppUpdatesURL=https://github.com/VerinEng3/esp-flasher-java/releases
AppCopyright=MIT License - Ajinkya Gokhale, Verin
VersionInfoVersion={#AppVersion}

; Escolha do modo de instalação: todos os usuários ou somente o atual
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog
UsePreviousPrivileges=yes

; {autopf}: Program Files (todos) ou %LOCALAPPDATA%\Programs (usuário atual)
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no

OutputDir=..\target\installer
OutputBaseFilename=ESP-Flasher-{#AppVersion}-setup
SetupIconFile=..\src\main\resources\icons\icon.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Remove arquivos da versão anterior (o Java embutido muda entre versões)
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
