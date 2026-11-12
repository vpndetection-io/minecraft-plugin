**Keep VPNs, proxies, Tor and bot hosts off your server.** VPNDetection checks each player's address as they join, and refuses or flags to your staff the ones who come through a VPN, a proxy, Tor or a hosting network.

## 🎯 What it stops

- Banned players coming back on a new address
- Bots flooding in from rented servers
- Players from countries you don't want, or from any but the ones you list

## ⚡ Up in a minute

1. Put the jar in your server's `plugins` folder and restart
2. VPNs, Tor and hosting networks are blocked from the first join, with no account
3. On a network, install it on the proxy alone: Velocity or BungeeCord sees each player once

## 🔧 You decide what happens

- Block, alert your staff or allow, separately for VPNs, Tor, hosting networks, proxies and privacy relays
- Rules on any field of the answer, like one VPN provider or one network
- Your own kick and alert messages, with color codes
- A bypass list for players, addresses and ranges
- `/vpnd check` looks up any player or address on the spot

## 🎁 Free to start

- No account: VPNs, Tor, hosting networks, privacy relays and countries, on every join
- Free account, signed in with `/vpnd login`: every check, proxies included, for 100 lookups a day
- Paid plan: every check on every join, with the provider named in your messages

## 🚀 Built for busy servers

- One jar for Paper, Spigot, Purpur, Folia, Velocity and BungeeCord, on Java 17 or newer
- A login waits at most 2.5 seconds, and on a proxy the lookup runs while the player signs in
- If our API can't be reached, players are let in, unless you set it to refuse them
- Each answer is kept for an hour, so a player who rejoins costs no second lookup

## 🔒 What it sends

For each player who joins, the plugin sends their address to `api.vpndetection.io` and nothing else about them: no name, no UUID. Requests name the plugin, its version and your server software. Privacy policy: https://vpndetection.io/privacy

## 💬 Setup and help

Every setting, command and rule: https://docs.vpndetection.io/integrations/minecraft. The source: https://github.com/vpndetection-io/minecraft-plugin. Questions: support@vpndetection.io
