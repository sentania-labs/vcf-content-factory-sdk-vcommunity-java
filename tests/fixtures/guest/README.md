These three files are what the programs of `GuestScripts` write, produced by running each program under PowerShell
7.5 with stand-ins for the Windows-only cmdlets (`Get-CimInstance`, `Get-Service`, `Get-WinEvent`). They hold the
real `ConvertTo-Csv` quoting and the real UTF-8 writer's bytes (no byte order mark), a line break and quotes inside
an event message, and non-ASCII text; `services-none-found.csv` and `events-none-found.csv` are what the programs write
when nothing is found (the header line alone). `GuestUnitTest.programOutput` reads them through the port's parser.
