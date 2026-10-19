import sys, struct, io, json, numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
sys.path.insert(0,'tools'); import mpq
race,FX,FY=sys.argv[1],float(sys.argv[2]),float(sys.argv[3]); K=float(sys.argv[4]) if len(sys.argv)>4 else 1.0; SRC=sys.argv[5] if len(sys.argv)>5 and sys.argv[5]!='-' else f'races/{sys.argv[1]}_F.png'; SX=float(sys.argv[6]) if len(sys.argv)>6 else 1.0; SY=float(sys.argv[7]) if len(sys.argv)>7 else 1.0; MODE=sys.argv[8] if len(sys.argv)>8 else 'rgb'; PINK=float(sys.argv[9]) if len(sys.argv)>9 else 0.0; SMOOTH=float(sys.argv[10]) if len(sys.argv)>10 else 0.0
m=mpq.Mpq('Patch-H.MPQ'); low={n.lower():n for n in m.listfile()}
d=m.read(low[f'character\\{race}\\female\\{race}female.m2'.lower()])
s=m.read(low[f'character\\{race}\\female\\{race}female00.skin'.lower()])
nv,ov=struct.unpack_from('<II',d,0x3C)
V=[struct.unpack_from('<3f4B4B3f2f2f',d,ov+48*i) for i in range(nv)]
h=struct.unpack_from('<4s10I',s,0)
idx=struct.unpack_from('<%dH'%h[1],s,h[2]); tri=struct.unpack_from('<%dH'%h[3],s,h[4])
nt,ot=struct.unpack_from('<II',d,0x50); texs=[struct.unpack_from('<IIII',d,ot+16*i) for i in range(nt)]
nl,ol=struct.unpack_from('<II',d,0x80); lookup=struct.unpack_from('<%dH'%nl,d,ol)
subs=[struct.unpack_from('<6H',s,h[8]+48*i) for i in range(h[7])]
out=dict(pos=[c for v in V for c in v[0:3]],nor=[c for v in V for c in v[11:14]],uv=[c for v in V for c in v[14:16]],batches=[])
for i in range(h[9]):
    b=struct.unpack_from('<BBHHHHHHHHHHH',s,h[10]+24*i)
    sid,lvl,vs,vc,ts,tc=subs[b[3]]; tt=texs[lookup[b[9]]][0]
    keep = sid==0 or (sid>=100 and sid%100==1 and sid//100 not in (15,17))
    if keep and tt in (1,8):
        st=ts+(lvl<<16)
        out['batches'].append(dict(geoset=sid,texType=tt,blend=0,ind=[idx[tri[st+k]] for k in range(tc)]))
json.dump(out,open(f'r3d/model_{race}.json','w'))
ex=low.get(f'character\\{race}\\female\\{race}femaleskin00_00_extra.blp')
if ex: Image.open(io.BytesIO(m.read(ex))).convert('RGB').save(f'r3d/extra_{race}.png')
else: Image.new('RGB',(8,8),(128,128,128)).save(f'r3d/extra_{race}.png')
male=np.asarray(Image.open('dr2/o-Male-Skin00.ppm').convert('RGB')).astype(float)/255
fem=np.asarray(Image.open(SRC).convert('RGB')).astype(float)/255
if SMOOTH>0:
    k=fem.shape[0]/512
    REG=[(0,0,256,128),(0,128,256,128),(0,256,256,64),(0,320,256,64),(0,384,256,128),
         (256,0,256,128),(256,128,256,64),(256,192,256,128),(256,320,256,128),(256,448,256,64)]
    sm=fem.copy()
    for x0,y0,w0,h0 in REG:
        X0,Y0,X1,Y1=int(x0*k),int(y0*k),int((x0+w0)*k),int((y0+h0)*k)
        for c in range(3):
            sm[Y0:Y1,X0:X1,c]=gaussian_filter(fem[Y0:Y1,X0:X1,c],SMOOTH,mode='reflect')
    fem=sm
ratio=male/np.maximum(np.stack([gaussian_filter(male[...,c],5) for c in range(3)],-1),1e-3)
if MODE=='lum':
    L=male@[0.299,0.587,0.114]; lr=L/np.maximum(gaussian_filter(L,5),1e-3); ratio=np.stack([lr]*3,-1)
MX,MY,R=337.5,89,9
o=fem.copy()
for y in range(int(FY-R*SY-3),int(FY+R*SY+4)):
    for x in range(int(FX-R*SX-3),int(FX+R*SX+4)):
        dx,dy=(x-FX)/SX,(y-FY)/SY; dd=(dx*dx+dy*dy)**.5; w=np.clip((R-dd)/(R*.45),0,1)
        if w<=0: continue
        sx,sy=MX+dx,MY+dy; ix,iy=int(sx),int(sy); fx,fy=sx-ix,sy-iy
        r=ratio[iy,ix]*(1-fx)*(1-fy)+ratio[iy,ix+1]*fx*(1-fy)+ratio[iy+1,ix]*(1-fx)*fy+ratio[iy+1,ix+1]*fx*fy
        c=np.clip(fem[y,x]*(1+(r-1)*K),0,1)
        if PINK>0:
            dark=float(np.clip(1-np.mean(r),0,1))*4
            lum=float(c@[0.299,0.587,0.114]); pink=np.array([1.0,0.72,0.78])*lum/0.80
            c=c*(1-min(1,PINK*dark))+np.clip(pink,0,1)*min(1,PINK*dark)
        o[y,x]=fem[y,x]*(1-w)+c*w
Image.fromarray((o*255).round().astype(np.uint8)).save(f'r3d/skin_{race}.png')
P=np.array(out['pos']).reshape(-1,3); print(race, 'height', P[:,2].max()-P[:,2].min(), 'maxx', P[:,0].max())
