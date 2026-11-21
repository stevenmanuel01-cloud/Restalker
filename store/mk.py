import re, sys, xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
def svg_of(path, size=None):
    r=ET.parse(path).getroot(); vw=r.get(A+'viewportWidth'); vh=r.get(A+'viewportHeight')
    def node(e):
        if e.tag=='path':
            at=f'd="{e.get(A+"pathData")}" fill="{e.get(A+"fillColor") or "none"}"'
            if e.get(A+'strokeColor'): at+=f' stroke="{e.get(A+"strokeColor")}" stroke-width="{e.get(A+"strokeWidth") or 1}"'
            return f'<path {at}/>'
        if e.tag=='group':
            px=float(e.get(A+'pivotX') or 0); py=float(e.get(A+'pivotY') or 0)
            tx=float(e.get(A+'translateX') or 0); ty=float(e.get(A+'translateY') or 0)
            sx=float(e.get(A+'scaleX') or 1); sy=float(e.get(A+'scaleY') or 1); rot=float(e.get(A+'rotation') or 0)
            t=f'translate({tx+px} {ty+py}) rotate({rot}) scale({sx} {sy}) translate({-px} {-py})'
            return f'<g transform="{t}">'+''.join(node(c) for c in e)+'</g>'
        return ''
    body=''.join(node(c) for c in r)
    s=f' width="{size}" height="{size}"' if size else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}"{s}>{body}</svg>'
if __name__=='__main__':
    D='/home/claude/restalker/android/app/src/main/res/drawable/'
    open('icon.svg','w').write(svg_of(D+'ic_launcher.xml'))
    open('icon.html','w').write('<html><body style="margin:0">'+svg_of(D+'ic_launcher.xml',512)+'</body></html>')
    logo=svg_of(D+'ic_launcher.xml',300)
    open('feature.html','w').write(f'''<html><head><style>
body{{margin:0;width:1024px;height:500px;background:#1F3A5F;font-family:"DejaVu Sans","Liberation Sans",Arial,sans-serif;overflow:hidden;position:relative}}
.l{{position:absolute;left:70px;top:100px;width:300px;height:300px;border-radius:64px;overflow:hidden;box-shadow:0 0 0 3px #2B4B74}}
.t{{position:absolute;left:430px;top:128px;color:#fff}}
h1{{margin:0;font-size:84px;letter-spacing:-1px;font-weight:700}}
p{{margin:14px 0 0 0;font-size:34px;line-height:44px;color:#C9D5E3}}
b{{color:#F2B134;font-weight:700}}
</style></head><body><div class="l">{logo}</div><div class="t"><h1>Restalker</h1><p>Back in stock?<br><b>You hear it first.</b></p><p style="font-size:24px;margin-top:22px">Restock alerts at the price you set</p></div></body></html>''')
