# Document Finder (Windows)
Scans a folder and its subfolders for pictures that aren't real photos: documents, receipts, screenshots, memes,
signs, posters, charts and so on. You review each one: **Document (Y)** moves it to your destination folder (keeping
its subfolders), **Not a document (N)** leaves it where it is. Nothing moves until you approve it. Undo with ← or
Ctrl+Z, or undo a whole session from the start screen.

Runs offline: a CLIP image model on your GPU (DirectML), with many CPU threads decoding images.
Log: `%LOCALAPPDATA%\DocumentFinder\document_finder.log` (Export log button).

Download: Releases → newest **Document Finder (build N)** → `DocumentFinder.zip`, unzip, run `Document Finder.exe`.
