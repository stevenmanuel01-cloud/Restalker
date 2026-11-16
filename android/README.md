# Restalker Mobile (Android)

Restalker on your phone: it watches a few Amazon products on its own and buys one when it is in stock at or under your cap.
It uses the desktop Restalker's own Amazon checkout (`app/src/main/assets/engine/`: catalog.js, logic.js, content.js, amz_part.js -
unchanged copies from desktop v120) inside the app's browser.

- One purchase per product (the lock is written before "Place your order" is allowed); Re-arm to buy again.
- Practice mode (on at first): every checkout stops right before "Place your order".
- Never answers a robot check or a code; never types a password or card number; store sign-ins stay in the app's browser.
- A purchase is told only after it was found in your Amazon order list.

Built and tested by GitHub Actions (`.github/workflows/android.yml`): the core rules on a computer, then the real app on virtual
Android phones (Android 11 and 14) against a stand-in Amazon (`testsite/`, `app/src/androidTest/`). Nothing reaches the real Amazon
in the tests.
