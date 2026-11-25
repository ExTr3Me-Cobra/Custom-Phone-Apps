"""Document Finder: finds pictures of documents, screenshots, memes, signs etc. among photos, then lets you review them.
Nothing moves until you approve it: approved images move to the destination folder (keeping their subfolders);
rejected ones never move. Every move is logged and can be undone."""
import json, logging, os, platform, queue, shutil, sys, threading, time, traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import numpy as np
from labels import DOC, PHOTO
from PIL import Image, ImageOps, ImageTk

try:
    import pillow_heif; pillow_heif.register_heif_opener(); HEIF = True
except Exception:
    HEIF = False

APP = 'Document Finder'
BASE = Path(getattr(sys, '_MEIPASS', Path(__file__).parent))
DATA = Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'DocumentFinder'
DATA.mkdir(parents=True, exist_ok=True)
LOG = DATA / 'document_finder.log'
MOVES = DATA / 'moves.jsonl'
SETTINGS = DATA / 'settings.json'
LAST = DATA / 'last_scan.json'
EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif', '.tif', '.tiff', '.heic', '.heif'}
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)

logging.basicConfig(filename=LOG, level=logging.DEBUG, encoding='utf-8',
                    format='%(asctime)s.%(msecs)03d %(levelname)s [%(threadName)s] %(message)s', datefmt='%Y-%m-%d %H:%M:%S')
log = logging.getLogger('df')
import faulthandler
_fh = open(DATA / 'crash.log', 'a', encoding='utf-8'); faulthandler.enable(_fh)  # hard crashes (native code) land here
sys.excepthook = lambda t, v, tb: log.critical('UNCAUGHT %s', ''.join(traceback.format_exception(t, v, tb)))
threading.excepthook = lambda a: log.critical('UNCAUGHT in thread %s: %s', a.thread.name,
                                               ''.join(traceback.format_exception(a.exc_type, a.exc_value, a.exc_traceback)))


def load_settings():
    try: return json.loads(SETTINGS.read_text())
    except Exception: return {}


def save_settings(s):
    try: SETTINGS.write_text(json.dumps(s, indent=1))
    except Exception: log.exception('saving settings')


class Model:
    """CLIP image encoder (ONNX) on the GPU through DirectML, falling back to the CPU."""
    def __init__(self):
        import onnxruntime as ort
        log.info('onnxruntime %s, providers available: %s', ort.__version__, ort.get_available_providers())
        so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        provs = [p for p in ('DmlExecutionProvider', 'CUDAExecutionProvider') if p in ort.get_available_providers()] + ['CPUExecutionProvider']
        self.sess = None
        for p in provs:
            try:
                self.sess = ort.InferenceSession(str(BASE / 'model' / 'visual.onnx'), so, providers=[p])
                self.sess.run(None, {'x': np.zeros((2, 3, 224, 224), np.float32)})  # warm-up / check it works
                self.provider = p; break
            except Exception:
                log.exception('provider %s failed, trying the next', p)
        log.info('using provider %s', self.provider)
        self.text = np.load(BASE / 'model' / 'text.npy')
        meta = json.loads((BASE / 'model' / 'meta.json').read_text())
        self.n_doc, self.scale = meta['n_doc'], meta['scale']

    def score(self, batch):
        f = self.sess.run(None, {'x': batch})[0]
        logits = self.scale * f @ self.text.T
        logits -= logits.max(1, keepdims=True)
        p = np.exp(logits); p /= p.sum(1, keepdims=True)
        doc = p[:, :self.n_doc].sum(1)
        return doc, p.argmax(1)


def prep(path):
    """Decode quickly (JPEG draft mode), centre-crop to 224 px. Returns the array or None."""
    t = time.perf_counter()
    try:
        im = Image.open(path)
        if im.format == 'JPEG': im.draft('RGB', (448, 448))
        im = ImageOps.exif_transpose(im).convert('RGB')
        w, h = im.size; s = 224 / min(w, h)
        im = im.resize((max(224, round(w * s)), max(224, round(h * s))), Image.BICUBIC)
        w, h = im.size; l, tp = (w - 224) // 2, (h - 224) // 2
        a = (np.asarray(im.crop((l, tp, l + 224, tp + 224)), np.float32) / 255 - MEAN) / STD
        return a.transpose(2, 0, 1), (time.perf_counter() - t) * 1000
    except Exception as e:
        log.warning('could not read %s: %r', path, e)
        return None, (time.perf_counter() - t) * 1000


def unique(dest):
    if not dest.exists(): return dest
    n = 1
    while True:
        c = dest.with_name(f'{dest.stem} ({n}){dest.suffix}')
        if not c.exists(): return c
        n += 1


class App:
    def __init__(self, root):
        self.root = root; root.title(APP); root.geometry('1100x800'); root.minsize(800, 600)
        self.s = load_settings(); self.model = None; self.items = []; self.idx = 0; self.history = []
        self.q = queue.Queue(); self.stop = threading.Event()
        log.info('=== %s start | Python %s | %s | %s | cores %s | HEIC %s', APP, sys.version.split()[0], platform.platform(),
                 platform.processor(), os.cpu_count(), HEIF)
        self.build_setup()

    # ---------- setup screen ----------
    def build_setup(self):
        for w in self.root.winfo_children(): w.destroy()
        f = ttk.Frame(self.root, padding=20); f.pack(fill='both', expand=True)
        ttk.Label(f, text=APP, font=('Segoe UI', 20, 'bold')).pack(anchor='w')
        ttk.Label(f, text='Finds documents, screenshots, memes, signs and other non-photos. Nothing moves until you approve it.').pack(anchor='w', pady=(0, 15))
        self.src = tk.StringVar(value=self.s.get('src', '')); self.dst = tk.StringVar(value=self.s.get('dst', ''))
        for label, var in (('Folder to scan (subfolders included)', self.src), ('Destination for approved documents', self.dst)):
            ttk.Label(f, text=label).pack(anchor='w')
            r = ttk.Frame(f); r.pack(fill='x', pady=(0, 10))
            ttk.Entry(r, textvariable=var).pack(side='left', fill='x', expand=True)
            ttk.Button(r, text='Browse…', command=lambda v=var, l=label: self.browse(v, l)).pack(side='left', padx=5)
        self.thr = tk.DoubleVar(value=self.s.get('thr', 0.35))
        r = ttk.Frame(f); r.pack(fill='x', pady=5)
        ttk.Label(r, text='Sensitivity (lower = catches more):').pack(side='left')
        ttk.Scale(r, from_=0.05, to=0.95, variable=self.thr, length=300).pack(side='left', padx=5)
        self.thr_l = ttk.Label(r, width=6); self.thr_l.pack(side='left')
        self.thr.trace_add('write', lambda *_: self.thr_l.config(text=f'{self.thr.get():.2f}')); self.thr_l.config(text=f'{self.thr.get():.2f}')
        b = ttk.Frame(f); b.pack(fill='x', pady=15)
        self.go = ttk.Button(b, text='Scan', command=self.start_scan); self.go.pack(side='left')
        ttk.Button(b, text='Stop', command=self.stop.set).pack(side='left', padx=5)
        ttk.Button(b, text='Resume last scan', command=self.resume).pack(side='left', padx=5)
        ttk.Button(b, text='Undo last session’s moves', command=self.undo_session).pack(side='left', padx=5)
        ttk.Button(b, text='Export log…', command=self.export_log).pack(side='right')
        self.prog = ttk.Progressbar(f, mode='determinate'); self.prog.pack(fill='x', pady=5)
        self.status = ttk.Label(f, text=''); self.status.pack(anchor='w')

    def browse(self, var, title):
        log.info('browse: %s (current %r)', title, var.get())
        try:
            d = filedialog.askdirectory(parent=self.root, title=title, mustexist=False,
                                        initialdir=var.get() if Path(var.get() or '.').is_dir() else str(Path.home()))
            log.info('browse result: %r', d)
            if d: var.set(str(Path(d)))
        except Exception:
            log.exception('folder picker failed'); messagebox.showerror(APP, 'The folder picker failed. You can type or paste the folder path instead.')

    def start_scan(self):
        src, dst = Path(self.src.get()), Path(self.dst.get())
        if not src.is_dir(): return messagebox.showerror(APP, 'Pick a folder to scan.')
        if not self.dst.get(): return messagebox.showerror(APP, 'Pick a destination folder.')
        self.s.update(src=str(src), dst=str(dst), thr=round(self.thr.get(), 3)); save_settings(self.s)
        self.stop.clear(); self.go.config(state='disabled')
        threading.Thread(target=self.scan, args=(src, dst, self.thr.get()), name='scan', daemon=True).start()
        self.poll()

    def poll(self):
        try:
            while True:
                kind, *v = self.q.get_nowait()
                if kind == 'status': self.status.config(text=v[0])
                elif kind == 'prog': self.prog.config(maximum=v[1], value=v[0])
                elif kind == 'done': return self.scan_done(v[0])
                elif kind == 'error': self.go.config(state='normal'); return messagebox.showerror(APP, v[0])
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def scan(self, src, dst, thr):
        try:
            t0 = time.perf_counter()
            if self.model is None:
                self.q.put(('status', 'Loading the model…')); self.model = Model()
            dst_r = dst.resolve()
            files = [p for p in src.rglob('*') if p.suffix.lower() in EXTS and dst_r not in p.resolve().parents]
            log.info('scan %s -> %s | threshold %.3f | %d images | listing %.1fs', src, dst, thr, len(files), time.perf_counter() - t0)
            found, done, bad, B = [], 0, 0, 64
            workers = max(4, (os.cpu_count() or 8) - 2)
            with ThreadPoolExecutor(workers, thread_name_prefix='decode') as ex:
                for start in range(0, len(files), B):
                    if self.stop.is_set(): log.info('stopped by user at %d', done); break
                    chunk = files[start:start + B]
                    res = list(ex.map(prep, chunk))
                    ok = [(p, a) for p, (a, _) in zip(chunk, res) if a is not None]; bad += len(chunk) - len(ok)
                    if ok:
                        ti = time.perf_counter()
                        doc, top = self.model.score(np.stack([a for _, a in ok]))
                        inf = (time.perf_counter() - ti) * 1000
                        lbl = DOC + PHOTO
                        for (p, _), d, t in zip(ok, doc, top):
                            log.debug('score %.3f top=%s %s', d, lbl[t], p)
                            if d >= thr: found.append((float(d), p, lbl[t]))
                        log.debug('batch %d: %d imgs, decode avg %.1fms, infer %.0fms', start // B, len(ok),
                                  sum(ms for _, ms in res) / len(res), inf)
                    done += len(chunk)
                    el = time.perf_counter() - t0
                    self.q.put(('prog', done, len(files)))
                    self.q.put(('status', f'{done}/{len(files)} scanned · {len(found)} found · {done / max(el, 1e-6):.0f}/s · {self.model.provider}'))
            found.sort(key=lambda x: -x[0])
            try: LAST.write_text(json.dumps({'src': str(src), 'dst': str(dst), 'thr': thr, 'items': [[d, str(p), l] for d, p, l in found]}))
            except Exception: log.exception('saving the scan')
            log.info('scan finished: %d scanned, %d unreadable, %d found, %.1fs', done, bad, len(found), time.perf_counter() - t0)
            self.q.put(('done', (found, src, dst)))
        except Exception:
            log.exception('scan failed'); self.q.put(('error', 'Scan failed. Use Export log and send it over.'))

    def resume(self):
        """Reopens the last scan: from last_scan.json, or rebuilt from the log's score lines."""
        import re
        src, dst = Path(self.src.get()), Path(self.dst.get())
        thr = self.thr.get(); items = None
        try:
            j = json.loads(LAST.read_text()); src, dst = Path(j['src']), Path(j['dst'])
            items = [(d, Path(p), l) for d, p, l in j['items']]; log.info('resume from last_scan.json: %d', len(items))
        except Exception:
            text = LOG.read_text(encoding='utf-8', errors='replace')
            k = text.rfind(' scan ' + str(src) + ' -> ')
            if k < 0: k = text.rfind('] scan ')
            if k < 0: return messagebox.showinfo(APP, 'No earlier scan found.')
            m = re.search(r'scan (.+?) -> (.+?) \| threshold ([0-9.]+)', text[k - 50:k + 2000])
            if m: src, dst, thr = Path(m.group(1)), Path(m.group(2)), float(m.group(3))
            items = [(float(a), Path(c), b) for a, b, c in re.findall(r'score ([0-9.]+) top=(.+?) ([A-Za-z]:\\.+)', text[k:]) if float(a) >= thr]
            items.sort(key=lambda x: -x[0]); log.info('resume rebuilt from the log: %d (threshold %.3f)', len(items), thr)
        items = [it for it in items if it[1].exists()]
        if not items: return messagebox.showinfo(APP, 'Nothing left from the last scan.')
        self.src.set(str(src)); self.dst.set(str(dst))
        self.scan_done((items, src, dst))

    def scan_done(self, r):
        self.items, self.src_root, self.dst_root = r
        self.session = time.strftime('%Y%m%d-%H%M%S'); self.idx = 0; self.history = []
        if not self.items:
            self.go.config(state='normal'); return messagebox.showinfo(APP, 'No documents found.')
        self.build_review()

    # ---------- review ----------
    # Each found item: [score, original path, top label, current path or None while still in place].
    # Undo stack: list of batches; each batch is a list of (item index, path it was moved to).
    def build_review(self):
        self.items = [[d, p, l, None] for d, p, l in self.items]
        self.undo_stack = []; self.sel = set(); self.anchor = None; self.mode = 'full'
        self.thumb_px = tk.IntVar(value=self.s.get('thumb', 160)); self.thumbs = {}; self.pil = {}; self.tq = queue.Queue(); self.poll_thumbs()
        self.pool = ThreadPoolExecutor(8, thread_name_prefix='thumb')
        for k, fn in (('<Left>', lambda e: self.step(-1)), ('<Right>', lambda e: self.step(1)), ('<Delete>', lambda e: self.delete_key()),
                      ('<Control-z>', lambda e: self.undo())):
            self.root.bind(k, fn)
        self.mode = 'full'; self.full_view()

    def frame_top(self, title):
        for w in self.root.winfo_children(): w.destroy()
        top = ttk.Frame(self.root, padding=6); top.pack(side='top', fill='x')
        self.info = ttk.Label(self.root, text=title, font=('Segoe UI', 10), anchor='w', padding=(8, 0))
        self.info.pack(side='top', fill='x')
        self.info.bind('<Configure>', lambda e: self.info.config(wraplength=max(200, e.width - 16)))
        ttk.Button(top, text='Export log…', command=self.export_log).pack(side='right')
        ttk.Button(top, text='Finish', command=self.finish).pack(side='right', padx=4)
        ttk.Button(top, text='Undo (Ctrl+Z)', command=self.undo).pack(side='right', padx=4)
        ttk.Button(top, text='Split view' if self.mode == 'full' else 'Full view', command=self.toggle_view).pack(side='right', padx=4)

    def toggle_view(self):
        self.mode = 'split' if self.mode == 'full' else 'full'
        log.info('view -> %s', self.mode)
        self.full_view() if self.mode == 'full' else self.split_view()

    def move_items(self, idxs):
        """Moves these found images to the destination (keeping subfolders). One undo step."""
        batch = []
        for i in idxs:
            d, p, lbl, cur = self.items[i]
            if cur: continue
            try:
                dest = unique(self.dst_root / p.relative_to(self.src_root)); dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(p), str(dest)); self.items[i][3] = dest; batch.append((i, dest))
                with open(MOVES, 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'session': self.session, 'from': str(p), 'to': str(dest), 't': time.time()}) + '\n')
                log.info('moved score=%.3f top=%s %s -> %s', d, lbl, p, dest)
            except Exception as e:
                log.exception('move failed %s', p); messagebox.showerror(APP, f'Could not move {p.name}:\n{e}'); break
        if batch: self.undo_stack.append(batch)
        return batch

    def undo(self):
        if not self.undo_stack: return
        batch = self.undo_stack.pop()
        for i, dest in reversed(batch):
            orig = self.items[i][1]
            try:
                orig.parent.mkdir(parents=True, exist_ok=True); shutil.move(str(dest), str(orig)); self.items[i][3] = None
                with open(MOVES, 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'session': self.session, 'undo': str(dest), 'back': str(orig)}) + '\n')
                log.info('undo: %s -> %s', dest, orig)
            except Exception as e:
                log.exception('undo failed'); messagebox.showerror(APP, f'Could not move it back:\n{e}')
        if self.mode == 'full': self.idx = batch[0][0]; self.show()
        else: self.refresh_split()

    # full view: ← → browse, Delete moves the shown image to the destination
    def full_view(self):
        self.frame_top('')
        b = ttk.Frame(self.root, padding=8); b.pack(side='bottom', fill='x')
        box = tk.Frame(self.root, bg='#111'); box.pack(side='top', fill='both', expand=True); box.pack_propagate(False)
        self.canvas = tk.Label(box, bg='#111', fg='white'); self.canvas.pack(fill='both', expand=True)
        ttk.Button(b, text='◀ Previous (←)', command=lambda: self.step(-1)).pack(side='left')
        ttk.Button(b, text='Next (→) ▶', command=lambda: self.step(1)).pack(side='left', padx=4)
        ttk.Button(b, text='Move to destination (Delete)', command=self.delete_key).pack(side='right')
        self.idx = min(getattr(self, 'idx', 0), len(self.items) - 1)
        self.root.bind('<Configure>', lambda e: self.show() if e.widget is self.root and self.mode == 'full' else None)
        self.root.after(50, self.show)

    def step(self, d):
        if self.mode != 'full': return
        self.idx = max(0, min(len(self.items) - 1, self.idx + d)); self.show()

    def delete_key(self):
        if self.mode == 'split': return self.move_selected()
        if self.items[self.idx][3] is None: self.move_items([self.idx])
        if self.idx < len(self.items) - 1: self.idx += 1
        self.show()

    def show(self):
        if self.mode != 'full' or not self.canvas.winfo_exists(): return
        d, p, lbl, cur = self.items[self.idx]
        moved = sum(1 for it in self.items if it[3])
        self.info.config(text=f'{self.idx + 1} / {len(self.items)}  ·  {moved} moved  ·  score {d:.2f} ({lbl})'
                              f'  ·  {"MOVED → " + str(cur) if cur else p}')
        try:
            im = ImageOps.exif_transpose(Image.open(cur or p)).convert('RGB')
            im.thumbnail((max(self.canvas.winfo_width(), 200), max(self.canvas.winfo_height(), 200)))
            self.tkimg = ImageTk.PhotoImage(im); self.canvas.config(image=self.tkimg, text='')
        except Exception as e:
            log.warning('preview failed %s: %r', p, e); self.canvas.config(image='', text=f'Cannot show this image\n{e}')

    # split view: left = destination folder, right = found images still in place
    def split_view(self):
        self.frame_top('Split view: click to select, Ctrl+click to add, Shift+click for a range. Double-click opens it full size.')
        bar = ttk.Frame(self.root, padding=(8, 4)); bar.pack(side='top', fill='x')
        ttk.Button(bar, text='◀ Move selected to destination (Delete)', command=self.move_selected).pack(side='left')
        ttk.Button(bar, text='Select all', command=lambda: (self.sel.update(self.right_ids()), self.refresh_split())).pack(side='left', padx=4)
        ttk.Button(bar, text='Select none', command=lambda: (self.sel.clear(), self.refresh_split())).pack(side='left')
        ttk.Label(bar, text='Thumbnail size').pack(side='right')
        sc = ttk.Scale(bar, from_=80, to=400, variable=self.thumb_px, length=180); sc.pack(side='right', padx=4)
        sc.bind('<ButtonRelease-1>', lambda e: self.resize_thumbs())  # redraw once you let go
        ttk.Button(bar, text='Change destination…', command=self.change_dst).pack(side='left', padx=12)
        pw = ttk.PanedWindow(self.root, orient='horizontal'); pw.pack(fill='both', expand=True, padx=6, pady=6)
        self.left = self.grid_panel(pw, 'Destination'); self.right = self.grid_panel(pw, 'Detected')
        self.refresh_split()

    def grid_panel(self, pw, title):
        f = ttk.Frame(pw); pw.add(f, weight=1)
        lab = ttk.Label(f, font=('Segoe UI', 11, 'bold')); lab.pack(anchor='w')
        c = tk.Canvas(f, bg='#1b1b1b', highlightthickness=0); sb = ttk.Scrollbar(f, command=c.yview)
        c.configure(yscrollcommand=sb.set); sb.pack(side='right', fill='y'); c.pack(fill='both', expand=True)
        c.bind('<Configure>', lambda e: self.root.after_idle(self.layout))
        c.bind('<Enter>', lambda e: c.bind_all('<MouseWheel>', lambda ev: c.yview_scroll(-ev.delta // 120 * 3, 'units')))
        return {'c': c, 'title': title, 'label': lab, 'paths': []}

    def right_ids(self): return [i for i, it in enumerate(self.items) if it[3] is None]

    def refresh_split(self):
        if self.mode != 'split': return
        try: dst = sorted((p for p in self.dst_root.rglob('*') if p.suffix.lower() in EXTS), key=lambda p: p.stat().st_mtime, reverse=True)
        except Exception: dst = []
        self.left['paths'] = [(None, p) for p in dst]
        self.right['paths'] = [(i, self.items[i][1]) for i in self.right_ids()]
        self.sel &= set(self.right_ids())
        self.left['label'].config(text=f'Destination: {self.dst_root}  ({len(dst)})')
        self.right['label'].config(text=f'Detected, not moved yet ({len(self.right["paths"])}) · {len(self.sel)} selected')
        self.layout()

    def change_dst(self):
        d = filedialog.askdirectory(parent=self.root, title='Destination for approved documents', initialdir=str(self.dst_root))
        if not d: return
        self.dst_root = Path(d); self.dst.set(str(self.dst_root)); self.s['dst'] = str(self.dst_root); save_settings(self.s)
        log.info('destination changed to %s', self.dst_root); self.refresh_split()

    def poll_thumbs(self):
        """Thumbnails are decoded on worker threads; Tk images are only made here, on the UI thread."""
        got = False
        try:
            while True:
                p, im = self.tq.get_nowait(); self.pil[p] = im; got = True
        except queue.Empty:
            pass
        if got: self.layout()
        if self.mode is not None: self.root.after(150, self.poll_thumbs)

    def resize_thumbs(self):
        self.s['thumb'] = int(self.thumb_px.get()); save_settings(self.s)
        self.thumbs.clear(); self.layout()  # new size: remake the Tk images from the kept pictures

    def layout(self):
        if self.mode != 'split': return
        t = int(self.thumb_px.get()); gap = 6
        for panel in (self.left, self.right):
            c = panel['c']; c.delete('all'); w = max(c.winfo_width(), t + gap)
            cols = max(1, w // (t + gap))
            for n, (i, p) in enumerate(panel['paths']):
                x, y = gap + (n % cols) * (t + gap), gap + (n // cols) * (t + gap)
                if i is not None and i in self.sel:
                    c.create_rectangle(x - 3, y - 3, x + t + 3, y + t + 3, outline='#22c3ff', width=4)
                img = self.thumb(p, t)
                tag = f'n{n}'
                if img: c.create_image(x + t // 2, y + t // 2, image=img, tags=tag)
                else: c.create_rectangle(x, y, x + t, y + t, fill='#333', outline='', tags=tag)
                if i is not None:
                    c.tag_bind(tag, '<Button-1>', lambda e, i=i: self.click(i, e))
                    c.tag_bind(tag, '<Double-Button-1>', lambda e, i=i: self.open_full(i))
            rows = (len(panel['paths']) + cols - 1) // cols
            c.configure(scrollregion=(0, 0, w, gap + rows * (t + gap)))

    def thumb(self, p, t):
        k = (str(p), t)
        if k in self.thumbs: return self.thumbs[k]
        im = self.pil.get(str(p))
        if im is None:
            if str(p) not in self.pil:
                self.pil[str(p)] = None  # loading
                def load(p=p):
                    try:
                        x = Image.open(p)
                        if x.format == 'JPEG': x.draft('RGB', (800, 800))
                        x = ImageOps.exif_transpose(x).convert('RGB'); x.thumbnail((400, 400))
                    except Exception as e:
                        log.debug('thumb failed %s: %r', p, e); x = Image.new('RGB', (40, 40), '#552222')
                    self.tq.put((str(p), x))
                self.pool.submit(load)
            return None
        c = im.copy(); c.thumbnail((t, t)); self.thumbs[k] = ImageTk.PhotoImage(c)
        return self.thumbs[k]

    def schedule_layout(self):
        if not getattr(self, '_lay', False):
            self._lay = True; self.root.after(150, lambda: (setattr(self, '_lay', False), self.layout()))

    def click(self, i, e):
        ids = self.right_ids()
        if e.state & 0x1 and self.anchor in ids:  # Shift: range
            a, b = sorted((ids.index(self.anchor), ids.index(i))); self.sel.update(ids[a:b + 1])
        elif e.state & 0x4:  # Ctrl: toggle
            self.sel ^= {i}; self.anchor = i
        else:
            self.sel = {i}; self.anchor = i
        self.refresh_split()

    def move_selected(self):
        if not self.sel: return
        log.info('move selected: %d', len(self.sel))
        self.move_items(sorted(self.sel)); self.sel.clear(); self.refresh_split()

    def open_full(self, i):
        self.idx = i; self.mode = 'full'; self.full_view()

    def finish(self):
        n = sum(1 for it in self.items if it[3])
        log.info('review finished: %d moved of %d found', n, len(self.items))
        messagebox.showinfo(APP, f'Done. {n} image(s) moved to the destination.')
        for k in ('<Left>', '<Right>', '<Delete>', '<Configure>', '<Control-z>', '<MouseWheel>'): self.root.unbind_all(k) if k == '<MouseWheel>' else self.root.unbind(k)
        self.pool.shutdown(wait=False, cancel_futures=True); self.mode = None
        self.build_setup()

    # ---------- tools ----------
    def undo_session(self):
        try: rows = [json.loads(l) for l in open(MOVES, encoding='utf-8')]
        except FileNotFoundError: return messagebox.showinfo(APP, 'No moves to undo.')
        last = next((r['session'] for r in reversed(rows) if 'session' in r), None)
        undone = {r['undo'] for r in rows if r.get('session') == last and 'undo' in r}
        todo = [r for r in rows if r.get('session') == last and 'to' in r and r['to'] not in undone]
        if not todo or not messagebox.askyesno(APP, f'Move {len(todo)} image(s) from the last session back where they came from?'): return
        ok = 0
        for r in reversed(todo):
            try:
                if Path(r['to']).exists():
                    back = unique(Path(r['from'])); back.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(r['to'], str(back)); ok += 1
                    with open(MOVES, 'a', encoding='utf-8') as f:
                        f.write(json.dumps({'session': last, 'undo': r['to'], 'back': str(back)}) + '\n')
            except Exception:
                log.exception('session undo failed for %s', r)
        log.info('session %s undone: %d of %d', last, ok, len(todo)); messagebox.showinfo(APP, f'Moved {ok} back.')

    def export_log(self):
        out = filedialog.asksaveasfilename(parent=self.root, title='Export log', defaultextension='.txt',
                                           initialfile=f'document_finder_log_{time.strftime("%Y%m%d-%H%M%S")}.txt',
                                           initialdir=str(Path.home() / 'Desktop'), filetypes=[('Text file', '*.txt'), ('All files', '*.*')])
        log.info('export log to %r', out)
        if not out: return
        out = str(Path(out))
        for h in log.handlers + logging.getLogger().handlers: h.flush()
        try:
            with open(out, 'w', encoding='utf-8') as o:
                o.write(f'{APP} log export {time.ctime()}\nsettings: {json.dumps(self.s)}\n\n')
                o.write(LOG.read_text(encoding='utf-8', errors='replace')[-20_000_000:])
                if MOVES.exists(): o.write('\n\n=== moves.jsonl ===\n' + MOVES.read_text(encoding='utf-8')[-2_000_000:])
                c = DATA / 'crash.log'
                if c.exists(): o.write('\n\n=== crash.log ===\n' + c.read_text(encoding='utf-8', errors='replace')[-2_000_000:])
            size = Path(out).stat().st_size
            log.info('exported %d bytes', size)
            try: __import__('subprocess').Popen(['explorer', '/select,', out])  # show it in File Explorer
            except Exception: pass
            messagebox.showinfo(APP, f'Saved ({size // 1024} KB):\n{out}')
        except Exception as e:
            log.exception('export failed'); messagebox.showerror(APP, str(e))


if __name__ == '__main__':
    root = tk.Tk()
    root.report_callback_exception = lambda t, v, tb: (log.critical('UI error %s', ''.join(traceback.format_exception(t, v, tb))),
                                                        messagebox.showerror(APP, f'Something went wrong: {v}\nUse Export log and send it over.'))
    try: ttk.Style().theme_use('vista')
    except Exception: pass
    App(root); root.mainloop()
