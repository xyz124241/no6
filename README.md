# SuperFlow (Paper 1.21.x)

## Jar bauen mit GitHub (ohne Installation)
1. Neues Repository auf github.com erstellen.
2. Alle Dateien dieses Ordners hochladen (inkl. Ordner `.github`).
3. Tab **Actions** -> "Build SuperFlow" -> läuft automatisch (oder "Run workflow").
4. Nach ~1 Minute: Lauf öffnen -> unten bei **Artifacts** -> `SuperFlow-jar` herunterladen.
5. ZIP entpacken, `SuperFlow-1.0.0.jar` in den `plugins`-Ordner, Server neu starten.

## Lokal bauen
JDK 21 + Maven -> `mvn package` -> `target/SuperFlow-1.0.0.jar`

## Befehl
/vjob reroll | /vjob <beruf>   (Villager anschauen), Permission: superflow.vjob
Alle Optionen in `config.yml`.
