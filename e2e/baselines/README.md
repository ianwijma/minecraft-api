# Visual references

CI compares the house checkpoints against committed PNGs in
`linux-ci-fabric/house/` and `linux-ci-neoforge/house/`. Each set belongs to
the GitHub Actions Ubuntu/Xvfb renderer and the `mapi-window-smoke` profile
(1280 × 720 framebuffer, GUI scale 2). Keep loader references separate.

Initial references were captured by [CI run 37126285659](https://github.com/ianwijma/minecraft-api/actions/runs/37126285659)
from commit `8a8be6b`, with Minecraft 26.2 and MAPI 0.1.0. All packaged-smoke
checks passed for both loaders before the reference artifacts were imported.

Exterior checkpoints mask two background horizon strips (x=0–399 and
x=875–1279, y=450–579). Distant chunk meshes outside the 97 × 97 prepared
stage can differ between fresh clients. The house silhouette remains
unmasked, the interior uses no masks, and comparison tolerances are unchanged.
The southeast pose also masks the narrow background horizon at x=400–463,
y=559 and x=832–874, y=559–560. Both loader references keep the adjacent
house walls outside those rectangles. Checkpoint settling uses the same masks
as baseline comparison, with the original shimmer and comparison thresholds.

Normal runs fail if a reference is missing and never modify it. Failures
upload actual and diff PNGs with the packaged-smoke acceptance reports.

To refresh references, run the `ci` workflow on your branch with the
`update_visual_baselines` input enabled. The existing operator opt-in for
packaged smoke tests still applies. Download `visual-baselines-fabric` and
`visual-baselines-neoforge` into their respective `linux-ci-<loader>/`
directories, inspect the PNG changes, and commit them. Then run normal CI
with the update input disabled to verify comparison against those images.
Updating references is an explicit maintenance operation, not a regression
test pass.

For local runs, choose a separate environment and initialize it explicitly:

```bash
node --experimental-strip-types e2e/run.ts --loader fabric --scenario house \
  --release-jar --env linux-local-fabric --update-baselines
```

Repeat without `--update-baselines` to compare. Local references are ignored;
do not replace CI references with captures from a different GPU or profile.
