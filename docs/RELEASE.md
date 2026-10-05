# Release (GitHub Actions)

Der Workflow [`.github/workflows/release.yml`](../.github/workflows/release.yml)
baut und veröffentlicht das Patch-Bundle automatisch als GitHub-Release.

## Ablauf

Bei Push auf `main`/`dev` (oder manuell via *workflow_dispatch*):

1. Checkout (volle Historie), JDK 17, Gradle-Cache.
2. `./gradlew build clean` (benötigt Zugang zum ReVanced-Registry).
3. Node LTS + `npm install` (semantic-release-Plugins).
4. GPG-Key importieren.
5. `semantic-release` (siehe [`.releaserc`](../.releaserc)):
   versioniert anhand der Conventional Commits, aktualisiert `CHANGELOG.md`,
   committet zurück, erstellt Tag + GitHub-Release und hängt
   `patches-*.rvp` (+ GPG-Signatur `.asc`) als Assets an; merged `main`→`dev`.

`dev` ist das Entwicklungs-Branch (Pre-Releases), `main` das Release-Branch.
Bei Push auf `dev` öffnet `open_pull_request.yml` automatisch einen Draft-PR
nach `main`.

## Benötigte Secrets/Variablen

Im Repo: **Settings → Secrets and variables → Actions**.

| Name | Typ | Zweck |
| --- | --- | --- |
| `GPG_PRIVATE_KEY` | Secret | Armored privater GPG-Schlüssel zum Signieren |
| `GPG_PASSPHRASE` | Secret | Passphrase des GPG-Schlüssels |
| `GPG_FINGERPRINT` | **Variable** | Fingerprint des Signier-**Subkeys** |
| `GH_PACKAGES_TOKEN` | Secret (empfohlen) | PAT mit `read:packages` für das ReVanced-Registry |
| `GITHUB_TOKEN` | automatisch | Von GitHub bereitgestellt |

Hinweis: Der Build lädt das ReVanced-Gradle-Plugin `app.revanced.patches` aus
`maven.pkg.github.com/revanced/registry`. Das Standard-`GITHUB_TOKEN` kann
org-fremde Packages i. d. R. nicht lesen → lege `GH_PACKAGES_TOKEN` (Classic
PAT oder Fine-grained mit **Packages: Read**) an. Der Workflow nutzt dafür
`secrets.GH_PACKAGES_TOKEN || secrets.GITHUB_TOKEN`.

## GPG-Schlüssel erzeugen

```bash
gpg --full-generate-key                 # RSA 4096, Passphrase setzen
gpg --list-secret-keys --keyid-format=long
gpg --fingerprint <KEYID>               # Subkey-Fingerprint kopieren
gpg --armor --export-secret-keys <KEYID> > private.key
```

## Werte hinterlegen

```bash
gh secret set   GPG_PRIVATE_KEY --repo chrisbinnefeld/foodvisor-cosori-scale < private.key
gh secret set   GPG_PASSPHRASE  --repo chrisbinnefeld/foodvisor-cosori-scale --body '<passphrase>'
gh variable set GPG_FINGERPRINT --repo chrisbinnefeld/foodvisor-cosori-scale --body '<subkey-fingerprint>'
gh secret set   GH_PACKAGES_TOKEN --repo chrisbinnefeld/foodvisor-cosori-scale --body '<pat-read-packages>'
```

Nach dem Signieren `private.key` lokal löschen (`shred -u private.key`).

## Troubleshooting

- **Job „Build" schlägt fehl (401/403/„could not resolve"):**
  `GH_PACKAGES_TOKEN` fehlt oder hat kein `read:packages`.
- **Job „Import GPG key"/„Release" schlägt fehl:** `GPG_PRIVATE_KEY`,
  `GPG_PASSPHRASE` oder die Variable `GPG_FINGERPRINT` fehlt/falsch.
- **Release wird ausgelöst, aber kein `.rvp`:** Prüfen, ob die Datei unter
  `patches/build/libs/patches-*.rvp` erzeugt wurde (About-Name des Bundles).
- Lokal gilt: **JDK 21** (JDK 27 bricht den Kotlin/Gradle-Build); im CI reicht 17.
