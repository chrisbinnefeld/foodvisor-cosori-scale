# Machbarkeitsanalyse: Cosori CNS-R101S in Foodvisor via ReVanced

## 1. Ausgangslage

| Thema | Stand |
| --- | --- |
| Ziel-App | Foodvisor, Paket `io.foodvisor.foodvisor` |
| Ziel-Waage | Cosori CNS-R101S (Edelstahl-Küchenwaage) |
| Waagen-App | VeSync (Cosori/Etekcity/Levoit) |
| Aktueller Stand | nichts analysiert, reines Gerüst |

## 2. Kernproblem

**Foodvisor besitzt von Haus aus keine Bluetooth-Waagen-Funktion.** Öffentlich
dokumentierte Features sind Foto-/KI-Erkennung, Barcode-Scan, manuelle Eingabe,
Rezepte, Fitness und die Anbindung an Apple Health / Google Fit. Es gibt keinen
Hinweis auf einen Bluetooth-Stack, keine Herstellerliste unterstützter Waagen
und keine Berechtigungen rund um BLE in der Store-Beschreibung.

Das bedeutet: Es gibt **keinen vorhandenen Code-Pfad**, den ein Patch nur
umleiten müsste. Ein Patch müsste die komplette Funktion neu in die APK
injizieren:

1. BLE-Scan und Verbindungsaufbau,
2. das Cosori-CNS-R101S-Protokoll,
3. eine UI-Aktion „von Waage übernehmen" und
4. das Zurückschreiben des Gewichts in die Mengen-/Portionslogik von
   Foodvisor.

Punkt 4 setzt voraus, dass eine geeignete Stelle in Foodvisor existiert (z. B.
das Feld für die Portionsgröße in Gramm). Das ist plausibel, aber erst nach
Deassemblierung der APK bestätigbar.

## 3. Die Cosori CNS-R101S

Die CNS-R101S wird regulär über die **VeSync-App** gekoppelt. Ein öffentlich
dokumentiertes BLE-Protokoll gab es nicht, aber die Arbeit an der verwandten
Etekcity-ESN00 (`hertzg/metekcity`) lieferte die Vorlage.

**Das Protokoll ist inzwischen gegen echte Hardware bestätigt** (Details,
Frames und Rohproben in `docs/PROTOCOL.md`):

- Service `0x1910`, Notify `0x2c12`, Write `0x2c11`, Read `0x2c10`.
- Frame `FE EF ?? ?? | type | len | payload | checksum`.
- Messung (`0xD0`): Vorzeichen, uint16 BE, Einheit, settled; **Gramm = raw / 10**.
- Verifiziert: leere Waage 0,0 g; 1-kg-Mehlpackung = 980,0 g (deckt sich exakt
  mit der Anzeige der Waage).
- Verbinden + Notify genügt, kein Handshake, kein abgeschlossenes Bonding nötig.

Damit ist der kritischste Pfad des Vorhabens **entschärft**. Restrisiko: die
Command-Seite (`SET_UNIT`/`SET_TARE` über `0x2c11`) ist noch nicht gegen dieses
Gerät verifiziert (aus ESN00 übernommen).

## 4. Was ReVanced leisten kann – und was nicht

ReVanced patcht Dalvik-Bytecode und APK-Ressourcen. Über eine *Extension*
(`extensions/foodvisor-scale.rve`) kann beliebiger Java/Kotlin-Code in die APK
eingebracht werden. Damit ist die BLE-Logik grundsätzlich machbar.

Grenzen:

- **Hook-Punkte müssen stabil sein.** Sie hängen von Foodvisor-Version und
  Obfuskation ab und brechen bei Updates.
- **Manifest-Berechtigungen.** Für BLE ab Android 12 braucht die App
  `BLUETOOTH_SCAN` und `BLUETOOTH_CONNECT`. Ob Foodvisor sie deklariert, ist
  unklar. Falls nicht, muss ein zusätzlicher Resource-Patch sie ergänzen
  (im Gerüst als TODO markiert).
- **Laufzeit-Berechtigungen.** Selbst mit Manifest-Eintrag muss der Nutzer sie
  erteilen; die Extension müsste den Permission-Flow anstoßen.
- **UI-Injektion.** Eine neue Schaltfläche in einen obfuskierten Screen
  einzuklinken ist der fragilste Teil.

## 5. Architektur-Optionen

### Option A – Reines ReVanced-Patch-Projekt (dieses Repo)

- BLE-Client als Extension, Patch hookt Foodvisor-UI.
- **Pro:** eine App, ein Ablauf, kein Root.
- **Contra:** hoher Aufwand, fragil, UI-Injektion schwierig.

### Option B – Companion-App (eigene BLE-Lese-App)

- Kleine Android-App liest die Waage und übergibt das Gewicht per Share/Intent,
  Clipboard oder URL-Scheme an Foodvisor.
- **Pro:** entkoppelt von Foodvisor-Updates, Protokoll nur einmal zu
  implementieren, kein Root, kein ReVanced.
- **Contra:** Übergabe in Foodvisor-Feld nur, wenn Foodvisor eine Schnittstelle
  (Deep Link/Clipboard-Einfügen) bietet; sonst manueller Zwischenschritt.

### Option C – Accessibility-Service oder Xposed/LSPosed

- Accessibility-Service liest Waage und tippt das Gewicht ins Feld.
- **Pro:** unabhängig von Foodvisor-Interna, funktioniert mit jedem UI.
- **Contra:** kein „ReVanced-Patch" mehr, zusätzliche Berechtigungen,
  LSPosed braucht Root.

## 6. Empfehlung

1. ~~Protokoll reverse-engineeren~~ **erledigt** (`docs/PROTOCOL.md`).
2. ~~Foodvisor-APK analysieren~~ **erledigt** (`docs/ANALYSIS.md`): Foodvisor
   7.5.6 hat keinen BLE-Code; der `QuantityPickerBottomSheet` liefert aber einen
   konkreten UI-Hook.
3. Nächster Schritt: Resource-Patch (BLE-Permissions) + Bytecode-Hook in
   `onViewCreated` + UI-/BLE-Extension. Details in `docs/ANALYSIS.md`.
4. **Option B** (Companion-App) bleibt Fallback, falls der Hook bricht.

## 7. Aufwand (grobe Schätzung)

| Arbeitspaket | Aufwand |
| --- | --- |
| APK besorgen, deassemblieren, Architektur verstehen | 1–2 Tage |
| ~~CNS-R101S-Protokoll reverse-engineeren~~ | **erledigt** |
| BLE-Extension implementieren und testen | 1–2 Tage |
| Foodvisor-Hooks finden und injizieren | 2–5+ Tage |
| Manifest-/Permission-Handling | 0,5 Tag |
| Test auf echten Geräten/Versionen | 1–2 Tage |

Für Option B reduziert sich der Foodvisor-spezifische Teil deutlich, weil die
Protokollarbeit entfällt, aber nicht der Übergabemechanismus.

## 8. Risiken

- ~~Protokoll nicht (einfach) dekodierbar → Vorhaben blockiert.~~ **entschärft**:
  Protokoll bestätigt.
- Kein stabiler Hook in Foodvisor → Option A nicht umsetzbar.
- Obfuskation und häufige Updates brechen den Patch.
- Rechtlich/ToS: Reverse Engineering der eigenen Geräte ist in vielen
  Jurisdiktionen zulässig; trotzdem keine Gewährleistung.

## 9. Nächste Schritte

1. ~~BLE-Protokoll der CNS-R101S mitschneiden~~ **erledigt** (`docs/PROTOCOL.md`).
2. ~~Foodvisor-APK mit jadx zerlegen und Hook finden~~ **erledigt**
   (`docs/ANALYSIS.md`, Hook: `QuantityPickerBottomSheet`).
3. Resource-Patch (BLE-Permissions) und Bytecode-Hook implementieren.
4. `CosoriScalePatch.kt` implementieren und auf gepinnte APK (7.5.6) anwenden.
