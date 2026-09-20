# Public preview signing key

`preview-test.keystore` is intentionally public development material, not a secret or a production signing identity. Alias: `androiddebugkey`; store/key password: `android`.

Only the separate `com.retrotv.emu.preview.console` build uses it. It allows future personal test builds to update the same preview package without deleting private progress. Anyone can reproduce its signature, so obtain APKs only from sources you trust. Use a private owner-controlled key for any release.

The main `com.retrotv.emu` package remains unchanged and requires its original key for in-place updates. No previously installed package is uninstalled or migrated automatically.
