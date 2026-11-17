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
