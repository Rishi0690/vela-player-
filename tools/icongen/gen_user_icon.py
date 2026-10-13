# v2.15: launcher icon from the user's own artwork (tools/icongen/user_icon_source.png).
import numpy as np
from PIL import Image, ImageDraw
R='app/src/main/res/'
src=np.asarray(Image.open('tools/icongen/user_icon_source.png').convert('RGB')).astype(np.float32)
# white background -> alpha (un-composite over white)
a=(255-src.min(axis=2))/255.0
a=np.clip((a-0.03)/0.97,0,1)
rgb=np.where(a[...,None]>0.001,(src-255*(1-a[...,None]))/np.maximum(a[...,None],1e-3),0)
rgb=np.clip(rgb,0,255)
img=Image.fromarray(np.dstack([rgb,a*255]).astype(np.uint8),'RGBA')
ys,xs=np.where(a>0.1); x0,x1,y0,y1=xs.min(),xs.max(),ys.min(),ys.max()
cx,cy=(x0+x1)/2,(y0+y1)/2; side=max(x1-x0,y1-y0)*1.02
logo=img.crop((int(cx-side/2),int(cy-side/2),int(cx+side/2),int(cy+side/2)))
def place(size,frac):
    c=Image.new('RGBA',(size,size),(0,0,0,0)); s=int(size*frac)
    c.alpha_composite(logo.resize((s,s),Image.LANCZOS),((size-s)//2,(size-s)//2)); return c
fg=place(432,0.58); fg.save(R+'drawable-nodpi/ic_launcher_fg.png')
m=np.asarray(fg)[...,3]; mono=Image.fromarray(np.dstack([np.full_like(m,255)]*3+[m]),'RGBA'); mono.save(R+'drawable-nodpi/ic_launcher_monochrome.png')
for d,s in dict(mdpi=48,hdpi=72,xhdpi=96,xxhdpi=144,xxxhdpi=192).items():
    for round_ in (False,True):
        big=s*4; c=Image.new('RGBA',(big,big),(0,0,0,0)); mask=Image.new('L',(big,big),0); dr=ImageDraw.Draw(mask)
        if round_: dr.ellipse((0,0,big-1,big-1),fill=255)
        else: dr.rounded_rectangle((0,0,big-1,big-1),radius=big*0.22,fill=255)
        c.paste(Image.new('RGBA',(big,big),(255,255,255,255)),(0,0),mask)
        c.alpha_composite(place(big,0.78))
        c.resize((s,s),Image.LANCZOS).save(R+f'mipmap-{d}/ic_launcher{"_round" if round_ else ""}.png')
