# 1.0.0 (2026-10-05)


### Features

* add Cosori CNS-R101S kitchen scale support to Foodvisor ([86563ad](https://github.com/chrisbinnefeld/foodvisor-cosori-scale/commit/86563adbdb3595561618ac6a7ddfb430696e64d9))

# Changelog

## [Unreleased]

### Added

- ReVanced-Patch-Repo für Foodvisor (`io.foodvisor.foodvisor`).
- Patch `Cosori CNS-R101S scale support`: hängt einen „Waage"-Link in den
  Quantity-Picker, verbindet die Cosori CNS-R101S und schreibt das Gewicht ins
  Mengenfeld; sendet zusätzlich die aktuellen Nährwerte per `SET_NUTRITION`
  an das Waagen-Display.
- Patch `Cosori CNS-R101S scale permissions`: ergänzt `BLUETOOTH_SCAN`
  (mit `neverForLocation`), `BLUETOOTH_CONNECT`, Legacy-BLE und
  `uses-feature bluetooth_le` im Manifest.
- Extension `CosoriScaleBridge` (BLE-Transport, UI-Injektion, Permission-Flow,
  Verbindungs-Reuse) und `CosoriCnsR101sProtocol` (Frame-Codec).
- Dokumentation: `docs/FEASIBILITY.md`, `docs/ANALYSIS.md`, `docs/PROTOCOL.md`.
- `tools/cns_r101s_probe.py` – Reproduzierbares BLE-Mitschnitt-Tool.

### Changed

- **Protokoll der CNS-R101S gegen echte Hardware bestätigt**
  (`docs/PROTOCOL.md`): Service `0x1910`, Notify `0x2c12`, Write `0x2c11`,
  Frame `FE EF ?? ?? | type | len | payload | checksum`.
- **`SET_NUTRITION` (0xC2) per HCI-Capture der VeSync-App verifiziert**:
  12 Werte × 3 Byte big-endian (0,1-Einheiten); im 20-Byte-Chunking (MTU 23)
  gesendet.
- Nährwert-Extraktion aus Foodvisors `MacroFoodAndFoodInfo` (Reflexion);
  Foodvisors Makro-Werte sind **Energie (kcal)** → Gramm = Energie / 4/9/4/2.

### Fixed

- Injection vor das normale `return-void` (nicht Fehlerpfad).
- `sourceFile`-Fingerprint scheiterte → stabiler `FoodUnitPickerView`-Name
  bzw. ViewModel per `androidx.lifecycle`-Superklasse.
- Scan lieferte keine Ergebnisse ohne `neverForLocation`.
- Long-Write an die Waage auf 20-Byte-Chunks (statt einzelnem Write).
- Nährwerte werden bei neuem Wiege-Vorgang erneut und zeitversetzt gesendet.
- Button/Verbindung bleibt über Sheet-Wechsel erhalten.

### Known limitations

- Die Display-Beschriftungen der Waage („Total Fat", „Protein" …) sind
  Firmware-fest und **englisch**; über das Protokoll nicht änderbar.
- Das gepatchte APK ist anders signiert → Google-/Apple-Login schlägt fehl;
  E-Mail/Passwort-Login verwenden.
