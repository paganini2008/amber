# Tutorial video

Records `docs/assets/amber-tutorial.mp4` (and its poster) from the real desktop app: a scripted, user-facing
walk-through with English subtitles. The steps are: open a legacy script, the offline views, the ER diagram,
online metadata, local optimized SQL, and AI suggestions. The real mouse and keyboard drive the app, so it looks
like a person using it.

```bash
# dry run: drives the app through the DevTools protocol, records nothing, saves a screenshot per subtitle
mvn -f backend/pom.xml -pl amber-core -Ptutorial package -DskipTests -Dtutorial.dry=true

# real recording (about 2 minutes; hands off the mouse and keyboard until it says "done")
mvn -f backend/pom.xml -pl amber-core -Ptutorial package -DskipTests
```

## Prerequisites

- macOS, Node 22 with `npm ci` done in `ui/amber-desktop`, `ffmpeg`, Google Chrome (renders the title cards).
- Accessibility and Screen Recording permission for the terminal that runs Maven. They are needed for the real
  mouse and the screen capture.
- `OPENAI_API_KEY` in the environment or `backend/.env`.
- A MySQL server holding `demo.customers` and `demo.orders`, listed as `mysql.url` / `mysql.user` /
  `mysql.password` in the git-ignored `backend/amber-core/src/test/resources/online-it.properties`. The tables come
  from `online-fixtures/mysql-demo.sql`. Amber only reads their metadata.
- An English keyboard layout (ABC, U.S., …) as the input source while recording. An input method such as
  Chinese Pinyin would change the text typed on screen. Switch it yourself; the run reminds you during the
  countdown.
- Optional: `AMBER_TUTORIAL_BGM` (environment or `backend/.env`), an audio file you are allowed to use as music.

## What it does

`run.mjs` checks the prerequisites and copies `nightly_orders.sql` to `/tmp/amber-tutorial/`. It then starts the
dev app with a clean settings profile (`AMBER_DEV_USER_DATA`), so your own connections and recent files never
appear. The profile is set to English, the dark theme, OpenAI and the MySQL connection. The window is sized to the
largest 16:9 that fits the screen. After a 10-second countdown, `record.mjs` runs: it moves the mouse with
`mouse.jxa` (CGEvents) and captures the screen with ffmpeg. Finally `compose.mjs` adds the title cards
(`cards.html`), the subtitles and the music.

Work files go to `backend/amber-core/target/tutorial/`: `raw.mov`, `marks.json` (subtitle times),
`meta.json`, and the screenshots of a dry run in `dry/`.
