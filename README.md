# Ships-PMS — Ship PMS (Android app)

A planned maintenance app for ships that works fully offline. It covers ship maintenance only (not the full SMS).

## Several ships
Keep records for every vessel you have served on. Tap the **ship name at the top** (or ⋮ → *Switch / add ship*) to switch ships or add a new one (name + IMO number).
Each ship has its own structure, jobs, work done, certificates and documents.

**Lock a ship** (⋮ → *Lock this ship*), for example when you sign off. All its records become **read-only**: you can still view and share them, but nothing can be added, changed or deleted.
You can set a PIN so that only you can unlock it. ⋮ → *Unlock this ship* reverses it.
The lock protects against accidental or unauthorised changes in the app. It does not encrypt the data on the phone.

## How it is organised
**Ship structure (left side)** – a tree of your ship:
`Main group → Sub-group → Item`, as deep as you need. Example:
`Engine Room › Main Engine › Fuel Injection Pumps`.
On a phone the tree slides in from the left (☰). On a tablet or in landscape it is always visible.
A red dot shows where something is overdue.

The top row of the tree is the ship's name. Select any level of the tree. The four tabs then show everything for that part of the ship and everything below it:

| Tab | What it shows |
|---|---|
| **Documents** | PDF manuals, drawings and certificates attached to the items |
| **Schedule** | Maintenance jobs, sorted by the next due date. Two rows of filters (see below) |
| **Work done** | History of work carried out: date, who did it, running hours, remarks and **photos** |
| **Certificates** | Ship and equipment certificates, with expiry and annual survey dates and a scan or photo |
| **Spare parts** | Spare parts inventory: stock, minimum level and store location, linked to the units they fit |

## Schedule filters
- **Type:** All open jobs · Recurring · One-time · Completed one-time
- **Due by:** Any time · Overdue · Due today · Within a week · Within a month · Within a year.
  Each "due by" list also includes overdue work, so it is the full list of what must be done by then. The number on each filter is how many jobs it contains.
- The search box filters further by text.
- **Share this list** (share icon at the top) sends the current list as text, e.g. by WhatsApp or email.

Running-hours jobs are placed in the "due by" lists assuming the machinery runs 24 hours a day, so the estimate is never later than the real due time.

## Maintenance jobs
- **Repeats** dropdown: *One-time*, *Recurring – by calendar* or *Recurring – by running hours*.
- **How often** dropdown: Daily, Weekly, Every 2 weeks, Monthly, Every 2 months, Quarterly, Every 4 months, Half-yearly, Annually, Every 18 months, Every 2 / 2½ / 3 / 4 / 5 / 10 years. For running hours: every 50 h up to 30,000 h. **Custom…** lets you enter any number.
- **One-time** jobs (e.g. a defect found during the weekly inspection) have a due date. Recording the work done marks them completed.
- *Record work done* on a job saves it to the history and moves the next due date forward.
- *Log work* records unplanned or corrective work.
- **At least one photo is required** before a work report can be saved (camera or gallery). Photos are resized to save space.
- Each item can keep a running-hours counter. Tap **Hours** in the header to update it.

## Spare parts inventory
- Each part has a name, part number, maker/drawing ref., store location, quantity in stock, minimum stock, unit and optional photos.
- **Link a part to one or more units**. For example, the same fuel filter can be linked to Generators No.1, No.2 and No.3. Select a unit in the tree to see only its parts. Select the ship to see all parts.
- Shown as a **table**: Part name · Part no. · In stock · Min. · Unit · Location · Maker/ref. · Used on. Swipe sideways to see all columns.
- **Tap a column heading to sort by it**, and tap it again to reverse the order (▲/▼). The app remembers your choice. Low stock shows in amber and out of stock in red.
- Tap a row for details and Take out / Receive. Long-press a row for more options. Sharing the list sends the table as text.
- Filters: All · Low stock (at or below minimum) · Out of stock · Not linked to a unit. Search by name, part number, maker, location or unit.
- **− Take out / + Receive** updates the stock, and each change is kept in the part's **stock history**.
- **Work reports can list the spare parts used.** Saving the report takes them out of stock automatically. Editing or deleting the report puts them back.
- Deleting a unit only removes its links. The parts stay in the inventory.

## Certificates
Name, number, issued by, issue date, expiry date and next annual survey/endorsement date. You can attach a PDF scan or photos.
Status: **Expired**, **Survey overdue**, **Due soon** (within 30 days) or OK. Filters: Expired/overdue · Within 30 days · Within 90 days.
Certificates added while "Whole ship" is selected are ship certificates. Certificates added while an item is selected belong to that item.

## Other
- Upload PDFs, or Share/Open a PDF from email/WhatsApp/Files into the app.
- Built-in PDF reader with zoom and go-to-page.
- **Backup to file / Restore from backup** (⋮ menu). The backup includes photos, certificates and documents. All data stays on the phone, so make backups regularly.
- Updating from Ship Docs v1 keeps your PDFs (under "Imported documents").

## Get the APK
Every push builds the app with GitHub Actions. Sign in to GitHub, open the **Actions** tab, open the latest **Build Android APK** run, and download **ShipDocs-apk** under *Artifacts*. Unzip it and install `app-release.apk`.

Requires Android 8.0 or newer.

## Build locally
`./gradlew assembleRelease` with the Android SDK and JDK 17, or open the project in Android Studio.
