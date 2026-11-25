# Restalker Mobile 1.1.7 (2026-11-25)
- Every linked device buys together (a hard rule): the default for "Who buys" is now "Both buy" - this device starts its checkout at the
  same moment as the PC and your other devices, and the first one at Place your order orders (never two orders). A device on which
  you chose "PC only" or "Instead of PC" keeps your choice. Until 1.1.6 the default was "PC only" (this device only watched).
- The fastest way is the default again: Buy Now is pressed as soon as the buy box has been read, while the page is still arriving.
  1.1.6 waited for the page to be read on a suspicion. Now the slower way is only TRIED when pages without the product follow early
  presses, kept only when the numbers prove it better (30 minutes, at most 8 hours), and the fastest way comes back by itself.
- The wait after checkouts that did not finish is short and never late for news: 30 seconds after three in a row, then 1, 2 and at
  most 5 minutes while they go on - and a new restock or another price ends the wait at once. Until 1.1.6: 10 minutes, whatever
  happened meanwhile. One message, not one per try.
- The diagnostics say how Buy Now is pressed now and why, and who buys.

# Restalker Mobile 1.1.6 (2026-11-24)
- A purchase starts when the product page has been read (its text is all there) - not while it is still arriving, as 1.1.5 did. On the
  PC, Amazon answered some purchases that began that early with pages without the product; the PC now measures whether that is the
  cause. The app still does not wait for pictures and scripts, or a fixed pause, as it did until 1.1.4.
- A page from Amazon that is not the product page is said so ("Amazon sent a page without the product") - not "out of stock".
- The checkout is the desktop's own, as of Restalker v157.

# Restalker Mobile 1.1.5 (2026-11-24)
- Amazon's purchase limit is recognised and kept PER SELLER: when Amazon's checkout says "you've reached the purchase limit for this
  item", that seller's offer is left alone for 6 hours; an offer from another seller at or under your cap is still bought. One
  message, no failed try counted, nothing locked - switch Buy off and on to try sooner. (Until 1.1.4 it was reported as "out of
  stock" and tried again.)
- Faster: the app looks at a product page while it is arriving and starts the purchase the moment the buy box has been read. Until
  1.1.4 it waited for the whole page (every picture and script), then a fixed quarter second.
- The checkout is the desktop's own, as of Restalker v156 (Amazon's limit note is understood the same way whether it appears at once
  or a moment later on the checkout page).

# Restalker Mobile 1.1.4 (2026-11-24)
- The Watch tab's banner is tidy: how many products are watched, and one button that says what it does - "Stop watching" /
  "Start watching". What Restalker is doing right now, how watching works in the background and the linked-desktop line moved to
  Settings > "Amazon, buying and this phone".

# Restalker Mobile 1.1.3 (2026-11-24)
- Watch all / Buy all: every group on the Watch tab has two buttons for the whole group. Buy all asks for the most you'll pay for
  the products that have no cap yet; stopping to watch a whole group asks first.
- Getting started folds to one line (and stays folded), says in a few words what each step is for, and links the setup guide.
  Every step is optional - for example, skip the Amazon sign-in if you want alerts only.
- Updates: the yellow line says in plain words where an update stands, shows a turning circle while it is fetched and installed,
  and its button reads "Install now".
- After an update the app opens again when its screen was open (Settings > Updates > "Open again after an update"). From Android 10
  Android allows that only with "Display over other apps"; without it you get a notification to tap. Watching continues either way.
- Fixed: red marks on products right after an update. A page that fails to load once is no longer flagged - only from the third
  look in a row.
- A restock line in Activity now says when NO notification could be shown on this device, and why (for example notifications
  switched off in the device's settings); a yellow line with a Fix button appears on the Watch tab in that case.

# Restalker Mobile 1.1.2 (2026-11-24; "send messages back to them in near zero latency")
- Faster: while it watches, the app keeps one connection to the Restalker relay open. A restock another Restalker spots, and what your
  linked PC or devices say, now arrive the moment they are sent (measured: under a tenth of a second on the relay's side). Until
  1.1.1 the app asked once a minute.
- Lighter: the open connection replaces the once-a-minute asking - less data and battery, and the relay can serve thousands of
  devices on its free plan.
- Nothing to set up. If the connection drops, the app opens it again by itself and asks the old way meanwhile, so nothing is missed.
- "Active" (the number of Restalkers watching) now updates by itself whenever it changes.

# Restalker Mobile 1.1.1 (2026-11-23; "i want to be able to save and reload all my data when i download the new app from the play store")
- New: Settings > Help and about > Your data.
  - **Save my data** writes one file (you choose where - Downloads is fine): your products with their caps and switches, your
    settings, your purchases, Activity, and the link with your other devices.
  - **Load my data** puts a saved file back, after asking. The app restarts itself, with watching off - press Start.
- Moving to the app from Google Play: Save my data, remove this app, install Restalker from Google Play, Load my data.
  (The two can not be installed over each other - that is why the file is needed.) You sign in to Amazon again afterwards.
- A file saved on another kind of phone loads everything except the link with your other devices - link that phone again.
- Loading an older file never forgets a purchase made since. A wrong or damaged file is refused and changes nothing.
- The file is yours and is sent nowhere. Keep it to yourself: it holds your device link.

# Restalker Mobile 1.1.0 (2026-11-22; "throttle the system based on battery life. Use 50% and 25% battery life as the steps")
- New setting, on by default: Settings > Amazon, buying and this phone > "Save battery when low".
  - Battery at 50% or less: this phone looks at each product every 5 minutes.
  - Battery at 25% or less: every 15 minutes.
  - Charging: no slowing down. With or without the PC running.
- A restock your PC or the crowd sees is still checked at once, and a checkout always runs - only the routine looks slow down.
- Switch it off to keep the full pace even when the battery is low. The Watch page says when it is slowing down.

# Restalker Mobile 1.0.9 (2026-11-21; "I think the app may be making my phone slower")
- With Who buys = "Both", this phone opened a full Amazon page about every 16 seconds all day - while your PC was watching the same
  products. Now, while your PC is watching, this phone looks by itself only every 10 minutes (about 85% less work, battery and data).
- It still races the PC: when the PC (or another Restalker) sees a restock, this phone looks at once and can buy it.
- If the PC goes quiet, this phone goes back to its usual pace by itself.

# Restalker Mobile 1.0.8 (2026-11-21; "I also have been unable to clear the 'bought' message on products. When I rearm something, it automatically switches back to just watching")
- Re-arm now really re-arms: the "Bought" note goes away AND Buy is switched on. Until now Re-arm left Buy off, so the product stayed
  "just watching" (and a linked PC heard "Buy off").
- Switching Buy on for a product that was bought also re-arms it.
- A product re-armed on your PC is re-armed on this phone too: the "Bought" note clears here and the PC keeps it on Buy. Until now the
  phone kept its "Bought" mark, told the PC "Buy off", and the PC went back to watching.
- Still one order per product: a PC change made before it heard of this phone's purchase never re-arms it.

# Restalker Mobile 1.0.7 (2026-11-20; "repackage the app into a compliant version" - Google Play)
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

# Restalker Mobile 1.0.6 (2026-11-20; "the ntfy messages aren't really needed anymore")
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

# Restalker Mobile 1.0.5 (2026-11-20; your 2 notes of 11:42 AM)
- Under the title: "3 stalkers active now" (was "3 Restalker devices active now") - everyone running Restalker and sharing is a
  stalker, hunting restocks together. The same words in Settings and in Words used.
- The number is counted in one place (the Restalker relay) and is the same on every device, whatever version it runs. Versions from
  before that count existed (the app up to 2.14, the PC up to v140) are now counted too, without updating - they are heard when they
  say hello to the crowd, every 6 hours. Only what those old versions SHOW is still their own older number, until they are updated once.

# Restalker Mobile 1.0.4 (2026-11-20; your 10 notes of 9:05 AM)
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

# Restalker Mobile 1.0.3 (2026-11-20; why your PC went "silent" every afternoon)
- ntfy.sh, the free message service the devices talk through, allows each home internet address 250 messages a day for ALL its
  Restalker devices together. Since your phones were set to "Both buy" (2.15) each sent "I'm listening" to the PC every 5 minutes -
  288 a day each - so the home's 250 were gone by morning; after that the PC's link, its reports and your phone messages were refused
  for the rest of the day. Now only a phone that buys INSTEAD of the PC sends it (the PC v144 counts the others by their hourly update).
- Fewer requests too: the link with the PC is read every 2 minutes (was every minute) and the crowd every 3 minutes (was 2). The PC's
  "in stock, look now" signal still arrives at once, and opening the app still reads the link at once.

# Restalker Mobile 1.0.2 (2026-11-19; your notes of 11:40 PM)
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

# Restalker Mobile 1.0.1 (2026-11-19; your notes of 9:39 PM)
- Share Restalker: under the QR code it now says what to do when the other phone blocks the install. Newer Samsung phones do that
  (Auto Blocker) even after you allow it: Settings > Security and privacy > Auto Blocker > off, install, then switch it back on.
- The download page starts with the download link and the install steps, then only that version's notes - no long list to scroll.
- From your next "Release to everyone" on, the download page (the QR code) always gives the version you released, never a beta.

# Restalker Mobile 1.0.0 (2026-11-19; your notes of 7:32 PM)
- New numbering: this is 1.0.0 - it comes right after 2.15; the next ones are 1.0.1, 1.0.2 and so on. (Only the name starts again; every device still sees it as the newer version
  and updates to it as usual.)
- BETA: a copy that is newer than the version everyone has shows an amber BETA right after its version (the header and About). That is
  your devices, which get every version first. Once you release a version to everyone it is the standard one - no mark, on any device.
  The download page (the QR code under Share Restalker) now also gives the version everyone has, not the beta.
- Watch: "In stock now" folds like the years - tap its bar (it stays folded; its count stays on the bar).
- Bought: sort it with the amber choices - All (a bar per month), Product, Store, Device. Every group is under a navy bar with its count
  and what it cost together, and folds with the amber button.
- Activity: every day folds with a tap on its bar (the same navy bar and amber button as on Watch and Bought).

# Restalker Mobile 2.15 (2026-11-19; your notes of 1:01 PM)
- The race to checkout: your phones and tablets buy at the same time as the PC (Who buys: both, set once on every device). The first
  of your devices to reach Place your order claims the product on the Restalker server and orders; the others stop - never two orders.
  This starts as soon as the PC knows the shared lock too (PC v141); until then "both" waits for the PC's own try, as before.
- Only your own devices (the master code, or linked to your PC) send their reports directly. Everyone else's go to the Restalker team on
  Discord, compiled once a day. "Send diagnostics" still only opens the share menu.
- Red marks on anything that needs you (not signed in, notifications or battery not allowed, an update waiting, a product whose checkout
  did not finish). They go away when you tap them and come back each day until done ("Remind me daily", Settings, Notifications).
- Settings: navy headers; what is inside sits in white; opening one part closes the others.
- Restalker on Discord (Settings): the invite, whether your restocks reach Discord, and an optional "Connect my Discord" (/link).
- Activity: a navy bar per day; only restocks, checkouts, purchases, price drops and problems.
- Watch: the years are navy bars that stay at the top while you scroll; the sets are white pills with an amber outline (look B).
- Check now, Check all now, Practice checkout and Run the practice show a turning circle until they are done.
- The header shows how many Restalker devices are active now (each working device checks in every 15 minutes).
- A product that is not on the Restalker list can be suggested for it (product page).
- From now on new versions reach your devices first; everyone else gets them when you say so.
- (your notes of 1:59 PM) A cap is needed only to buy. Watch works without one: a message when it is in stock at any price, and it
  is shared with the crowd. Buy (and Buy now on a message) asks for the cap first. Adding a product: leave the cap empty to only watch.
  (Products without a cap stay on this phone - the PC gets a product once it has a cap.)
- (your notes of 1:59 PM) Each time the app starts it looks once at every product not looked at in the last 6 hours - also the ones not
  watched - so their prices are there when you set a cap. For products not watched nothing is said, shared or bought.

# Restalker Mobile 2.14 (2026-11-19; from the phone's own reports overnight)
- Less data: between two looks the Amazon page is now closed and quiet every time (before, only when the next look was more than 20
  seconds away - with 8 products every 90 seconds the last page stayed open all night, its scripts and ads still working). Your phone
  used about 158 MB an hour on mobile data while it covered for the PC.
- The diagnostics now say where the data goes (Amazon looks, checkouts, orders, pictures, everything else), so any remaining waste
  can be found.
- When this phone stands by, Activity says the real reason ("another phone / tablet buys it") - 2.13 always said "the linked desktop
  buys it", even when the PC was off.

# Restalker Mobile 2.13 (2026-11-18; your notes of 5:41 PM)
- Activity shows only what matters. No more lines for the app starting, updates being installed (they showed up to 3 times), the
  watching notice being swiped away, or a device change waiting for the next link round. "Updated to v..." stays. (The hidden lines
  still go into the problem reports.)
- A restock is ONE line: what was seen, who spotted it, and what happens now - e.g. "Nauctis Secret Lair Bundle: in stock at $89.99
  (another Restalker spotted it first) - the linked desktop buys it (this phone stands by)." Before, that took three lines. A tip from
  the crowd that turned out to be sold out by the time this phone looked no longer adds a line.

# Restalker Mobile 2.12 (2026-11-18; your notes of 3:33 PM)
- Pull down to refresh: the circle keeps turning while it refreshes and goes away by itself when it is done. (Before, the refresh
  said "done" before the screen even saw it start, so the arrow froze where your finger left it until you changed tabs.)
- Settings -> Notifications: choose Recommended (all but price drops), All, Restocks only or Purchases only, or switch each kind
  on or off: restocks, price drops (new),
  purchases, and "when it needs you". The phone's messages and its ntfy alerts both follow your choice.
- Price drops (new, off until you switch them on): a watched product for sale over your cap that gets at least 5% (and $5) cheaper.
  A price bouncing back and forth alerts once, not every time.
- Settings is folded: each part is one line - its name and where it stands - and opens when you tap it.
- No constant "watching" notification: no icon, no sound, not on the lock screen, and it never changes. Android requires a notice while
  an app watches all day, so a single line stays at the very bottom of your notifications - swipe it away and it stays away, or tap
  "Hide the watching line completely" (Settings -> Notifications) and switch it off there. Watching goes on either way.

# Restalker Mobile 2.11 (2026-11-18; your notes of 1:18 PM)
- Product page: the big price is the LOWEST price seen in stock anywhere lately. When that is another store, it has a * and says where
  ("$639.20* @ Walmart"), with a line under it: buy it yourself there - Restalker buys on Amazon only (for now) - and Amazon's price.
- The cap box and the - / + buttons are about half their size (the same text size).
- "Latest" is the same size on every product; older lines scroll inside it, with an amber scroll bar.
- Other stores: Open on every store, in stock or not (to watch it there, or save it to a list).
- The logo in the header; the mascot (the dot with its eyes and hat) in About and beside "nothing here" notes.
- More amber on the Watch page: every year's fold button is an amber circle, every set's an amber ring, the chosen filter is amber -
  so the brand color stays in view while you scroll.

# Restalker Mobile 2.10 (2026-11-18; your notes of 12:22 PM)
- Price bar zoomed in: it runs from half the MSRP to twice the MSRP (rounded - e.g. $200 to $950 for a $455.88 box), so the labels
  have more room. A line under it says the range. A price outside it sits at that end; its label still shows the real price.
- TCG chart: the time axis shows only quarters ("Q3 '26", "Q4 '26"); low and high in whole dollars. Details: the TCG link.
- Best price: only a price seen in stock in the last 2 hours counts. Before, a store the PC no longer looked at kept its last price -
  the Star Trek box showed Walmart $369.99, the price the PC bought it at there.

# Restalker Mobile 2.9 (2026-11-18; your notes of 8:09 AM)
- "Bought 2×" next to each product's picture (the list and the product page) - every linked device's purchases counted.
- TCG chart: prices along the left side, and your cap is always drawn as a labelled dashed line (the chart's range takes it in).
- Updates: Settings says exactly whether Android will still ask on this device. Android 12+ installs an update by itself only when
  Restalker installed the app last time - a copy installed from the download page asks once more (tap Install), then never again.
  Android 11 and older always ask. The PC shows it per device too.
- ntfy alerts: Settings -> "Alerts on any device (ntfy)": one alert code for you - your PC's when linked, otherwise made here and the
  same on every phone / tablet you link. Subscribe to it in the free ntfy app on any device (code, QR or "Open in ntfy"); this device
  sends its restocks, purchases and "needs you" alerts there (restocks your PC also alerts about are not sent twice).

# Restalker Mobile 2.8 (2026-11-18; your notes of 10:58 PM)
- Pictures without a PC: the central list now carries each product's picture (TCGplayer's photo of exactly that product - the same one
  the PC shows), so a new phone shows them at once, with nothing to open. Products without one get their picture from their own Amazon
  page, now also while no product is switched on.
- TCG price chart on every product page, for every phone (linked to a PC or not): TCGplayer's own market price of the last 3 months,
  read once a day at an off-peak hour (~2 AM Pacific) for the whole central list and published as one small file the phones read.
  Phones without a PC also get the TCG market price and the link to the product's TCGplayer page.
- Price bar: the right end is the highest price seen (every store's price and TCG's highest of the last 3 months) plus a little room -
  a line under the bar says which price; green turns to red more gradually (a soft warm middle, green and red further apart).
- The contributor count is right at once: the first read of the crowd channel goes back 12 hours (everyone says hello every 6), and the
  count is read every time you open the app - also when not watching.

# Restalker Mobile 2.7 (2026-11-17; your notes of 8:35 PM)
- Fair play: each product is bought at most 3 times ever, counting every linked device (the PC and your phones / tablets). After that
  it is still watched and shared with the crowd, but not bought. The owner's own devices have no limit (recognised by themselves; a
  device linked to the owner's PC too; spare: Settings -> About -> Master code).
- Product page: a chart of the TCG market price over time (what the linked PC recorded), with your cap as a dashed line.

# Restalker Mobile 2.6 (2026-11-17; your notes of 6:50 PM)
- The Restalker crowd: every Restalker that shares (phones and PCs) tells the others when it sees a product of the central list come in
  stock - only the product, store, price and time (nothing personal). A sighting from the crowd makes this phone look itself at once;
  it never buys on the crowd's word. Settings -> "Share with the Restalker crowd" (on by default).
- Under the title: "XXX total contributors and counting" (every device heard on the crowd channel so far).
- Settings -> About: why sharing helps everyone.
- New logo (DH-2): the app icon and the notification icon.
- The price bar is drawn without a gradient shader (the same look) - the Android 13 / 14 test phones froze on it.

# Restalker Mobile 2.5 (2026-11-17; your notes of 6:50 PM)
- New look (A+): the logo's navy and amber - a navy header, amber buttons and switches, a slightly darker navy-tinted background so
  the white boxes stand out. Green tint = good to go (in stock under your cap), red tint = no good (over the cap).
- Price bar: one gradient from dark green (cheap) to dark red (very expensive) - green up to the MSRP, light around it, red past the
  TCG price; a white marker shows the price now, the amber knob your cap.
- Product page: "Best price: $___ @ store" - the cheapest place that has it in stock right now, with a link to that store.

# Restalker Mobile 2.4 (2026-11-17; your notes of 5:55 PM)
- FIX: the update question no longer comes back in a loop. When Android asks you to confirm an update (or Play Protect wants to scan
  it), it asks at most once every 6 hours for that version; Update now asks at once. The notice clears when the question is answered.
- Bought: every purchase of every linked device - this one, the PC and your other phones / tablets - with the time, price, store, the
  device and the browser that bought it, and the total.
- Product page: a Bought section (above Latest) with that product's purchases; the TCG price opens its TCGplayer page.
- Price bar: each label's real width is measured (bigger text in the phone's settings included), so the numbers never overlap.

# Restalker Mobile 2.3 (2026-11-17; your notes of this afternoon)
- Each phone or tablet now decides its own buying. Settings -> Linked devices lists the PC and every linked phone / tablet (name,
  version, when it was last heard). Per device: PC only (watches, does not buy - optionally "Buy if the PC stops"), Both buy, or
  Instead of PC. You can change another device's choice from here too; changing one never changes the others. Rename this device there.
- Swipe left / right to move between Watch, Bought, Activity and Settings.
- Pull down on any of those to refresh (reads the PC link and looks for an update - it does not re-check every product).
  Watch has a "Check all now" button for that.
- Pull down on a product's page: it is looked at again right now (and the PC link is read for the other stores).
- Price bar: the fixed prices ($0, MSRP, TCG, now) sit above the line, on separate rows so they never overlap; the cap and its knob
  sit below the line on their own.
- Cap box: type an exact cap next to the - / + buttons and tap Done.

# Restalker Mobile 2.2 (2026-11-17; your notes of this morning)
- Who buys on Amazon (Settings, when linked): PC (as before - this phone only takes a backup look every 30 minutes, the "next look in
  12 min" you saw), Phone (the PC keeps watching and tells this phone within seconds; this phone checks it and checks out) or Both (both
  try; right before Place your order this phone waits for the PC's own try and orders only if that try failed - never two orders).
- Product page: change the cap right at the price bar - drag the cap mark, or tap - / + ($5 steps). Other stores are at the bottom now;
  a store that has it in stock gets Open (that store's page in your browser - you buy it there yourself).
- New installs start in buying mode (not practice).
- Get all products (Settings): every Magic product Restalker knows, with its links - no caps, nothing watched until you switch it on.
- A product without a cap asks for one before Watch / Buy goes on.

# Restalker Mobile 2.1 (2026-11-17)
- Battery: while the PC buys, the phone is no longer kept awake at all; it is woken for a moment every 15 minutes instead, and when
  the PC has stopped, it takes over within those 15 minutes.
- After an update, the PC learns the app's new version at once.

# Restalker Mobile 2.0 (2026-11-17; your notes of last night)
- Pictures: the PC's picture first (the PC checks each picture against the product) - the phone shows the same pictures as the PC;
  its own Amazon picture only when not linked or the PC has none. (1.9: the main picture first, a badge picture never kept.)
- Product screen: the picture large, the name under it; the price bar has ticks for $0, MSRP, TCG, your cap and the price now - green
  under the cap, red over it.
- Practice checkout without the PC: Settings -> Practice checkout - paste ANY Amazon item that is in stock (a cheap one is fine); it
  goes to "Place your order" and stops. Practice on a product of yours now also works when it is over the cap.
- The watching notice can be swiped away (it stays away; watching goes on). While the PC buys, the phone is on standby: no icon, it
  may sleep between looks, and the page is closed between looks - much less battery.
- Several phones / tablets: link each one with the same desktop code - all stay the same and only one of them buys (the PC first).
  1.4-1.9 could take a second phone for the desktop.
- Share: Settings -> Share Restalker shows a QR code of the download page.
- FIX: Buy 2 / Buy 3 was forgotten at every start since 1.8 (and a linked PC then got "Buy 1" back from the phone). Found by the
  phone-and-PC link test.

# Restalker Mobile 1.9 (2026-11-17; from the app's own report)
- Pictures: on some pages a small badge came before the product picture, and its picture (which Amazon's picture server does not
  even have) was kept for 7 products. The page's main picture is taken first now; a picture kept for two different products, or
  missing on Amazon's picture server, is dropped by itself and the right one looked for again.

# Restalker Mobile 1.8 (2026-11-16; from the app's own report)
- Pictures: the 1.7 report showed Amazon's pages give their pictures in forms 1.7 did not accept (28 products without one). Every
  form of an Amazon product picture is taken now, kept in one plain form, and downloaded small.

# Restalker Mobile 1.7 (2026-11-16; includes 1.6)
- Updates by itself: new versions download, are checked, and install on their own (never during a purchase). Android asks you once
  to allow it (Allow) - after that, no more downloading. (Android 11 and older: also a tap on Install for each update.)
- Pictures fixed: 1.5/1.6 kept Amazon's grey placeholder dot from the phone page instead of the product picture. The real picture is
  taken now, downloaded by the app itself; the desktop's picture is shown when the phone has none.
- Self-check: the app sends a report by itself when something is wrong (pictures, watching stopped, desktop link, data use,
  a product marked bought without an order, a crash, a failed update).

# Restalker Mobile 1.6 (2026-11-16; from your report)
- FIX: a product the desktop had bought BEFORE the link (weeks ago) was marked "Bought" here, and the phone told the desktop "Buy off".
  Now only a purchase since the link counts; such products are corrected once by themselves.
- Report a problem asks "What went wrong?" (optional) - your words go first in the report.
- Pictures also for products that are not watched (one look each, on Wi-Fi).
- Less data: while the linked desktop buys, the phone looks every 30 minutes (was 10; about 80 MB an hour) - the desktop's view shows meanwhile.

# Restalker Mobile 1.5 (2026-11-16; owner's notes on 1.4)
- Product pictures: Amazon's own picture, read from the product's page the first time it is looked at.
- "In stock" = for sale at any price (also just over the cap); new "Watched" filter.
- Buy 1 / 2 / 3 on the product screen (separate orders, as on the desktop) - the same on both when linked.
- Running icon: Settings -> Running icon off = no icon in the status bar (the notice is tucked away; Android requires one while watching).
- Linked: what the desktop sees now (stock, price, when), the MSRP, the TCG price and the product's other stores; the desktop's names.

# Restalker Mobile 1.4 (2026-11-16)
- New look: four tabs (Watch, Bought, Activity, Settings), a screen for each product, an "Add a product" sheet.
- Home screen grouped like the desktop: release year, then set; each folds with a tap (and stays folded). "In stock now" on top.
  Filters: All / In stock / Set to buy.
- Link with the desktop (Settings -> Scan the desktop's QR code; desktop v124 "Link phone"): products are the same on both (add,
  remove, price cap, Buy, Watch); only one buys - the desktop while it runs, this phone when the desktop is stopped or silent 45 min.
  While the desktop buys, the phone looks every 10 minutes as a backup. Buy now on a phone message still buys at once on the phone.
  Encrypted; no passwords shared.

Restalker Mobile 1.3 (Android).

- NEW: Report problem (top of the Products tab, and in the Log tab): the app's log is sent as a report. Automatic reports (on): when a
  checkout does not buy or something needs you, a report is sent by itself (at most every 30 minutes).
- The log now keeps Amazon's own words at every checkout step.


- Watches your Amazon products on its own, all day, and buys a product set to Buy when it is in stock at or under your cap
  (the desktop Restalker's own Amazon checkout). One purchase per product; Re-arm to buy again.
- Practice mode (on at first) stops every checkout right before "Place your order"; switching it off asks first.
- Getting-started steps, a Test message button, Send diagnostics, Wi-Fi only, data used shown.
- Messages: restock (Buy now / Open / Snooze 3 h), bought (only after it is found in your Amazon orders), practice, and "needs you"
  (sign in, robot check - never answered by the app, shipping over $20, no internet).
- Share a product from the Amazon app to Restalker (short a.co links are followed).

Install: open this file on your Android phone and allow "Install unknown apps" for your browser when Android asks.
