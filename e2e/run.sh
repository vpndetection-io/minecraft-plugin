#!/bin/bash

# Boots real servers in docker with the plugin installed, joins each one from addresses a stub API
# answers for, and checks what every login came to.
#
#   ./e2e/run.sh                          # every target
#   ./e2e/run.sh paper-26.3 velocity-4.3.0
#   JAR=dist/target/VPNDetection-1.0.0.jar ./e2e/run.sh   # skip the build
#
# Needs docker, java 17 or newer (the probe and the stub run on the host), curl, jq and sha256sum.
# Servers download into e2e/.cache, and each target's files live in e2e/.work/<target>.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
WORK=$ROOT/e2e/.work
CACHE=$ROOT/e2e/.cache
STUB_PORT=18765

# <target> <project> <version> <java>
TARGETS=(
    "paper-26.3 paper 26.3 25"
    "folia-26.3 folia 26.3 25"
    "paper-1.21.11 paper 1.21.11 21"
    "paper-1.20.1 paper 1.20.1 17"
    "velocity-4.3.0 velocity 4.3.0 25"
    "velocity-3.4.0 velocity 3.4.0 17"
    "bungeecord bungeecord latest 21"
)

main() {
    local wanted=("$@")
    local jar=${JAR:-}
    if [[ -z "$jar" ]]; then
        ./scripts/mvn.sh -q -DskipTests package
        jar=$(ls dist/target/VPNDetection-*.jar | grep -v original | head -1)
    fi
    jar=$(realpath "$jar")
    VERSION=$(unzip -p "$jar" plugin.yml | sed -n "s/^version: '\(.*\)'$/\1/p")
    mkdir -p "$WORK" "$CACHE"
    trap cleanup EXIT

    local failed=0
    local line
    for line in "${TARGETS[@]}"; do
        read -r target project version jdk <<< "$line"
        if [[ ${#wanted[@]} -gt 0 && ! " ${wanted[*]} " =~ " ${target} " ]]; then
            continue
        fi
        echo "== $target"
        if run_target "$target" "$project" "$version" "$jdk" "$jar"; then
            echo "ok   $target"
        else
            echo "FAIL $target (its files: e2e/.work/$target)"
            failed=1
        fi
        stop_target "$target"
    done
    return "$failed"
}

run_target() {
    local target=$1 project=$2 version=$3 jdk=$4 jar=$5
    local dir=$WORK/$target
    local server
    server=$(download "$project" "$version")
    rm -rf "${dir:?}"
    # Velocity names a plugin's folder by its id, the others by its name.
    local data=$dir/plugins/VPNDetection
    if [[ "$project" == "velocity" ]]; then
        data=$dir/plugins/vpndetection
    fi
    mkdir -p "$data"
    cp "$server" "$dir/server.jar"
    cp "$jar" "$dir/plugins/"

    local port=25565
    if [[ "$project" == "velocity" || "$project" == "bungeecord" ]]; then
        port=25577
    fi
    if [[ "$project" == "paper" || "$project" == "folia" ]]; then
        echo "eula=true" > "$dir/eula.txt"
        cat > "$dir/server.properties" <<EOF
online-mode=false
white-list=false
server-port=$port
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
EOF
    fi

    # The first start writes the server's own config files, which the second start reads.
    boot "$target" "$project" "$jdk" "$dir"
    stop_target "$target"
    case "$project" in
        paper | folia)
            sed -i 's/proxy-protocol: false/proxy-protocol: true/' "$dir/config/paper-global.yml"
            ;;
        velocity)
            sed -i -e 's/^online-mode = true/online-mode = false/' \
                -e 's/^haproxy-protocol = false/haproxy-protocol = true/' \
                -e "s/^bind = .*/bind = \"0.0.0.0:$port\"/" "$dir/velocity.toml"
            ;;
        bungeecord)
            sed -i -e 's/online_mode: true/online_mode: false/' \
                -e 's/proxy_protocol: false/proxy_protocol: true/' \
                -e "s/host: 0.0.0.0:25577/host: 0.0.0.0:$port/" "$dir/config.yml"
            ;;
    esac

    # The shipped config, with a key so every answer is the paid plan's, and one country refused.
    sed -e 's/^api-key: ""/api-key: "e2e-0123456789abcdef"/' \
        -e 's/^  block: \[\]/  block: [CN]/' \
        "$ROOT/core/src/main/resources/io/vpndetection/minecraft/config.yml" > "$data/config.yml"
    echo "8.8.4.4  # staff" > "$data/bypass.txt"

    : > "$dir/stub.log"
    java "$ROOT/e2e/StubApi.java" "$STUB_PORT" "$dir/stub.log" > "$dir/stub.out" 2>&1 &
    STUB_PID=$!
    wait_for "$dir/stub.out" "listening on" 60

    boot "$target" "$project" "$jdk" "$dir"
    wait_for "$dir/console.log" "VPNDetection $VERSION on " 60

    # BungeeCord sends a player to a server behind it before finishing their login, and the run has
    # none, so a player it let in is turned away by BungeeCord itself, with this message.
    local admitted="SUCCESS"
    if [[ "$project" == "bungeecord" ]]; then
        admitted="DISCONNECT"
    fi
    local ok=0
    expect "a VPN is refused" "DISCONNECT" "VPN (nordvpn)" probe "$port" bot1 45.83.91.1 || ok=1
    expect "a refused country gets its own message" "DISCONNECT" "accept players from your country" \
        probe "$port" bot2 36.110.1.2 || ok=1
    expect "a home connection gets in" "$admitted" "$(admitted_text "$project")" probe "$port" alex 177.1.2.3 || ok=1
    expect "a bypassed address gets in" "$admitted" "$(admitted_text "$project")" probe "$port" staff 8.8.4.4 || ok=1
    expect "a private address gets in" "$admitted" "$(admitted_text "$project")" probe "$port" lan 10.1.2.3 || ok=1
    expect "the same VPN again is refused" "DISCONNECT" "VPN (nordvpn)" probe "$port" bot3 45.83.91.1 || ok=1

    expect_count "$dir/stub.log" "/45.83.91.1" 1 "a refused address is looked up once, then cached" || ok=1
    expect_count "$dir/stub.log" "/8.8.4.4" 0 "a bypassed address is never looked up" || ok=1
    expect_count "$dir/stub.log" "/10.1.2.3" 0 "a private address is never looked up" || ok=1
    expect_count "$dir/stub.log" "/api/v1/entitlement" 1 "the key is checked on start" || ok=1
    if grep -q "vpndetection-minecraft/$VERSION (" "$dir/stub.log"; then
        echo "  ok   the User-Agent names the plugin: $(cut -f2 "$dir/stub.log" | sort -u | head -1)"
    else
        echo "  FAIL no request named the plugin in its User-Agent: $(cut -f2 "$dir/stub.log" | sort -u)"
        ok=1
    fi
    if grep -q "Refused bot1 from 45.83.91.1: VPN (nordvpn)" "$dir/console.log"; then
        echo "  ok   the console logs the refusal"
    else
        echo "  FAIL the console never logged bot1's refusal"
        ok=1
    fi
    return "$ok"
}

admitted_text() {
    if [[ "$1" == "bungeecord" ]]; then
        echo "Could not connect to a default or fallback server"
    fi
}

probe() {
    java "$ROOT/e2e/Probe.java" 127.0.0.1 "$@"
}

# expect <what> <SUCCESS|DISCONNECT> <text the outcome must hold> <command...>
expect() {
    local what=$1 outcome=$2 text=$3
    shift 3
    local got
    got=$("$@" || true)
    if [[ "$got" == "$outcome"* && "$got" == *"$text"* ]]; then
        echo "  ok   $what"
        return 0
    fi
    echo "  FAIL $what: $got"
    return 1
}

expect_count() {
    local log=$1 path=$2 want=$3 what=$4
    local got
    got=$(cut -f1 "$log" | grep -cx "$path" || true)
    if [[ "$got" == "$want" ]]; then
        echo "  ok   $what"
        return 0
    fi
    echo "  FAIL $what: $path was asked $got times"
    return 1
}

boot() {
    local target=$1 project=$2 jdk=$3 dir=$4
    local image
    image=$(java_image "$jdk")
    # Only Paper's command line takes --nogui; Velocity refuses an option it doesn't know.
    local args=()
    if [[ "$project" == "paper" || "$project" == "folia" ]]; then
        args=(--nogui)
    fi
    : > "$dir/console.log"
    docker rm -f "vpnd-e2e-$target" > /dev/null 2>&1 || true
    docker run -d --name "vpnd-e2e-$target" --network host -v "$dir:/srv" -w /srv "$image" \
        java -Xmx1G "-Dvpndetection.api-url=http://127.0.0.1:$STUB_PORT" -jar server.jar "${args[@]}" > /dev/null
    docker logs -f "vpnd-e2e-$target" > "$dir/console.log" 2>&1 &
    if ! wait_for "$dir/console.log" "Done \\(|Listening on " 300; then
        echo "  the server never finished starting; its log ends:"
        tail -20 "$dir/console.log" | sed 's/^/    /'
        return 1
    fi
}

stop_target() {
    docker stop -t 60 "vpnd-e2e-$1" > /dev/null 2>&1 || true
    docker rm -f "vpnd-e2e-$1" > /dev/null 2>&1 || true
    if [[ -n "${STUB_PID:-}" ]]; then
        kill "$STUB_PID" 2> /dev/null || true
        wait "$STUB_PID" 2> /dev/null || true
        STUB_PID=
    fi
}

cleanup() {
    local line
    for line in "${TARGETS[@]}"; do
        read -r target _ <<< "$line"
        docker rm -f "vpnd-e2e-$target" > /dev/null 2>&1 || true
    done
    stop_target none
}

wait_for() {
    local file=$1 pattern=$2 seconds=$3
    local i
    for ((i = 0; i < seconds * 4; i++)); do
        if grep -qE "$pattern" "$file" 2> /dev/null; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

java_image() {
    case "$1" in
        17) echo "eclipse-temurin:17.0.20_8-jre-noble" ;;
        21) echo "eclipse-temurin:21.0.12_8-jre-noble" ;;
        25) echo "eclipse-temurin:25.0.4_7-jre-noble" ;;
    esac
}

# Prints the path of a server's jar, downloading it the first time: PaperMC's builds checked against
# the SHA-256 their API publishes, and BungeeCord's latest successful build from its CI.
download() {
    local project=$1 version=$2
    local out=$CACHE/$project-$version.jar
    if [[ -s "$out" ]]; then
        echo "$out"
        return 0
    fi
    if [[ "$project" == "bungeecord" ]]; then
        curl -fsSL -o "$out.part" \
            "https://hub.spigotmc.org/jenkins/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar"
    else
        local build url sum
        build=$(curl -fsS "https://fill.papermc.io/v3/projects/$project/versions/$version/builds/latest")
        url=$(jq -r '.downloads."server:default".url' <<< "$build")
        sum=$(jq -r '.downloads."server:default".checksums.sha256' <<< "$build")
        curl -fsSL -o "$out.part" "$url"
        echo "$sum  $out.part" | sha256sum -c --quiet - >&2
    fi
    mv "$out.part" "$out"
    echo "$out"
}

main "$@"
