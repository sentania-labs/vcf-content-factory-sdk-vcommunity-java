package com.vcfcf.adapters.vcommunity.guest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The three PowerShell programs a guest runs, written for this port from the columns the keys need (design 8.3).
 * Each is sent as command text through {@code -EncodedCommand}, so nothing is stored as a script file in the
 * guest and the execution policy for script files does not apply. They use cmdlets and object literals only,
 * apart from one UTF-8 writer that has a cmdlet fallback for a guest in Constrained Language Mode.
 *
 * <p>The input and output paths are bound as single-quoted literals at the top. The text carries no
 * credential: the account is the one the guest operation itself runs as.
 *
 * <p>What each reproduces of the original (key map rows, design 8.3): the operating system script gives the six
 * values of R72 to R77; the services script gives {@code Name}, {@code DisplayName}, {@code Status} and
 * {@code StartType} of every listed service that exists (R78 to R80); the events script gives the newest event
 * of the last five minutes for each listed log and event id (the text of the published event).
 */
public final class GuestScripts {

    /** Which script. */
    public enum Kind {
        OS("OSInfo", OS_BODY),
        SERVICES("Services", SERVICES_BODY),
        EVENTS("EventLog", EVENTS_BODY);

        /** Part of the temporary directory's name. */
        public final String label;
        final String body;

        Kind(String label, String body) {
            this.label = label;
            this.body = body;
        }

        /** The suffix of the temporary directory the guest creates. */
        public String directorySuffix() {
            return "-" + label + "-TEMP";
        }
    }

    private GuestScripts() {}

    /** Where the programs and the temporary directories are, as in the original. */
    public static final String TEMP_PARENT = "C:\\Windows\\Temp";
    public static final String POWERSHELL = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    public static final String DIRECTORY_PREFIX = "VCFOperationsvCommunity-";
    public static final String INPUT_NAME = "input.txt";
    public static final String OUTPUT_NAME = "output.csv";

    private static final String PRELUDE = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$ProgressPreference = 'SilentlyContinue'",
            "function Save-Csv($items) {",
            "    $lines = @($items | ConvertTo-Csv -NoTypeInformation)",
            "    try {",
            "        $utf8 = New-Object System.Text.UTF8Encoding($false)",
            "        [System.IO.File]::WriteAllLines($OutputFile, $lines, $utf8)",
            "    } catch {",
            "        $lines | Out-File -LiteralPath $OutputFile -Encoding utf8",
            "    }",
            "}");

    private static final String OS_BODY = String.join("\n",
            "$os = Get-CimInstance -ClassName Win32_OperatingSystem",
            "$release = $null",
            "$version = Get-ItemProperty -LiteralPath 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion' -ErrorAction SilentlyContinue",
            "if ($version) { $release = $version.ReleaseId }",
            "Save-Csv ([PSCustomObject]@{",
            "    Name = $os.Caption",
            "    Version = $os.Version",
            "    BuildNumber = $os.BuildNumber",
            "    OSArchitecture = $os.OSArchitecture",
            "    LastBootUpTime = $os.LastBootUpTime",
            "    ReleaseId = $release",
            "})");

    private static final String SERVICES_BODY = String.join("\n",
            "$found = @()",
            "foreach ($name in (Get-Content -LiteralPath $InputFile -Encoding UTF8)) {",
            "    if ($name -notmatch '\\S') { continue }",
            "    foreach ($svc in @(Get-Service -Name $name -ErrorAction SilentlyContinue)) {",
            "        $found += [PSCustomObject]@{",
            "            Name = $svc.Name",
            "            DisplayName = $svc.DisplayName",
            "            Status = $svc.Status",
            "            StartType = $svc.StartType",
            "        }",
            "    }",
            "}",
            "Save-Csv $found");

    private static final String EVENTS_BODY = String.join("\n",
            "$since = (Get-Date).AddMinutes(-5)",
            "$tab = [char]9",
            "$found = @()",
            "foreach ($line in (Get-Content -LiteralPath $InputFile -Encoding UTF8)) {",
            "    $parts = $line -split $tab",
            "    if ($parts.Count -ne 2) { continue }",
            "    $log = $parts[0]",
            "    $id = $parts[1]",
            "    try {",
            "        $record = Get-WinEvent -FilterHashtable @{ LogName = $log; Id = [int]$id; StartTime = $since } -MaxEvents 1 -ErrorAction Stop",
            "    } catch {",
            "        if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') {",
            "            Write-Host ('query failed: ' + $log + ' ' + $id + ': ' + $_.Exception.Message)",
            "        }",
            "        continue",
            "    }",
            "    $found += [PSCustomObject]@{",
            "        Level = $record.LevelDisplayName",
            "        LevelNumber = $record.Level",
            "        Event = '[Timestamp=' + $record.TimeCreated + '] [Category=' + $log + '] [EventID=' + $id + '] [Source=' + $record.ProviderName + '] [Message=' + $record.Message + ']'",
            "    }",
            "}",
            "Save-Csv $found");

    /** The program text with its two paths bound; ends the process with 0, or 2 when anything threw. */
    public static String program(Kind kind, String inputPath, String outputPath) {
        return "$InputFile = " + literal(inputPath) + "\n$OutputFile = " + literal(outputPath) + "\n" + PRELUDE
                + "\ntry {\n" + kind.body + "\nexit 0\n} catch {\nexit 2\n}\n";
    }

    /** The arguments of the process: the program as Base64 of its UTF-16LE text. No secret is in them. */
    public static String arguments(String program) {
        String encoded = Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_16LE));
        return "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded;
    }

    static String literal(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20) throw new IllegalArgumentException("a control character in a path");
        }
        return "'" + path.replace("'", "''") + "'";
    }
}
