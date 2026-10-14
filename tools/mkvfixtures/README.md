# MKV test fixtures (v2.19.2)
Fixtures in `app/src/test/resources/streams/` used by `StreamSeekabilityTest`:
- `normal.mkv` — ffmpeg x264+AAC, 10 s, Cues listed in the first SeekHead (ordinary file).
- `nested.mkv` — `normal.mkv` rewritten: first SeekHead lists only [SeekHead#2, Info]; SeekHead#2 appended at the end of
  the Segment lists Info/Tracks/Tags/Cues (layout of the reported R2 stream). Media3 1.3.1 reports it unseekable.
- `nocues.mkv` — 20 s file with the Cues element and its SeekHead entry replaced by Void (no index at all).

Regenerate:
```bash
ffmpeg -f lavfi -i testsrc=size=96x64:rate=10 -f lavfi -i sine=frequency=440:sample_rate=8000 -t 10 -c:v libx264 -preset ultrafast -g 10 -pix_fmt yuv420p -c:a aac -b:a 16k -cluster_time_limit 1000 -cluster_size_limit 4000 normal.mkv
ffmpeg -f lavfi -i testsrc=size=160x120:rate=25 -f lavfi -i sine=frequency=440:sample_rate=8000 -t 20 -c:v libx264 -preset ultrafast -qp 8 -g 25 -pix_fmt yuv420p -c:a aac -b:a 16k -cluster_time_limit 1000 big.mkv
python3 rewrite.py normal.mkv nested.mkv big.mkv nocues.mkv   # python3 ebml.py <file> dumps the layout
```
