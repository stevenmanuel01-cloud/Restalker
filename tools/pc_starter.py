"""The PC program as one download for a new user (owner 2026-11-20: someone who starts with the phone app needs a way to get the PC
program). Made from a released update zip with the program's own "copy for a new user" (export.py), so it is the same thing a user
gets from Copy > Download the copy for a new user - without any products: the program, START HERE.txt and the two start files.
Nothing personal can be in it: an update zip holds program files only.

usage: python3 tools/pc_starter.py releases/restalker_vNNN_update.zip out.zip"""
import os, subprocess, sys, tempfile, zipfile


def main(update_zip, out_zip):
    work = tempfile.mkdtemp(prefix="rk_starter_")
    with zipfile.ZipFile(update_zip) as z:
        for n in z.namelist():                      # (no path may leave the work folder)
            if n.startswith("/") or ".." in n.split("/"):
                raise SystemExit(f"bad name in the zip: {n}")
        z.extractall(work)
    tops = [d for d in os.listdir(work) if os.path.isfile(os.path.join(work, d, "export.py"))]
    if len(tops) != 1:
        raise SystemExit("the update zip has no export.py")
    src = os.path.join(work, tops[0])
    out_zip = os.path.abspath(out_zip)
    code = "import export, json, sys; p, i = export.build('new', products=False, discord=False, out_path=sys.argv[1]); print(json.dumps(dict(i, version=export.VERSION)))"
    r = subprocess.run([sys.executable, "-c", code, out_zip], cwd=src, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("could not build: " + r.stderr[-400:])
    with zipfile.ZipFile(out_zip) as z:
        names = z.namelist()
    need = ("Restalker/app.py", "Restalker/START HERE.txt", "Restalker/setup_first_time.bat", "Restalker/start_app.bat", "Restalker/buyer_engine/manifest.json")
    missing = [n for n in need if n not in names]
    personal = [n for n in names if n.split("/")[-1] in ("targets.yaml", "ui_state.yaml", "store_logins.bin", "card_code.bin", "notify_phone.txt",
                                                        "discord_webhook.txt", "purchases.csv", "phone_link.json", "restalker_link.json", "reader_link.json")]
    if missing or personal:
        os.remove(out_zip)
        raise SystemExit(f"NOT MADE - missing {missing}, must not be in it {personal}")
    print(r.stdout.strip())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
