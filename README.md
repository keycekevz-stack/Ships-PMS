# Ships-PMS — Ship PMS (Android app)

A planned maintenance app for ships that works fully offline. It covers ship maintenance only (not the full SMS).

## How it is organised
**Ship structure (left side)** – a tree of your ship:
`Main group → Sub-group → Item`, as deep as you need. Example:
`Engine Room › Main Engine › Fuel Injection Pumps`.
On a phone the tree slides in from the left (☰). On a tablet or in landscape it is always visible.
A red dot shows where something is overdue.

Select any level of the tree. The three tabs then show everything for that part of the ship and everything below it:

| Tab | What it shows |
|---|---|
| **Documents** | PDF manuals, drawings and certificates attached to the items |
| **Schedule** | Planned maintenance jobs with status: **Overdue**, **Due soon** or OK. Filter by status and sort by urgency |
| **Work done** | History of work carried out: date, who did it, running hours and remarks |

## Maintenance jobs
- Intervals by calendar (days / weeks / months / years) or by **running hours**.
- *Record work done* on a job saves it to the history and moves the next due date forward.
- *Log work* records unplanned or corrective work.
- Each item can keep a running-hours counter. Tap **Hours** in the header to update it.

## Other
- Upload PDFs, or Share/Open a PDF from email/WhatsApp/Files into the app.
- Built-in PDF reader with zoom and go-to-page.
- **Backup to file / Restore from backup** (⋮ menu). All data stays on the phone, so make backups regularly.
- Updating from Ship Docs v1 keeps your PDFs (under "Imported documents").

## Get the APK
Every push builds the app with GitHub Actions. Sign in to GitHub, open the **Actions** tab, open the latest **Build Android APK** run, and download **ShipDocs-apk** under *Artifacts*. Unzip it and install `app-release.apk`.

Requires Android 8.0 or newer.

## Build locally
`./gradlew assembleRelease` with the Android SDK and JDK 17, or open the project in Android Studio.
