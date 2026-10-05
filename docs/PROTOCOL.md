# Protokoll: Cosori CNS-R101S ("COSORI Nutrition Scale")

> **Status: bestätigt** gegen echte Hardware (2026-10-05). Das Protokoll ist
> identisch zur Etekcity-ESN00-Familie (`hertzg/metekcity`), mit abweichendem
> Header.

## Gerät

| Feld | Wert |
| --- | --- |
| Anzeigename | `COSORI Nutrition Scale` |
| MAC (dieses Gerät) | `C0:60:40:03:CA:79` (Public) |
| Advertised Service | `00001910-0000-1000-8000-00805f9b34fb` |
| Manufacturer Data | Company-ID `0x8400`, Payload = ... + MAC |
| Sleep | schläft sehr schnell; advertiset nur kurz nach Aktivität |

## GATT

| UUID | Props | Zweck |
| --- | --- | --- |
| `00001910-...` | – | Haupt-Service |
| `00002c12-...` | `notify` | Messdaten-Stream (Gerät → Host) |
| `00002c11-...` | `write-without-response`, `write` | Kommandos (Host → Gerät) |
| `00002c10-...` | `read` | Read |
| `00001800` / `00001801` / `0000180a` | – | Standard (GAP/GATT/Device Info) |
| `f000ffc0-0451-4000-b000-000000000000` | write/notify | TI-Vendor/OTA, **nicht** relevant |

Verbinden + Notify aktivieren genügt – **kein Handshake nötig**. Pairing
schließt nicht ab (`Bonded: no`), Notify funktioniert trotzdem.

## Frame-Format

```
FE EF B2 B3 | type | len | payload[len] | checksum
checksum = (type + len + payload[0..len-1]) & 0xFF
```

- `B2 B3`: **variiert je Modell** – CNS-R101S: `00 84`; ESN00: `C0 A2`.
  Daher **nicht** validieren, nur `FE EF`.
- Alle beobachteten Frames bestanden die Checksumme (`cksum=OK`).

## Packet-Typen

| Typ | Name | Richtung | Payload | Status |
| --- | --- | --- | --- | --- |
| `0xC0` | SET_UNIT | Host→Gerät | `unit` (uint8) | aus ESN00 übernommen |
| `0xC1` | SET_TARE | Host→Gerät | `reset` (bool) | aus ESN00 übernommen |
| `0xC4` | SET_AUTO_OFF | Host→Gerät | `timeout` (uint8) | aus ESN00 übernommen |
| `0xD0` | MEASUREMENT | Gerät→Host | 5 Byte, s.u. | **bestätigt** |
| `0xD1` | UNIT_STATE | Gerät→Host | `unit` | aus ESN00 |
| `0xD2` | UNKNOWN | Gerät→Host | 1 Byte (`0x2b` beobachtet) | offen |
| `0xD3` | TARE_STATE | Gerät→Host | `isOn` | aus ESN00 |
| `0xD5` | AUTO_OFF_STATE | Gerät→Host | uint16 | aus ESN00 |
| `0xE0` | ERROR_STATE | Gerät→Host | `isOn` | aus ESN00 |
| `0xE4` | ITEM_STATE | Gerät→Host | `isOn` (00/01) | **bestätigt** |

### MEASUREMENT (`0xD0`), 5-Byte-Payload

| Byte | Feld | Bedeutung |
| --- | --- | --- |
| 0 | sign | `0x00` positiv, `0x01` negativ |
| 1–2 | weight | uint16 **big-endian**, Einheit 0,1 g |
| 3 | unit | `00` g, `02` ml, `03` floz, `04` ml Milch, `05` floz Milch, `06` oz, `01` lb:oz |
| 4 | settled | `00` misst, `01` stabil |

**Gewicht in Gramm = weight / 10** (bzw. `/10` der gewählten Einheit).

## SET_NUTRITION (`0xC2`) – bestätigt per HCI-Capture der VeSync-App

Frame: `FE EF 00 84 | C2 | 24 | payload(36) | checksum`.

Payload = **12 Werte × 3 Byte big-endian**, jeweils `angezeigter Wert × 10`
(0,1-Einheiten), in dieser Reihenfolge:

| # | Feld | # | Feld |
| --- | --- | --- | --- |
| 0 | calories | 6 | sodium |
| 1 | caloriesFromFat (`totalFat × 9`) | 7 | potassium |
| 2 | totalFat | 8 | totalCarbs |
| 3 | saturatedFat | 9 | dietaryFiber |
| 4 | transFat | 10 | sugars |
| 5 | cholesterol | 11 | protein |

Beispiel (VeSync): `...c224 001b44 001680 000280 000041 000000 000000 000000 000000 006e00 000000 00002c 000000` → 698 kcal, 576 kcal a. Fett (64 g × 9 ✓), 64 g Fett, 6,5 g gesättigt, 11 g Carbs, 4,4 g Zucker.

**Transport:** Die Waage handelt MTU 23 aus. Der 43-Byte-Frame wird daher als
mehrere **ATT Write Commands (`0x52`, handle 18 = `0x2c11`) à 20 Byte**
gesendet – nicht als ein Write. Der Bridge-Encoder fragmentiert entsprechend.

## Verifizierte Rohproben

| Frame (hex) | raw | Ergebnis |
| --- | --- | --- |
| `feef0084 d0 05 00 0000 00 01 d6` | 0 | 0,0 g, stabil |
| `feef0084 d0 05 00 0026 48 00 01 44` | 9800 | **980,0 g, stabil** (Anzeige der Waage: 980 g ✓) |
| `feef0084 d0 05 00 258a 00 00 84` | 9610 | 961,0 g, transient |
| `feef0084 e4 01 01 e6` | – | ITEM_STATE an |
| `feef0084 e4 01 00 e5` | – | ITEM_STATE aus |
| `feef0084 d2 01 2b fe` | – | UNKNOWN 0xD2 |

## Referenz-Decoder (Auszug)

```java
if (data[0] != (byte)0xFE || data[1] != (byte)0xEF) return invalid;
int type = data[4] & 0xFF, len = data[5] & 0xFF;
if (checksum(data, 4, 2 + len) != (data[6 + len] & 0xFF)) return invalid;
if (type != 0xD0 || len < 5) return invalid;
int sign = data[6] == 0 ? 1 : -1;
int raw  = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
int unit = data[9] & 0xFF;
boolean settled = data[10] != 0;
float grams = sign * raw / 10f;
```

Vollständig: `extensions/extension/.../CosoriCnsR101sProtocol.java`.

## Reproduktion

Das Mitschnitt-Tool liegt im Repo: `tools/cns_r101s_probe.py`.

```bash
python3 -m venv /tmp/opencode/venv
/tmp/opencode/venv/bin/pip install bleak
/tmp/opencode/venv/bin/python tools/cns_r101s_probe.py --seconds 60
# optional: --address C0:60:40:03:CA:79 --tare
```

Hinweis: Zum Verbinden erst **aktiv scannen** und bei Sichtung sofort
verbinden; `BleakClient(adresse)` scheitert, solange die Waage nicht advertiset.
Die Waage schläft sehr schnell ein – während des Mitschnitts wach halten
(Taste drücken / Gewicht auflegen).

## Offene Punkte

- `0xD2` (Heartbeat/Status?) unklar; irrelevant für Gewicht.
- Command-Seite (`SET_UNIT`/`SET_TARE` über `0x2c11`) noch nicht gegen dieses
  Gerät verifiziert; Implementierung aus ESN00 übernommen.
- Kein Login/Bonding nötig; ob eine ältere VeSync-App-Abhängigkeit besteht,
  ist nicht nötig für den Patch.
