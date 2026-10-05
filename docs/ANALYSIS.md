# Foodvisor-APK-Analyse

Ziel: Geeignete Hook-Punkte finden, um ein Waagengewicht in die Mengen-/
Portionslogik von Foodvisor einzuspeisen, sowie freie/belegte BLE-Berechtigungen
klären.

## 0. Erstes Ergebnis (2026-10-05)

APK vom eigenen Gerät gezogen (`io.foodvisor.foodvisor`), **Version 7.5.6**
(versionCode 5551, minSdk 28, targetSdk 36). Foodvisor ist ein **App-Bundle**:

```
base.apk
split_config.arm64_v8a.apk
split_config.de.apk
split_config.en.apk
split_config.xxhdpi.apk
```

Erste Auswertung der DEX-Strings und des Manifests:

| Frage | Ergebnis |
| --- | --- |
| `BluetoothGatt`/`BluetoothLeScanner`/`BluetoothAdapter` | **0 Treffer** |
| Waagen-SDKs (renpho, yunmai, withings, etekcity, tanita) | **0 Treffer** |
| `BLUETOOTH`-Berechtigungen im Manifest | **keine** |
| `bluetooth`-Strings (14) | nur Firebase-Analytics-Felder (`getBluetooth`, `clearBluetooth`, …) |
| Health Connect | `androidx.health.*`, `READ_WEIGHT`/`WRITE_WEIGHT` (nur Körpergewicht) |
| Portionslogik-Begriffe | `serving` 291, `quantity` 209, `gram` 165, `portion` 6 |

**Schlussfolgerung:** Foodvisor 7.5.6 besitzt **keine** Verbindung zu Küchenwaagen
und **keinerlei** BLE-Code oder -Berechtigungen. Ein ReVanced-Patch müsste

1. `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` per Resource-Patch ins Manifest bringen,
2. den Runtime-Permission-Flow auslösen,
3. die komplette BLE-Anbindung (Extension ist vorhanden) sowie eine UI-Aktion in
   die Portions-/Mengeneingabe injizieren.

Das stützt **Option B (Companion-App)** aus `docs/FEASIBILITY.md`. Für Option A
ist als Nächstes eine echte Dekompilierung (jadx) nötig, um die Portions-UI als
stabilen Hook-Punkt zu finden.

## 0.1 Hook-Punkt: QuantityPickerBottomSheet (v7.5.6)

Diese Klassen wurden per jadx (`--single-class`, da R8/Kotlin-Klassen teils
übersprungen werden) extrahiert. Namen sind **obfuskiert und versionsabhängig**.

| Rolle | Klasse (v7.5.6) | Quelle |
| --- | --- | --- |
| Bottom-Sheet-Fragment | `io.foodvisor.mealxp.view.search.overlay.quantitypicker.g` | `QuantityPickerBottomSheet.kt` |
| ViewModel | `...quantitypicker.l` | `QuantityPickerViewModel.kt` |
| Mengenfeld-Host | `io.foodvisor.mealxp.view.food.FoodUnitPickerView` | – |
| State | `...quantitypicker.h` (`Food(foodUnits, selectedFoodUnitId, quantity, isLiquid)`) | – |
| Binding | `io.foodvisor.workout.view.session.m` (Feld `.f36296b` = Root `LinearLayout`) | – |

**Wichtige Methoden/Felder:**

- `g.onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V` – nach dem Original
  idealer Injectionspunkt. Layout `bottom_sheet_quantity_picker`; IDs
  `buttonValidate`, `foodUnitPickerView`, `imageViewBar`.
- In `FoodUnitPickerView`: Feld `f32014q` (Binding) mit `f27973b` =
  `TextInputEditText` (Menge) und `f27974c` = `AutoCompleteTextView` (Einheit).
- `FoodUnitPickerView` Zeile ~256:
  `textInputPickerQuantity.addTextChangedListener(new y(this));`
  → **Setzen des Textes löst die bestehende Mengen-Pipeline aus**
  (`getQuantityChangedFlow()` → `QuantityPickerViewModel.onQuantityChanged(float)`).
- `l.a(float)` (Kotlin `QuantityPickerViewModel.onQuantityChanged`) und
  `QuantityPickerViewModel$onQuantityChanged$1` bestätigen den Fluss.

**Injektionsstrategie für Option A (implementiert):**

1. **Resource-Patch:** `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (+ Legacy) und
   `uses-feature bluetooth_le` ins Manifest.
2. **Bytecode-Patch:** Hook am `FoodUnitPickerView`-Konstruktor
   `(Context, AttributeSet)`, kurz vor dem finalen `return-void`
   (`CosoriScaleBridge.attach(p0)`). Grund: `FoodUnitPickerView` wird per Namen
   im Layout-XML instanziiert und ist daher **nicht obfuskiert** – stabiler als
   `QuantityPickerBottomSheet` (obfuskiert zu `g`) und als `onViewCreated`
   (dort fehlt das `SourceFile`-Attribut in der Release-APK, Fingerprint matchte
   nicht).
3. **Extension `attach(View)`** (siehe `CosoriScaleBridge`):
   - Button „Waage" an die `FoodUnitPickerView` (MaterialCardView) hängen.
   - Klick: Runtime-Permissions anfragen, dann `startScan(context, callback)`.
   - `onWeight(grams, settled)`: per View-Traversierung das `TextInputEditText`
     finden und `setText(String.valueOf(grams))` – der vorhandene `TextWatcher`
     aktualisiert das ViewModel.
4. Callback-Interface, BLE und Protokoll sind in der Extension umgesetzt.

**Fallstricke:** Obfuskierte Feld-/Klassennamen ändern sich pro
Foodvisor-Version → Feldzugriff über View-Traversierung, Klassenzugriff über den
(XML-)stabilen `FoodUnitPickerView`. Einheit (g vs. Portion/ml) wird noch nicht
gesetzt.

**Status:** Implementiert, **erfolgreich gebaut und gepatcht** gegen Foodvisor
7.5.6. Verifiziert im gepatchten APK: Permissions vorhanden,
`CosoriScaleBridge.attach(this)` im `FoodUnitPickerView`-Konstruktor. Runtime-
Test auf Gerät steht aus.

## 0.2 Nährwert-Übertragung ans Waagen-Display (implementiert)

Ziel: Die aktuell in Foodvisor angezeigten Nährwerte an die CNS-R101S senden
(`SET_NUTRITION`, siehe `docs/PROTOCOL.md`).

- **Datenquelle:** `io.foodvisor.core.data.entity.MacroFoodAndFoodInfo` →
  `getNutritionalScore()`. Die Instanz wird per Reflexion über den
  `Activity`-`ViewModelStore` gefunden (erstes nicht-null `MacroFoodAndFoodInfo`,
  i. d. R. das Detail-Sheet-ViewModel `io.foodvisor.mealxp.view.food.v0`).
- **Wichtig:** `NutritionalScore.getMacros()` liefert **Energie (kcal)**, nicht
  Gramm (`Macro`-Enum-Koeffizienten 4/9/4/2). Gramm = kcal / Koeffizient.
  `getMicros()` (SatFat, TransFat, Cholesterol, Sodium, Potassium, Sugars) ist
  bereits in der Ziel-Einheit. `getCalories()` = kcal.
- **Timing:** Nach dem Setzen des Gewichts berechnet Foodvisor die Nährwerte
  asynchron neu → der Patch sendet zeitversetzt (300/900/1600 ms) und bei jedem
  neuen Wiege-Vorgang erneut.
- **Transport:** 20-Byte-Chunks (MTU 23), siehe `CosoriScaleBridge.writeCommand`.
- **Verifiziert am Gerät:** Kalorien, gesättigte Fettsäuren, Gesamtfett und
  Protein stimmen mit Foodvisor überein.
- **Nicht möglich:** Die Display-Beschriftungen sind Firmware-fest (Englisch).

## 1. APK beschaffen

Foodvisor liegt als **Split/App-Bundle** vor; alle Teile ziehen:

```bash
adb shell pm path io.foodvisor.foodvisor
adb pull /data/app/.../base.apk /tmp/opencode/foodvisor/
adb pull /data/app/.../split_config.arm64_v8a.apk /tmp/opencode/foodvisor/
# ... alle weiteren split_config.*.apk

adb shell dumpsys package io.foodvisor.foodvisor | grep -iE "versionName|versionCode"
```

Für **Analyse** genügt `base.apk` (mit `unzip` entpacken, DEX durchsuchen). Für
**Patchen** müssen die Splits zu einem APK gemerged werden:
`java -jar APKEditor.jar m -i <bundle> -o merged.apk`. Für reproduzierbare
Patches **exakt eine Version pinnen**.

## 2. Deassemblieren

```bash
# Lesbarer Java-Code
jadx -d /tmp/opencode/foodvisor-jadx /tmp/opencode/foodvisor.apk

# Smali + Manifest/Ressourcen
apktool d -f -o /tmp/opencode/foodvisor-apktool /tmp/opencode/foodvisor.apk
```

## 3. Manifest prüfen

```bash
grep -i "bluetooth\|permission" /tmp/opencode/foodvisor-apktool/AndroidManifest.xml
```

Zu klären:

- Sind `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+) deklariert?
- `BLUETOOTH`/`BLUETOOTH_ADMIN` (Legacy)?
- `uses-feature android:name="android.hardware.bluetooth_le"` vorhanden?

Falls nicht → Resource-Patch nötig, der die Einträge ergänzt.

## 4. Nach Anknüpfungspunkten suchen

```bash
grep -rniE "bluetooth|BluetoothGatt|BluetoothLeScanner|scale|weigh|weight" \
  /tmp/opencode/foodvisor-jadx/sources | head -50

grep -rniE "\bgramm?|\bg\b|portion|quantity|serving" \
  /tmp/opencode/foodvisor-jadx/sources | head -50
```

Gesuchte Konzepte:

| Konzept | Kandidat |
| --- | --- |
| Mengenfeld Gramm | UI-ViewModel mit `weight`/`quantity`/`grams` |
| Portionsauswahl | Enum/Model für Einheiten (`g`, `ml`, Portion) |
| Tagebuch-Eintrag | Datenmodell für Log-/Diary-Eintrag |
| Deep Link / Share | `intent-filter`, `data`-Scheme in Manifest |

## 5. API-Dump für stabile Signaturen

```bash
# Methodensignaturen der interessanten Klassen
javap -p -classpath /tmp/opencode/foodvisor.apk \
  io.foodvisor.foodvisor.<...> 2>/dev/null
```

Für einen ReVanced-Patch sind die Smali-/DEX-Signaturen (Descriptor, nicht
Zeilen) maßgeblich.

## 6. Hook-Strategie festhalten

Ergebnis hier eintragen:

| Frage | Antwort |
| --- | --- |
| Foodvisor-Version | 7.5.6 (Code 5551), App-Bundle |
| Klasse des Mengenfelds | `...quantitypicker.g` (BottomSheet), Feld in `FoodUnitPickerView` |
| Feld-/Getter-Signatur | `onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V`; `getQuantityChangedFlow()` |
| Activity für Permission-Request | die Fragment-Activity (`requireActivity()`) |
| BLE-Permissions bereits vorhanden? | **nein** |
| Deep Link/Share vorhanden? | Manifest hat `BROWSABLE`/Custom-Schemes – im Detail offen |
| Gewählte Architektur (A/B/C) | **A** jetzt konkret umsetzbar (Hook vorhanden); B bleibt Fallback |

## 7. Patch-Matching umsetzen

In `CosoriScalePatch.kt` die passenden Matcher (`classDef`, `method`, …) ergänzen
und die Extension-Aufrufe injizieren. Bei jeder Foodvisor-Version erneut prüfen.
