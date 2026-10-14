"""v2.19.3 tail-damage fixtures (MKV content, deliberately named .mp4 like the reported file).
Usage: python3 damage.py <normal.mkv> <outdir>
  normal.mp4     normal.mkv byte-for-byte (MKV mis-named .mp4)
  cutcues.mp4    file cut in the middle of the Cues element (Cues header claims more bytes than exist)
  zerocues.mp4   full length, Cues element overwritten with zeros (preallocated / incomplete download)
  badcuesptr.mp4 SeekHead's Cues position points into the middle of a Cluster (stale SeekHead after an edit)
  seekpast.mp4   SeekHead's Cues position points past the end of the file
  truncated.mp4  file cut at ~60 %, mid-Cluster (Cues listed but gone: truncated download)
  cuttail.mp4    Cues removed (Void) and the file cut in the middle of its last Cluster
"""
import sys, os
from ebml import elems
from rewrite import segment, seekheads, void, CUES
SEG_CLUSTER=0x1F43B675
def kids(b,p,h,s): return elems(b,p+h,p+h+s)
def cues_abs(b):
    p,h,s=segment(b)
    for i,q,hh,ss in kids(b,p,h,s):
        if i==CUES: return q,hh,ss
def set_cues_pos(b,newpos):
    p,h,s=segment(b); q,hh,ss,entries=seekheads(b,p,h,s)
    pos,r,tot=entries[CUES]
    i=b.index(b'\x53\xAC',r,r+tot); n=b[i+2]&0x7F  # SeekPosition: ID, 1-byte size, n-byte value
    b[i+3:i+3+n]=newpos.to_bytes(n,'big')
    for k,r2,h3,s3 in elems(b,q+hh,q+hh+ss):
        if k==0xBF: b[r2:r2+h3+s3]=void(h3+s3)  # stale CRC-32
def main(src,out):
    b=open(src,'rb').read()
    p,h,s=segment(b); seg0=p+h
    cq,ch,cs=cues_abs(b)
    w=lambda n,d: open(os.path.join(out,n),'wb').write(bytes(d))
    w('normal.mp4',b)
    w('cutcues.mp4',b[:cq+ch+cs//2])
    z=bytearray(b); z[cq:cq+ch+cs]=b'\0'*(ch+cs); w('zerocues.mp4',z)
    clusters=[(q,hh,ss) for i,q,hh,ss in kids(b,p,h,s) if i==SEG_CLUSTER]
    mid=clusters[len(clusters)//2]
    x=bytearray(b); set_cues_pos(x,(mid[0]+mid[1]+40)-seg0); w('badcuesptr.mp4',x)
    x=bytearray(b); set_cues_pos(x,len(b)+100000-seg0); w('seekpast.mp4',x)
    w('truncated.mp4',b[:int(len(b)*0.6)])
    n=bytearray(b)
    q,hh,ss,entries=seekheads(n,p,h,s)
    pos,r,tot=entries[CUES]; n[r:r+tot]=void(tot)
    for k,r2,h3,s3 in elems(n,q+hh,q+hh+ss):
        if k==0xBF: n[r2:r2+h3+s3]=void(h3+s3)
    n[cq:cq+ch+cs]=void(ch+cs)
    last=clusters[-1]
    w('cuttail.mp4',n[:last[0]+last[1]+last[2]//2])
if __name__=='__main__': main(sys.argv[1],sys.argv[2])

# ---- v2.19.3 second round: the reported crash (UnexpectedLoaderException: ArrayIndexOutOfBoundsException
# src.length=65536 srcPos=-1643236477 ... length=1643236477) = DefaultEbmlReader skipFully((int) size) with
# size = 2,651,730,819 (0x9E0E3383, 5-byte VINT 08 9E 0E 33 83) wrapped negative.
HUGE=b'\xEC\x08\x9E\x0E\x33\x83'   # Void, size 2,651,730,819
def huge_fixtures(src,out):
    b=open(src,'rb').read(); p,h,s=segment(b)
    cq,ch,cs=cues_abs(b)
    w=lambda n,d: open(os.path.join(out,n),'wb').write(bytes(d))
    # staleptr: the Cues target holds a huge element (stale/garbage seek target) -> plain Media3 reproduces the
    # exact message (fresh input after the seek: empty 64 KiB peek buffer).
    x=bytearray(b); x[cq:cq+len(HUGE)]=HUGE; w('staleptr.mp4',x)
    # hugeelem: a garbage huge element in place of a middle Cluster's header (crash mid-playback with plain Media3)
    clusters=[(q,hh,ss) for i,q,hh,ss in kids(b,p,h,s) if i==SEG_CLUSTER]
    mid=clusters[len(clusters)//2]
    x=bytearray(b); x[mid[0]:mid[0]+len(HUGE)]=HUGE; w('hugeelem.mp4',x)
    # leadjunk: 16 junk bytes before the EBML header (the Matroska Sniffer accepts up to 1024)
    w('leadjunk.mp4',HUGE+b'\0'*10+b)
if __name__=='__main__': huge_fixtures(sys.argv[1],sys.argv[2])

# bigvoid.head / bigvoid.tail: a LEGIT 2,651,730,819-byte Void before the first Cluster (Cues removed). The test
# writes head, leaves a 2.65 GB hole (sparse file, zeros = the Void's content) and writes tail.
def bigvoid(src,out):
    b=bytearray(open(src,'rb').read()); p,h,s=segment(b); assert h==12
    q,hh,ss,entries=seekheads(b,p,h,s)
    pos,r,tot=entries[CUES]; b[r:r+tot]=void(tot)
    for k,r2,h3,s3 in elems(b,q+hh,q+hh+ss):
        if k==0xBF: b[r2:r2+h3+s3]=void(h3+s3)
    cq,ch,cs=cues_abs(b); b[cq:cq+ch+cs]=void(ch+cs)
    first=[q2 for i,q2,_,_ in kids(b,p,h,s) if i==SEG_CLUSTER][0]
    n=2651730819
    b[p+4:p+12]=bytes([0x01])+(s+len(HUGE)+n).to_bytes(7,'big')
    open(os.path.join(out,'bigvoid.head'),'wb').write(bytes(b[:first])+HUGE)
    open(os.path.join(out,'bigvoid.tail'),'wb').write(bytes(b[first:]))
if __name__=='__main__': bigvoid(sys.argv[1],sys.argv[2])
