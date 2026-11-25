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
            log.info('scan finished: %d scanned, %d unreadable, %d found, %.1fs', done, bad, len(found), time.perf_counter() - t0)
            self.q.put(('done', (found, src, dst)))
        except Exception:
            log.exception('scan failed'); self.q.put(('error', 'Scan failed. Use Export log and send it over.'))

    def scan_done(self, r):
        self.items, self.src_root, self.dst_root = r
        self.session = time.strftime('%Y%m%d-%H%M%S'); self.idx = 0; self.history = []
        if not self.items:
            self.go.config(state='normal'); return messagebox.showinfo(APP, 'No documents found.')
        self.build_review(); self.show()

    # ---------- review ----------
    def build_review(self):
        for w in self.root.winfo_children(): w.destroy()
        top = ttk.Frame(self.root, padding=8); top.pack(fill='x')
        self.info = ttk.Label(top, font=('Segoe UI', 11)); self.info.pack(side='left')
        ttk.Button(top, text='Export log…', command=self.export_log).pack(side='right')
        ttk.Button(top, text='Finish', command=self.finish).pack(side='right', padx=5)
        self.canvas = tk.Label(self.root, bg='#111'); self.canvas.pack(fill='both', expand=True)
        b = ttk.Frame(self.root, padding=10); b.pack(fill='x')
        ttk.Button(b, text='◀ Back / Undo  (←, Ctrl+Z)', command=self.undo).pack(side='left')
        ttk.Button(b, text='Not a document  (N)', command=lambda: self.decide(False)).pack(side='right', padx=5)
        ttk.Button(b, text='Document  (Y)', command=lambda: self.decide(True)).pack(side='right')
        for k, fn in (('y', lambda e: self.decide(True)), ('n', lambda e: self.decide(False)), ('<Left>', lambda e: self.undo()),
                      ('<Control-z>', lambda e: self.undo())):
            self.root.bind(k if k.startswith('<') else k, fn)
        self.root.bind('<Configure>', lambda e: self.show() if e.widget is self.root else None)

    def show(self):
        if not hasattr(self, 'canvas') or not self.canvas.winfo_exists(): return
        if self.idx >= len(self.items): return self.finish()
        d, p, lbl = self.items[self.idx]
        self.info.config(text=f'{self.idx + 1} / {len(self.items)}   ·   score {d:.2f} ({lbl})   ·   {p}')
        try:
            im = ImageOps.exif_transpose(Image.open(p)).convert('RGB')
            W, H = max(self.canvas.winfo_width(), 200), max(self.canvas.winfo_height(), 200)
            im.thumbnail((W, H)); self.tkimg = ImageTk.PhotoImage(im); self.canvas.config(image=self.tkimg, text='')
        except Exception as e:
            log.warning('preview failed %s: %r', p, e); self.canvas.config(image='', text=f'Cannot show this image\n{e}', fg='white')

    def decide(self, is_doc):
        if self.idx >= len(self.items): return
        d, p, lbl = self.items[self.idx]; moved = None
        if is_doc:
            try:
                rel = p.relative_to(self.src_root)
                dest = unique(self.dst_root / rel); dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(p), str(dest)); moved = dest
                with open(MOVES, 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'session': self.session, 'from': str(p), 'to': str(dest), 't': time.time()}) + '\n')
            except Exception as e:
                log.exception('move failed %s', p); return messagebox.showerror(APP, f'Could not move it:\n{e}')
        log.info('decision %s score=%.3f top=%s %s%s', 'DOC' if is_doc else 'NOT', d, lbl, p, f' -> {moved}' if moved else '')
        self.history.append((self.idx, moved)); self.idx += 1; self.show()

    def undo(self):
        if not self.history: return
        i, moved = self.history.pop()
        if moved:
            orig = self.items[i][1]
            try:
                orig.parent.mkdir(parents=True, exist_ok=True); shutil.move(str(moved), str(orig))
                with open(MOVES, 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'session': self.session, 'undo': str(moved), 'back': str(orig)}) + '\n')
            except Exception as e:
                log.exception('undo failed'); return messagebox.showerror(APP, f'Could not move it back:\n{e}')
        log.info('undo item %d (%s)', i, 'moved back' if moved else 'no move')
        self.idx = i; self.show()

    def finish(self):
        n = sum(1 for _, m in self.history if m)
        log.info('review finished: %d reviewed, %d moved', len(self.history), n)
        messagebox.showinfo(APP, f'Done. {n} image(s) moved to the destination.\nReviewed {len(self.history)} of {len(self.items)}.')
        self.root.unbind('y'); self.root.unbind('n'); self.root.unbind('<Configure>'); self.canvas = None
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
