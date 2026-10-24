# [<img src="https://s3.vpndetection.io/vpndetection-public/brand/mark.svg" alt="VPNDetection" height="28"/>](https://vpndetection.io/) VPNDetection Minecraft Plugin

[![version](https://img.shields.io/github/v/release/vpndetection-io/minecraft-plugin)](CHANGELOG.md)
[![license](https://img.shields.io/github/license/vpndetection-io/minecraft-plugin)](LICENSE)

The official [VPNDetection](https://vpndetection.io) plugin for Minecraft servers.

It checks each player's address as they join, and refuses or flags to your staff the ones who come through a VPN, a proxy, Tor or a hosting network. That keeps banned players from coming back on a new address and bots from flooding in from rented servers. One jar runs on Paper, Spigot, Folia, Velocity and BungeeCord.

## Getting Started

Download `VPNDetection-<version>.jar` from the [latest release](https://github.com/vpndetection-io/minecraft-plugin/releases/latest) and put it in your server's `plugins` folder, then restart. On a network, install it on the proxy alone: Velocity or BungeeCord sees each player once, before any server behind it does.

The plugin works without an account, checking for VPNs only. To check everything else, sign in from the console:

```console
/vpnd login
```

It prints a link and a code. Open the link, sign in or create a free account, and pick an API key: the plugin saves the key in `config.yml` and starts using it at once. To paste a key instead, put it in `config.yml`'s `api-key` and run `/vpnd reload`, or set the `VPNDETECTION_API_KEY` environment variable.

It runs on Java 17 or newer: Paper, Spigot, Purpur, Folia and other Bukkit servers, Velocity 3.4 and later, and BungeeCord and its forks.

## What it checks

`config.yml` sets what happens to a player whose address is each of these: `block` refuses the login with your kick message, `alert` lets them in and tells staff who hold `vpndetection.alerts`, and `allow` does nothing.

| Check | Matches | Needs | Default |
|---|---|---|---|
| `vpn` | Commercial VPN servers | nothing | `block` |
| `tor` | Tor relays | a free key | `block` |
| `hosting` | Datacenters and cloud providers | a free key | `block` |
| `proxy` | Residential, datacenter, mobile and public proxies | a paid plan | `block` |
| `relay` | Privacy relays and company web gateways, like iCloud Private Relay | a free key | `allow` |

With a free key, `hosting` goes by the kind of network announcing the address. A paid plan answers it for each address, and names the VPN, hosting or proxy provider in your messages. A check your plan doesn't answer is skipped, and the console says so once. The [pricing page](https://vpndetection.io/pricing) says what each plan answers.

Countries need a free key. List the two-letter codes to refuse in `countries.block`, or the only ones to let in in `countries.allow`:

```yaml
countries:
  block: [CN, RU]
  allow: []
```

A player whose country can't be placed is let in.

### Rules

For anything the checks don't cover, a rule blocks or alerts on any field of the answer. A match is written in the shape of the answer and holds when every field it names does. A list means any of them, and `{gte: 5}` compares a number:

```yaml
rules:
  - action: block
    reason: NordVPN
    match:
      vpn:
        provider: nordvpn
  - action: alert
    reason: a busy residential proxy
    match:
      resproxy:
        hits: {gte: 5}
  - action: block
    reason: M247
    match:
      asn:
        asn: 9009
```

The fields are those of a [lookup](https://docs.vpndetection.io/api/lookup/lookupIp), under the same names. A field set to `false` is ignored, so a rule says what to act on, never what to let through. A rule naming a field your plan doesn't answer never matches, and the console says so once.

## Commands

Every command needs `vpndetection.admin`. `/vpnd` is short for `/vpndetection`.

| Command | Does |
|---|---|
| `/vpnd check <player\|address>` | Looks one up and says what a login from it would get |
| `/vpnd login` | Signs in and saves an API key |
| `/vpnd status` | Your plan and what's left of it, and what's been checked since the server started |
| `/vpnd bypass add\|remove <player\|address\|range>` | Never checks a player or an address, or does again |
| `/vpnd bypass list` | Lists everything never checked |
| `/vpnd reload` | Reads `config.yml` and `bypass.txt` again |

Players and addresses that are never checked live in `bypass.txt`, beside `config.yml`, one per line: a player's name or UUID, an address, or a range like `203.0.113.0/24`. `/vpnd bypass add` on an online player stores their UUID, which outlasts a change of name.

## Messages

`messages.kick` is shown to a player refused by a check or a rule, and `messages.kick-country` to one refused by country. Staff with `vpndetection.alerts` see `messages.alert` and `messages.blocked`, which the console logs too, at most five lines every ten seconds so a bot flood can't bury your chat. Use `&` color codes and `&#rrggbb` for any color. `{player}`, `{ip}`, `{country}` and `{reason}` are filled in, the last with what matched, like `VPN (nordvpn)`.

## When a lookup fails

If our API can't be reached or your allowance is spent, the player is let in, so an outage of ours never locks out your players. Set `fail-closed: true` to refuse them instead. A login waits at most 2.5 seconds for its lookup. On a proxy the lookup starts as the player connects and runs while they sign in, so a login rarely waits on it at all.

## What it sends

For each player who joins, the plugin sends their address to `api.vpndetection.io` and nothing else about them: no name, no UUID. Each answer is kept in memory for `cache-minutes`, an hour by default, so a player who rejoins costs no second lookup, and players joining together from one address share one. Requests name the plugin, its version and the server software in their `User-Agent`. With a key, each lookup counts against that key's plan as any API call does. Our privacy policy is at https://vpndetection.io/privacy, and questions go to support@vpndetection.io.

## Building

```console
./scripts/mvn.sh verify
```

builds and tests every module in Docker, and writes the plugin to `dist/target/VPNDetection-<version>.jar`. `./e2e/run.sh` boots real Paper, Folia, Velocity and BungeeCord servers with it, and joins each from addresses a stub of the API answers for.

## Other Libraries

There are official VPNDetection client libraries available for many languages including PHP, Python, Go, Java, Ruby, and many popular frameworks such as Django, Rails, and Laravel. See our GitHub at https://github.com/vpndetection-io for more.

## About VPNDetection

VPN Detection API: Accurate anonymity detection identifying VPNs, residential proxies, hosting servers, Tor nodes, CDNs, relays and more.

[<img src="https://s3.vpndetection.io/vpndetection-public/brand/mark.svg" alt="VPNDetection" height="64"/>](https://vpndetection.io/)

## License

This project is licensed under the [MIT License](LICENSE).
