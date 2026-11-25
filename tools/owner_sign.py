"""The owner's side of a signed request to the relay. Needs the owner's PRIVATE key (never in this repository).
usage: python3 owner_sign.py <private key .pem> reports <since ms>          -> t and s for the "Reports to the owner" job
       python3 owner_sign.py <private key .pem> notice "<title>" "<text>"   -> t and s for the "Notice to every device" job
       python3 owner_sign.py <private key .pem> open <reports.sealed.json> <out.json>   -> opens what the reports job handed on"""
import base64, hashlib, json, subprocess, sys, tempfile, time, os


def sign(key, words):
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=words.encode(), capture_output=True, check=True).stdout
    return base64.urlsafe_b64encode(sig).decode().rstrip("=")


def clean(x, n):
    import re
    return re.sub(r"[<>]", "", re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", " ", str(x))).strip()[:n]


def main(a):
    key, what = a[0], a[1]
    t = int(time.time() * 1000)
    if what == "reports":
        print(json.dumps({"since": str(int(a[2])), "t": str(t), "s": sign(key, f"restalker-owner:reports:{t}:{int(a[2])}")}))
    elif what == "notice":
        title, text = clean(a[2], 80), clean(a[3], 600)
        h = hashlib.sha256((title + "\n" + text).encode()).hexdigest()
        print(json.dumps({"title": title, "text": text, "t": str(t), "s": sign(key, f"restalker-owner:notice:{t}:{h}")}))
    elif what == "open":
        d = json.load(open(a[2]))["enc"]
        with tempfile.TemporaryDirectory() as td:
            kf = os.path.join(td, "k")
            open(kf, "wb").write(base64.b64decode(d["k"]))
            raw = subprocess.run(["openssl", "pkeyutl", "-decrypt", "-inkey", key, "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256",
                                  "-pkeyopt", "rsa_mgf1_md:sha256", "-in", kf], capture_output=True, check=True).stdout
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        open(a[3], "wb").write(AESGCM(raw).decrypt(base64.b64decode(d["iv"]), base64.b64decode(d["data"]), None))
        print("opened:", len(json.load(open(a[3]))["reports"]), "report(s)")


if __name__ == "__main__":
    main(sys.argv[1:])
