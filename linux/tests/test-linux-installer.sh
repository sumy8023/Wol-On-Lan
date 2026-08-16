#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
LINUX_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
# shellcheck disable=SC1090,SC1091
. "$LINUX_DIR/linux-installer-lib.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

if [ ! -f "$LINUX_DIR/wol" ]; then
  fail "wol management command is missing"
fi
sh -n "$LINUX_DIR/wol" || fail "wol management command has invalid shell syntax"
wol_help=$(sh "$LINUX_DIR/wol" --help)
printf '%s\n' "$wol_help" | grep -F 'wol stop' >/dev/null || fail "wol help omits stop command"
printf '%s\n' "$wol_help" | grep -F 'wol restart' >/dev/null || fail "wol help omits restart command"
printf '%s\n' "$wol_help" | grep -F 'wol reset-admin-key' >/dev/null || fail "wol help omits reset-admin-key command"
printf '%s\n' "$wol_help" | grep -F 'wol reset-config' >/dev/null || fail "wol help omits reset-config command"
printf '%s\n' "$wol_help" | grep -F 'wol uninstall' >/dev/null || fail "wol help omits uninstall command"
if sh "$LINUX_DIR/wol" unsupported >/dev/null 2>&1; then
  fail "wol accepted an unsupported command"
fi
if sh "$LINUX_DIR/wol" reset-admin-key --invalid >/dev/null 2>&1; then
  fail "wol reset-admin-key accepted an unsupported option"
fi
if sh "$LINUX_DIR/wol" reset-config --invalid >/dev/null 2>&1; then
  fail "wol reset-config accepted an unsupported option"
fi
wol_cancelled=$(printf 'no\n' | sh "$LINUX_DIR/wol" reset-admin-key)
printf '%s\n' "$wol_cancelled" | grep -F '已取消' >/dev/null || fail "wol reset-admin-key bypassed confirmation"
wol_cancelled=$(printf 'no\n' | sh "$LINUX_DIR/wol" reset-config)
printf '%s\n' "$wol_cancelled" | grep -F '已取消' >/dev/null || fail "wol reset-config bypassed confirmation"

assert_eq() {
  [ "$1" = "$2" ] || fail "expected '$2', got '$1'"
}

assert_eq "$(wol_java_major_from_output 'openjdk version "17.0.12" 2024-07-16')" "17"
assert_eq "$(wol_java_major_from_output 'java version "1.8.0_402"')" "8"
assert_eq "$(wol_java_major_from_output 'openjdk version "21" 2023-09-19')" "21"
assert_eq "$(wol_package_manager_from_list 'yum apt-get pacman')" "apt-get"
assert_eq "$(wol_package_manager_from_list 'zypper')" "zypper"
assert_eq "$(wol_ipv4_from_route_output '1.1.1.1 via 192.168.1.1 dev eth0 src 192.168.1.20 uid 1000')" "192.168.1.20"
assert_eq "$(wol_ipv4_from_address_list '127.0.0.1 169.254.1.2 10.0.0.8')" "10.0.0.8"

wol_logs='[服务]Web 管理后台地址：http://127.0.0.1:14250/aBcDeFGh/
管理令牌：old-token-must-not-be-returned
首次生成的管理令牌：Fresh-Token_24'
assert_eq "$(wol_extract_admin_url "$wol_logs")" "http://127.0.0.1:14250/aBcDeFGh/"
assert_eq "$(wol_extract_first_token "$wol_logs")" "Fresh-Token_24"
assert_eq "$(wol_extract_first_token '首次生成的管理令牌: ASCII-Token_24')" "ASCII-Token_24"
assert_eq "$(wol_extract_first_token '本次生成的管理令牌：Reset-Token_24')" "Reset-Token_24"
assert_eq "$(wol_extract_first_token '管理令牌：old-token-must-not-be-returned' || true)" ""
wol_admin_token_is_valid 'Ab!234' || fail "valid management token rejected"
if wol_admin_token_is_valid '12345'; then fail "short management token accepted"; fi
if wol_admin_token_is_valid 'bad token'; then fail "management token with a space accepted"; fi
assert_eq "$(wol_admin_url_for_ipv4 'http://127.0.0.1:14250/aBcDeFGh/' '192.168.1.20' ':14250')" "http://192.168.1.20:14250/aBcDeFGh/"
assert_eq "$(wol_admin_url_for_ipv4 'http://127.0.0.1:14250/aBcDeFGh/' '192.168.1.20' '127.0.0.1:14250')" "http://127.0.0.1:14250/aBcDeFGh/"

wol_tmp=$(mktemp -d)
trap 'rm -rf "$wol_tmp"' EXIT HUP INT TERM
cat >"$wol_tmp/config.yml" <<'EOF'
listen: ":14250"
admin_path: "aBcDeFGh"
admin_token: ""
EOF
assert_eq "$(wol_yaml_scalar_value "$wol_tmp/config.yml" listen)" ":14250"
assert_eq "$(wol_yaml_scalar_value "$wol_tmp/config.yml" admin_path)" "aBcDeFGh"
assert_eq "$(wol_yaml_scalar_value "$wol_tmp/config.yml" admin_token)" ""
wol_yaml_has_key "$wol_tmp/config.yml" admin_token || fail "admin_token key was not detected"
if wol_yaml_has_key "$wol_tmp/config.yml" missing_key; then fail "missing YAML key was detected"; fi

printf 'Sidecar-Token_24\r\n' >"$wol_tmp/config.yml.admin-token"
if ! wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token"; then
  fail "legacy plaintext management token sidecar rejected"
fi
if [ -n "$(wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token" 2>/dev/null || true)" ]; then
  fail "sidecar validator returned management token"
fi
printf 'Sidecar-Token_24\nsecond-line\n' >"$wol_tmp/config.yml.admin-token"
if wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token"; then
  fail "multi-line management token sidecar accepted"
fi

# The dollar signs are verifier separators, not shell expansions.
# shellcheck disable=SC2016
printf '%s\n' 'pbkdf2-sha256$v1$210000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' >"$wol_tmp/config.yml.admin-token"
wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token" || fail "valid PBKDF2 verifier sidecar rejected"
# shellcheck disable=SC2016
printf '%s\n' 'pbkdf2-sha256$v1$99999$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' >"$wol_tmp/config.yml.admin-token"
if wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token"; then
  fail "PBKDF2 verifier with too few iterations accepted"
fi
# shellcheck disable=SC2016
printf '%s\n' 'pbkdf2-sha256$v1$210000$bad salt$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' >"$wol_tmp/config.yml.admin-token"
if wol_admin_token_sidecar_is_usable "$wol_tmp/config.yml.admin-token"; then
  fail "PBKDF2 verifier with invalid base64 accepted"
fi

echo "Linux installer helper tests passed."
