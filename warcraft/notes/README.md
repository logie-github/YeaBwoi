# Patch notes

Each change to the World of Warcraft app adds one file here, named `YYYY-MM-DD-what.md` (one file a change,
so sessions working at once never touch the same file). Its lines are what a player reads in the app's update
dialog: a short Markdown list, plain words, what changed for them and what to do, if anything.

```
- Ralia's mod and Alternate Female Options now show on every race with the Alternative HD Patch.
```

CI (`.github/workflows/build-warcraft.yml`, the release step) puts the files added since the app's previous
published release into the new release's text, between `<!-- notes -->` and `<!-- /notes -->`. The app's
update dialog shows every release's notes since the one installed, newest first. Files are never edited or
removed once released; a later change gets its own file.
