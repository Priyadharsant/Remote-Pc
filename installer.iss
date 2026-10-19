; Script generated for Inno Setup 6
; Remote PC Server & Modern React Dashboard Setup Script

#define MyAppName "Remote PC"
#define MyAppVersion "3.2.0"
#define MyAppPublisher "Remote PC"
#define MyAppServerExe "Remote_Server.exe"
#define MyAppGuiExe "Remote PC GUI.exe"
#define MyAppIcon "server\logo.ico"

#ifndef AppCompression
#define AppCompression "lzma2/fast"
#endif

[Setup]
AppId={{D37E64B2-9F81-4C55-8AA3-BD76C3AE8521}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Remote PC
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=.
OutputBaseFilename=Remote_PC_Setup
SetupIconFile={#MyAppIcon}
Compression={#AppCompression}
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
DisableProgramGroupPage=auto
CloseApplications=force
CloseApplicationsFilter=*.exe
RestartApplications=no
UninstallDisplayIcon={app}\{#MyAppIcon}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "startup"; Description: "Launch Remote PC Core Server silently in background on Windows startup"; GroupDescription: "Automatic Startup:"

[Files]
; Core Server executable and resources
Source: "Remote_Server.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "server\logo.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "server\logo.png"; DestDir: "{app}"; Flags: ignoreversion
Source: "server\config.json"; DestDir: "{app}"; Flags: onlyifdoesntexist
Source: "C:\Users\priya\AppData\Local\Programs\Python\Python312\python312.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "C:\Users\priya\AppData\Local\Programs\Python\Python312\vcruntime140.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "server\bin\ffmpeg.exe"; DestDir: "{app}\bin"; Flags: ignoreversion

; Modern React + Electron GUI Suite (win-unpacked directory)
Source: "desktop-gui\dist-electron\win-unpacked\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\Remote PC Control Suite"; Filename: "{app}\{#MyAppGuiExe}"; IconFilename: "{app}\logo.ico"; Tasks: desktopicon
Name: "{group}\Remote PC Control Suite"; Filename: "{app}\{#MyAppGuiExe}"; IconFilename: "{app}\logo.ico"
Name: "{group}\Remote PC Background Server"; Filename: "{app}\{#MyAppServerExe}"; Parameters: "--startup"; IconFilename: "{app}\logo.ico"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "RemotePCServer"; ValueData: """{app}\{#MyAppServerExe}"" --startup"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppServerExe}"; Parameters: "--startup"; Flags: nowait runhidden
Filename: "{app}\{#MyAppGuiExe}"; Description: "Open Remote PC Control Suite now"; Flags: nowait postinstall skipifsilent unchecked

[UninstallRun]
Filename: "taskkill"; Parameters: "/F /IM {#MyAppServerExe}"; Flags: runhidden; RunOnceId: "StopRemoteServer"
Filename: "taskkill"; Parameters: "/F /IM ""{#MyAppGuiExe}"""; Flags: runhidden; RunOnceId: "StopRemoteGui"

[UninstallDelete]
Type: files; Name: "{app}\Remote_Server.log"

[Code]
procedure KillRunningProcesses();
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/F /IM Remote_Server.exe /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/F /IM "Remote PC GUI.exe" /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/F /IM electron.exe /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(500);
end;

function InitializeSetup(): Boolean;
begin
  KillRunningProcesses();
  Result := True;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  KillRunningProcesses();
  Result := '';
end;
