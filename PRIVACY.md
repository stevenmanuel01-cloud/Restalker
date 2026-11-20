# Restalker privacy policy

Last updated: November 20, 2026

This policy covers **Restalker Mobile** (the Android app) and the **Restalker PC program**. Restalker watches products in online
stores (Magic: The Gathering products on Amazon) and tells you when one is back in stock at or under the price you set. It watches
products, never people.

## The short version

- There is **no account**. Restalker never asks for your name, email address, phone number or location.
- Your product list, price caps, settings and purchase history stay **on your own device**.
- Restalker has **no ads**, sells nothing, and gives nothing to advertisers or data brokers.
- Your Amazon password and payment details are typed into Amazon's own pages. Restalker does not read, keep or send them.

## What stays on your device

Your products and price caps, your settings, the app's log, what the app bought for you (product, price, time, Amazon's order
number), and - if you signed in to Amazon inside the app - Amazon's sign-in cookies, kept by the app's built-in browser exactly as
a normal browser keeps them. Removing the app (or Android Settings > Apps > Restalker > Storage > Clear storage) erases all of it.

## What leaves your device

**1. A device code and "I am here".** The first time it runs, Restalker makes up a random code for this device. It is not tied to
your name, your Google account, your phone number or the phone's hardware. While Restalker is running it tells the Restalker relay
(our server, run on Cloudflare) that the device with this code is active. That is how the "stalkers active now" number is counted.
The relay keeps the code and the time it was last heard, and deletes both once the device has not been heard for 90 days.

**2. Crowd sightings (on by default; switch off in Settings).** When your device sees a watched product for sale, it shares the
sighting so others get alerted sooner: the store's product number, the price, the time, and the device code. Nothing about you.
Sightings are kept for up to 2 hours on the live line; confirmed restocks (product, price, time, how many devices saw it) are kept
for up to 60 days and may be posted on the Restalker Discord server.

**3. Linking your own devices (only if you link them).** When you link a phone, tablet or PC with a link code, they exchange your
product list, settings and what is running, through the relay. Every message is **sealed on your device with your link code
before it is sent** - the relay cannot read it, and neither can we. Sealed messages are deleted after at most 12 hours. Older
versions, and times when the relay cannot be reached, pass the same sealed messages through the public message service ntfy.sh.

**4. Problem reports (your choice).** If you allow it, or when you tap "Send a report", Restalker sends a report so a problem can
be fixed: the app's log, your product list, what the app bought (with Amazon order numbers removed), the app's version, and the
phone's model and Android version. No name, address, password or card. Reports are read by the developer, may be passed to a
private developer channel (Discord, ntfy.sh), and are deleted from the relay after 30 days. The Google Play edition asks you first
and sends none until you say yes; you can change your answer any time in Settings > Help and about.

**5. Product suggestions.** If you add a product that is not in Restalker's catalog, its store product number and name may be sent
to the relay as a suggestion for the catalog (kept up to 90 days).

**6. Looking at store pages.** To check stock and prices, your device loads the product pages from the store (amazon.com), the same
way a browser would. The store sees those requests, and, if you signed in, that they come from your account. A product's
"market price" link opens tcgplayer.com in your browser.

**7. Buying (off unless you switch it on).** If you switch on buying for a product, the app places the order on Amazon's own pages
inside its built-in browser, with the account you signed in to there and the address and payment method saved in that Amazon
account. Restalker does not see or keep your card number or password. The order is between you and Amazon.

**8. Catalog and updates.** Restalker downloads its product catalog from GitHub. The edition downloaded from GitHub also checks
GitHub for new versions; the Google Play edition is updated by Google Play. GitHub, Google and Cloudflare see your internet
address when your device contacts them, as with any website.

**9. Alerts through ntfy (only if you set it up).** The PC program can send alerts to the ntfy app on a phone. The alert text
(product, price, link) then passes through ntfy.sh on a private channel name made up on your PC.

**10. Connecting your Discord (only if you do it).** If you join the Restalker Discord and connect it to the app with the one-time
code, the relay keeps your Discord user number and display name next to your device code, so the server can show which members
take part. Disconnecting is done by asking on the Discord server or through the contact below.

## Who receives data

| Service | What it gets | Why |
|---|---|---|
| Cloudflare (hosts the Restalker relay) | Device code, activity time, sightings, sealed link messages, reports you allowed | Runs the server |
| ntfy.sh | Sealed link messages (fallback), alerts you set up | Message delivery |
| Discord | Confirmed restocks (no device or person named); reports you allowed, in a private developer channel | Community alerts; fixing problems |
| GitHub | Requests for the catalog and (GitHub edition) new versions | Downloads |
| Amazon | The page requests your device makes | Checking stock and prices; your orders |

Nothing is sold. Nothing is shared for advertising. All of it travels over encrypted connections (HTTPS).

## Permissions the app uses

- **Internet / network state** - to look at store pages and reach the relay.
- **Notifications** - to alert you.
- **Running in the background (foreground service), start after restart, keep awake** - so watching continues while the app is
  not on screen. It runs only after you tap Start, always shows a notice while it runs, and stops when you tap Stop.

- **Camera** - only while you scan the link code (a QR code) shown by your PC or other device, and only if you choose to scan.
  The picture is read on the device for the code and is not saved or sent anywhere. You can type the code instead.

The app does not use your location, contacts, microphone, photos, files, messages or call log.

## Keeping and deleting

Everything the relay holds expires by itself, at the times given above for each kind of data. You can stop sending at any time:
switch off "Share with the crowd" and automatic reports in Settings, unlink your devices, or remove the app - what is on your
device is erased with it, and what the relay still holds runs out on its own.

## Children

Restalker is not directed at children under 13 and does not knowingly collect data from them. Buying requires an Amazon account,
which Amazon offers to adults only.

## Changes

If this policy changes, the new version is posted on this page with a new date.

## Contact

Restalker is made by an independent developer. Questions and deletion requests:
https://github.com/stevenmanuel01-cloud/Restalker/issues

Restalker is not affiliated with, endorsed by or sponsored by Amazon, Wizards of the Coast, TCGplayer, Google, Discord or ntfy. Magic:
The Gathering is a trademark of Wizards of the Coast LLC.
