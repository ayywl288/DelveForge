#!/bin/sh
set -eu
folder=/state/projects/$PROJECT_ID/home/.m2
for directory in /state/projects "/state/projects/$PROJECT_ID" "/state/projects/$PROJECT_ID/home" "$folder"; do
    test ! -L "$directory"
    mkdir -p "$directory"
done
# 每个配置文件独立发布，已有配置属于项目，永不覆盖。
for file in delveforge-central.xml settings.xml; do
    if ! test -e "$folder/$file" && ! test -L "$folder/$file"; then
        tmp=$(mktemp "$folder/.central.XXXXXX")
        trap 'rm -f "$tmp"' EXIT
        cat /cache-config/maven-settings.xml >"$tmp"
        chmod 644 "$tmp"
        ln "$tmp" "$folder/$file" || test -e "$folder/$file"
        rm -f "$tmp"
    fi
done
