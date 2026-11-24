# Restalker - Guide

How to set Restalker up and what everything on its page does. The newest changes: [the phone app](android/RELEASE_NOTES.md) and the notes shown in the program when it updates.

## What Restalker does

Restalker watches Magic: The Gathering products at Amazon, Best Buy, Target, Walmart and GameStop, and buys a product by itself the moment it restocks at or under your price cap. You get a message only when something is bought or something needs you.

## First start

Restalker runs on a Windows PC. Setup takes about 10 minutes, once. (Where to get it: [Get the PC program](README.md#get-the-pc-program-windows).)

1. Install Python (free) from [python.org/downloads](https://www.python.org/downloads/). During the install, tick **Add python.exe to PATH**.
2. Unzip the Restalker folder anywhere, for example Documents\Restalker.
3. Double-click **setup_first_time.bat** once. It installs what the program needs.
4. Double-click **start_app.bat**. The program opens in your browser. Leave the small black window in the taskbar open: it is the program.
5. Press **Start watching**.

Then set up buying:

1. Sign in to each store you want to buy from: press **Sign in** above that store's column, sign in in the window that opens, tick *Keep me signed in*, then press **Done**.
2. Save your stores' sign-ins under **Sign-ins** (top bar). They are encrypted on your PC, so the browsers can sign themselves back in.
3. Save your card's security code under **Card code** (top bar). Checkouts then never wait for you.
4. On the **Products** tab, set a **Price cap** for each product: the most it may ever pay for the item.
5. Choose **Buy** next to a product (or a whole group). It is bought automatically at the next restock at or under the cap.

## Setup checklist

Tick each one as you go. When all are ticked, Restalker can buy by itself.

- [ ] Python installed, with *Add python.exe to PATH* ticked
- [ ] **setup_first_time.bat** run once
- [ ] **start_app.bat** opens the page, and **Start watching** pressed
- [ ] Each store you buy from signed in (**Sign in** above its column - the column then shows a green tick)
- [ ] Store sign-ins saved under **Sign-ins** (top bar)
- [ ] Card security code saved under **Card code** (top bar)
- [ ] Phone set up: ntfy app installed, QR code under **Phone** scanned
- [ ] **Send a test** pressed - the message arrived, and **Test this button** on the phone answered *Done*
- [ ] A price cap set for each product you want (an amber note under a cap means it is below MSRP + 5 %)
- [ ] **Buy** chosen for the products you want
- [ ] Chrome Remote Desktop set up, so you can answer a card code or a store check while away
- [ ] The PC set never to sleep while Restalker runs

## The top bar

The top bar has three rows. It stays at the top of every tab.

| Row | What is there | What to do with it |
| --- | --- | --- |
| Watching | Running / Stopped, Start watching, Stop, how many restocks and tracker alerts were seen, Speed, Gaming, Diagnostics, Report problem | Start watching once after opening. Speed sets how much of the PC it may use. Download diagnostics when asked for help. |
| Buying | Buying ON/OFF, the buying status, Purchases, Problems, Sign-ins, Card code | Buying OFF = nothing is bought. To stop everything at once, press Stop (Watching row): no browser buys anything from that moment, and nothing is disarmed. |
| Alerts | Phone, Browser alerts, Send a test, Show log | Phone sets up messages on your phone (scan the QR code with the free ntfy app). Send a test checks that they arrive. |

While a checkout runs, an orange bar under the top bar shows the store, the product and each step.

Next to the counts, a small dot shows how the stores are being read: green *all stores read*; amber *slowed* when a store refused faster reads (all its products are read a little slower for a while); red *stalled* when none of a store's products has been read for minutes. It only shows what holds for a whole store, never one product. A store stalled for 15 minutes is also sent to your phone (at most once an hour) and listed under Problems.

At the very top, next to the version: **Check for updates**, and **Phone**, **Copy** and **Help**.

## The tabs

**Products** is where you decide what to buy. Each product has three choices:

- **Off**: not watched.
- **Searching**: watched, never bought.
- **Buy**: bought by itself at the next restock at or under its price cap. Once bought it stops; tap *Buy one more* for another.
- **Show checkout**: while a checkout runs, this button (top banner, or the product's square) puts the live checkout page on your screen. Nothing is pressed for you. Use **Hide** to put it away (it also goes away by itself 5 minutes after the checkout ends). Closing that window stops that checkout.

Each store has its own column with a switch, and a square per product showing the store's latest answer and its price. Green = in stock.

Some products are sold on more than one page at the same store. A square can hold up to 4 pages of the product: press **+ another link** under the first one. Each page gets its own switch and square, marked *another page*, and **change** or **remove** acts on that page only. All pages are watched and bought, but still only one of the product is bought in all. When a tracker watches another Amazon page of one of your products, Restalker adds that page by itself and marks it *FOUND ON A TRACKER'S PAGE*. It is bought only after its own page shows this exact product. Press **NO - wrong** and it is removed and never added again.

Click a product's name to see its restocks, newest first: date and time, store and price, and **Bought** or **Not bought** with a short reason (over your cap, sold out at checkout, the card code was asked, one already bought, set to Searching...).

A price cap below the product's MSRP + 5 % gets a small amber note under it: a store pricing it a little above MSRP is then not bought. The note only points it out; your cap is never changed.

**Trackers** lists the restock trackers (TYPA, TrackaLacker, HotStock, NowInStock, Discord), grouped by how they reach the program, each with an on/off switch. Trackers only make the program look sooner; it always buys from its own reading of the store. A switch changed while watching takes effect at the next Start watching.

**Browsers** lists every browser the program runs, what it does now, what it costs your PC and how many purchases it made. On a slower PC, switch off some of the other buying browsers (Chrome, Edge, Hidden Chrome, Firefox, Camoufox) to save processor and memory. The buying browser and the store reader always stay on.

## Phone messages and their buttons

Install the free **ntfy** app, press **Phone** in the top bar and scan the QR code. Messages come only for a purchase or a problem (restock messages are off; switch them on under Phone if you want them).

| Message | Buttons | What a tap does |
| --- | --- | --- |
| Bought | Buy one more, store orders | Buy one more asks "Are you sure?" once, then sets the product to buy one more at the next restock |
| Shipping over the limit | Approve, Do not buy | Answers the waiting checkout (it waits up to 10 minutes) |
| Order result unclear | It was bought, Not ordered - buy again, store orders | Settles the order; *Not ordered* asks once more because it buys again |
| Card code, password or a person check | Open Remote Desktop | Opens Chrome Remote Desktop: the window is already open on the PC. These are never done by phone message |

Every button works once, for its own product, for a limited time. The PC answers each tap ("Done" or "Could not - why"). Press **Send a test**, then **Test this button** on the phone, to check that taps reach the PC.

Keep your phone's ntfy name private: whoever can read your messages could also press their buttons.

**Send a test** also checks your Discord channel, without posting anything there, so nobody watching the channel gets a message. Each copy of Restalker posts its real restocks under its own name (*Restalker · abc123*).

## Purchases and Problems

**Purchases** (top bar) lists every purchase, newest first: when, store, product, price, order number, which browser bought it, and a link to that store's order list. It is kept for good in **purchases.csv** in the Restalker folder.

**Problems** (top bar) shows only what needs you right now, each with its button: shipping waiting for your OK, a card code, a store's person check or password, an order whose result is unknown, a store you are not signed in to. A solved problem disappears by itself, so the button goes back to *Problems: none*. Below it are the earlier problems and, folded away, the routine activity (checkouts, sold out, tried again).

## Copying Restalker

Press **Copy** in the top bar and choose one of two copies. Both download as one zip.

|  | For a new user | For me on another PC |
| --- | --- | --- |
| Program | yes | yes |
| Product list (links, price caps) | your choice | yes |
| Tracker list | yes | yes |
| Table switches, settings, what is set to Buy | no | yes |
| Phone and Discord set-up | no (Discord channel: your choice) | yes |
| Purchases and history | no | yes |
| Store sign-ins and card code | no | no: they are locked to this PC; type them in once on the other PC |
| Browsers' sign-ins | no | no: sign in to each store once on the other PC |

On the other PC: unzip, double-click **setup_first_time.bat** once, then **start_app.bat**. Read **START HERE.txt** in the zip.

Run Restalker on one PC at a time: two PCs buying the same products can buy one each. Keep the *other PC* copy private; it holds your phone and Discord names.

Updates contain only program files, never data, so either copy takes the same updates.

Next to the version number at the top is the update button. It says **Check for updates**: press it to look right away, or let Restalker look by itself once an hour. When a new version is ready it turns green and says **Update available (vNNN)**. Press it to install: only program files are replaced, and your products, caps, settings, sign-ins, card code and purchases are never touched (a copy of them is put aside first). Restalker closes and starts again by itself in about a minute. **Go back** (in Help) returns to the version before. It never installs during a checkout.

## The phone app and linked devices

Restalker Mobile (Android) watches Amazon on its own and buys there too. Link it with the PC and both hold the same products, and only one of them orders a product.

1. On the PC press **Phone** (top right). It shows a code.
2. In the app: **Settings** > **Linked devices** > **Scan the desktop's QR code**, and point the phone at the code. (No camera? **Type the code**.)
3. More phones or tablets: scan the same code with each.

Started with the phone first? In the app, **Settings** > **Linked devices** > **Send me the link**, open it on the PC, install Restalker there, then do the three steps above. The phone's products go to the PC at the first link.

**Phone** on the PC lists this PC and every linked device: its version, when it was last heard, and its buying choice, which you can change there or in the app:

| Choice | What that device does |
| --- | --- |
| PC only | Watches and tells you; it buys only if the PC stops (unless you switch that off) |
| Both buy | Buys too, alongside the PC. The first to reach Place your order orders; the other stops |
| Instead of PC | Buys on Amazon in place of the PC, which keeps watching every store and tells it within seconds |

"Heard" is the device's newest sign of life: each device says "I am here" every few minutes. The link is encrypted on your devices (the Restalker relay that carries it cannot read it); no passwords, addresses or orders are shared.

**Alerts on your phone - two ways:**

| Way | What you get | Sign-up |
| --- | --- | --- |
| The Restalker app, linked (best) | The app's own alerts and this PC's (a purchase, something that needs you), within a minute | None: scan the code, done |
| The free ntfy app (any phone, no Restalker app) | This PC's messages, with buttons (Buy one more, Approve ...) | **Alerts** > **Phone: set up**: install ntfy, scan the code shown there, press **Send a test** |

With both on one phone, ntfy shows the PC's messages and the app does not repeat them.

**Which phones:** the app needs Android 8 or newer (2017 and later). On an older phone (Android 5-7) use the free ntfy app instead (the second way above). A phone that cannot scan a QR code: open the download link on it - text or email the link to that phone - and in the app use **Type the code** to link.

**In the app (1.1.3):** every group on the Watch tab has **Watch all** and **Buy all** (Buy asks for the most you'll pay where no cap is set). **Getting started** folds with a tap; every step is optional - skip the Amazon sign-in to get alerts only. After an update the app opens again by itself where Android allows it (**Settings** > **Updates**).

**Amazon restocks are seen in Amazon's "All buying options" list first** (PC v152): Amazon's own offer shows there before the main page does. An alert from that list opens the page with the list - add Amazon's offer to the cart there.

**Keeping the app's data (a new phone, or moving to the app from Google Play):** in the app, **Settings** > **Help and about** > **Save my data** writes your products, caps, settings, purchases and Activity into one file you keep. **Load my data** puts it back; the app restarts with watching off. The app from Google Play and the one from the download page cannot be installed over each other, so: Save my data, remove the app, install the other one, Load my data, sign in to Amazon again. Keep the file to yourself - it holds your device link. A file from another kind of phone loads everything but the link; link that phone again.

## Words used

| Word | What it means |
| --- | --- |
| Off / Searching / Buy (app: Watch / Buy) | Not watched / watched and you are told / bought by itself at or under your cap |
| Price cap | The most it ever pays for the product. Needed only to buy |
| In stock | For sale at or under your cap. In the app, "In stock now" = seen for sale in the last 6 hours |
| Over cap | For sale, but above your cap - not bought |
| MSRP / TCG | The published price / the TCGplayer market price, for comparing |
| Trackers | Restock trackers only make Restalker look sooner; it buys only what it reads at the store itself |
| The Restalker crowd | Every Restalker that shares tells the others when it sees a listed product in stock (product, store, price, time - nothing personal) |
| Stalkers active now | Every PC, phone and tablet running Restalker and sharing right now - all hunting restocks together. The same number on every device |
| Missed (sold out) | It sold out before the order went through. Nothing was bought |
| b (as in v147b or 1.0.6b) | A beta: a version only the owner's devices have until it is released to everyone |

Fair play: Restalker buys each product at most 3 times, counting every linked device, so restocks go to players rather than resellers.

## When something looks wrong

| You see | Do this |
| --- | --- |
| A red notice that some files are OLD | Copy ALL files of the newest update into the folder, close the black window, open start_app.bat again |
| Problems shows a number | Open Problems and press the button next to each one |
| "Amazon's purchase limit reached (seller ...)" | Amazon decides how many of an item one account may buy from one seller. Restalker leaves that seller's offer alone for 6 hours and still buys an offer from another seller at or under your cap. Nothing is locked. To try that seller sooner, choose Buy again for the product. |
| A red "stalled" dot next to a store | Wait a few minutes (it keeps trying by itself). If it stays red, press Stop, then start_app.bat again; if it still stays, Download diagnostics and send the file |
| A store column says *NOT signed in* | Press Sign in above that store's column, or save its sign-in under Sign-ins |
| No phone messages | Press Send a test; check the ntfy app is subscribed to exactly the name shown under Phone |
| The PC is slow | Speed (top bar) to Balanced or Light, or switch some browsers off on the Browsers tab |
| Linked devices says a device was "heard" long ago | Open Restalker on that device and let it update itself. (A device with a version from before v147 / app 1.0.6 still depends on the free message service's 250 messages a day; newer ones do not) |
| Anything else | Press **Report problem** (or **Diagnostics** and send the file). It holds no passwords, card code or addresses |
