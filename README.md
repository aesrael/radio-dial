# Radio Dial

<img src="favicon.svg" alt="Radio Dial logo" width="96">

A tiny direct-stream radio player I made for myself, to listen to the stations I care about. No accounts, no maze.

**Live URL:** [https://radio-dial.pages.dev](https://radio-dial.pages.dev)

### What's on the dial

Stations are grouped, UK first, then a select set of US public radio, then genres that
work anywhere — pop, hip hop, rock, electronic, jazz, blues, soul, reggae, Latin,
classical, ambient and oldies. Groups are filters: pick one and the arrows tune
within that group only.

### Development & Deployment

- Open `index.html` locally in a browser to run without a build step (needs a local server for stations.json: `python3 -m http.server`, then open http://localhost:8000).
- Station list lives in `stations.json` — edit it directly, or regenerate it (below).
- To deploy updates to Cloudflare Pages:
  ```bash
  npx wrangler pages deploy . --project-name=radio-dial
  ```

### Regenerating stations.json

`tools/build_stations.py` keeps the hand-tuned set in `stations.pinned.json` and appends
stations discovered from [radio-browser](https://www.radio-browser.info/):

```bash
python3 tools/build_stations.py            # rebuild + probe every stream
python3 tools/build_stations.py --no-probe # skip the network probe
python3 tools/build_stations.py --dry-run  # report only, write nothing
```

It only accepts streams that work from an https page (no `http://`, no mixed content),
codecs Safari can play (MP3/AAC/HLS), one entry per brand, sane names, and it probes each
URL before writing it. Groups, tag lists, per-group caps and the hand-picked names live
at the top of the script.

### Dead streams

Streams die. The app remembers: when a station fails it's retried twice on the spot
(transient mobile blips usually recover), then marked offline for a week,
skipped by the arrows, and hidden from the lists (toggle **Show offline** to see or retry
it). Interruptions (calls, notifications, Bluetooth handoffs) auto-resume. **Check streams** probes every URL once and hides the ones that don't answer.
`stations.json` is the source of truth — if a lot go red, regenerate it.

### Controls

- **Up / Down / Left / Right**: tune within the current group
- **Space**: Play / Pause
- **M**: Mute / Unmute
- **T**: Theme
