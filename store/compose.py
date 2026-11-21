from PIL import Image, ImageDraw, ImageFont
import sys
S='/home/claude/kit/shots/'; O='/home/claude/kit/store/'
B='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'; R='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
items=[('store_1_watch.png','Back in stock?','You hear it first.'),
       ('store_3_product.png','Set your price.','Alerts only at or under your cap.'),
       ('store_2_list.png','Pick what to watch','Products ready by year and set.'),
       ('store_6_alerts.png','You choose','which alerts reach you.'),
       ('store_4_bought.png','Optional: let it buy','Never above the cap you set.')]
W,H=1080,1920
for k,(f,t1,t2) in enumerate(items,1):
    c=Image.new('RGB',(W,H),'#1F3A5F'); d=ImageDraw.Draw(c)
    f1=ImageFont.truetype(B,68); f2=ImageFont.truetype(R,42)
    w1=d.textlength(t1,font=f1); d.text(((W-w1)/2,84),t1,font=f1,fill='#FFFFFF')
    w2=d.textlength(t2,font=f2); d.text(((W-w2)/2,178),t2,font=f2,fill='#F2B134')
    s=Image.open(S+f).convert('RGB'); s=s.crop((0,128,1080,2400))
    sw=840; sh=round(s.size[1]*sw/s.size[0]); s=s.resize((sw,sh),Image.LANCZOS)
    m=Image.new('L',(sw,sh),0); ImageDraw.Draw(m).rounded_rectangle((0,0,sw,sh+80),radius=44,fill=255)
    x=(W-sw)//2; y=290
    d.rounded_rectangle((x-10,y-10,x+sw+10,y+sh+90),radius=54,fill='#0E1B2C')
    c.paste(s,(x,y),m)
    c.save(O+f'restalker-store-{k}.png'); print(k,c.size)
