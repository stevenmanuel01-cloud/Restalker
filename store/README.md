# Store page material (Google Play)

`play/` holds what is uploaded to the store page by hand: the icon (512), the banner (1024x500) and five phone pictures
(1080x1920). The pictures are made from screenshots the phone tests take (`z98_store_shots`, Google Play edition) with
`compose.py`; the icon and banner are drawn from the app's own logo with `mk.py` and `shot.js`.
Every build on main puts them, the app bundle and the short film on the "play-kit" release page.
