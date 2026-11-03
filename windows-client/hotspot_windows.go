//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Liga o Hotspot Móvel do Windows pela API do sistema (WinRT), via PowerShell.
const hotspotPS = `
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation` + "`" + `1' })[0]
  $asAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and -not $_.IsGenericMethod })[0]
  function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
  function AwaitAction($op) { $t = $asAction.Invoke($null, @($op)); $t.Wait(-1) | Out-Null }
  [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime] | Out-Null
  [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime] | Out-Null
  $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  if ($null -eq $profile) { Write-Output 'ERRO: este notebook está sem internet'; exit 0 }
  $tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
  $type = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]
  if ('%ACTION%' -eq 'stop') {
    $r = Await ($tm.StopTetheringAsync()) $type
    Write-Output ('OK ' + $r.Status)
    exit 0
  }
  $cfg = $tm.GetCurrentAccessPointConfiguration()
  $cfg.Ssid = '%SSID%'
  $cfg.Passphrase = '%PASS%'
  try { $cfg.Band = 1 } catch { }   # 2,4 GHz, quando o Windows permitir
  AwaitAction ($tm.ConfigureAccessPointAsync($cfg))
  $r = Await ($tm.StartTetheringAsync()) $type
  if ($r.Status -eq 0) { Write-Output ('OK conectado por ' + $profile.ProfileName) } else { Write-Output ('ERRO: ' + $r.Status + ' ' + $r.AdditionalErrorMessage) }
} catch {
  Write-Output ('ERRO: ' + $_.Exception.Message)
}
`

func hotspot(action string) error {
	script := strings.NewReplacer("%ACTION%", action, "%SSID%", hostSSID, "%PASS%", passphrase).Replace(hotspotPS)
	path := filepath.Join(dataDir(), "hotspot.ps1")
	// BOM UTF-8 para o PowerShell 5 ler os acentos.
	if err := os.WriteFile(path, append([]byte{0xEF, 0xBB, 0xBF}, script...), 0o600); err != nil {
		return err
	}
	out, err := run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path)
	out = strings.TrimSpace(out)
	if err != nil {
		return fmt.Errorf("powershell: %v %s", err, out)
	}
	if strings.Contains(out, "ERRO") || !strings.Contains(out, "OK") {
		return fmt.Errorf("%s", out)
	}
	logf("Hotspot Móvel: %s", out)
	return nil
}
