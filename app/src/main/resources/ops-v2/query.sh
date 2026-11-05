#!/usr/bin/env bash
# Bounded, NUL-framed output. Requires bash/find/sort/awk and optionally rg.
set -u
export LC_ALL=C
op=$1 root=$2 pattern=$3 glob=$4 regex=$5 ignore=$6 max=$7 files_only=$8
budget=4096
work=$(mktemp -d) || exit 70
trap 'rm -rf -- "$work"' EXIT
partial=0; count=0; bytes=0; limited=0; diagnostic=''
finish() {
  local status=complete
  (( limited )) && status=limited
  (( partial )) && status=partial_error
  printf 'S\0%s\0%s\0' "$status" "$diagnostic"
}
if [[ ! -e "$root" ]]; then printf 'S\0error\0path does not exist\0'; exit 0; fi
if [[ "$op" == glob && ! -d "$root" ]]; then printf 'S\0error\0not a directory\0'; exit 0; fi
engine=awk
command -v rg >/dev/null 2>&1 && [[ "${OPS_FORCE_AWK:-0}" != 1 ]] && engine=rg
if [[ "$op" == grep && "$regex" == true && "$engine" != rg ]]; then
  printf 'S\0error\0regex search requires rg; literal fallback is available\0'; exit 0
fi
if [[ "$op" == grep && "$engine" == rg && "$regex" == true ]]; then
  printf '' | rg --no-unicode -e "$pattern" > /dev/null 2> "$work/error"
  rc=$?
  if (( rc > 1 )); then printf 'S\0error\0%s\0' "$(head -c 500 "$work/error")"; exit 0; fi
fi
# Filter BEFORE search and limits; retain enumeration errors instead of claiming no match.
find "$root" -type f -name "$glob" -print0 > "$work/list" 2> "$work/error" || partial=1
if (( partial )); then diagnostic=$(head -c 500 "$work/error"); fi
if ! sort -z "$work/list" > "$work/sorted"; then printf 'S\0error\0sort -z unavailable\0'; exit 0; fi
while IFS= read -r -d '' file; do
  if [[ "$op" == glob ]]; then
    (( count+=1 ))
    cost=$(( ${#file} + 4 ))
    if (( count > max || bytes + cost > budget )); then limited=1; break; fi
    printf 'F\0%s\0' "$file"; (( bytes+=cost )); continue
  fi
  remain=$(( max - count + 1 ))
  [[ "$files_only" == true ]] && remain=1
  if [[ "$engine" == rg ]]; then
    flags=(-n --no-heading --no-filename --color never --no-unicode -I --max-columns 1000 --max-columns-preview --max-count "$remain")
    [[ "$regex" != true ]] && flags+=(-F)
    [[ "$ignore" == true ]] && flags+=(-i)
    rg "${flags[@]}" -e "$pattern" -- "$file" > "$work/hits" 2> "$work/error"
    rc=$?
  else
    # Environment avoids awk -v escape reinterpretation of literal patterns.
    OPS_PATTERN="$pattern" OPS_IGNORE="$ignore" OPS_REMAIN="$remain" awk '
      BEGIN { p=ENVIRON["OPS_PATTERN"]; fold=ENVIRON["OPS_IGNORE"]=="true"; n=ENVIRON["OPS_REMAIN"]+0; if(fold)p=tolower(p) }
      index($0,sprintf("%c",0)) { exit 4 }
      { s=$0; if(fold)s=tolower(s); if(index(s,p)) { print NR ":" substr($0,1,1000); if(++found>=n)exit } }
    ' "$file" > "$work/hits" 2> "$work/error"
    rc=$?
    (( rc == 4 )) && continue # binary files are outside the text search contract
  fi
  if (( rc > 1 )); then partial=1; diagnostic=$(head -c 500 "$work/error"); continue; fi
  [[ ! -s "$work/hits" ]] && continue
  if [[ "$files_only" == true ]]; then
    (( count+=1 )); cost=$(( ${#file} + 4 ))
    if (( count > max || bytes + cost > budget )); then limited=1; break; fi
    printf 'F\0%s\0' "$file"; (( bytes+=cost )); continue
  fi
  while IFS= read -r hit || [[ -n "$hit" ]]; do
    line=${hit%%:*}; text=${hit#*:}
    (( count+=1 )); cost=$(( ${#file} + ${#line} + ${#text} + 8 ))
    if (( count > max || bytes + cost > budget )); then limited=1; break; fi
    printf 'H\0%s\0%s\0%s\0' "$file" "$line" "$text"
    (( bytes+=cost ))
  done < "$work/hits"
  (( limited )) && break
done < "$work/sorted"
finish
