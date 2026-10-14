#!/usr/bin/env bash
set -euo pipefail

: "${MAPI_MESA_VERSION:?Set the verified Mesa package version}"
: "${MAPI_XVFB_VERSION:?Set the verified Xvfb package version}"

sudo apt-get update

# Mesa libraries have exact-version dependencies. Align the runner's installed
# Mesa packages too, since hosted image updates can preinstall newer libraries.
mapfile -t mesa_packages < <(
    {
        dpkg-query -W -f='${binary:Package}\t${source:Package}\t${db:Status-Status}\n' |
            awk '$2 == "mesa" && $3 == "installed" { print $1 }'
        printf '%s\n' libgbm1 libgl1-mesa-dri mesa-libgallium mesa-vulkan-drivers
    } | sort -u
)
mesa_specs=()
for package in "${mesa_packages[@]}"; do
    mesa_specs+=("$package=$MAPI_MESA_VERSION")
done
sudo apt-get install --yes --allow-downgrades --no-install-recommends \
    "${mesa_specs[@]}" "xvfb=$MAPI_XVFB_VERSION"
