# Restalker Mobile (Android)

Restalker on your phone: it watches a few Amazon products on its own and buys one when it is in stock at or under your cap.
It uses the desktop Restalker's own Amazon checkout (`app/src/main/assets/engine/`: catalog.js, logic.js, content.js, amz_part.js -
unchanged copies from desktop v120) inside the app's browser.

- One purchase per product (the lock is written before "Place your order" is allowed); Re-arm to buy again.
- Practice mode (on at first): every checkout stops right before "Place your order".
- Never answers a robot check or a code; never types a password or card number; store sign-ins stay in the app's browser.
- A purchase is told only after it was found in your Amazon order list.

Numbering: 1.0.0 (2026-11-19) comes right after 2.15 - the owner had the name start again; the version code keeps counting, and
everything that compares versions goes by the code. Always three parts (1.0.1, 1.1.0 ...): the releases of the first two-part
1.0 - 1.8 are still on GitHub under mobile-v1.0 ... mobile-v1.8. A new version is a GitHub pre-release (the owner's devices get it
through mobile/beta.json) until "Release to everyone" makes it the latest release and copies it to mobile/latest.json.

Built and tested by GitHub Actions (`.github/workflows/android.yml`): the core rules on a computer, then the real app on virtual
Android phones (Android 11 and 14) against a stand-in Amazon (`testsite/`, `app/src/androidTest/`). Nothing reaches the real Amazon
in the tests.
