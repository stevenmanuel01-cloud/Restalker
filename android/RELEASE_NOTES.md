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
- Self-check: the app sends a report to Claude by itself when something is wrong (pictures, watching stopped, desktop link, data use,
  a product marked bought without an order, a crash, a failed update).

# Restalker Mobile 1.6 (2026-11-16; from your report)
- FIX: a product the desktop had bought BEFORE the link (weeks ago) was marked "Bought" here, and the phone told the desktop "Buy off".
  Now only a purchase since the link counts; such products are corrected once by themselves.
- Report a problem asks "What went wrong?" (optional) - your words go to Claude first.
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

- NEW: Report problem (top of the Products tab, and in the Log tab): the app's log goes straight to Claude. Automatic reports (on): when a
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
