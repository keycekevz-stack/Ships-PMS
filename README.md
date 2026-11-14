# Ships-PMS — Ship Docs (Android app)

Manuals, PMS, certificates, etc. — an Android app that keeps your ship's PDF files on the phone/tablet and lets you find and read them quickly, **fully offline**.

## Features
- **Upload PDFs** — tap *Upload PDF*, pick one or many files (from phone storage, Google Drive, USB, etc.) and choose a folder.
- **Receive from other apps** — *Share* or *Open with* a PDF from email/WhatsApp/Files → *Ship Docs* to save it.
- **Ship folders** — Certificates, Manuals, PMS / Maintenance, Procedures / SMS, Checklists, Drawings & Plans, Reports & Forms, Other.
- **Fast search** by file name, filter by folder, ★ favorites, sort by name / recently added / recently opened.
- **Built-in PDF viewer** — pinch or double-tap to zoom, page counter, *Go to page*.
- Rename, move to another folder, share, open in another PDF app, delete.
- Files are copied into the app's private storage, so they stay available with no internet.

## Get the APK
Every push builds the app with GitHub Actions:
1. Open the repository's **Actions** tab → latest **Build Android APK** run.
2. Download the **ShipDocs-apk** artifact (a zip), unzip it and copy `app-release.apk` to the phone.
3. On the phone, open the APK and allow *Install unknown apps* when asked.

Requires Android 8.0 or newer.

## Build locally
Open the folder in Android Studio, or run `./gradlew assembleRelease` with the Android SDK installed (JDK 17).
The APK is written to `app/build/outputs/apk/release/`.

> Note: the release build is signed with the debug key so it installs directly. Use your own signing key before publishing to the Play Store.
