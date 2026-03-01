# RuneAlytics

A RuneLite plugin that automatically syncs your loot, drops, bank value, and wealth history to [RuneAlytics.com](https://runealytics.com) — giving you detailed analytics, leaderboards, and long-term progress tracking you can't get inside the game client alone.

---

## Features

### 🏆 Loot Tracking
- Automatically records every drop as you play
- Tracks loot from NPCs, bosses, clue scrolls, and skilling activities
- Configurable minimum loot value threshold to filter out junk drops
- Option to track all NPCs or limit tracking to specific sources

### 📊 Drop Analytics
- View your full drop history and session summaries on RuneAlytics.com
- See drop rates, rare drop streaks, and profit-per-hour breakdowns
- Compare your luck against other players on the leaderboard

### 🏦 Bank & Wealth Tracking
- Syncs your bank contents every time you open the bank interface
- Captures inventory and equipped items alongside bank value for accurate total wealth
- Track your net worth over time with daily, weekly, monthly, and yearly graphs
- Public or private bank visibility — you control who can view your data

### 📅 Wealth History
- Visual wealth graph with 7 time-period views: Today, Yesterday, 7 Days, 30 Days, Weekly, Monthly, Yearly
- Gain/loss indicators and wealth rating for each period
- Milestone tracking to celebrate financial goals

### 🔗 RuneAlytics Integration
- Automatic account verification via the plugin — no manual steps required
- Syncs seamlessly in the background without interrupting gameplay
- Feature flags let the website enable or disable tabs per account

---

## Setup

1. Install the **RuneAlytics** plugin from the RuneLite Plugin Hub
2. Create a free account at [RuneAlytics.com](https://runealytics.com)
3. Enter your verification code in the plugin configuration panel
4. Log in to OSRS — the plugin will verify and begin syncing automatically

---

## Configuration

| Setting | Default | Description |
|---|---|---|
| API Token | *(empty)* | Your RuneAlytics account verification code |
| Enable Loot Tracking | On | Record drops and loot events |
| Track All NPCs | On | Track drops from every NPC (off = bosses only) |
| Minimum Loot Value | 0 gp | Ignore drops below this GE value |
| Sync to Server | On | Send data to RuneAlytics.com |
| Auto-Verify Account | On | Verify your OSRS account automatically on login |
| Sync Timeout | 10s | HTTP timeout for API requests |

---

## Privacy

This plugin collects and transmits the following gameplay data to `https://runealytics.com`:

- **Loot events** — NPC name, item IDs, quantities, and approximate GE values
- **Bank contents** — item IDs and quantities when the bank interface is opened
- **Inventory and equipped items** — captured at the same time as bank syncs for accurate total wealth calculation
- **XP gains** — skill and XP delta per session

**No account credentials, passwords, or payment information are collected.**  
Your OSRS username is used only to associate data with your RuneAlytics profile.  
Bank visibility defaults to **private** — you must explicitly make it public.

All data is processed under the [RuneAlytics Privacy Policy](https://runealytics.com/privacy).

---

## Data Sent to External Server

> ⚠️ **This plugin sends data to a third-party server.**  
> Server: `https://runealytics.com`  
> You must create an account and agree to the RuneAlytics Terms of Service to use this plugin.

Data is only sent when:
- A loot event occurs (if loot tracking is enabled)
- You open your bank interface (bank sync)
- You gain XP in a skill

All syncs happen in a background thread and do not affect game performance.

---

## Troubleshooting

**Plugin panel shows "Not Verified"**  
→ Make sure your API token is entered correctly in plugin settings. The token is found in your RuneAlytics account dashboard.

**Bank not syncing**  
→ Open your bank in-game. The sync happens automatically when the bank interface loads. Check that "Sync to Server" is enabled in settings.

**Loot not appearing on the website**  
→ Confirm "Enable Loot Tracking" is on and your account is verified. There may be a short delay before drops appear on the site.

**DiskLruCache warnings in the console**  
→ These are harmless warnings from a corrupted RuneLite HTTP cache file. Delete the folder at `%USERPROFILE%\.runelite\cache\okhttp` and restart RuneLite to clear them permanently.

---

## Support

- Website: [runealytics.com](https://runealytics.com)
- Issues & feature requests: [GitHub Issues](https://github.com/RuneAlytics/runealytics-plugin/issues)

---

## License

BSD 2-Clause License — see [LICENSE](LICENSE) for details.