#!/usr/bin/env sh

# Pure parsing helpers live here so installer behavior can be tested without
# root privileges, systemd, a package manager, or a live network.

wol_java_major_from_output() {
  wol_version=$(printf '%s\n' "$1" | sed -n 's/.*version "\([^"]*\)".*/\1/p' | sed -n '1p')
  [ -n "$wol_version" ] || return 1
  case "$wol_version" in
    1.*) wol_version=${wol_version#1.} ;;
  esac
  wol_major=${wol_version%%[._+-]*}
  case "$wol_major" in
    ''|*[!0-9]*) return 1 ;;
  esac
  printf '%s\n' "$wol_major"
}

wol_java_major() {
  [ -n "${1:-}" ] && [ -x "$1" ] || return 1
  wol_java_output=$("$1" -version 2>&1) || return 1
  wol_java_major_from_output "$wol_java_output"
}

wol_java_is_17_or_newer() {
  wol_major=$(wol_java_major "$1") || return 1
  [ "$wol_major" -ge 17 ]
}

wol_find_java17() {
  if [ -n "${JAVA_HOME:-}" ] && wol_java_is_17_or_newer "$JAVA_HOME/bin/java"; then
    printf '%s\n' "$JAVA_HOME/bin/java"
    return 0
  fi

  wol_path_java=$(command -v java 2>/dev/null || true)
  if [ -n "$wol_path_java" ] && wol_java_is_17_or_newer "$wol_path_java"; then
    printf '%s\n' "$wol_path_java"
    return 0
  fi

  for wol_candidate in \
    /usr/lib/jvm/java-17*/bin/java \
    /usr/lib/jvm/jre-17*/bin/java \
    /usr/lib64/jvm/java-17*/bin/java \
    /usr/lib64/jvm/jre-17*/bin/java \
    /opt/java/openjdk/bin/java
  do
    if [ -x "$wol_candidate" ] && wol_java_is_17_or_newer "$wol_candidate"; then
      printf '%s\n' "$wol_candidate"
      return 0
    fi
  done
  return 1
}

wol_package_manager_from_list() {
  wol_available=" $* "
  for wol_manager in apt-get apt dnf yum pacman zypper; do
    case "$wol_available" in
      *" $wol_manager "*) printf '%s\n' "$wol_manager"; return 0 ;;
    esac
  done
  return 1
}

wol_detect_package_manager() {
  wol_found=''
  for wol_manager in apt-get apt dnf yum pacman zypper; do
    if command -v "$wol_manager" >/dev/null 2>&1; then
      wol_found="$wol_found $wol_manager"
    fi
  done
  wol_package_manager_from_list "$wol_found"
}

wol_ipv4_from_route_output() {
  printf '%s\n' "$1" | awk '
    {
      for (i = 1; i < NF; i++) {
        if ($i == "src" && $(i + 1) ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/) {
          print $(i + 1)
          exit
        }
      }
    }
  '
}

wol_ipv4_from_address_list() {
  printf '%s\n' "$1" | awk '
    {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && $i !~ /^127\./ && $i !~ /^169\.254\./) {
          print $i
          exit
        }
      }
    }
  '
}

wol_first_reachable_ipv4() {
  if command -v ip >/dev/null 2>&1; then
    wol_route=$(ip -4 route get 1.1.1.1 2>/dev/null || true)
    wol_ip=$(wol_ipv4_from_route_output "$wol_route")
    if [ -n "$wol_ip" ]; then
      printf '%s\n' "$wol_ip"
      return 0
    fi
    wol_addresses=$(ip -o -4 addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | tr '\n' ' ' || true)
    wol_ip=$(wol_ipv4_from_address_list "$wol_addresses")
    if [ -n "$wol_ip" ]; then
      printf '%s\n' "$wol_ip"
      return 0
    fi
  fi
  if command -v hostname >/dev/null 2>&1; then
    wol_addresses=$(hostname -I 2>/dev/null || true)
    wol_ip=$(wol_ipv4_from_address_list "$wol_addresses")
    if [ -n "$wol_ip" ]; then
      printf '%s\n' "$wol_ip"
      return 0
    fi
  fi
  return 1
}

wol_extract_admin_url() {
  printf '%s\n' "$1" | grep -Eo 'http://(\[[^]]+\]|[^[:space:]/:]+):[0-9]+/[^[:space:]]+/' | sed -n '1p'
}

wol_extract_first_token() {
  printf '%s\n' "$1" | LC_ALL=C awk '
    {
      labels[1] = "首次生成的管理令牌"
      labels[2] = "本次生成的管理令牌"
      for (i = 1; i <= 2; i++) {
        if (index($0, labels[i])) {
          value = substr($0, index($0, labels[i]) + length(labels[i]))
          sub(/^:/, "", value)
          sub(/^[^!-~]*/, "", value)
          print value
          exit
        }
      }
    }
  '
}

wol_yaml_scalar_value() {
  wol_yaml_file=$1
  wol_yaml_key=$2
  [ -f "$wol_yaml_file" ] || return 1
  wol_yaml_value=$(awk -v key="$wol_yaml_key" '
    $0 ~ "^[[:space:]]*" key "[[:space:]]*:" {
      line = $0
      sub("^[[:space:]]*" key "[[:space:]]*:[[:space:]]*", "", line)
      sub(/[[:space:]]+#.*$/, "", line)
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      print line
      exit
    }
  ' "$wol_yaml_file")
  case "$wol_yaml_value" in
    \"*\") wol_yaml_value=${wol_yaml_value#\"}; wol_yaml_value=${wol_yaml_value%\"} ;;
    \'*\') wol_yaml_value=${wol_yaml_value#\'}; wol_yaml_value=${wol_yaml_value%\'} ;;
  esac
  printf '%s\n' "$wol_yaml_value"
}

wol_yaml_has_key() {
  wol_yaml_file=$1
  wol_yaml_key=$2
  [ -f "$wol_yaml_file" ] || return 1
  awk -v key="$wol_yaml_key" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      if (line ~ "^" key "[[:space:]]*:") found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "$wol_yaml_file"
}

wol_admin_token_is_valid() {
  LC_ALL=C awk '
    function base64url_bytes(value, minimum, maximum, length_value, remainder, decoded) {
      length_value = length(value)
      if (length_value == 0 || value !~ /^[A-Za-z0-9_-]+$/) return 0
      remainder = length_value % 4
      if (remainder == 1) return 0
      decoded = int(length_value * 6 / 8)
      return decoded >= minimum && decoded <= maximum
    }
    function valid_verifier(value, fields, field_count, iterations) {
      field_count = split(value, fields, /\$/)
      if (field_count != 5 || fields[1] != "pbkdf2-sha256" || fields[2] != "v1") return 0
      if (fields[3] !~ /^[0-9]+$/) return 0
      iterations = fields[3] + 0
      if (iterations < 100000 || iterations > 1000000) return 0
      return base64url_bytes(fields[4], 16, 64) && base64url_bytes(fields[5], 32, 64)
    }
    BEGIN {
      value = ARGV[1]
      if (valid_verifier(value)) exit 0
      if (value ~ /^pbkdf2-sha256\$/) exit 1
      valid_length = length(value) >= 6 && length(value) <= 256
      exit(valid_length && value ~ /^[!-~]+$/ ? 0 : 1)
    }
  ' "$1"
}

wol_admin_token_sidecar_is_usable() {
  wol_admin_token_file=$1
  [ -f "$wol_admin_token_file" ] || return 1
  [ ! -L "$wol_admin_token_file" ] || return 1
  # Validate the verifier or a one-line legacy token in place. The sidecar
  # contents never enter a shell variable or command output.
  LC_ALL=C awk '
    function base64url_bytes(value, minimum, maximum, length_value, remainder, decoded) {
      length_value = length(value)
      if (length_value == 0 || value !~ /^[A-Za-z0-9_-]+$/) return 0
      remainder = length_value % 4
      if (remainder == 1) return 0
      decoded = int(length_value * 6 / 8)
      return decoded >= minimum && decoded <= maximum
    }
    function valid_verifier(value, fields, field_count, iterations) {
      field_count = split(value, fields, /\$/)
      if (field_count != 5 || fields[1] != "pbkdf2-sha256" || fields[2] != "v1") return 0
      if (fields[3] !~ /^[0-9]+$/) return 0
      iterations = fields[3] + 0
      if (iterations < 100000 || iterations > 1000000) return 0
      return base64url_bytes(fields[4], 16, 64) && base64url_bytes(fields[5], 32, 64)
    }
    NR == 1 {
      value = $0
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      valid = valid_verifier(value)
      # Reserve the verifier prefix so a damaged verifier cannot be treated
      # as a legacy plaintext credential by the installer.
      if (!valid && value !~ /^pbkdf2-sha256\$/ && length(value) >= 6 && length(value) <= 256 && value ~ /^[!-~]+$/) valid = 1
      next
    }
    NR > 1 { valid = 0 }
    END { exit(NR == 1 && valid ? 0 : 1) }
  ' "$wol_admin_token_file" 2>/dev/null
}

wol_admin_url_for_ipv4() {
  wol_url=$1
  wol_ip=$2
  wol_listen=${3:-}
  [ -n "$wol_url" ] || return 1
  [ -n "$wol_ip" ] || { printf '%s\n' "$wol_url"; return 0; }
  case "$wol_listen" in
    127.*|localhost:*|\[::1\]:*|::1:*) printf '%s\n' "$wol_url"; return 0 ;;
  esac
  case "$wol_url" in
    http://127.0.0.1:*) printf 'http://%s%s\n' "$wol_ip" "${wol_url#http://127.0.0.1}" ;;
    http://localhost:*) printf 'http://%s%s\n' "$wol_ip" "${wol_url#http://localhost}" ;;
    *) printf '%s\n' "$wol_url" ;;
  esac
}
