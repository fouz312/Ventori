# Beginner guide: build the Vantora APK on GitHub

You only need a web browser and a GitHub account (free). No Android Studio.

## 1. Create the repository
1. Sign in at github.com → click **+** (top right) → **New repository**.
2. **Repository name:** `vantora` (any name works; this guide assumes `vantora`).
3. Choose **Private** (recommended) or Public. Leave "Add a README" **unticked**. Click **Create repository**.

## 2. Upload the project
1. Unzip `Vantora.zip` on your computer/phone so you have a folder with `app`, `docs`, `.github`, `scripts`, `native`, `build.gradle.kts`…
2. On the new empty repository page click **uploading an existing file**.
3. Drag the **contents** of the unzipped folder in (not the folder itself). The folder `.github/workflows` is hidden on some systems — it **must** be uploaded, it contains the build recipe. If your upload tool skips hidden folders: click **Add file → Create new file**, type `.github/workflows/android-build.yml` as the name, and paste the file's text.
4. Files that must be there: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/`, `scripts/fetch-firmware.sh`, `native/qemu/build-qemu.sh`, `.github/workflows/*.yml`, `.gitignore`.
5. Scroll down, click **Commit changes**. Do **not** upload Windows ISO files.

## 3. Run the build
1. Open the **Actions** tab. If asked, click **I understand my workflows, enable them**.
2. Click **Android Build** (left) → **Run workflow** (right) → keep "Also compile the QEMU engine" ticked → **Run workflow**.
3. Two jobs run: `qemu-engine` (slow, 30–90 min the first time; may fail on the first try — that is expected, see below) and `app`.
4. Green tick = success, red cross = click the job and read the failing step. The `app` job still produces an APK even if `qemu-engine` failed (the app then says the engine is not bundled).

## 4. Download the APK
1. Click the finished run → scroll to **Artifacts** at the bottom.
2. Download **vantora-debug-apk** (a zip). Unzip it to get `app-debug.apk`.
3. The run's **Summary** page lists whether the QEMU engine was bundled.

## 5. Install on your phone
1. Copy `app-debug.apk` to the phone, open it with the Files app.
2. Android asks to allow "install unknown apps" for that app → allow → **Install**.
3. First launch: allow notifications (needed for the VM's foreground notification).

## 6. If the build fails
Copy the red error lines from the failing step and paste them back to me; this is the first-ever compile, so a few fixes are normal. For the QEMU job the log shows which dependency or QEMU source file failed (typically a bionic missing function) — fixes go in `native/qemu/patches/`.

## 7. Update the project
Replace/edit files on GitHub (open file → pencil icon → Commit) or upload a new zip's contents over the old ones. Every push to `main` starts a build automatically; or use **Run workflow**. Cached QEMU deps make later engine builds faster.

## 8. Future APK versions
Edit `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, commit, run the workflow, download the new artifact. Installing over the old version keeps data only when both APKs are signed with the same key (debug builds from GitHub use a temporary debug key that can differ between runs — uninstall first if Android refuses).

## 9. Signed release APK
1. **Actions → Create Keystore → Run workflow.** Download artifact `keystore-secrets-DELETE-AFTER-USE`, open `keystore-secrets.txt`.
2. **Settings → Secrets and variables → Actions → New repository secret.** Create four secrets with exactly these names and the values from the file: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. Delete the artifact (run page → trash icon). **Back the keystore values up somewhere safe**: losing them means you can never update the app under the same signature.
4. **Actions → Signed Release APK → Run workflow** (optionally enter a tag like `v0.1.0` to also publish a GitHub Release). Download `vantora-release-apk`.
5. In the app: Settings → Updates → enter `yourname/vantora` to check releases.
