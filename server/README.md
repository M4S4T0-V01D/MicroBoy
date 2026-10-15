# MicroBoy link server

Matchmaking and relay for MicroBoy's online link cable, on Cloudflare Workers
and Durable Objects. The phones do all the emulation: both run both consoles
and exchange only button presses. This server introduces players to each
other, and relays their messages when they can't connect directly.

- `GET /lobby` (WebSocket) goes to a single `Lobby` Durable Object. It
  tracks online players and builds two lists per player: people sharing their
  game, and nickname-only players. It also forwards invites and handles
  blocks and reports. A player reported by 3 different people in a week is
  hidden for a week.
- `GET /room/<32 hex>?p=1|2` (WebSocket) goes to one `Room` Durable Object
  per match. It forwards everything one player sends to the other: the
  handshake, compressed save states, and button presses when there's no
  direct UDP path.

Both use WebSocket hibernation, so idle players cost nothing.

Privacy: the server never sees an install id. It keeps a salted SHA-256 of
it (`PLAYER_SALT` secret) as the player id. Nicknames are limited to letters,
digits, spaces and `. _ -`, and a short word list is rejected.

## Commands

```sh
npm install
npx wrangler dev                 # local, with PLAYER_SALT from .dev.vars
node test/smoke.mjs              # against ws://127.0.0.1:8787
node test/smoke.mjs wss://microboy-link.masato-void.workers.dev
npx wrangler deploy
echo "<random>" | npx wrangler secret put PLAYER_SALT   # once
```

Live at https://microboy-link.masato-void.workers.dev. The app's URL is
`LinkConfig.SERVER`.
