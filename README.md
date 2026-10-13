# LightCraft for Android (personal build)

An Android wrapper around the browser version of [LightCraft](https://github.com/storytold/lightcraft),
the open-source photo library and raw developer from the ArtCraft project. LightCraft's own UI
(egui compiled to WebAssembly) runs full screen inside a WebView; this repo adds the Android glue:

- serves the bundled site from a fixed secure in-app origin (`https://appassets.androidplatform.net`),
  which LightCraft needs for its render workers and its on-device library storage
- connects the page's "open" button to Android's file picker (multi-select)
- saves exports to `Downloads/LightCraft`
- hides the system bars, survives rotation without reloading, and reloads instead of crashing if
  the WebView runs out of memory on a very large raw file

This is an unofficial build, not affiliated with the ArtCraft Team.

## Building

GitHub Actions does everything (`.github/workflows/build-apk.yml`): it checks out LightCraft at the
pinned commit, builds the web version with `cargo xtask web`, bundles it, and builds a signed APK.
Every push to `main` publishes the APK as a new release. To build a different LightCraft version,
run the workflow manually and enter a commit, tag or branch, or change `LIGHTCRAFT_PINNED_REF`.

## Your photo library

The library lives in the WebView's storage for this app. It survives app updates as long as:

- builds keep the same signing key, and
- the in-app origin in `MainActivity.java` never changes.

Uninstalling the app, or clearing its storage, deletes the library. LightCraft's web version has
library backup and restore in its File menu; use it before uninstalling.

## Signing key

`signing/lightcraft-release.jks.enc` is the release key, encrypted with AES-256 (OpenSSL,
PBKDF2, 600,000 iterations). It is safe in a public repository because it is useless without the
passphrase, which lives only in the `LC_SIGNING_PASSPHRASE` Actions secret (it is also the keystore
and key password). CI decrypts the key into the runner's temp folder, signs, and deletes it.

Keep a copy of the passphrase somewhere safe, such as a password manager. GitHub never shows a secret
again, and without the passphrase future builds can't be signed with this key, which means a
reinstall that wipes the library.

## Licenses

LightCraft is dual-licensed MIT or Apache-2.0. Its license files, NOTICE and third-party
attributions are bundled in the app under `assets/www/licenses/`. The ArtCraft and LightCraft names
and logos are the ArtCraft Team's trademarks; this wrapper uses its own icon.
