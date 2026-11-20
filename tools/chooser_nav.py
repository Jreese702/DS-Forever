"""PadTabCtl p57 THE CHOOSER AS A GRID, for the off-device tests: walk the Controller room's chooser with the D-pad the way a player
does (the spell tabs and Macros are grids of COLS a row, Extra a list under folding category headers), by what the add-on reports.

    from chooser_nav import Nav
    n = Nav(L, ctl)            # ctl(L) -> the newest padtabctl document's `c`
    n.tab("extra"); n.goto("DSMAP"); n.press("PAD1")
"""
COLS = 4
GRID = {"spells", "general", "professions", "macros"}


class Nav:
    def __init__(self, L, ctl):
        self.L = L
        self.ctl = ctl

    def press(self, key):
        self.L.execute('pp("%s")' % key)

    def ch(self):
        return self.ctl(self.L).get('chooser') or {}

    def rows(self):
        t = self.L.eval('WowCompanion.padTabCtl.chooserRows()')
        out = []
        for i in range(1, len(t) + 1):
            r = dict(t[i])
            k = r.get('kind')
            if k in ('mount', 'item'):
                rid = r.get('key')
            elif k == 'macro':
                rid = 'mc:' + r.get('key')
            elif k == 'head':
                rid = 'h:' + r.get('key')
            else:
                rid = r.get('id') if r.get('id') is not None else (r.get('key') if r.get('key') is not None else k)
            if isinstance(rid, float):
                rid = int(rid)
            out.append(rid)
        return out

    def to_tabs(self):
        for _ in range(60):
            if self.ch().get('tabs'):
                return True
            self.press("PADDUP")
        return False

    def tab(self, name, down=True):
        """the chooser's tab [name] by its tab row (landing opens it), then Down into its rows"""
        self.to_tabs()
        for d in ("PADDRIGHT", "PADDLEFT"):
            for _ in range(6):
                if self.ch().get('list') == name:
                    break
                was = self.ch().get('list')
                self.press(d)
                if self.ch().get('list') == was:
                    break
            if self.ch().get('list') == name:
                break
        if down:
            self.press("PADDDOWN")
        return self.ch().get('list') == name

    def category_of(self, ident):
        xl = self.L.eval('WowCompanion.padMap.extraList()')
        for i in range(1, len(xl) + 1):
            x = dict(xl[i])
            key = x.get('key') if x.get('kind') in ('command', 'bind') else ('m:' if x.get('kind') == 'mount' else 'i:') + str(int(x.get('id')))
            if key == ident:
                k = x.get('kind')
                if k == 'command':
                    return 'ds' if str(x.get('key')).startswith('DS') else 'cmd'
                return k
        return None

    def goto(self, ident):
        """the cursor onto row [ident] (a spell id, a command key, "m:<id>", "mc:<name>", "h:<cat>", "clear"); a folded Extra category
        is opened on the way (A on its header). True when it got there"""
        rows = self.rows()
        if ident not in rows and self.ch().get('list') == 'extra':
            cat = self.category_of(ident)
            if cat and self.goto('h:' + cat):
                self.press("PAD1")
                rows = self.rows()
        if ident not in rows:
            return False
        if self.ch().get('tabs'):
            self.press("PADDDOWN")
        t = rows.index(ident) + 1
        grid = self.ch().get('list') in GRID
        for _ in range(200):
            r = self.ch().get('row') or 1
            if r == t:
                return True
            if not grid:
                self.press("PADDDOWN" if t > r else "PADDUP")
                continue
            pinned = rows[-1] in ('clear', 'uncombat', 'uncface')
            cells = len(rows) - (1 if pinned else 0)
            if pinned and t == len(rows):
                self.press("PADDDOWN")
                continue
            if pinned and r == len(rows):
                self.press("PADDUP")
                continue
            rr, tr = (r - 1) // COLS, (t - 1) // COLS
            if rr != tr:
                self.press("PADDDOWN" if tr > rr else "PADDUP")
            else:
                self.press("PADDRIGHT" if t > r else "PADDLEFT")
        return self.ch().get('id') == ident

    def open_all(self):
        """every Extra category opened (A on each folded header)"""
        for _ in range(10):
            rows = self.rows()
            heads = [r for r in rows if isinstance(r, str) and r.startswith('h:')]
            xo = (self.ctl(self.L).get('xo') or '').split(',')
            shut = [h for h in heads if h[2:] not in xo]
            if not shut:
                return True
            self.goto(shut[0])
            self.press("PAD1")
        return False
