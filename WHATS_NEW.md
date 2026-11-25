# Restalker - what's new

Every version that was released to everyone, newest first, and what changed in it. (Test versions that only the owner's devices ran are not listed on their own: their changes are under the version that first brought them to everyone.)

## Restalker for Windows (PC) - v159

v159: the fastest way first, a slower way only on proof, and back by itself. Buy Now is pressed as soon as the buy box is read again (v157/v158 waited on a suspicion); the slower way is only tried when pages without the product follow early presses, kept only when the numbers prove it, and ends by itself. The rest after 10 failed checkouts is 30 s (then 1, 2, at most 5 min) and a new restock, a tracker's alert or another price ends it at once (it was 5 minutes, whatever happened). The linked phone is told at every new try. Target: its data address is asked the way Target's own page asks, and a slowed reading pace must prove itself or it speeds up again (it was stuck at one request per 2 minutes).

The PC program updates itself; its title bar shows the version that is running.

## Restalker Mobile (Android)

### 1.1.2 (2026-11-24)

- Faster: while it watches, the app keeps one connection to the Restalker relay open. A restock another Restalker spots, and what your
  linked PC or devices say, now arrive the moment they are sent (measured: under a tenth of a second on the relay's side). Until
  1.1.1 the app asked once a minute.
- Lighter: the open connection replaces the once-a-minute asking - less data and battery, and the relay can serve thousands of
  devices on its free plan.
- Nothing to set up. If the connection drops, the app opens it again by itself and asks the old way meanwhile, so nothing is missed.
- "Active" (the number of Restalkers watching) now updates by itself whenever it changes.

### 1.1.1 (2026-11-23)

- New: Settings > Help and about > Your data.
  - **Save my data** writes one file (you choose where - Downloads is fine): your products with their caps and switches, your
    settings, your purchases, Activity, and the link with your other devices.
  - **Load my data** puts a saved file back, after asking. The app restarts itself, with watching off - press Start.
- Moving to the app from Google Play: Save my data, remove this app, install Restalker from Google Play, Load my data.
  (The two can not be installed over each other - that is why the file is needed.) You sign in to Amazon again afterwards.
- A file saved on another kind of phone loads everything except the link with your other devices - link that phone again.
- Loading an older file never forgets a purchase made since. A wrong or damaged file is refused and changes nothing.
- The file is yours and is sent nowhere. Keep it to yourself: it holds your device link.
- New setting, on by default: Settings > Amazon, buying and this phone > "Save battery when low".
  - Battery at 50% or less: this phone looks at each product every 5 minutes.
  - Battery at 25% or less: every 15 minutes.
  - Charging: no slowing down. With or without the PC running.
- A restock your PC or the crowd sees is still checked at once, and a checkout always runs - only the routine looks slow down.
- Switch it off to keep the full pace even when the battery is low. The Watch page says when it is slowing down.
- With Who buys = "Both", this phone opened a full Amazon page about every 16 seconds all day - while your PC was watching the same
  products. Now, while your PC is watching, this phone looks by itself only every 10 minutes (about 85% less work, battery and data).
- It still races the PC: when the PC (or another Restalker) sees a restock, this phone looks at once and can buy it.
- If the PC goes quiet, this phone goes back to its usual pace by itself.
- Re-arm now really re-arms: the "Bought" note goes away AND Buy is switched on. Until now Re-arm left Buy off, so the product stayed
  "just watching" (and a linked PC heard "Buy off").
- Switching Buy on for a product that was bought also re-arms it.
- A product re-armed on your PC is re-armed on this phone too: the "Bought" note clears here and the PC keeps it on Buy. Until now the
  phone kept its "Bought" mark, told the PC "Buy off", and the PC went back to watching.
- Still one order per product: a PC change made before it heard of this phone's purchase never re-arms it.
- The app is now made in two editions from the same program. The one on this page (GitHub) works as before and still updates
  itself. The new Google Play edition follows the store's rules: it never installs anything itself (Google Play updates it), and it
  does not ask Android to be let off battery saving with a pop-up - the Battery row opens Android's own list instead and says
  what to pick there.
- Both editions are now made for Android 16 (Google Play takes nothing older for a new app). Android 16 changed how the Back
  button reaches an app; the built-in Amazon browser was adapted, so Back there still goes one page back first.
- Fixed on Android 15 and 16: in the built-in Amazon browser the note and the Done button sat under the clock and the battery
  mark at the top of the screen (white on white), and the bottom of the page under the navigation bar. Both are clear now.
- Settings, Help and about has a new row: Privacy policy (what stays on your device, what is sent, and to whom).
- Problem reports from devices that are not the owner's no longer carry Amazon order numbers (they read "(hidden)").
- Google Play edition only: automatic problem reports are off until you answer one question on the Watch page (Yes, send them /
  No). It sits under "Getting started". The switch in Settings says exactly what a report holds.
- Google Play edition only: "Share the app" sends the Google Play link.
- Tested on Android 16 too now (5 virtual phones: Android 11, 13, 14, 15, 16), and the Google Play edition has its own full run.
- Your linked devices now talk to each other through the Restalker relay, not through the free message service (ntfy). That service
  allows a home 250 messages a day for all its devices together, and the linked devices' product updates alone used them up - then
  nothing got through for the rest of the day. The relay has no such limit. Everything is sealed with your link code on the device,
  as before: the relay cannot read it.
- One request a minute does the whole round: products and caps, buying choices, who is running, and the crowd's sightings.
- The crowd's sightings are shared and read on the relay too (and still on the old channel for versions that read only there).
- Your linked PC's own messages (a purchase there, something that needs you there) now show in this app as its own notification -
  within a minute, by the kinds you chose in Settings. If this phone also has the ntfy app and the PC has ntfy alerts set up, ntfy
  shows them (with their buttons) and the app does not repeat them. Needs PC v147.
- "Buy now" from the PC still rings at once over the open connection, and now also arrives with the next round if that is refused.
- ntfy stays for: alerts on a phone WITHOUT this app (Settings, Notifications says so), and a linked PC / phone / tablet that still
  runs an older version - it keeps working without updating; the others send it their updates the old way for as long as it is heard.
- If the relay cannot be reached, the link works over ntfy exactly as before.

### 1.0.5 (2026-11-20)

- Under the title: "3 stalkers active now" (was "3 Restalker devices active now") - everyone running Restalker and sharing is a
  stalker, hunting restocks together. The same words in Settings and in Words used.
- The number is counted in one place (the Restalker relay) and is the same on every device, whatever version it runs. Versions from
  before that count existed (the app up to 2.14, the PC up to v140) are now counted too, without updating - they are heard when they
  say hello to the crowd, every 6 hours. Only what those old versions SHOW is still their own older number, until they are updated once.
- Watch (note 1): a product that is NOT watched is never looked at all day - only once when you open the app (if its last look is
  over 6 hours old), or by your linked PC. Every such card now says how old its data is ("Off (not watched) · checked 7 h ago",
  "desktop saw it 20 min ago", "not looked at yet"). "In stock now" holds only what was seen for sale in the last 6 hours; an older
  sighting stays on its card in grey as "Was over cap · $72.10".
- Linked devices (note 2): "heard" was only the time of a device's last message on the free message service - and that service
  refuses a home's messages once its 250 a day are used, so a PC that was running looked silent for hours. Now every linked device
  also says "I am here" to the Restalker relay every 15 minutes (no message used; what it says is sealed with your link code), and
  Linked devices shows the newest sign of life: "running · heard 3 min ago". When product updates from the PC are not arriving, or
  the day's messages are used up, it says exactly that - with the time they start again.
- Fewer messages: automatic problem reports at most every 2 hours (was 30 minutes); when few of the day's messages are left the
  hourly "still here" and the automatic reports wait, so a product you change, a purchase and an alert still get through.
- A buying choice made on the PC (its new Linked devices list, PC v145) is taken here too.
- Watch hours (note 3): Settings, Amazon, buying and this phone - "All day" or "Set hours" (start and stop, in half-hour steps; over
  midnight works). Outside them the phone does not look or buy by itself and may sleep; Check now and Buy now still work, and a
  linked PC keeps going. For a hard look overnight: set the night's hours and choose Look every 60 s.
  "Watching all day allowed (battery)" now says what it is: "Android lets Restalker keep running in the background (battery setting)".
- Price drops (note 4): also at or under your cap when the product is watched but not set to Buy - with a Buy now button. (Set to
  Buy it is bought by itself; and the moment it comes under your cap is still the restock message, not a second alert.)
- The "Restalker is watching" notice (note 5): Android puts it up again each time watching starts (every update, every restart) and
  an app may not remove its own - a swipe only hides it until then. The notice now has a Hide button, and Settings, Notifications a
  row for it: both open the phone's own switch for exactly this notice. Switch it off there once and it never comes back; watching
  goes on. The row then shows a green check.
- Reports (note 6): plain words for everyone - "Send problem reports automatically", "Send a report now", "Report sent".
- The logo (note 7): half again as big on Watch (a bit less on a narrow screen or with big text, so the title keeps its lines), and
  the same navy bar - logo, the tab's name, the version - on Bought, Activity and Settings.
- Watch (note 8): the little switch is now a Stop / Start button, and the line under it says it: "Runs in the background - also
  after you close the app or swipe it away. Stop ends it."
- No PC yet (note 10): Settings, Linked devices shows the steps and "Send me the link" (e-mail it to yourself, open it on the PC).
  The PC then shows the code and the phone scans it - no camera needed on the PC - and the phone's products go to the PC.
- Fixed: claiming a product right before Place your order no longer waits for a link round that is talking to the network.
- ntfy.sh, the free message service the devices talk through, allows each home internet address 250 messages a day for ALL its
  Restalker devices together. Since your phones were set to "Both buy" (2.15) each sent "I'm listening" to the PC every 5 minutes -
  288 a day each - so the home's 250 were gone by morning; after that the PC's link, its reports and your phone messages were refused
  for the rest of the day. Now only a phone that buys INSTEAD of the PC sends it (the PC v144 counts the others by their hourly update).
- Fewer requests too: the link with the PC is read every 2 minutes (was every minute) and the crowd every 3 minutes (was 2). The PC's
  "in stock, look now" signal still arrives at once, and opening the app still reads the link at once.
- Watch: every year's bar says what is in it - "11 products · 4 watched · 2 set to buy".
- Watch: Watch and Buy buttons right on each product (amber = on). Buy on a product without a cap opens it so you can set the cap.
- Product page: a cap changed with the bar or - / + is held for 30 seconds from the first change, then saved and noted once
  ("cap $555.00 → $580.00") - no line per $5 step. "Save now" saves at once; a cap typed in the box (Done) is saved at once.
- Product page: the price bar runs from a bit under the lowest to a quarter over the highest price (price now, cap, MSRP, TCG and
  every store's current listing), so the marks spread out instead of bunching up.
- Product page: no Change cap or Practice checkout buttons (the cap is right at the bar; Practice checkout is in Settings).
- Settings: 6 parts instead of 13 - Linked devices; Amazon, buying and this phone; Notifications (with ntfy); Products; Community
  (Discord, the crowd, sharing the app); Help and about (problems, "Send a report", the words used, About).
- Activity: one entry per event - the product, then one line: what was seen, what was done and how it ended
  ("In stock $89.99 → checkout (2 tries) → missed (sold out)"). Reports are no longer listed there.
- A beta now shows a "b" after its version (v1.0.2b). Once you release it to everyone, the same copy shows v1.0.2 - nothing is renumbered.
- Share Restalker: under the QR code it now says what to do when the other phone blocks the install. Newer Samsung phones do that
  (Auto Blocker) even after you allow it: Settings > Security and privacy > Auto Blocker > off, install, then switch it back on.
- The download page starts with the download link and the install steps, then only that version's notes - no long list to scroll.
- From your next "Release to everyone" on, the download page (the QR code) always gives the version you released, never a beta.
- New numbering: this is 1.0.0 - it comes right after 2.15; the next ones are 1.0.1, 1.0.2 and so on. (Only the name starts again; every device still sees it as the newer version
  and updates to it as usual.)
- BETA: a copy that is newer than the version everyone has shows an amber BETA right after its version (the header and About). That is
  your devices, which get every version first. Once you release a version to everyone it is the standard one - no mark, on any device.
  The download page (the QR code under Share Restalker) now also gives the version everyone has, not the beta.
- Watch: "In stock now" folds like the years - tap its bar (it stays folded; its count stays on the bar).
- Bought: sort it with the amber choices - All (a bar per month), Product, Store, Device. Every group is under a navy bar with its count
  and what it cost together, and folds with the amber button.
- Activity: every day folds with a tap on its bar (the same navy bar and amber button as on Watch and Bought).
