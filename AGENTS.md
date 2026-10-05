# AGENTS.md – foodvisor-revanced

## Projekt

ReVanced-Patch-Repo für Foodvisor (`io.foodvisor.foodvisor`) mit experimentellem
Cosori-CNS-R101S-Waagen-Support. Siehe `docs/FEASIBILITY.md` für den Status.

## Build

Voraussetzungen:

- **JDK 21** (`brew install openjdk@21`). Achtung: JDK 27 bricht den
  Gradle/Kotlin-Build ("IllegalArgumentException: 27"); JDK 17/21 verwenden.
- Android SDK (`ANDROID_HOME` oder `local.properties` mit `sdk.dir`):
  `brew install --cask android-commandlinetools`, dann
  `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`.
- **GitHub-Packages-Zugang**: Das ReVanced-Plugin `app.revanced.patches` liegt
  nur im Registry `maven.pkg.github.com/revanced/registry`. In
  `~/.gradle/gradle.properties` (oder Env `GITHUB_ACTOR`/`GITHUB_TOKEN`):
  `gpr.user=<GitHub-Login>` und `gpr.key=<Token mit read:packages>`
  (`gh auth refresh -s read:packages`).

```bash
./gradlew build
```

Ausgabe: `patches/build/libs/patches-*.rvp`.

Nur Patch-Modul kompilieren (schneller Check):

```bash
./gradlew :patches:compileKotlin
```

## Umgebung dieses Rechners

Installiert: JDK 21 (`/var/home/linuxbrew/.linuxbrew/opt/openjdk@21`), Android
SDK (`/home/linuxbrew/.linuxbrew/share/android-commandlinetools`, platform-35),
`adb` (platform-tools), `jadx`, `gh`. `gradlew` lädt Gradle selbst. Offen:
GitHub-Packages-Token (s. o.) zum Bauen.

## Struktur

- `patches/src/main/kotlin/io/foodvisor/patches/` – Patch-Definitionen
- `extensions/extension/src/main/java/io/foodvisor/extension/` – in die APK
  injizierter Java-Code (BLE-Transport + Protokoll)
- `docs/` – Machbarkeit, APK-Analyse, Protokoll-Capture

## Konventionen

- Protokoll der CNS-R101S ist bestätigt (`docs/PROTOCOL.md`,
  `PROTOCOL_IMPLEMENTED = true`); UUIDs und Decoder sind fixiert.
- Patch ist implementiert und auf Foodvisor 7.5.6 getestet (Gewicht +
  Nährwerte); Hook-Punkte in `docs/ANALYSIS.md` dokumentiert. Obfuskierte
  Namen ändern sich pro Version → über stabile Anker matchen
  (`FoodUnitPickerView`-Name, `androidx.lifecycle`-Superklasse, Typ `MacroFoodAndFoodInfo`).

## Verifikation

- `./gradlew build` muss durchlaufen.
- Vor Release: Patch auf eine gepinnte Foodvisor-APK anwenden
  (`revanced-cli`) und auf echtem Gerät testen.
