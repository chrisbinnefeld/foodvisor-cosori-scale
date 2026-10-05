# foodvisor-revanced

ReVanced-Patch-Repo für **Foodvisor** (`io.foodvisor.foodvisor`) mit
experimentellem Support für die **Cosori CNS-R101S** Edelstahl-Küchenwaage.

> **Status: implementiert und auf Foodvisor 7.5.6 auf einem Gerät getestet.**
> Gewicht und Nährwert-Übertragung funktionieren. Siehe
> [`docs/PROTOCOL.md`](docs/PROTOCOL.md) und [`docs/ANALYSIS.md`](docs/ANALYSIS.md).

## Warum das kein trivialer Patch ist

Foodvisor besitzt **keine eingebaute Bluetooth-Waagen-Funktion**. Es gibt also
keinen vorhandenen Code-Pfad, den der Patch nur umleiten müsste; die BLE-Logik
wird vollständig als Extension injiziert. Zusätzlich war das BLE-Protokoll der
CNS-R101S nicht öffentlich dokumentiert (sie läuft sonst über die VeSync-App)
und wurde für dieses Projekt reverse-engineered.

Details, Architektur-Optionen und Aufwand: [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md).

## Patch

| Patch | Beschreibung | Ziel-App |
| --- | --- | --- |
| `Cosori CNS-R101S scale support` | BLE-Brücke: „Waage"-Link im Quantity-Picker; schreibt das Gewicht ins Mengenfeld und sendet die Nährwerte ans Waagen-Display | `io.foodvisor.foodvisor` (alle Versionen) |
| `Cosori CNS-R101S scale permissions` | Ergänzt `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (u. Legacy) im Manifest | `io.foodvisor.foodvisor` (alle Versionen) |

Der Scale-Patch hängt automatisch vom Permissions-Patch ab.

## Struktur

- `patches/` – Patch-Definitionen (Kotlin)
- `extensions/extension/` – in die APK injizierter Java-Code (BLE-Transport +
  Protokoll)
- `docs/`
  - [`FEASIBILITY.md`](docs/FEASIBILITY.md) – Machbarkeit, Optionen, Aufwand
  - [`ANALYSIS.md`](docs/ANALYSIS.md) – Foodvisor-APK-Analyseplan
  - [`PROTOCOL.md`](docs/PROTOCOL.md) – Capture-Playbook für die CNS-R101S

## Bekannte Einschränkungen

- Das gepatchte APK ist mit einem anderen Schlüssel signiert. **Google- und
  Apple-Login schlagen daher fehl** ("app is not registered to use OAuth2.0").
  Lösung: mit **E-Mail/Passwort** anmelden (ggf. vorher über „Passwort
  vergessen" setzen). Die Serverdaten bleiben erhalten.
- Die **Beschriftungen auf dem Waagen-Display sind Firmware-fest und Englisch**
  („Total Fat", „Protein" …). Der Patch sendet nur die Zahlenwerte
  (`SET_NUTRITION`); eine Übersetzung ist über das Protokoll nicht möglich.
- Die Einheit (g/ml/Portion) wird vom Patch noch nicht gesetzt; das Gewicht wird
  als Gramm in das Mengenfeld geschrieben.
- Die Nährwerte werden erst gesendet, nachdem Foodvisor sie für die aktuelle
  Menge neu berechnet hat (kurze Verzögerung).

## Bauen

Voraussetzungen: **JDK 21** und Android SDK; für das ReVanced-Gradle-Plugin ein
GitHub-Packages-Token mit `read:packages`. Details in [`AGENTS.md`](AGENTS.md).

```bash
./gradlew build
```

Ausgabe: `patches/build/libs/patches-1.0.4.rvp`.

## Patch anwenden (revanced-cli)

Gilt für Foodvisor **7.5.6** (App-Bundle mit Splits). ReVanced-Patcher v21
verwenden (passend zum Bundle), z. B. `revanced-cli` **v5.0.2**.

```bash
# 1) Splits zu einem APK mergen (base + split_config.*.apk)
java -jar APKEditor.jar m -i foodvisor-splits/ -o foodvisor-merged.apk

# 2) Patchen (Bundle ist unsigniert -> -b)
java -jar revanced-cli.jar patch \
  -p patches/build/libs/patches-1.0.4.rvp \
  -o foodvisor-patched.apk \
  foodvisor-merged.apk

# 3) Installieren (Original vorher deinstallieren; Google-Login geht nicht,
#    stattdessen E-Mail/Passwort nutzen)
adb uninstall io.foodvisor.foodvisor
adb install foodvisor-patched.apk
```

Hinweis: Das gepatchte APK wird mit einem festen ReVanced-Schlüssel signiert –
Updates per `adb install -r` behalten die Verbindung/Daten bei.

## Lizenz

GPLv3 – siehe [`LICENSE`](LICENSE).
