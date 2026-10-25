# Field Vault

Speak it. Link it. Find it later.

A phone notes app for field work and brainstorming. Talk your notes in with your keyboard's mic, link a word to its own vault page, and every later mention of that name links back to it automatically.

- Works offline after the first visit (installable: Add to Home screen)
- Notes are saved on the phone, in the browser
- Copy backup / Restore from the ⋯ menu

## Android app

Download and install: https://github.com/cloudstifa1-cpu/field-vault/releases/latest/download/field-vault.apk

Every push to `main` rebuilds the APK (see `.github/workflows/android.yml`). Before bundling, `build/protect.mjs` scrambles the code (obfuscation) so the linking engine can't easily be read or copied. Only the scrambled copy ships in the APK; the readable source stays in this private repo. The app runs with no signal.
