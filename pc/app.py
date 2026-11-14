"""Teks ke Suara (PC) versi jendela (Tkinter)."""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from tts_core import SynthesisError, synthesize_blocking

VOICE_LABELS = {
    "Gadis (wanita)": "gadis",
    "Ardi (pria)": "ardi",
}


def open_with_default_player(path: str) -> None:
    if sys.platform.startswith("win"):
        os.startfile(path)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


class App(ttk.Frame):
    def __init__(self, root: tk.Tk) -> None:
        super().__init__(root, padding=12)
        self.root = root
        self.events: queue.Queue = queue.Queue()
        self.busy = False

        root.title("Teks ke Suara")
        root.geometry("760x640")
        root.minsize(560, 520)
        self.pack(fill="both", expand=True)

        self.voice_var = tk.StringVar(value="Gadis (wanita)")
        self.rate_var = tk.IntVar(value=0)
        self.pitch_var = tk.IntVar(value=0)
        self.volume_var = tk.IntVar(value=0)
        self.normalize_var = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value="Siap. Butuh koneksi internet untuk membuat suara.")

        self._build()
        self.root.after(100, self._poll)

    # ---------------------------------------------------------------- UI

    def _build(self) -> None:
        ttk.Label(self, text="Teks (boleh banyak baris)").pack(anchor="w")

        box = ttk.Frame(self)
        box.pack(fill="both", expand=True, pady=(4, 8))
        self.text = tk.Text(box, wrap="word", undo=True, font=("Segoe UI", 11), height=12)
        scroll = ttk.Scrollbar(box, command=self.text.yview)
        self.text.configure(yscrollcommand=scroll.set)
        self.text.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        row = ttk.Frame(self)
        row.pack(fill="x")
        ttk.Button(row, text="Buka file .txt…", command=self.open_file).pack(side="left")
        ttk.Button(row, text="Hapus", command=lambda: self.text.delete("1.0", "end")).pack(side="left", padx=6)

        opts = ttk.LabelFrame(self, text="Pengaturan", padding=8)
        opts.pack(fill="x", pady=10)
        opts.columnconfigure(1, weight=1)

        ttk.Label(opts, text="Suara").grid(row=0, column=0, sticky="w")
        ttk.Combobox(
            opts, textvariable=self.voice_var, values=list(VOICE_LABELS), state="readonly", width=18
        ).grid(row=0, column=1, sticky="w", padx=8)

        self._slider(opts, 1, "Kecepatan", self.rate_var, -50, 100, "%")
        self._slider(opts, 2, "Nada", self.pitch_var, -50, 50, " Hz")
        self._slider(opts, 3, "Volume", self.volume_var, -50, 50, "%")

        ttk.Checkbutton(
            opts, text="Rapikan teks otomatis (singkatan, Rp, persen, emoji, simbol)", variable=self.normalize_var
        ).grid(row=4, column=0, columnspan=3, sticky="w", pady=(6, 0))

        buttons = ttk.Frame(self)
        buttons.pack(fill="x")
        self.btn_preview = ttk.Button(buttons, text="Dengarkan", command=self.preview)
        self.btn_preview.pack(side="left")
        self.btn_save = ttk.Button(buttons, text="Simpan MP3…", command=self.save)
        self.btn_save.pack(side="left", padx=8)

        ttk.Label(self, textvariable=self.status_var, wraplength=700).pack(anchor="w", pady=(10, 0))

    def _slider(self, parent, row, label, var, lo, hi, unit) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w")
        value = ttk.Label(parent, width=9)
        scale = ttk.Scale(
            parent, from_=lo, to=hi, orient="horizontal",
            command=lambda v: (var.set(int(round(float(v)))), value.configure(text=f"{var.get():+d}{unit}")),
        )
        scale.set(var.get())
        scale.grid(row=row, column=1, sticky="ew", padx=8, pady=2)
        value.grid(row=row, column=2, sticky="e")
        value.configure(text=f"{var.get():+d}{unit}")

    # ---------------------------------------------------------------- Aksi

    def open_file(self) -> None:
        path = filedialog.askopenfilename(filetypes=[("Teks", "*.txt"), ("Semua file", "*.*")])
        if not path:
            return
        try:
            with open(path, encoding="utf-8-sig") as f:
                content = f.read()
        except (OSError, UnicodeDecodeError) as e:
            messagebox.showerror("Teks ke Suara", f"File tidak bisa dibuka:\n{e}")
            return
        self.text.delete("1.0", "end")
        self.text.insert("1.0", content)

    def _source(self) -> str | None:
        source = self.text.get("1.0", "end").strip()
        if not source:
            self.status_var.set("Tulis atau tempel teks dulu.")
            return None
        return source

    def preview(self) -> None:
        source = self._source()
        if source is None or self.busy:
            return
        fd, path = tempfile.mkstemp(prefix="teks-ke-suara-", suffix=".mp3")
        os.close(fd)
        self._run(source, path, open_after=True)

    def save(self) -> None:
        source = self._source()
        if source is None or self.busy:
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".mp3", initialfile="teks-ke-suara.mp3", filetypes=[("MP3", "*.mp3")]
        )
        if path:
            self._run(source, path, open_after=False)

    def _run(self, source: str, path: str, open_after: bool) -> None:
        self.busy = True
        self.btn_preview.state(["disabled"])
        self.btn_save.state(["disabled"])
        self.status_var.set("Membuat suara…")

        params = dict(
            voice=VOICE_LABELS[self.voice_var.get()],
            rate=self.rate_var.get(),
            pitch=self.pitch_var.get(),
            volume=self.volume_var.get(),
            use_normalize=self.normalize_var.get(),
        )

        def work() -> None:
            try:
                audio = synthesize_blocking(
                    source,
                    on_progress=lambda d, t: self.events.put(("progress", (d, t))),
                    **params,
                )
                with open(path, "wb") as f:
                    f.write(audio)
                self.events.put(("done", (path, open_after)))
            except SynthesisError as e:
                self.events.put(("error", str(e)))
            except Exception as e:
                self.events.put(("error", f"Terjadi kesalahan: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def _poll(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "progress":
                    done, total = payload
                    self.status_var.set(f"Membuat suara… bagian {done} dari {total}")
                elif kind == "done":
                    path, open_after = payload
                    self._finish("Memutar di pemutar bawaan…" if open_after else f"Tersimpan: {path}")
                    if open_after:
                        try:
                            open_with_default_player(path)
                        except OSError as e:
                            self.status_var.set(f"File siap di {path}, tapi tidak bisa dibuka otomatis: {e}")
                else:
                    self._finish(payload)
        except queue.Empty:
            pass
        self.root.after(100, self._poll)

    def _finish(self, message: str) -> None:
        self.busy = False
        self.btn_preview.state(["!disabled"])
        self.btn_save.state(["!disabled"])
        self.status_var.set(message)


def main() -> None:
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
