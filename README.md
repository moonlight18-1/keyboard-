# ML Keyboard (Android IME)

Ultra-fast, zero-lag English-only Android keyboard built with Kotlin and a custom hardware-accelerated Canvas `InputMethodService`.

## Key Features

- **Zero-Lag Custom Canvas Rendering**: No inflated key button trees—all keys are drawn directly on a hardware-accelerated Canvas with pre-allocated paints and multi-touch pointer tracking.
- **English-Only Precision**: Clean `en_US` QWERTY layout, `?123` and `=\<` symbol layers, optional top number row, Shift / Caps Lock, and fast hold-to-repeat Backspace.
- **Instant Local English Dictionary**: Sub-millisecond prefix suggestions and common English typo correction with zero network requests.
- **Synced Studio Defaults**:
  - Theme: `DARK`
  - Key Height: `STANDARD`
  - Number Row: `Disabled`
  - Auto-Capitalize: `Enabled`
  - Double-Space Period: `Enabled`
  - English Auto-Correct: `Enabled`

## Build APK Automatically on GitHub

1. Create a new repository on GitHub (for example, `ml-keyboard`).
2. Extract this ZIP and upload all files (including the hidden `.github/workflows/build-apk.yml` folder) to the repository's `main` branch.
3. Open the **Actions** tab on GitHub—the **Build ML Keyboard APK** workflow runs automatically.
4. Once complete, download the **ML-Keyboard-APK** artifact, extract `ML-Keyboard-debug.apk`, and install it on your Android phone.
5. Open **ML Keyboard** on your phone, tap **1. Enable ML Keyboard in Settings**, and then tap **2. Set as Default Keyboard**.
