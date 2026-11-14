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
 * of the look-back window (five minutes, or the time since the VM was last read) for each listed log and event id (the text
 * of the published event), and names the queries it could not run.
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
    /** The guest names the two files (design 8.2): prefix and suffix are ours, the middle is the guest's. */
    public static final String INPUT_PREFIX = "input";
    public static final String INPUT_SUFFIX = ".txt";
    public static final String OUTPUT_PREFIX = "output";
    public static final String OUTPUT_SUFFIX = ".csv";

    private static final String PRELUDE = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$ProgressPreference = 'SilentlyContinue'",
            "function Save-Csv($items, $columns) {",
            "    $lines = @($items | ConvertTo-Csv -NoTypeInformation)",
            "    if ($lines.Count -eq 0) { $lines = @(($columns | ForEach-Object { '\"' + $_ + '\"' }) -join ',') }",
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
            "}) 'Name','Version','BuildNumber','OSArchitecture','LastBootUpTime','ReleaseId'");

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
            "Save-Csv $found 'Name','DisplayName','Status','StartType'");

    private static final String EVENTS_BODY = String.join("\n",
            "$since = (Get-Date).AddMinutes(-$WindowMinutes)",
            "$tab = [char]9",
            "$found = @()",
            "$position = 0",
            "foreach ($line in (Get-Content -LiteralPath $InputFile -Encoding UTF8)) {",
            "    $position++",
            "    $parts = $line -split $tab",
            "    if ($parts.Count -ne 2) { continue }",
            "    $log = $parts[0]",
            "    $id = $parts[1]",
            "    try {",
            "        $record = Get-WinEvent -FilterHashtable @{ LogName = $log; Id = [int]$id; StartTime = $since } -MaxEvents 1 -ErrorAction Stop",
            "    } catch {",
            "        if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') {",
            "            $found += [PSCustomObject]@{ Level = ''; LevelNumber = ''; Event = ''; Failed = $position }",
            "        }",
            "        continue",
            "    }",
            "    $found += [PSCustomObject]@{",
            "        Level = $record.LevelDisplayName",
            "        LevelNumber = $record.Level",
            "        Event = '[Timestamp=' + $record.TimeCreated + '] [Category=' + $record.LogName + '] [EventID=' + $id + '] [Source=' + $record.ProviderName + '] [Message=' + $record.Message + ']'",
            "        Failed = ''",
            "    }",
            "}",
            "Save-Csv $found 'Level','LevelNumber','Event','Failed'");

    /**
     * The program text with its two paths bound ({@code inputPath} is null for a program that reads no file); ends the
     * process with 0, or 2 when anything threw. The output always has its header line, so an output with rows
     * left out is told from a program that wrote nothing.
     */
    public static String program(Kind kind, String inputPath, String outputPath) {
        return program(kind, inputPath, outputPath, DEFAULT_WINDOW_MINUTES);
    }

    /** How far back the events program looks when nothing says more: the original's five minutes. */
    public static final int DEFAULT_WINDOW_MINUTES = 5;

    /** As {@link #program(Kind, String, String)}; the events program looks back {@code windowMinutes}. */
    public static String program(Kind kind, String inputPath, String outputPath, int windowMinutes) {
        return "$WindowMinutes = " + Math.max(1, Math.min(windowMinutes, 24 * 60)) + "\n$InputFile = " + (inputPath == null ? "$null" : literal(inputPath)) + "\n$OutputFile = " + literal(outputPath) + "\n" + PRELUDE
                + "\ntry {\n" + kind.body + "\nexit 0\n} catch {\nexit 2\n}\n";
    }

    /** The arguments of the process: the program as Base64 of its UTF-16LE text. No secret is in them. */
    public static String arguments(String program) {
        String encoded = Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_16LE));
        return "-NoLogo -NoProfile -NonInteractive -EncodedCommand " + encoded;
    }

    static String literal(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20) throw new IllegalArgumentException("a control character in a path");
        }
        return "'" + path.replace("'", "''") + "'";
    }
}
