"""Summarises tools/speed-bench.sh logs: SAI's CPU for the strokes, how long SAI keeps drawing
after the pen is up, and how much of its CPU time ran on the fast cores.
Usage: python3 tools/speed-benchstats.py bench-out/speed-*.txt  (not part of the app)

A thread's CPU between two samples is counted on the core it was on at the second sample
(field 39 of /proc/<pid>/task/<tid>/stat is only the last core): a fair guess at 50-100 ms."""
import statistics as st
import sys

HZ = 100           # clock ticks per second (USER_HZ on Android)
BUSY = 0.30        # SAI still drawing while it uses more than this share of one core


def parse(path):
    cores, marks, samples, pids = {}, [], [], []
    cur = None
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if line.startswith('# cpu '):
            f = line.split()
            if len(f) >= 5 and f[4].isdigit():
                cores[int(f[2])] = int(f[4])
        elif line.startswith('# pids'):
            pids = line.split()[2:]
        elif line[:2] in ('S ', 'E '):
            marks.append((line[0], float(line[2:])))
        elif line.startswith('T '):
            cur = (float(line[2:]), {})
            samples.append(cur)
        elif line.startswith('P ') and cur is not None:
            _, pid, rest = line.split(' ', 2)
            tid = rest.split(' ', 1)[0]
            comm = rest[rest.find('(') + 1:rest.rfind(')')]
            f = rest[rest.rfind(')') + 2:].split()
            if len(f) < 37:
                continue        # a thread that ended mid-read
            cur[1][(pid, tid)] = (comm, int(f[11]) + int(f[12]), int(f[36]))
    return cores, marks, samples, pids


def intervals(samples):
    """(t0, t1, {key: (comm, ticks, core)}) for each pair of samples."""
    out = []
    for (t0, a), (t1, b) in zip(samples, samples[1:]):
        d = {}
        for k, (comm, ticks, core) in b.items():
            if k in a:
                d[k] = (comm, ticks - a[k][1], core)
        out.append((t0, t1, d))
    return out


def report(path):
    cores, marks, samples, pids = parse(path)
    iv = intervals(samples)
    name = path.split('/')[-1]
    if not iv:
        print(f"== {name}: no samples"); return
    fast = {c for c, k in cores.items() if k == max(cores.values())} if cores else set()
    print(f"== {name}: {len(iv)} samples over {iv[-1][1] - iv[0][0]:.1f} s, "
          f"fast cores {sorted(fast) or '?'}")

    starts = [t for k, t in marks if k == 'S']
    ends = [t for k, t in marks if k == 'E']
    if starts:
        # strokes: SAI is the only pid
        lo, hi = starts[0], ends[-1] + 2.0
        run = [x for x in iv if x[0] >= lo - 0.2 and x[1] <= hi + 0.2]
        total = sum(t for _, _, d in run for _, t, _ in d.values()) / HZ
        print(f"  SAI CPU for {len(starts)} strokes: {total:.1f} core-seconds")
        tails = []
        for e in ends:
            for t0, t1, d in iv:
                if t1 <= e:
                    continue
                ticks = sum(t for _, t, _ in d.values())
                if ticks < BUSY * (t1 - t0) * HZ:
                    tails.append(max(0.0, t0 - e) * 1000); break
        if tails:
            print(f"  SAI drawing after pen up, ms: median {st.median(tails):.0f}, "
                  f"min {min(tails):.0f}, max {max(tails):.0f}  (sample {1000 * (iv[0][1] - iv[0][0]):.0f} ms)")
        where(run, fast, by='thread')
    else:
        where(iv, fast, by='process', seconds=iv[-1][1] - iv[0][0])


def where(iv, fast, by, seconds=None):
    per_core, per = {}, {}
    for _, _, d in iv:
        for (pid, tid), (comm, ticks, core) in d.items():
            per_core[core] = per_core.get(core, 0) + ticks
            key = (pid, tid, comm) if by == 'thread' else pid
            c = per.setdefault(key, [comm, 0, 0])
            if by == 'process' and tid == pid:
                c[0] = comm
            c[1] += ticks
            if core in fast:
                c[2] += ticks
    total = sum(per_core.values())
    if not total:
        print("  no CPU time at all"); return
    on_fast = sum(t for c, t in per_core.items() if c in fast)
    cores = '  '.join(f"cpu{c} {100 * t / total:.0f}%" for c, t in sorted(per_core.items()) if t)
    print(f"  on the fast cores: {100 * on_fast / total:.0f}% of the time   ({cores})")
    top = sorted(per.items(), key=lambda kv: -kv[1][1])[:8]
    if by == 'thread':
        print("  busiest threads:  share of SAI's CPU, of it on fast cores")
        for (pid, tid, comm), (_, t, f) in top:
            print(f"    {comm[:15]:15} tid {tid:>6}  {100 * t / total:3.0f}%  fast {100 * f / max(t, 1):3.0f}%")
    else:
        print("  processes of the app, share of one core while idle:")
        for pid, (comm, t, f) in top:
            print(f"    {comm[:15]:15} pid {pid:>6}  {100 * t / HZ / seconds:5.1f}%  fast {100 * f / max(t, 1):3.0f}%")
        print(f"    all together {100 * total / HZ / seconds:.1f}%")


if __name__ == '__main__':
    for p in sys.argv[1:]:
        report(p)
