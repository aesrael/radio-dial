# Radio Dial

A tiny direct-stream radio player I made for myself, to listen to the stations I care about. No accounts, no maze.

**Live URL:** [https://radio-dial.pages.dev](https://radio-dial.pages.dev)

### Development & Deployment

- Open `index.html` locally in a browser to run without a build step (needs a local server for stations.json: `python3 -m http.server`, then open http://localhost:8000).
- Station list lives in `stations.json` — edit it to add/remove stations, no code changes.
- To deploy updates to Cloudflare Pages:
  ```bash
  npx wrangler pages deploy . --project-name=radio-dial
  ```

### Controls

- **Up / Down / Left / Right**: Tune frequency
- **Space**: Play / Pause
- **M**: Mute / Unmute

