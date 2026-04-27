# Client AntiCheat Sniffer (Forge 1.12.2)

## Einfach erklärt („idiotensicher“)

- Du packst diese **kleine Mod** in deinen Minecraft-Ordner wie jede andere Mod auch.
- Sie **verhindert kein Cheaten** und kickt niemanden — sie **merkt nur**, wenn bei dir im Spiel oder in den Logs Dinge auftauchen, die zu **bekannten Cheat-/Inject-Spuren** passen (z. B. bestimmte Mod-Kennung, bestimmte versteckte Klassennamen, typische Konsolen-/Log-Zeilen).
- Wenn so etwas erkannt wird, passiert bei dir lokal:
  - In der Datei **`logs/anticheat-client.log`** steht **`cheat erkannt`** (oder beim Selbsttest eine Zeile mit **`SELFTEST`**). Zusätzlich kann eine Zeile **`screenshot: ...`** stehen.
  - Im **Chat** erscheint kurz **`[AntiCheat] cheat erkannt`** (rot).
  - **Vier Screenshots** landen unter **`screenshots/anticheat/`**: direkt beim Alarm, sowie **5, 10 und 30 Sekunden** danach (gemeinsamer Datei-Prefix pro Alarm; ein neuer Alarm bricht noch ausstehende Aufnahmen der vorherigen Serie ab).
- Du kannst mit **`/anti help`** sehen, welche Befehle es gibt. Mit **`/anti debug test`** prüfst du **einmal**, ob Schreiben in die Log-Datei und die Diagnose noch funktionieren — **ohne** dafür einen echten Cheat-Alarm auszulösen.
- **`/anti scan`** ist der **echte** Check wie im Hintergrund alle paar Sekunden: Wenn wirklich etwas Verdächtiges da ist, kann dabei derselbe Alarm kommen wie bei echter Erkennung.
- **`/anti status`** und **`/anti debug test`** zeigen nur eine **Momentaufnahme** („jetzt gerade“). **„dd nein“** oder **„keine Klassen“** heißt nicht automatisch „alles sauber“ — ein Inject kann später kommen oder andere Spuren hinterlassen.

**Kurz:** Die Mod ist ein **Warnsignal für dich** auf dem Client, kein Server-Ban-System.

---

## Fachlich / technisch

### Zweck

Clientseitige **Heuristik-Erkennung** für ein definiertes Bedrohungsbild (u. a. „DoomsDay“-artige Injects): Kombination aus **Log4j2-Filter**, **Tail** von `latest.log` / `debug.log`, **Umleitung von `System.out` / `System.err`** (früh über Coremod-Bootstrap) und **periodischer Laufzeit-Prüfung** (Mod-Liste Forge `Loader`, `Class.forName` über mehrere `ClassLoader`, inkl. `Launch.classLoader`, sowie Scan von `Thread.getAllStackTraces()` nach Paket `net.java.*`).

### Ausgaben

- **Append** an `logs/anticheat-client.log` bei Treffer: Zeile `cheat erkannt`; optional weitere Zeile `screenshot: screenshots/anticheat/...`.
- **Chat-Hook** (`ClientChatAlert`): HUD-Meldung an den lokalen Spieler.
- **Screenshots** (`AntiCheatScreenshots`, `ScreenShotHelper`): Unterverzeichnis `screenshots/anticheat/`, pro Erkennung **vier** Dateien (0 s, 5 s, 10 s, 30 s; Namenssuffix `_*s.png`).

### Chat-Zeichen und Diagnose-Texte

- Build mit **`compileJava.options.encoding = UTF-8`**, Chat-Zeilen über **`TextComponentString` + `Style.setColor`** (statt Rohtext mit §-Prefix), normale ASCII-Trennzeichen im Hilfetext — vermeidet falsch dargestellte Umlaute und „â€”“-Artefakte.
- Diagnosezeilen betonen explizit: **nur aktueller Zustand**, kein Beweis für oder gegen Cheats.

### Erkennungslogik (Kurzüberblick)

- String-Signaturen in Logzeilen (u. a. `doomsdayargs`, `net.java.*`, FML/Prism-`System.exit`-Warnung, JSON-`dd`-Mod-Referenzen, u. a.).
- Ziffern-/Konsolen-Spam mit Streak und Cooldown.
- Laufzeit: Mod-ID **`dd`**, geladene Klassen aus einer festen Liste unter `net.java.*`, Stacktrace-Treffer.

### Befehle (nur Client, `ClientCommandHandler`)

| Befehl | Funktion |
|--------|----------|
| `/anti help` | Hilfe |
| `/anti version` | Mod-Version |
| `/anti status` | Kurzdiagnose (ohne Alarm) |
| `/anti debug on` / `off` | Ausführlichere Diagnose (`ClassLoader`-Infos bei `test`/`status`) |
| `/anti debug test` | Schreibt **SELFTEST**-Zeile ins Log + einmal Diagnose **ohne** `triggerRuntimeCheckAlert` |
| `/anti scan` | Führt `DoomsdayRuntimeProbe.runAll()` aus — **kann** echten Alarm auslösen |

Aliase: `/anticheat`, `/cac`.

### Einschränkungen

- **Kein Schutz** vor unbekannten Cheats oder reinem Speicher-Manipulation ohne Log-/Klassen-Fußabdruck.
- **False Positives** möglich, wenn legitime Software dieselben Strings oder Klassenmuster nutzt.
- **Nur Client** (`clientSideOnly`); Server sieht die Erkennung nicht automatisch.

### Bausteine im Code

- `EarlyCorePlugin` / `ConsoleSniffer` — frühe Konsole.
- `CheatLogFilter` + `CheatDetector` — Log-Pipeline.
- `LatestLogTailer` — Datei-Tail.
- `DoomsdayRuntimeProbe` — Laufzeit.
- `AntiCheatCommand` / `AntiCheatDebug` — Ingame-Tools.

---

*Version der Mod siehe `ClientAntiCheatMod.VERSION` / `build.gradle`.*
