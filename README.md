# Machinery Logbook

An offline app for machinery manuals, photos, and maintenance records.
It works on Android and iPhone, and no Xcode or app store is needed.

## What it does
- A list of machines with maker, model, serial no., location, and running hours
- A work log for each machine: date, job, type of work, status, work done, parts used, who did it, next due date, and photos
- PDF manuals and documents stored with each machine
- Photos taken with the camera or picked from the gallery (resized automatically to save space)
- A printable report for each machine (Print → Save as PDF)
- Backup and restore to one file
- Works with no internet once installed. All data stays on the phone.

## Put it online (one time, free, with GitHub Pages)
1. On github.com, open your `machinery-logbook` repository.
2. Click **Add file → Upload files**. Drag in **everything in this folder**, including the `icons` folder. Click **Commit changes**.
3. Go to **Settings → Pages**. Under "Build and deployment", set Source to **Deploy from a branch**, Branch to **main**, and folder to **/ (root)**. Click **Save**.
4. Wait 1–2 minutes. Your app's address will be
   `https://YOUR-GITHUB-USERNAME.github.io/machinery-logbook/`

> GitHub Pages is free only for **public** repositories. A public repo is safe here: only the app's code is public. Your machines, logs, photos, and manuals are never uploaded. They stay on your phone.
> To make the repo public: **Settings → General → Danger Zone → Change visibility → Public**.

## Install on the phone
**Android (Chrome):** open the address above, then tap menu ⋮ → **Install app** (or **Add to Home screen**).
**iPhone (Safari):** open the address, then tap Share → **Add to Home Screen**.

Open it once while online. After that it works fully offline.

## Keep your data safe
Data lives only on that phone. Use **⚙ → Make backup file** regularly and save the file to Google Drive, email, or your computer.
Uninstalling the app or clearing the browser's site data deletes the records. Restore them with **⚙ → Restore from backup**.

## Updating the app later
After changing any file, open `sw.js` and change `machinery-logbook-v1` to `-v2` (then `-v3`, and so on). Upload the changed files.
Phones pick up the new version the next time the app is opened online, usually on the second launch.

## Test on your Mac (optional)
In Terminal, inside this folder, run: `python3 -m http.server 8000`
Then open http://localhost:8000 in Chrome or Safari.
