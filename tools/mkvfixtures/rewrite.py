"""Builds the v2.19.2 MKV fixtures from ffmpeg output.
nested: first SeekHead lists only [SeekHead#2 @ end, Info]; SeekHead#2 appended at the end of the Segment lists
        Info, Tracks, Tags, Cues (same layout as the reported R2 file). Offsets of everything else unchanged.
nocues: Cues element and its SeekHead entry replaced by Void (same sizes)."""
import sys
from ebml import elems, eid, vint
SEG=0x18538067; SH=0x114D9B74; SEEK=0x4DBB; CUES=0x1C53BB6B; INFO=0x1549A966
def u(n,l): return n.to_bytes(l,'big')
def seek(idv,pos):
    c=b'\x53\xAB\x84'+u(idv,4)+b'\x53\xAC\x88'+u(pos,8)
    return b'\x4D\xBB'+bytes([0x80|len(c)])+c
def void(total):
    assert total>=2
    if total-2<=126: return b'\xEC'+bytes([0x80|(total-2)])+b'\0'*(total-2)
    return b'\xEC\x01'+u(total-9,7)+b'\0'*(total-9)
def segment(b):
    for i,p,h,s in elems(b,0,len(b)):
        if i==SEG: return p,h,s
def kids(b,p,h,s): return elems(b,p+h,p+h+s)
def seekheads(b,p,h,s):
    out={}
    for i,q,hh,ss in kids(b,p,h,s):
        if i==SH:
            for k,r,h3,s3 in elems(b,q+hh,q+hh+ss):
                if k!=SEEK: continue
                d={x[0]:b[x[1]+x[2]:x[1]+x[2]+x[3]] for x in elems(b,r+h3,r+h3+s3)}
                out[int.from_bytes(d[0x53AB],'big')]=(int.from_bytes(d[0x53AC],'big'),r,h3+s3)
            return q,hh,ss,out
def nested(src,dst):
    b=bytearray(open(src,'rb').read()); p,h,s=segment(b); assert h==12
    q,hh,ss,entries=seekheads(b,p,h,s)
    sh2pos=s  # segment-relative position of the appended SeekHead
    content=seek(SH,sh2pos)+seek(INFO,entries[INFO][0])
    content+=void(ss-len(content)); assert len(content)==ss
    b[q+hh:q+hh+ss]=content
    c2=b''.join(seek(i,entries[i][0]) for i in entries)
    sh2=b'\x11\x4D\x9B\x74'+bytes([0x80|len(c2)])+c2
    b+=sh2
    b[p+4:p+12]=bytes([0x01])+u(s+len(sh2),7)
    open(dst,'wb').write(b)
def nocues(src,dst):
    b=bytearray(open(src,'rb').read()); p,h,s=segment(b)
    q,hh,ss,entries=seekheads(b,p,h,s)
    pos,r,tot=entries[CUES]; b[r:r+tot]=void(tot)
    for k,r2,h3,s3 in elems(b,q+hh,q+hh+ss):
        if k==0xBF: b[r2:r2+h3+s3]=void(h3+s3)  # stale CRC-32 of the edited SeekHead
    for i,qq,h2,s2 in kids(b,p,h,s):
        if i==CUES: b[qq:qq+h2+s2]=void(h2+s2)
    open(dst,'wb').write(b)
nested(sys.argv[1],sys.argv[2]); nocues(sys.argv[3],sys.argv[4])
