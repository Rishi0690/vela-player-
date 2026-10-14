import sys
def vint(b,p):
    f=b[p]; l=1
    while l<=8 and not (f & (0x80>>(l-1))): l+=1
    v=f & (0xFF>>l)
    for k in range(1,l): v=(v<<8)|b[p+k]
    return v,l
def eid(b,p):
    f=b[p]; l=1
    while not (f & (0x80>>(l-1))): l+=1
    return int.from_bytes(b[p:p+l],'big'),l
def elems(b,start,end):
    p=start; out=[]
    while p<end:
        i,il=eid(b,p); s,sl=vint(b,p+il)
        if s==(1<<(7*sl))-1: s=end-(p+il+sl)
        out.append((i,p,il+sl,s)); p+=il+sl+s
    return out
NAMES={0x114D9B74:'SeekHead',0x1549A966:'Info',0x1654AE6B:'Tracks',0x1F43B675:'Cluster',0x1C53BB6B:'Cues',0x1254C367:'Tags',0xEC:'Void',0x18538067:'Segment',0x1A45DFA3:'EBML',0x4DBB:'Seek',0x53AB:'SeekID',0x53AC:'SeekPos'}
def dump(fn):
    b=open(fn,'rb').read()
    top=elems(b,0,len(b))
    for i,p,h,s in top:
        print(NAMES.get(i,hex(i)),p,h,s)
        if i==0x18538067:
            seg0=p+h
            for j,q,hh,ss in elems(b,p+h,p+h+s):
                if j!=0x1F43B675: print('  ',NAMES.get(j,hex(j)),'segoff',q-seg0,'hdr',hh,'size',ss)
                if j==0x114D9B74:
                    for k,r,h3,s3 in elems(b,q+hh,q+hh+ss):
                        if k!=0x4DBB: print('     child',NAMES.get(k,hex(k)),s3); continue
                        sub=elems(b,r+h3,r+h3+s3)
                        d={NAMES.get(x[0]):b[x[1]+x[2]:x[1]+x[2]+x[3]] for x in sub}
                        print('     seek',d['SeekID'].hex(),int.from_bytes(d['SeekPos'],'big'))
            print('  clusters',sum(1 for j in elems(b,p+h,p+h+s) if j[0]==0x1F43B675))
if __name__=='__main__': dump(sys.argv[1])
