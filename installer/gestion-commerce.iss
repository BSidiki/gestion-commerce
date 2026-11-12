; Installateur Windows de Gestion Commerce (Inno Setup 6).
; Construit par installer\build.ps1, qui prépare d'abord le dossier build\GestionCommerce.
;
; Choix d'installation :
;   - installation pour l'utilisateur Windows courant, sans droits d'administrateur ;
;   - programme dans  %LOCALAPPDATA%\Programs\GestionCommerce ;
;   - données dans    %LOCALAPPDATA%\GestionCommerce  (jamais touchées par l'installateur
;     ni par la désinstallation : base, sauvegardes locales, journaux).

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

#define AppName "Gestion Commerce"
#define AppExe "GestionCommerce.exe"

[Setup]
AppId={{8E0F5B7C-3C55-4E0B-9C0A-6B1F2D7A4E11}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Gestion Commerce
DefaultDirName={localappdata}\Programs\GestionCommerce
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir=build
OutputBaseFilename=GestionCommerce-Installation-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExe}

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "Créer un raccourci sur le Bureau"
Name: "autostart"; Description: "Démarrer {#AppName} à l'ouverture de Windows (recommandé)"

[Files]
Source: "build\GestionCommerce\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
; Au démarrage de Windows, le logiciel se lance en arrière-plan, sans ouvrir le navigateur.
Name: "{userstartup}\{#AppName}"; Filename: "{app}\{#AppExe}"; Parameters: "--no-browser"; Tasks: autostart

[Run]
Filename: "{app}\{#AppExe}"; Description: "Ouvrir {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Le logiciel tourne en arrière-plan : il faut l'arrêter avant de retirer ses fichiers.
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#AppExe}"; Flags: runhidden; RunOnceId: "ArreterGestionCommerce"

[Code]
// Mise à jour : l'ancienne version tourne peut-être encore. On l'arrête avant de remplacer ses fichiers.
// Les données, rangées ailleurs, ne sont pas concernées.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM {#AppExe}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(1000);
  Result := '';
end;
